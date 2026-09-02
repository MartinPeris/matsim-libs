package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.VehicleEndsParkingSearch;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.handler.VehicleEndsParkingSearchEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.vehicles.Vehicle;

import java.io.BufferedWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks link-level parking occupancy during the mobsim and exposes capacity/occupancy
 * snapshots for parking search time calculation.
 * <p>
 * Occupancy is kept both as a single total per link, which is what {@link #getParkingCount} reports and what the
 * search-time functions consume, and as two pools: on-street (kerb) and off-street. A parking vehicle takes an
 * on-street space if one is free and spills to off-street otherwise; the off-street pool is unbounded here, so a
 * vehicle is never refused. The pool a vehicle parked in is remembered, so its departure releases the right pool.
 * The peak off-street occupancy per link is the size of off-street supply the link would have needed.
 */
public class ParkingOccupancyObserver implements MobsimScopeEventHandler, VehicleEntersTrafficEventHandler, VehicleEndsParkingSearchEventHandler, BeforeMobsimListener, MobsimBeforeSimStepListener {
	private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(ParkingOccupancyObserver.class);

	private ParkingCapacityInitializer parkingCapacityInitializer;
	private Network network;
	private OutputDirectoryHierarchy outputDirectoryHierarchy;
	private Config config;

	Map<Id<Link>, Integer> indexByLinkId;
	int[] parkingOccupancyOfLastTimeStep;
	int[] parkingOccupancy;
	int[] capacity;

	// Pools. The totals above are always the sum of the two occupancies, and capacity[] the sum of the two capacities.
	int[] onStreetCapacity;
	int[] onStreetOccupancy;
	int[] offStreetOccupancy;
	int[] offStreetPeakOccupancy;
	/** Which pool each currently parked vehicle occupies. Absent for vehicles seeded as initial occupancy. */
	private final Map<Id<Vehicle>, Boolean> parkedOnStreetByVehicle = new HashMap<>();

	double lastTimeStep = -1;

	@Inject
	ParkingOccupancyObserver(Network network, ParkingCapacityInitializer parkingCapacityInitializer, Config config, OutputDirectoryHierarchy outputDirectoryHierarchy) {
		this.parkingCapacityInitializer = parkingCapacityInitializer;
		this.network = network;
		this.config = config;
		this.outputDirectoryHierarchy = outputDirectoryHierarchy;
	}

	@Override
	public synchronized void handleEvent(VehicleEntersTrafficEvent event) {
		checkTime(event.getTime());

		// unpark vehicle
		int index = indexByLinkId.get(event.getLinkId());

		// We might have initialized to little initial parking, thus more vehicles enter traffic than expected. In this case, we just ignore the event.
		if (parkingOccupancy[index] <= 0) {
			parkedOnStreetByVehicle.remove(event.getVehicleId());
			return;
		}
		parkingOccupancy[index]--;

		Boolean wasOnStreet = parkedOnStreetByVehicle.remove(event.getVehicleId());
		if (wasOnStreet == null) {
			// Seeded as initial occupancy, so its pool is unknown. Initial occupancy filled on-street first, so
			// release in the same order the unknown vehicles were seeded: on-street while any remains, then off-street.
			wasOnStreet = onStreetOccupancy[index] > 0;
		}
		if (wasOnStreet) {
			onStreetOccupancy[index]--;
		} else {
			offStreetOccupancy[index]--;
		}
	}

	@Override
	public synchronized void handleEvent(VehicleEndsParkingSearch event) {
		checkTime(event.getTime());

		// park vehicle
		int index = indexByLinkId.get(event.getLinkId());
		parkingOccupancy[index]++;

		// Kerb first; off-street only once the kerb is full. Off-street is not capped here, so nobody is refused.
		boolean onStreet = onStreetOccupancy[index] < onStreetCapacity[index];
		if (onStreet) {
			onStreetOccupancy[index]++;
		} else {
			offStreetOccupancy[index]++;
			offStreetPeakOccupancy[index] = Math.max(offStreetPeakOccupancy[index], offStreetOccupancy[index]);
		}
		parkedOnStreetByVehicle.put(event.getVehicleId(), onStreet);
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		initialize(event.getIteration(), network);

		//reset timer
		lastTimeStep = -1;
	}

	@Override
	public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent e) {
		double currentTimeStep = e.getSimulationTime();
		if (currentTimeStep == lastTimeStep) {
			throw new IllegalArgumentException("Already processed time " + currentTimeStep);
		}

		if (currentTimeStep < lastTimeStep) {
			throw new IllegalArgumentException("Events must be ordered by time.");
		}

		// currentTimeStep > lastEventTime => we have to store current count under last and initialize a new parkingCount
		this.parkingOccupancyOfLastTimeStep = Arrays.copyOf(this.parkingOccupancy, this.parkingOccupancy.length);

		lastTimeStep = currentTimeStep;
	}

	private void checkTime(double now) {
		if (now != lastTimeStep) {
			throw new IllegalArgumentException("Time " + now + " does not match last time " + lastTimeStep);
		}
	}

	private void initialize(int iteration, Network network) {
		indexByLinkId = new HashMap<>(network.getLinks().size());
		int linkCount = network.getLinks().size();

		capacity = new int[linkCount];
		parkingOccupancy = new int[linkCount];
		parkingOccupancyOfLastTimeStep = new int[linkCount];
		onStreetCapacity = new int[linkCount];
		onStreetOccupancy = new int[linkCount];
		offStreetOccupancy = new int[linkCount];
		offStreetPeakOccupancy = new int[linkCount];
		parkedOnStreetByVehicle.clear();

		Map<Id<Link>, ParkingCapacityInitializer.ParkingInitialPools> initialPools = parkingCapacityInitializer.initializePools();
		writeInitialParkingOccupancy(iteration, initialPools);

		int counter = 0;
		for (Id<Link> id : network.getLinks().keySet()) {
			indexByLinkId.put(id, counter++);

			ParkingCapacityInitializer.ParkingInitialPools pools = initialPools.getOrDefault(id, new ParkingCapacityInitializer.ParkingInitialPools(0, 0, 0));
			int index = indexByLinkId.get(id);

			capacity[index] = pools.capacity();
			onStreetCapacity[index] = pools.onStreetCapacity();

			// Seed initial occupancy kerb-first as well, so a link that starts full starts with its kerb full.
			int occupancy = pools.occupancy();
			onStreetOccupancy[index] = Math.min(occupancy, pools.onStreetCapacity());
			offStreetOccupancy[index] = occupancy - onStreetOccupancy[index];
			offStreetPeakOccupancy[index] = offStreetOccupancy[index];

			parkingOccupancyOfLastTimeStep[index] = occupancy;
			parkingOccupancy[index] = occupancy;
		}
	}

	synchronized Map<Id<Link>, ParkingCount> getParkingCount(double now, Map<Id<Link>, Double> weightedLinks) {
		checkTime(now);

		Map<Id<Link>, ParkingCount> result = new HashMap<>();
		for (Map.Entry<Id<Link>, Double> entry : weightedLinks.entrySet()) {
			Id<Link> linkId = entry.getKey();
			double weight = entry.getValue();
			int index = indexByLinkId.get(linkId);
			// Use the last completed timestep so vehicles processed earlier in the
			// current timestep do not immediately affect later vehicles.
			int occupancy = parkingOccupancyOfLastTimeStep[index];
			result.put(linkId, new ParkingCount(occupancy, capacity[index], weight));
		}
		return result;
	}

	synchronized int getOnStreetCapacity(Id<Link> linkId) {
		return onStreetCapacity[indexByLinkId.get(linkId)];
	}

	synchronized int getOnStreetOccupancy(Id<Link> linkId) {
		return onStreetOccupancy[indexByLinkId.get(linkId)];
	}

	synchronized int getOffStreetOccupancy(Id<Link> linkId) {
		return offStreetOccupancy[indexByLinkId.get(linkId)];
	}

	/** Highest simultaneous off-street occupancy seen on the link this iteration: the off-street supply it needed. */
	synchronized int getOffStreetPeakOccupancy(Id<Link> linkId) {
		return offStreetPeakOccupancy[indexByLinkId.get(linkId)];
	}

	private void writeInitialParkingOccupancy(int iteration, Map<Id<Link>, ParkingCapacityInitializer.ParkingInitialPools> initialPools) {
		String file = outputDirectoryHierarchy.getIterationFilename(iteration, ParkingUtils.PARKING_INITIAL_FILE);
		BufferedWriter bufferedWriter = IOUtils.getBufferedWriter(file);

		log.info("Writing initial parking occupancy to {}", file);
		try {
			CSVPrinter csvPrinter = new CSVPrinter(bufferedWriter, CSVFormat.Builder.create()
				.setDelimiter(config.global().getDefaultDelimiter().charAt(0))
				.setHeader(new String[]{"linkId", "capacity", "occupancy"}).build());

			for (Map.Entry<Id<Link>, ParkingCapacityInitializer.ParkingInitialPools> entry : initialPools.entrySet()) {
				csvPrinter.printRecord(entry.getKey(), entry.getValue().capacity(), entry.getValue().occupancy());
			}
			csvPrinter.close();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		log.info("Finished writing initial parking occupancy to {}", file);
	}
}
