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

package org.matsim.contrib.drt.extension.skims;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;

/**
 * Builds a zone- and time-binned DRT wait-time skim from the events of each mobsim iteration and
 * makes it queryable during the next replanning pass.
 * <p>
 * Measurement is not re-implemented here: {@link DrtEventSequenceCollector} already assembles the
 * per-request event sequences, and the wait time uses the same formula MATSim's own DRT analysis
 * uses, {@code pickedUp - earliestDepartureTime}, measured from readiness rather than from
 * submission so that a prebooked request is not charged for its own booking lead time.
 * <p>
 * Three properties are deliberate.
 * <ul>
 * <li>Values are blended across iterations rather than replaced, because a skim overwritten
 * wholesale each iteration invites the router and the mobsim to chase each other.
 * {@link DrtWaitTimeSkimParams#getSmoothingWeight()} controls the damping; 1.0 restores
 * replacement.</li>
 * <li>A zone and bin with no observations inherits a coarser aggregate rather than a constant, and
 * every lookup reports which level it came from — including whether a zone/bin value is fresh or
 * merely carried forward from an earlier iteration.</li>
 * <li>Rejected requests are counted and reported but do not enter the mean. A rejection is an
 * unbounded wait, and averaging it in would require a number this class has no basis to invent.
 * The consequence is a systematic optimism wherever rejection is common, which is why the
 * rejection count sits beside the wait time in the CSV rather than out of sight.</li>
 * </ul>
 * The published table is an immutable snapshot swapped in at the end of an iteration, so concurrent
 * routing threads always read a consistent skim.
 *
 * @author Monash Healthy Active Cities
 */
public final class ZonalDrtWaitTimeSkim implements DrtWaitTimeSkim, IterationEndsListener {

	private static final Logger log = LogManager.getLogger(ZonalDrtWaitTimeSkim.class);

	private final String mode;
	private final DrtWaitTimeSkimParams params;
	private final ZoneSystem zoneSystem;
	private final Network network;
	private final DrtEventSequenceCollector collector;
	@Nullable
	private final MatsimServices services;
	private final String delimiter;
	private final int binCount;

	private volatile SkimData data;

	public ZonalDrtWaitTimeSkim(String mode, DrtWaitTimeSkimParams params, ZoneSystem zoneSystem,
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
	public Lookup lookup(Id<Link> fromLinkId, double time) {
		SkimData snapshot = this.data;
		Id<Zone> zoneId = zoneOf(fromLinkId);
		boolean timeKnown = !Double.isNaN(time);
		int bin = timeKnown ? binOf(time) : -1;

		if (zoneId != null && timeKnown) {
			double[] byBin = snapshot.zoneBin.get(zoneId);
			if (byBin != null && !Double.isNaN(byBin[bin])) {
				return new Lookup(byBin[bin],
						snapshot.wasRefreshed(zoneId, bin) ? Source.ZONE_TIME_BIN : Source.ZONE_TIME_BIN_CARRIED);
			}
		}
		if (zoneId != null) {
			Double zoneMean = snapshot.zoneMean.get(zoneId);
			if (zoneMean != null) {
				return new Lookup(zoneMean, Source.ZONE_MEAN);
			}
		}
		if (timeKnown && !Double.isNaN(snapshot.globalBin[bin])) {
			return new Lookup(snapshot.globalBin[bin], Source.GLOBAL_TIME_BIN);
		}
		if (!Double.isNaN(snapshot.globalMean)) {
			return new Lookup(snapshot.globalMean, Source.GLOBAL_MEAN);
		}
		return new Lookup(params.getDefaultWaitTime(), Source.DEFAULT);
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		update();
		if (params.isWriteSkimCsv() && services != null) {
			write(services.getControllerIO()
					.getIterationFilename(event.getIteration(), "drtWaitTimeSkim_" + mode + ".csv"));
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
		int originsOutsideZoneSystem = 0;
		int pickupsBeforeReadiness = 0;

		for (EventSequence sequence : collector.getPerformedRequestSequences().values()) {
			double earliestDepartureTime = sequence.getSubmitted().getEarliestDepartureTime();
			Id<Zone> zoneId = zoneOf(sequence);
			if (zoneId == null) {
				originsOutsideZoneSystem++;
				continue;
			}
			int bin = binOf(earliestDepartureTime);
			for (EventSequence.PersonEvents personEvents : sequence.getPersonEvents().values()) {
				Optional<PassengerPickedUpEvent> pickedUp = personEvents.getPickedUp();
				if (pickedUp.isEmpty()) {
					continue;
				}
				double waitTime = pickedUp.get().getTime() - earliestDepartureTime;
				if (waitTime < 0) {
					// picked up before the passenger was ready to leave: not a wait
					pickupsBeforeReadiness++;
					continue;
				}
				obs.addWait(zoneId, bin, waitTime);
			}
		}

		for (EventSequence sequence : collector.getRejectedRequestSequences().values()) {
			Id<Zone> zoneId = zoneOf(sequence);
			if (zoneId != null) {
				obs.addRejection(zoneId, binOf(sequence.getSubmitted().getEarliestDepartureTime()));
			}
		}

		report(obs, originsOutsideZoneSystem, pickupsBeforeReadiness);
		return obs;
	}

	@Nullable
	private Id<Zone> zoneOf(EventSequence sequence) {
		return zoneOf(sequence.getSubmitted().getFromLinkId());
	}

	/**
	 * Resolves a link to its zone, or to {@code null} where no zone applies.
	 * <p>
	 * The zone system is built on this mode's <em>filtered</em> network, and
	 * {@link ZoneSystem#getZoneForLinkId} is not required to tolerate a link outside it —
	 * {@code SquareGridZoneSystem}, for one, dereferences the link and throws. A caller holding a
	 * link id from elsewhere in the scenario would therefore get a NullPointerException from a
	 * method documented to always return a number. Screen those links out here instead, so such a
	 * lookup falls through to the coarser aggregates and reports its {@link Source} honestly.
	 * <p>
	 * The identity check is not redundant with the {@code null} check. {@link org.matsim.api.core.v01.IdMap}
	 * resolves purely by {@link Id#index()} and does not verify that the stored key matches, so a
	 * foreign id whose index collides with an occupied slot silently yields <em>another</em> link.
	 * Comparing the returned link's own id turns that aliasing into a miss rather than into a wait
	 * time read from the wrong zone.
	 */
	@Nullable
	private Id<Zone> zoneOf(@Nullable Id<Link> linkId) {
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

	private void report(Observations obs, int originsOutsideZoneSystem, int pickupsBeforeReadiness) {
		if (originsOutsideZoneSystem > 0) {
			// worth saying out loud: these trips are invisible to the skim, so a zone system that
			// does not cover the service area quietly starves it
			log.warn("Mode {}: {} DRT requests started outside the wait-time skim's zone system and"
					+ " were not counted. Check that the zone system covers the service area.", mode,
					originsOutsideZoneSystem);
		}
		if (pickupsBeforeReadiness > 0) {
			log.debug("Mode {}: ignored {} requests picked up before the passenger was ready.", mode,
					pickupsBeforeReadiness);
		}
		if (obs.totalRejections > 0) {
			double share = 100.0 * obs.totalRejections / (obs.totalRejections + obs.totalCount);
			// a rejection is an unbounded wait; leaving it out of the mean makes the skim optimistic
			// exactly where service is worst, so the rate belongs in the log, not only in the CSV
			log.warn("Mode {}: {} of {} requests were rejected ({}%). Rejections are counted in the"
							+ " wait-time skim's CSV but excluded from the mean, so the skim understates"
							+ " how bad service is in zones that reject often.", mode, obs.totalRejections,
					obs.totalRejections + obs.totalCount, String.format("%.1f", share));
		}
	}

	private SkimData blend(SkimData previous, Observations obs) {
		double weight = params.getSmoothingWeight();
		int minObservations = params.getMinObservations();

		Map<Id<Zone>, double[]> zoneBin = new HashMap<>();
		previous.zoneBin.forEach((zoneId, values) -> zoneBin.put(zoneId, values.clone()));
		Map<Id<Zone>, Double> zoneMean = new HashMap<>(previous.zoneMean);
		Map<Id<Zone>, boolean[]> refreshed = new HashMap<>();

		for (Map.Entry<Id<Zone>, double[]> entry : obs.sums.entrySet()) {
			Id<Zone> zoneId = entry.getKey();
			double[] sums = entry.getValue();
			int[] counts = obs.counts.get(zoneId);

			double zoneSum = 0;
			int zoneCount = 0;
			for (int bin = 0; bin < binCount; bin++) {
				zoneSum += sums[bin];
				zoneCount += counts[bin];
				if (!qualifies(counts[bin], minObservations)) {
					continue;
				}
				// only materialise a zone's array once a bin actually earns a value, so that zones
				// seen but never qualifying do not accumulate all-NaN arrays for the whole run
				double[] target = zoneBin.computeIfAbsent(zoneId, id -> newNaNArray(binCount));
				target[bin] = blendValue(target[bin], sums[bin] / counts[bin], weight);
				refreshed.computeIfAbsent(zoneId, id -> new boolean[binCount])[bin] = true;
			}
			if (qualifies(zoneCount, minObservations)) {
				zoneMean.merge(zoneId, zoneSum / zoneCount, (prev, observed) -> blendValue(prev, observed, weight));
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

		return new SkimData(Collections.unmodifiableMap(zoneBin), Collections.unmodifiableMap(zoneMean),
				globalBin, globalMean, Collections.unmodifiableMap(refreshed),
				Collections.unmodifiableMap(obs.counts), Collections.unmodifiableMap(obs.rejections));
	}

	/**
	 * A count qualifies only if it is both non-zero and at least the configured threshold. The
	 * non-zero check is not redundant: a misconfigured threshold of zero would otherwise admit
	 * empty bins and blend {@code 0.0/0 = NaN} over every good value in the table.
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
	 * Writes the published skim: one row per zone and time bin carrying a value, plus every zone and
	 * bin that saw a rejection. {@code observations} and {@code rejections} count the most recent
	 * iteration only, so a row with a value but no observations was carried forward from an earlier
	 * one, and a row with rejections but no wait time is a zone the skim cannot see into at all.
	 */
	public void write(String fileName) {
		SkimData snapshot = this.data;
		double binSize = params.getTimeBinSize();
		try (BufferedWriter writer = IOUtils.getBufferedWriter(fileName)) {
			writer.write(String.join(delimiter, "zone", "timeBin", "binStart", "binEnd", "observations",
					"rejections", "waitTime", "carriedForward"));
			writer.newLine();
			for (Id<Zone> zoneId : snapshot.allZones()) {
				double[] values = snapshot.zoneBin.get(zoneId);
				int[] counts = snapshot.latestCounts.get(zoneId);
				int[] rejections = snapshot.latestRejections.get(zoneId);
				for (int bin = 0; bin < binCount; bin++) {
					double value = values == null ? Double.NaN : values[bin];
					int observations = counts == null ? 0 : counts[bin];
					int rejected = rejections == null ? 0 : rejections[bin];
					if (Double.isNaN(value) && rejected == 0) {
						continue;
					}
					writer.write(String.join(delimiter, //
							zoneId.toString(), //
							Integer.toString(bin), //
							Double.toString(bin * binSize), //
							Double.toString((bin + 1) * binSize), //
							Integer.toString(observations), //
							Integer.toString(rejected), //
							Double.isNaN(value) ? "" : Double.toString(value), //
							Boolean.toString(!Double.isNaN(value) && !snapshot.wasRefreshed(zoneId, bin))));
					writer.newLine();
				}
			}
		} catch (IOException e) {
			log.error("Could not write DRT wait time skim for mode {} to {}", mode, fileName, e);
			throw new UncheckedIOException(e);
		}
	}

	private record SkimData(Map<Id<Zone>, double[]> zoneBin, Map<Id<Zone>, Double> zoneMean, double[] globalBin,
							double globalMean, Map<Id<Zone>, boolean[]> refreshedThisIteration,
							Map<Id<Zone>, int[]> latestCounts, Map<Id<Zone>, int[]> latestRejections) {

		static SkimData empty(int binCount) {
			return new SkimData(Map.of(), Map.of(), newNaNArray(binCount), Double.NaN, Map.of(), Map.of(), Map.of());
		}

		boolean wasRefreshed(Id<Zone> zoneId, int bin) {
			boolean[] flags = refreshedThisIteration.get(zoneId);
			return flags != null && flags[bin];
		}

		java.util.Set<Id<Zone>> allZones() {
			java.util.Set<Id<Zone>> zones = new java.util.TreeSet<>(java.util.Comparator.comparing(Id::toString));
			zones.addAll(zoneBin.keySet());
			zones.addAll(latestRejections.keySet());
			return zones;
		}
	}

	private static final class Observations {
		private final int binCount;
		private final Map<Id<Zone>, double[]> sums = new HashMap<>();
		private final Map<Id<Zone>, int[]> counts = new HashMap<>();
		private final Map<Id<Zone>, int[]> rejections = new HashMap<>();
		private final double[] globalSums;
		private final int[] globalCounts;
		private double totalSum = 0;
		private int totalCount = 0;
		private int totalRejections = 0;

		Observations(int binCount) {
			this.binCount = binCount;
			this.globalSums = new double[binCount];
			this.globalCounts = new int[binCount];
		}

		void addWait(Id<Zone> zoneId, int bin, double waitTime) {
			sums.computeIfAbsent(zoneId, id -> new double[binCount])[bin] += waitTime;
			counts.computeIfAbsent(zoneId, id -> new int[binCount])[bin]++;
			globalSums[bin] += waitTime;
			globalCounts[bin]++;
			totalSum += waitTime;
			totalCount++;
		}

		void addRejection(Id<Zone> zoneId, int bin) {
			rejections.computeIfAbsent(zoneId, id -> new int[binCount])[bin]++;
			totalRejections++;
		}
	}
}
