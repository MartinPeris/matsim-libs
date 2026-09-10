package org.matsim.contrib.skims;

import java.util.Arrays;

import org.matsim.core.trafficmonitoring.TimeBinUtils;

/**
 * Running mean of observed durations per time-of-day bin, with damping across iterations.
 * <p>
 * The mean within an iteration is kept incrementally rather than as a sum and a count, so a bin observed
 * many times cannot overflow and two reads of the same bin always agree.
 * <p>
 * Across iterations the bin is damped: {@code kept = (1 - weight) * previous + weight * observed}. A skim
 * is an input to the routing that produces the next iteration's traffic, so an undamped skim can oscillate,
 * every traveller avoiding the stop that was slow last iteration and so making it fast, then crowding back.
 * Damping is the standard remedy and the same one MATSim's own travel-time feedback uses. A bin with
 * nothing observed this iteration keeps its previous value rather than decaying towards zero, because no
 * observation is an absence of evidence, not evidence of no wait.
 * <p>
 * Adapted from {@code org.matsim.contrib.pseudosimulation.trafficinfo.MeanByTimeBin}, which in turn
 * descends from Sergio Ordonez's {@code eventsBasedPTRouter} contrib.
 */
final class MeanByTimeBin {

	private final double[] kept;
	private final boolean[] published;
	private final double[] observed;
	private final int[] counts;

	MeanByTimeBin(int binCount) {
		this.kept = new double[binCount];
		this.published = new boolean[binCount];
		this.observed = new double[binCount];
		this.counts = new int[binCount];
	}

	int binCount() {
		return kept.length;
	}

	synchronized void add(int bin, double value) {
		counts[bin]++;
		observed[bin] += (value - observed[bin]) / counts[bin];
	}

	/** The damped value a consumer should use. */
	synchronized double mean(int bin) {
		return kept[bin];
	}

	/**
	 * Whether {@link #mean} has anything to report. True only once an iteration has been consolidated,
	 * never merely because observations are accumulating: a caller reading the skim during a mobsim must
	 * get the previous iteration's answer, not a half-built one. A flag rather than a test on the value,
	 * because a genuine damped mean of zero is a legitimate observation at a stop nobody waits at.
	 */
	synchronized boolean hasValue(int bin) {
		return published[bin];
	}

	synchronized int count(int bin) {
		return counts[bin];
	}

	/**
	 * Folds this iteration's observations into the kept value and clears them, ready for the next
	 * iteration. Bins with no observation are left alone.
	 */
	synchronized void consolidate(double weight) {
		for (int bin = 0; bin < kept.length; bin++) {
			if (counts[bin] > 0) {
				kept[bin] = (1.0 - weight) * kept[bin] + weight * observed[bin];
				published[bin] = true;
			}
		}
		Arrays.fill(observed, 0.0);
		Arrays.fill(counts, 0);
	}

	synchronized void reset() {
		Arrays.fill(published, false);
		Arrays.fill(kept, 0.0);
		Arrays.fill(observed, 0.0);
		Arrays.fill(counts, 0);
	}

	static int binOf(double time, double binSize, int binCount) {
		return TimeBinUtils.getTimeBinIndex(time, binSize, binCount);
	}
}
