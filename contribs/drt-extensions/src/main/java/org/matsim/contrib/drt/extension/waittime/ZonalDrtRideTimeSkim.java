/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.contrib.drt.extension.waittime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import jakarta.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Identifiable;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector.EventSequence;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;

/**
 * Builds an origin-destination-zone and time-binned skim of how much longer DRT rides actually take
 * than the unshared ride they were quoted against, and makes it queryable during the next replanning
 * pass.
 * <p>
 * Structurally this is the wait skim of {@link ZonalDrtWaitTimeSkim} with a pair for a key and a
 * ratio for a value; the damping, carry-forward reporting and CSV all behave the same way and for
 * the same reasons. Three things differ.
 * <ul>
 * <li><b>The value is dimensionless.</b> {@code (droppedOff - pickedUp) / unsharedRideTime} is
 * comparable between a two-kilometre pair and a ten-kilometre one, which is what makes the coarser
 * fallback levels meaningful at all. An absolute mean ride time is not aggregable in that way.</li>
 * <li><b>The table is sparse.</b> Only pairs that were actually travelled are stored. Keying on
 * pairs is what made the earlier stop-keyed attempt in this lineage intractable — an all-pairs table
 * is O(n²) in keys and was allocated eagerly — so the table here grows with the origin-destination
 * pairs a scenario uses, not with the square of the zone count.</li>
 * <li><b>There is no configured default.</b> A caller that gets {@link Source#DEFAULT} already holds
 * a better number than this class could invent, so it is told to leave its own estimate alone.</li>
 * </ul>
 * Only requests that were both picked up <em>and</em> dropped off contribute: without a drop-off
 * there is no ride to measure. {@link DrtEventSequenceCollector} does not require a drop-off for a
 * sequence to count as performed, so this is filtered explicitly.
 * <p>
 * The published table is an immutable snapshot swapped in at the end of an iteration, so concurrent
 * routing threads always read a consistent skim.
 *
 * @author Monash Healthy Active Cities
 */
public final class ZonalDrtRideTimeSkim implements DrtRideTimeSkim, IterationEndsListener {

	private static final Logger log = LogManager.getLogger(ZonalDrtRideTimeSkim.class);

	private final String mode;
	private final DrtRideTimeSkimParams params;
	private final ZoneSystem zoneSystem;
	@Nullable
	private final Network network;
	private final DrtEventSequenceCollector collector;
	@Nullable
	private final MatsimServices services;
	private final String delimiter;
	private final int binCount;

	private volatile SkimData data;

	public ZonalDrtRideTimeSkim(String mode, DrtRideTimeSkimParams params, ZoneSystem zoneSystem,
			@Nullable Network network, DrtEventSequenceCollector collector, @Nullable MatsimServices services,
			String delimiter) {
		this.mode = mode;
		this.params = params;
		this.zoneSystem = zoneSystem;
		this.network = network;
		this.collector = collector;
		this.services = services;
		this.delimiter = delimiter;
		this.binCount = params.getBinCount();
		this.data = SkimData.empty(binCount);
	}

	@Override
	public Lookup lookup(Id<Link> fromLinkId, Id<Link> toLinkId, double time) {
		SkimData snapshot = this.data;
		Id<Zone> fromZone = zoneOf(fromLinkId);
		Id<Zone> toZone = zoneOf(toLinkId);
		ZonePair pair = fromZone == null || toZone == null ? null : new ZonePair(fromZone, toZone);
		boolean timeKnown = !Double.isNaN(time);
		int bin = timeKnown ? binOf(time) : -1;

		if (pair != null && timeKnown) {
			double[] byBin = snapshot.pairBin.get(pair);
			if (byBin != null && !Double.isNaN(byBin[bin])) {
				return new Lookup(byBin[bin],
						snapshot.wasRefreshed(pair, bin) ? Source.ZONE_PAIR_TIME_BIN : Source.ZONE_PAIR_TIME_BIN_CARRIED);
			}
		}
		if (pair != null) {
			Double pairMean = snapshot.pairMean.get(pair);
			if (pairMean != null) {
				return new Lookup(pairMean, Source.ZONE_PAIR_MEAN);
			}
		}
		if (timeKnown && !Double.isNaN(snapshot.globalBin[bin])) {
			return new Lookup(snapshot.globalBin[bin], Source.GLOBAL_TIME_BIN);
		}
		if (!Double.isNaN(snapshot.globalMean)) {
			return new Lookup(snapshot.globalMean, Source.GLOBAL_MEAN);
		}
		// NaN rather than 1.0: a caller that ignores the source must not silently scale by a number
		// that was never measured
		return new Lookup(Double.NaN, Source.DEFAULT);
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		update();
		if (params.isWriteSkimCsv() && services != null) {
			write(services.getControllerIO()
					.getIterationFilename(event.getIteration(), "drtRideTimeSkim_" + mode + ".csv"));
		}
	}

	/**
	 * Folds the observations of the iteration just finished into the published skim. Exposed for
	 * tests, which drive the collector directly rather than running a mobsim.
	 */
	public void update() {
		this.data = blend(this.data, collect());
	}

	private Observations collect() {
		Observations obs = new Observations(binCount);
		int outsideZoneSystem = 0;
		int withoutDropoff = 0;
		int withoutUsableReference = 0;

		for (EventSequence sequence : collector.getPerformedRequestSequences().values()) {
			DrtRequestSubmittedEvent submitted = sequence.getSubmitted();
			Id<Zone> fromZone = zoneOf(submitted.getFromLinkId());
			Id<Zone> toZone = zoneOf(submitted.getToLinkId());
			if (fromZone == null || toZone == null) {
				outsideZoneSystem++;
				continue;
			}
			double unsharedRideTime = submitted.getUnsharedRideTime();
			if (!(unsharedRideTime > 0)) {
				// a zero or unset reference would make every ratio infinite; NaN-safe by construction
				withoutUsableReference++;
				continue;
			}

			ZonePair pair = new ZonePair(fromZone, toZone);
			int bin = binOf(submitted.getEarliestDepartureTime());

			for (EventSequence.PersonEvents personEvents : sequence.getPersonEvents().values()) {
				Optional<PassengerPickedUpEvent> pickedUp = personEvents.getPickedUp();
				Optional<PassengerDroppedOffEvent> droppedOff = personEvents.getDroppedOff();
				if (pickedUp.isEmpty() || droppedOff.isEmpty()) {
					// no drop-off means no ride to measure; the collector does not require one
					withoutDropoff++;
					continue;
				}
				double rideTime = droppedOff.get().getTime() - pickedUp.get().getTime();
				if (rideTime < 0) {
					continue;
				}
				obs.add(pair, bin, rideTime / unsharedRideTime);
			}
		}

		report(obs, outsideZoneSystem, withoutDropoff, withoutUsableReference);
		return obs;
	}

	@Nullable
	private Id<Zone> zoneOf(@Nullable Id<Link> linkId) {
		// same defence as ZonalDrtWaitTimeSkim: the zone system is built on this mode's filtered
		// network, and IdMap resolves by index without checking the stored key, so an unknown link
		// can otherwise throw or, worse, alias onto another link's zone
		if (linkId == null) {
			return null;
		}
		if (network != null) {
			Link link = network.getLinks().get(linkId);
			if (link == null || !linkId.equals(link.getId())) {
				return null;
			}
		}
		return zoneSystem.getZoneForLinkId(linkId).map(Identifiable::getId).orElse(null);
	}

	private void report(Observations obs, int outsideZoneSystem, int withoutDropoff, int withoutUsableReference) {
		if (outsideZoneSystem > 0) {
			log.warn("Mode {}: {} DRT rides began or ended outside the ride-time skim's zone system and"
					+ " were not counted. Check that the zone system covers the service area.", mode,
					outsideZoneSystem);
		}
		if (withoutDropoff > 0) {
			log.debug("Mode {}: ignored {} requests that were picked up but never dropped off.", mode,
					withoutDropoff);
		}
		if (withoutUsableReference > 0) {
			log.warn("Mode {}: {} DRT requests carried no usable unsharedRideTime and were not counted.",
					mode, withoutUsableReference);
		}
		if (obs.totalCount > 0) {
			log.info("Mode {}: ride-time skim observed {} rides over {} origin-destination zone pairs;"
					+ " mean ride was {} times the unshared ride.", mode, obs.totalCount, obs.sums.size(),
					String.format("%.2f", obs.totalSum / obs.totalCount));
		}
	}

	private SkimData blend(SkimData previous, Observations obs) {
		double weight = params.getSmoothingWeight();
		int minObservations = params.getMinObservations();

		Map<ZonePair, double[]> pairBin = new HashMap<>();
		previous.pairBin.forEach((pair, values) -> pairBin.put(pair, values.clone()));
		Map<ZonePair, Double> pairMean = new HashMap<>(previous.pairMean);
		Map<ZonePair, boolean[]> refreshed = new HashMap<>();

		for (Map.Entry<ZonePair, double[]> entry : obs.sums.entrySet()) {
			ZonePair pair = entry.getKey();
			double[] sums = entry.getValue();
			int[] counts = obs.counts.get(pair);

			double pairSum = 0;
			int pairCount = 0;
			for (int bin = 0; bin < binCount; bin++) {
				pairSum += sums[bin];
				pairCount += counts[bin];
				if (!qualifies(counts[bin], minObservations)) {
					continue;
				}
				// materialise a pair's array only once a bin earns a value, so that pairs seen but
				// never qualifying do not accumulate all-NaN arrays for the whole run
				double[] target = pairBin.computeIfAbsent(pair, p -> newNaNArray(binCount));
				target[bin] = blendValue(target[bin], sums[bin] / counts[bin], weight);
				refreshed.computeIfAbsent(pair, p -> new boolean[binCount])[bin] = true;
			}
			if (qualifies(pairCount, minObservations)) {
				pairMean.merge(pair, pairSum / pairCount, (prev, observed) -> blendValue(prev, observed, weight));
			}
		}

		double[] globalBin = previous.globalBin.clone();
		for (int bin = 0; bin < binCount; bin++) {
			if (qualifies(obs.globalCounts[bin], minObservations)) {
				globalBin[bin] = blendValue(globalBin[bin], obs.globalSums[bin] / obs.globalCounts[bin], weight);
			}
		}

		double globalMean = previous.globalMean;
		if (qualifies(obs.totalCount, minObservations)) {
			globalMean = blendValue(globalMean, obs.totalSum / obs.totalCount, weight);
		}

		return new SkimData(Collections.unmodifiableMap(pairBin), Collections.unmodifiableMap(pairMean),
				globalBin, globalMean, Collections.unmodifiableMap(refreshed),
				Collections.unmodifiableMap(obs.counts));
	}

	/**
	 * A count qualifies only if it is both non-zero and at least the configured threshold. The
	 * non-zero check is not redundant: a misconfigured threshold of zero would otherwise admit empty
	 * cells and blend {@code 0.0/0 = NaN} over every good value in the table.
	 */
	private static boolean qualifies(int count, int minObservations) {
		return count > 0 && count >= minObservations;
	}

	private static double blendValue(double previous, double observed, double weight) {
		return Double.isNaN(previous) ? observed : weight * observed + (1 - weight) * previous;
	}

	private int binOf(double time) {
		if (Double.isNaN(time) || time < 0) {
			return 0;
		}
		return Math.min(binCount - 1, (int)(time / params.getTimeBinSize()));
	}

	private static double[] newNaNArray(int length) {
		double[] array = new double[length];
		Arrays.fill(array, Double.NaN);
		return array;
	}

	/**
	 * Writes the published skim: one row per origin-destination zone pair and time bin carrying a
	 * value. {@code observations} counts the most recent iteration only, so a row with a value but
	 * no observations was carried forward from an earlier one.
	 */
	public void write(String fileName) {
		SkimData snapshot = this.data;
		double binSize = params.getTimeBinSize();
		try (BufferedWriter writer = IOUtils.getBufferedWriter(fileName)) {
			writer.write(String.join(delimiter, "fromZone", "toZone", "timeBin", "binStart", "binEnd",
					"observations", "rideTimeFactor", "carriedForward"));
			writer.newLine();
			for (ZonePair pair : sortedPairs(snapshot)) {
				double[] values = snapshot.pairBin.get(pair);
				int[] counts = snapshot.latestCounts.get(pair);
				for (int bin = 0; bin < binCount; bin++) {
					double value = values == null ? Double.NaN : values[bin];
					if (Double.isNaN(value)) {
						continue;
					}
					writer.write(String.join(delimiter, //
							pair.from().toString(), //
							pair.to().toString(), //
							Integer.toString(bin), //
							Double.toString(bin * binSize), //
							Double.toString((bin + 1) * binSize), //
							Integer.toString(counts == null ? 0 : counts[bin]), //
							Double.toString(value), //
							Boolean.toString(!snapshot.wasRefreshed(pair, bin))));
					writer.newLine();
				}
			}
		} catch (IOException e) {
			log.error("Could not write DRT ride time skim for mode {} to {}", mode, fileName, e);
			throw new UncheckedIOException(e);
		}
	}

	private static Set<ZonePair> sortedPairs(SkimData snapshot) {
		Set<ZonePair> pairs = new TreeSet<>(Comparator.comparing((ZonePair p) -> p.from().toString())
				.thenComparing(p -> p.to().toString()));
		pairs.addAll(snapshot.pairBin.keySet());
		return pairs;
	}

	/**
	 * An ordered origin-destination pair of zones. A same-zone pair is a legitimate key, not an edge
	 * case: short internal rides are exactly where a DRT feeder operates.
	 */
	private record ZonePair(Id<Zone> from, Id<Zone> to) {
	}

	private record SkimData(Map<ZonePair, double[]> pairBin, Map<ZonePair, Double> pairMean, double[] globalBin,
							double globalMean, Map<ZonePair, boolean[]> refreshedThisIteration,
							Map<ZonePair, int[]> latestCounts) {

		static SkimData empty(int binCount) {
			return new SkimData(Map.of(), Map.of(), newNaNArray(binCount), Double.NaN, Map.of(), Map.of());
		}

		boolean wasRefreshed(ZonePair pair, int bin) {
			boolean[] flags = refreshedThisIteration.get(pair);
			return flags != null && flags[bin];
		}
	}

	private static final class Observations {
		private final int binCount;
		private final Map<ZonePair, double[]> sums = new HashMap<>();
		private final Map<ZonePair, int[]> counts = new HashMap<>();
		private final double[] globalSums;
		private final int[] globalCounts;
		private double totalSum = 0;
		private int totalCount = 0;

		Observations(int binCount) {
			this.binCount = binCount;
			this.globalSums = new double[binCount];
			this.globalCounts = new int[binCount];
		}

		void add(ZonePair pair, int bin, double factor) {
			sums.computeIfAbsent(pair, p -> new double[binCount])[bin] += factor;
			counts.computeIfAbsent(pair, p -> new int[binCount])[bin]++;
			globalSums[bin] += factor;
			globalCounts[bin]++;
			totalSum += factor;
			totalCount++;
		}
	}
}
