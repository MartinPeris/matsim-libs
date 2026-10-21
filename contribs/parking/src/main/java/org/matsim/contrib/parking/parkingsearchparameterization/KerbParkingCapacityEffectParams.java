package org.matsim.contrib.parking.parkingsearchparameterization;

import java.util.Locale;

/**
 * How parked cars reduce the capacity and speed of the street they are parked on.
 * <p>
 * With {@code s} the occupied kerb share, a link in scope gets
 * <ul>
 * <li>flow and storage factor {@code f = max(fMin, 1 - w * s)}: flow capacity and number of lanes are the link's base
 * values times {@code f};</li>
 * <li>speed factor {@code g = max(sMin, 1 - v * s)}: free speed is the base free speed times {@code g}.</li>
 * </ul>
 * {@code s} depends on {@link EffectMode}:
 * <ul>
 * <li>{@link EffectMode#off}: no effect; the run is identical to one without this feature.</li>
 * <li>{@link EffectMode#link}: {@code s} is the link's own on-street occupancy over its on-street capacity. Applies to
 * kerb-eligible links with positive scaled capacity.</li>
 * <li>{@link EffectMode#cell}: {@code s} is the summed on-street occupancy over the summed on-street capacity of every
 * kerb-eligible link whose midpoint lies in the same square cell of side {@code cellSizeMetres}, and is applied to
 * every kerb-eligible link in the cell, including those whose own scaled capacity is zero. Applies in cells with
 * positive summed capacity. This exists because at a 10% sample one scaled kerb space stands for about ten real
 * ones, so a single link's share jumps in steps of a tenth or more and most links have no scaled space at all.</li>
 * </ul>
 * Occupancy is re-read every time step but the factors are re-evaluated only every {@code updateIntervalSeconds}, so a
 * link receives at most one change per interval. See {@link KerbParkingCapacityEffect} for why the interval exists
 * and for the lag it implies.
 *
 * @param mode                  off, link or cell
 * @param cellSizeMetres        side of a cell in cell mode, in the network's (projected) coordinate units
 * @param fMin                  lower bound of the flow and storage factor, in [0, 1]
 * @param w                     flow and storage reduction per unit of occupied share, at least 0
 * @param sMin                  lower bound of the speed factor, in (0, 1]
 * @param v                     speed reduction per unit of occupied share, at least 0
 * @param updateIntervalSeconds how often factors are re-evaluated, at least 1 s
 * @param flowTolerance         how far the applied flow factor may lag behind {@code f} before flow capacity and lanes
 *                              are changed, in [0, 0.5). 0, the default, applies {@code f} exactly. A positive value
 *                              trades a bounded error in {@code f} for fewer capacity changes; see
 *                              {@link KerbParkingCapacityEffect} on why fewer changes can matter. The speed factor is
 *                              always applied exactly, and a return to {@code s = 0} always restores the base.
 */
public record KerbParkingCapacityEffectParams(EffectMode mode, double cellSizeMetres, double fMin, double w,
											  double sMin, double v, double updateIntervalSeconds, double flowTolerance) {

	public enum EffectMode {off, link, cell}

	public static final double DEFAULT_CELL_SIZE_METRES = 300.0;
	public static final double DEFAULT_F_MIN = 0.5;
	public static final double DEFAULT_W = 0.5;
	public static final double DEFAULT_S_MIN = 0.7;
	public static final double DEFAULT_V = 0.2;
	/**
	 * One minute: well below the hours a car typically stays parked, and long enough that a link is not re-evaluated
	 * every second; see {@link KerbParkingCapacityEffect} on why frequent changes matter.
	 */
	public static final double DEFAULT_UPDATE_INTERVAL_SECONDS = 60.0;
	public static final double DEFAULT_FLOW_TOLERANCE = 0.0;

	public KerbParkingCapacityEffectParams {
		if (mode == null) {
			throw new IllegalArgumentException("mode must not be null");
		}
		if (!(cellSizeMetres > 0)) {
			throw new IllegalArgumentException("cellSizeMetres must be positive, got " + cellSizeMetres);
		}
		if (!(fMin >= 0 && fMin <= 1)) {
			throw new IllegalArgumentException("fMin must be in [0, 1], got " + fMin);
		}
		if (!(w >= 0)) {
			throw new IllegalArgumentException("w must be at least 0, got " + w);
		}
		if (!(sMin > 0 && sMin <= 1)) {
			throw new IllegalArgumentException("sMin must be in (0, 1], got " + sMin + "; a free speed of zero would stop traffic for good");
		}
		if (!(v >= 0)) {
			throw new IllegalArgumentException("v must be at least 0, got " + v);
		}
		if (!(updateIntervalSeconds >= 1)) {
			throw new IllegalArgumentException("updateIntervalSeconds must be at least 1, got " + updateIntervalSeconds);
		}
		if (!(flowTolerance >= 0 && flowTolerance < 0.5)) {
			throw new IllegalArgumentException("flowTolerance must be in [0, 0.5), got " + flowTolerance);
		}
	}

	/** The seven specified parameters, with the default flow tolerance of 0 (exact). */
	public KerbParkingCapacityEffectParams(EffectMode mode, double cellSizeMetres, double fMin, double w, double sMin,
										   double v, double updateIntervalSeconds) {
		this(mode, cellSizeMetres, fMin, w, sMin, v, updateIntervalSeconds, DEFAULT_FLOW_TOLERANCE);
	}

	public static KerbParkingCapacityEffectParams defaults(EffectMode mode) {
		return new KerbParkingCapacityEffectParams(mode, DEFAULT_CELL_SIZE_METRES, DEFAULT_F_MIN, DEFAULT_W,
			DEFAULT_S_MIN, DEFAULT_V, DEFAULT_UPDATE_INTERVAL_SECONDS, DEFAULT_FLOW_TOLERANCE);
	}

	public static KerbParkingCapacityEffectParams off() {
		return defaults(EffectMode.off);
	}

	public KerbParkingCapacityEffectParams withMode(EffectMode newMode) {
		return new KerbParkingCapacityEffectParams(newMode, cellSizeMetres, fMin, w, sMin, v, updateIntervalSeconds, flowTolerance);
	}

	public KerbParkingCapacityEffectParams withUpdateIntervalSeconds(double seconds) {
		return new KerbParkingCapacityEffectParams(mode, cellSizeMetres, fMin, w, sMin, v, seconds, flowTolerance);
	}

	public KerbParkingCapacityEffectParams withFlowTolerance(double tolerance) {
		return new KerbParkingCapacityEffectParams(mode, cellSizeMetres, fMin, w, sMin, v, updateIntervalSeconds, tolerance);
	}

	/**
	 * The flow factor to apply, given the one applied now and the exact target: the target if it is 1 (restoration is
	 * always exact) or differs from the applied one by at least the tolerance, otherwise the applied one unchanged.
	 */
	public double flowFactorToApply(double applied, double target) {
		if (target == 1.0 || Math.abs(target - applied) >= flowTolerance) {
			return target;
		}
		return applied;
	}

	/** {@code max(fMin, 1 - w * s)}; exactly 1 at {@code s = 0}, so restoring to the base value is exact. */
	public double flowFactor(double share) {
		return Math.max(fMin, 1.0 - w * share);
	}

	/** {@code max(sMin, 1 - v * s)}; exactly 1 at {@code s = 0}. */
	public double speedFactor(double share) {
		return Math.max(sMin, 1.0 - v * share);
	}

	public String describe() {
		return String.format(Locale.ROOT,
			"effectMode=%s cellSize=%.1f m fMin=%s w=%s sMin=%s v=%s updateInterval=%.0f s flowTolerance=%s (f = max(fMin, 1 - w*s) on flow capacity and lanes; g = max(sMin, 1 - v*s) on free speed)",
			mode, cellSizeMetres, fMin, w, sMin, v, updateIntervalSeconds, flowTolerance);
	}
}
