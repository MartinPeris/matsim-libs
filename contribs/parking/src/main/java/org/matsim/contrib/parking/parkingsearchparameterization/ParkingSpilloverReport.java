package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.BeforeMobsimListener;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes what the kerb-first parking model observed: per link, the off-street supply it needed; network-wide, how
 * the two pools filled over the day.
 * <p>
 * Two files per iteration, both copied to the output directory at shutdown:
 * <ul>
 * <li>{@value #PER_LINK_FILE}: one row per link with any parking capacity or any spillover &mdash; on-street
 * capacity, peak off-street occupancy (the structured parking the link would have needed), the number of
 * spillover events, whether kerb parking is possible on the link at all, the peak and event count of the
 * arrivals on links where it is not, and the occupancy of both pools when the file is written.</li>
 * <li>{@value #NETWORK_FILE}: one row per time bin with network-wide on-street capacity, on-street occupancy,
 * off-street occupancy, and the part of that off-street occupancy sitting on links where kerb parking is not
 * possible.</li>
 * </ul>
 * The last columns of each file separate two things that look identical in the off-street numbers: a street whose
 * kerb is full, which is genuine demand for off-street supply, and a motorway that received an arrival only
 * because an activity coordinate snapped to it, which is an artefact of the scenario. Subtract the non-parkable
 * figures from the off-street ones to get the off-street supply a city would actually need.
 * <p>
 * The two {@code end...Occupancy} columns are the state at the end of the mobsim, which is what a district-level
 * aggregation needs: per-link peaks happen at different moments and cannot be summed, whereas the end state is one
 * simultaneous snapshot. In a run without replanning that snapshot is also the peak, because agents finish the day
 * parked and occupancy only rises.
 * The per-link file is filtered to links that matter because writing every link of a city network per iteration
 * is expensive and the zero rows carry no information.
 */
public class ParkingSpilloverReport implements MobsimBeforeSimStepListener, BeforeMobsimListener, IterationEndsListener, ShutdownListener {
	private static final Logger log = LogManager.getLogger(ParkingSpilloverReport.class);

	public static final String PER_LINK_FILE = "parking_pools_per_link.csv";
	public static final String NETWORK_FILE = "parking_pools_network.csv";
	public static final double DEFAULT_BIN_SIZE_SECONDS = 3600.0;

	private final ParkingOccupancyObserver observer;
	private final Network network;
	private final OutputDirectoryHierarchy outputDirectoryHierarchy;
	private final Config config;
	private final double binSizeSeconds;

	private final List<double[]> networkBins = new ArrayList<>();
	private double nextBinStart;

	@Inject
	public ParkingSpilloverReport(ParkingOccupancyObserver observer, Network network, OutputDirectoryHierarchy outputDirectoryHierarchy, Config config) {
		this(observer, network, outputDirectoryHierarchy, config, DEFAULT_BIN_SIZE_SECONDS);
	}

	public ParkingSpilloverReport(ParkingOccupancyObserver observer, Network network, OutputDirectoryHierarchy outputDirectoryHierarchy, Config config, double binSizeSeconds) {
		if (!(binSizeSeconds > 0)) {
			throw new IllegalArgumentException("binSizeSeconds must be positive, got " + binSizeSeconds);
		}
		this.observer = observer;
		this.network = network;
		this.outputDirectoryHierarchy = outputDirectoryHierarchy;
		this.config = config;
		this.binSizeSeconds = binSizeSeconds;
	}

	@Override
	public void notifyBeforeMobsim(BeforeMobsimEvent event) {
		networkBins.clear();
		nextBinStart = 0.0;
	}

	@Override
	public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent e) {
		double now = e.getSimulationTime();
		// The observer snapshots its own state in this same callback; sample at every bin boundary reached, so a
		// jump over several bins still records one row per bin.
		while (now >= nextBinStart) {
			ParkingOccupancyObserver.PoolTotals totals = observer.getPoolTotals();
			networkBins.add(new double[]{nextBinStart, totals.onStreetCapacity(), totals.onStreetOccupancy(),
				totals.offStreetOccupancy(), totals.nonParkableOccupancy()});
			nextBinStart += binSizeSeconds;
		}
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		writePerLink(outputDirectoryHierarchy.getIterationFilename(event.getIteration(), PER_LINK_FILE));
		writeNetwork(outputDirectoryHierarchy.getIterationFilename(event.getIteration(), NETWORK_FILE));
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		for (String file : new String[]{PER_LINK_FILE, NETWORK_FILE}) {
			try {
				IOUtils.copyFile(outputDirectoryHierarchy.getIterationFilename(event.getIteration(), file), outputDirectoryHierarchy.getOutputFilename(file));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	void writePerLink(String file) {
		log.info("Writing per-link parking pools to {}", file);
		try (BufferedWriter writer = IOUtils.getBufferedWriter(file);
			 CSVPrinter csv = new CSVPrinter(writer, format("linkId", "onStreetCapacity", "offStreetPeakOccupancy",
				 "spilloverEvents", "kerbParkingPermitted", "nonParkablePeakOccupancy", "nonParkableArrivals",
				 "endOnStreetOccupancy", "endOffStreetOccupancy"))) {
			for (Id<Link> linkId : network.getLinks().keySet()) {
				int onCap = observer.getOnStreetCapacity(linkId);
				int offPeak = observer.getOffStreetPeakOccupancy(linkId);
				int spills = observer.getOffStreetSpilloverEvents(linkId);
				if (onCap == 0 && offPeak == 0 && spills == 0) {
					continue;
				}
				csv.printRecord(linkId, onCap, offPeak, spills, observer.isKerbParkingPermitted(linkId),
					observer.getNonParkablePeakOccupancy(linkId), observer.getNonParkableArrivalEvents(linkId),
					observer.getOnStreetOccupancy(linkId), observer.getOffStreetOccupancy(linkId));
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	void writeNetwork(String file) {
		log.info("Writing network parking pools time series to {}", file);
		try (BufferedWriter writer = IOUtils.getBufferedWriter(file);
			 CSVPrinter csv = new CSVPrinter(writer, format("binStart", "onStreetCapacity", "onStreetOccupancy",
				 "offStreetOccupancy", "nonParkableOccupancy"))) {
			for (double[] bin : networkBins) {
				csv.printRecord(Time.writeTime(bin[0]), (long) bin[1], (long) bin[2], (long) bin[3], (long) bin[4]);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private CSVFormat format(String... header) {
		return CSVFormat.Builder.create()
			.setDelimiter(config.global().getDefaultDelimiter().charAt(0))
			.setHeader(header)
			.build();
	}
}
