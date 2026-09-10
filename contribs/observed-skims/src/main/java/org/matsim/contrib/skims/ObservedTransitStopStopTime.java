package org.matsim.contrib.skims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Singleton;

/**
 * Inter-stop travel times measured from the mobsim's own events, per stop pair and time bin.
 * <p>
 * A bus sharing a lane with the cars it is stuck behind takes longer than its timetable claims, every
 * day. Routing that reads the schedule keeps sending people onto it. This records what the run took:
 *
 * <pre>
 * stopStopTime = VehicleArrivesAtFacilityEvent.time(to) - VehicleDepartsAtFacilityEvent.time(from)
 * </pre>
 *
 * measured vehicle by vehicle between consecutive calls, so dwell time at the first stop is excluded
 * and only the running time between the two is counted. It is filed under the bin the vehicle departed
 * in, which is the bin a router knows when it asks how long the next leg will take.
 *
 * <h2>Why the key is a stop pair and not a route</h2>
 * Congestion is a property of the road, not of the operator. Two routes sharing a corridor suffer it
 * together, and keying by route would split the evidence between them and halve the sample on each. A
 * pair also lets a route with no observations of its own borrow from another that runs the same
 * stretch, which is the common case in a dense network. The cost is that a route with genuine priority
 * along the corridor, a bus lane one service uses and another does not, is averaged with one that has
 * none. Where that matters, key by route instead; the interface does not forbid it.
 *
 * <h2>The scheduled fallback</h2>
 * A pair with nothing observed returns what the timetable implies: the mean, across every route that
 * runs the pair consecutively, of the arrival offset at the second stop less the departure offset at
 * the first. Unlike waiting, this does not vary by time of day in the schedule, so one figure per pair
 * suffices. Pairs no route runs consecutively are unknown, and are reported as zero excess rather than
 * an invented duration.
 *
 * @author Monash Healthy Active Cities
 */
@Singleton
public final class ObservedTransitStopStopTime implements TransitStopStopTime, VehicleArrivesAtFacilityEventHandler,
		VehicleDepartsAtFacilityEventHandler, IterationEndsListener {

	private final double binSize;
	private final int binCount;
	private final double updateWeight;

	private final Map<StopPair, MeanByTimeBin> measured = new HashMap<>();
	private final Map<StopPair, Double> scheduled = new HashMap<>();

	/** Where each transit vehicle last departed, and when. */
	private final Map<Id<Vehicle>, Id<TransitStopFacility>> lastStopOfVehicle = new HashMap<>();
	private final Map<Id<Vehicle>, Double> lastDepartureOfVehicle = new HashMap<>();

	public ObservedTransitStopStopTime(TransitSchedule schedule, double binSize, double endTime,
			double updateWeight) {
		if (!(binSize > 0)) {
			throw new IllegalArgumentException("binSize must be positive, got " + binSize);
		}
		if (!(updateWeight > 0) || updateWeight > 1) {
			throw new IllegalArgumentException("updateWeight must be in (0, 1], got " + updateWeight);
		}
		this.binSize = binSize;
		this.updateWeight = updateWeight;
		this.binCount = Math.max(1, (int) Math.ceil(endTime / binSize));

		Map<StopPair, double[]> sums = new HashMap<>();
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				List<TransitRouteStop> stops = route.getStops();
				for (int i = 0; i + 1 < stops.size(); i++) {
					TransitRouteStop from = stops.get(i);
					TransitRouteStop to = stops.get(i + 1);
					double runningTime = to.getArrivalOffset().or(to::getDepartureOffset).seconds()
							- from.getDepartureOffset().or(from::getArrivalOffset).seconds();
					if (runningTime < 0) {
						continue;
					}
					StopPair pair = new StopPair(from.getStopFacility().getId(), to.getStopFacility().getId());
					double[] sumAndCount = sums.computeIfAbsent(pair, p -> new double[2]);
					sumAndCount[0] += runningTime;
					sumAndCount[1]++;
					measured.computeIfAbsent(pair, p -> new MeanByTimeBin(binCount));
				}
			}
		}
		sums.forEach((pair, sumAndCount) -> scheduled.put(pair, sumAndCount[0] / sumAndCount[1]));
	}

	@Override
	public double stopStopTime(Id<TransitStopFacility> fromStopId, Id<TransitStopFacility> toStopId, double time) {
		StopPair pair = new StopPair(fromStopId, toStopId);
		MeanByTimeBin times = measured.get(pair);
		if (times == null) {
			// No route runs these two stops consecutively, so there is nothing to fall back to and no
			// duration to invent.
			return 0.0;
		}
		int bin = MeanByTimeBin.binOf(time, binSize, binCount);
		return times.hasValue(bin) ? times.mean(bin) : scheduled.getOrDefault(pair, 0.0);
	}

	@Override
	public double excessStopStopTime(Id<TransitStopFacility> fromStopId, Id<TransitStopFacility> toStopId,
			double time) {
		StopPair pair = new StopPair(fromStopId, toStopId);
		MeanByTimeBin times = measured.get(pair);
		if (times == null) {
			return 0.0;
		}
		int bin = MeanByTimeBin.binOf(time, binSize, binCount);
		if (!times.hasValue(bin)) {
			return 0.0;
		}
		return times.mean(bin) - scheduled.getOrDefault(pair, times.mean(bin));
	}

	/** Observations recorded this iteration, before damping. For tests and reporting. */
	int observationCount(Id<TransitStopFacility> fromStopId, Id<TransitStopFacility> toStopId, double time) {
		MeanByTimeBin times = measured.get(new StopPair(fromStopId, toStopId));
		return times == null ? 0 : times.count(MeanByTimeBin.binOf(time, binSize, binCount));
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		for (MeanByTimeBin times : measured.values()) {
			times.consolidate(updateWeight);
		}
	}

	@Override
	public void reset(int iteration) {
		lastStopOfVehicle.clear();
		lastDepartureOfVehicle.clear();
	}

	@Override
	public void handleEvent(VehicleDepartsAtFacilityEvent event) {
		lastStopOfVehicle.put(event.getVehicleId(), event.getFacilityId());
		lastDepartureOfVehicle.put(event.getVehicleId(), event.getTime());
	}

	@Override
	public void handleEvent(VehicleArrivesAtFacilityEvent event) {
		Id<TransitStopFacility> from = lastStopOfVehicle.get(event.getVehicleId());
		Double departedAt = lastDepartureOfVehicle.get(event.getVehicleId());
		if (from == null || departedAt == null) {
			// The vehicle's first call of the day: no preceding departure to measure from.
			return;
		}
		MeanByTimeBin times = measured.get(new StopPair(from, event.getFacilityId()));
		if (times != null) {
			times.add(MeanByTimeBin.binOf(departedAt, binSize, binCount), event.getTime() - departedAt);
		}
	}

	private record StopPair(Id<TransitStopFacility> from, Id<TransitStopFacility> to) {
	}
}
