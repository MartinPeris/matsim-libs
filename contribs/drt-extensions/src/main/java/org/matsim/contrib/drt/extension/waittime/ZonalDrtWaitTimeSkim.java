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
 * per-request event sequences, and the wait time is defined exactly as MATSim's own DRT analysis
 * defines it, {@code pickedUp - earliestDepartureTime}, so that a routed wait and a reported wait
 * cannot drift apart.
 * <p>
 * Two properties are deliberate. First, values are blended across iterations rather than replaced,
 * because a skim that is overwritten wholesale each iteration invites the router and the mobsim to
 * chase each other; {@link DrtWaitTimeSkimParams#getSmoothingWeight()} controls the damping, and
 * setting it to 1.0 restores the undamped behaviour. Second, a zone with no observations in a bin
 * inherits a coarser aggregate rather than a constant, and every lookup reports which level it came
 * from.
 * <p>
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
	private final DrtEventSequenceCollector collector;
	@Nullable
	private final MatsimServices services;
	private final String delimiter;
	private final int binCount;

	private volatile SkimData data;

	public ZonalDrtWaitTimeSkim(String mode, DrtWaitTimeSkimParams params, ZoneSystem zoneSystem,
			DrtEventSequenceCollector collector, @Nullable MatsimServices services, String delimiter) {
		this.mode = mode;
		this.params = params;
		this.zoneSystem = zoneSystem;
		this.collector = collector;
		this.services = services;
		this.delimiter = delimiter;
		this.binCount = params.getBinCount();
		this.data = SkimData.empty(binCount);
	}

	@Override
	public Lookup lookup(Id<Link> fromLinkId, double time) {
		SkimData snapshot = this.data;
		Id<Zone> zoneId = zoneSystem.getZoneForLinkId(fromLinkId).map(Identifiable::getId).orElse(null);
		boolean timeKnown = !Double.isNaN(time);
		int bin = timeKnown ? binOf(time) : -1;

		if (zoneId != null && timeKnown) {
			double[] byBin = snapshot.zoneBin.get(zoneId);
			if (byBin != null && !Double.isNaN(byBin[bin])) {
				return new Lookup(byBin[bin], Source.ZONE_TIME_BIN);
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
			String file = services.getControllerIO()
					.getIterationFilename(event.getIteration(), "drtWaitTimeSkim_" + mode + ".csv");
			write(file);
		}
	}

	/**
	 * Folds the observations of the iteration just finished into the published skim. Exposed for
	 * tests, which drive the collector directly rather than running a mobsim.
	 */
	public void update() {
		Observations obs = collect();
		this.data = blend(this.data, obs);
	}

	private Observations collect() {
		Observations obs = new Observations(binCount);
		int originsOutsideZoneSystem = 0;
		int pickupsBeforeReadiness = 0;

		for (EventSequence sequence : collector.getPerformedRequestSequences().values()) {
			double earliestDepartureTime = sequence.getSubmitted().getEarliestDepartureTime();
			Id<Zone> zoneId = zoneSystem.getZoneForLinkId(sequence.getSubmitted().getFromLinkId())
					.map(Identifiable::getId)
					.orElse(null);
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
				obs.add(zoneId, bin, waitTime);
			}
		}

		if (originsOutsideZoneSystem > 0) {
			// worth saying out loud: these trips are invisible to the skim, so a zone system that
			// does not cover the service area quietly starves it
			log.warn("Mode {}: {} DRT requests started outside the wait-time skim's zone system and"
					+ " were not counted. Check that the zone system covers the service area.",
					mode, originsOutsideZoneSystem);
		}
		if (pickupsBeforeReadiness > 0) {
			log.debug("Mode {}: ignored {} requests picked up before the passenger was ready.", mode,
					pickupsBeforeReadiness);
		}
		return obs;
	}

	private SkimData blend(SkimData previous, Observations obs) {
		double weight = params.getSmoothingWeight();
		int minObservations = params.getMinObservations();

		Map<Id<Zone>, double[]> zoneBin = new HashMap<>();
		previous.zoneBin.forEach((zoneId, values) -> zoneBin.put(zoneId, values.clone()));

		Map<Id<Zone>, Double> zoneMean = new HashMap<>(previous.zoneMean);

		for (Map.Entry<Id<Zone>, double[]> entry : obs.sums.entrySet()) {
			Id<Zone> zoneId = entry.getKey();
			double[] sums = entry.getValue();
			int[] counts = obs.counts.get(zoneId);
			double[] target = zoneBin.computeIfAbsent(zoneId, id -> newNaNArray(binCount));

			double zoneSum = 0;
			int zoneCount = 0;
			for (int bin = 0; bin < binCount; bin++) {
				zoneSum += sums[bin];
				zoneCount += counts[bin];
				if (counts[bin] >= minObservations) {
					target[bin] = blendValue(target[bin], sums[bin] / counts[bin], weight);
				}
			}
			if (zoneCount >= minObservations) {
				zoneMean.merge(zoneId, zoneSum / zoneCount,
						(prev, observed) -> blendValue(prev, observed, weight));
			}
		}

		double[] globalBin = previous.globalBin.clone();
		for (int bin = 0; bin < binCount; bin++) {
			if (obs.globalCounts[bin] >= minObservations) {
				globalBin[bin] = blendValue(globalBin[bin],
						obs.globalSums[bin] / obs.globalCounts[bin], weight);
			}
		}

		double globalMean = previous.globalMean;
		if (obs.totalCount >= minObservations) {
			globalMean = blendValue(globalMean, obs.totalSum / obs.totalCount, weight);
		}

		return new SkimData(Collections.unmodifiableMap(zoneBin), Collections.unmodifiableMap(zoneMean),
				globalBin, globalMean, obs.counts);
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
		java.util.Arrays.fill(array, Double.NaN);
		return array;
	}

	/**
	 * Writes the published skim, one row per zone and time bin that carries a value. The
	 * observation count is the number of trips seen in the most recent iteration only, so a row
	 * with a value but no observations is one carried forward from earlier iterations.
	 */
	public void write(String fileName) {
		SkimData snapshot = this.data;
		double binSize = params.getTimeBinSize();
		try (BufferedWriter writer = IOUtils.getBufferedWriter(fileName)) {
			writer.write(String.join(delimiter, "zone", "timeBin", "binStart", "binEnd",
					"observationsThisIteration", "waitTime"));
			writer.newLine();
			for (Map.Entry<Id<Zone>, double[]> entry : snapshot.zoneBin.entrySet()) {
				Id<Zone> zoneId = entry.getKey();
				double[] values = entry.getValue();
				int[] counts = snapshot.latestCounts.get(zoneId);
				for (int bin = 0; bin < values.length; bin++) {
					if (Double.isNaN(values[bin])) {
						continue;
					}
					writer.write(String.join(delimiter, //
							zoneId.toString(), //
							Integer.toString(bin), //
							Double.toString(bin * binSize), //
							Double.toString((bin + 1) * binSize), //
							Integer.toString(counts == null ? 0 : counts[bin]), //
							Double.toString(values[bin])));
					writer.newLine();
				}
			}
		} catch (IOException e) {
			log.error("Could not write DRT wait time skim for mode {} to {}", mode, fileName, e);
			throw new UncheckedIOException(e);
		}
	}

	private record SkimData(Map<Id<Zone>, double[]> zoneBin, Map<Id<Zone>, Double> zoneMean, double[] globalBin,
							double globalMean, Map<Id<Zone>, int[]> latestCounts) {

		static SkimData empty(int binCount) {
			return new SkimData(Map.of(), Map.of(), newNaNArray(binCount), Double.NaN, Map.of());
		}
	}

	private static final class Observations {
		private final int binCount;
		private final Map<Id<Zone>, double[]> sums = new HashMap<>();
		private final Map<Id<Zone>, int[]> counts = new HashMap<>();
		private final double[] globalSums;
		private final int[] globalCounts;
		private double totalSum = 0;
		private int totalCount = 0;

		Observations(int binCount) {
			this.binCount = binCount;
			this.globalSums = new double[binCount];
			this.globalCounts = new int[binCount];
		}

		void add(Id<Zone> zoneId, int bin, double waitTime) {
			sums.computeIfAbsent(zoneId, id -> new double[binCount])[bin] += waitTime;
			counts.computeIfAbsent(zoneId, id -> new int[binCount])[bin]++;
			globalSums[bin] += waitTime;
			globalCounts[bin]++;
			totalSum += waitTime;
			totalCount++;
		}
	}
}
