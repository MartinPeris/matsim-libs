package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.matsim.contrib.parking.parkingsearchparameterization.KerbParkingCapacityEffectParams.EffectMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The factor formulas: {@code f = max(fMin, 1 - w*s)}, {@code g = max(sMin, 1 - v*s)}.
 */
class KerbParkingCapacityEffectParamsTest {

	@Test
	void defaultsAreTheSeptemberSpecification() {
		KerbParkingCapacityEffectParams p = KerbParkingCapacityEffectParams.defaults(EffectMode.cell);
		assertEquals(EffectMode.cell, p.mode());
		assertEquals(300.0, p.cellSizeMetres());
		assertEquals(0.5, p.fMin());
		assertEquals(0.5, p.w());
		assertEquals(0.7, p.sMin());
		assertEquals(0.2, p.v());
		assertEquals(60.0, p.updateIntervalSeconds());
		assertEquals(0.0, p.flowTolerance(), "exact by default");
		assertEquals(EffectMode.off, KerbParkingCapacityEffectParams.off().mode());
	}

	@Test
	void factorsAreLinearInTheShareWithDefaults() {
		KerbParkingCapacityEffectParams p = KerbParkingCapacityEffectParams.defaults(EffectMode.link);
		assertEquals(1.0, p.flowFactor(0.0), 0.0, "exactly 1 at s = 0, so restoring multiplies the base by exactly 1");
		assertEquals(1.0, p.speedFactor(0.0), 0.0);
		assertEquals(0.875, p.flowFactor(0.25), 1e-15);
		assertEquals(0.95, p.speedFactor(0.25), 1e-15);
		assertEquals(0.75, p.flowFactor(0.5), 1e-15);
		assertEquals(0.9, p.speedFactor(0.5), 1e-15);
		assertEquals(0.5, p.flowFactor(1.0), 1e-15, "with w = 0.5 a full kerb halves capacity and fMin = 0.5 is just reached");
		assertEquals(0.8, p.speedFactor(1.0), 1e-15, "with v = 0.2 a full kerb costs a fifth of free speed, above sMin = 0.7");
	}

	@Test
	void lowerBoundsClampTheFactors() {
		KerbParkingCapacityEffectParams p = new KerbParkingCapacityEffectParams(EffectMode.link, 300, 0.6, 1.0, 0.9, 0.5, 60);
		assertEquals(0.6, p.flowFactor(1.0), 0.0, "1 - 1.0 * 1 = 0 is clamped to fMin");
		assertEquals(0.7, p.flowFactor(0.3), 1e-15, "above the bound the formula applies");
		assertEquals(0.9, p.speedFactor(0.4), 0.0, "1 - 0.5 * 0.4 = 0.8 is clamped to sMin");
		assertEquals(0.95, p.speedFactor(0.1), 1e-15);
	}

	@Test
	void zeroWeightsGiveNoEffect() {
		KerbParkingCapacityEffectParams p = new KerbParkingCapacityEffectParams(EffectMode.link, 300, 0.5, 0.0, 0.7, 0.0, 60);
		assertEquals(1.0, p.flowFactor(1.0), 0.0);
		assertEquals(1.0, p.speedFactor(1.0), 0.0);
	}

	@Test
	void invalidParametersAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(null, 300, 0.5, 0.5, 0.7, 0.2, 60));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 0, 0.5, 0.5, 0.7, 0.2, 60));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, 1.1, 0.5, 0.7, 0.2, 60));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, -0.1, 0.5, 0.7, 0.2, 60));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, 0.5, -0.5, 0.7, 0.2, 60));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, 0.5, 0.5, 0.0, 0.2, 60),
			"a free speed factor of zero would stop traffic for good");
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, 0.5, 0.5, 0.7, -0.2, 60));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, 0.5, 0.5, 0.7, 0.2, 0.5));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, Double.NaN, 0.5, 0.5, 0.7, 0.2, 60));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, 0.5, 0.5, 0.7, 0.2, 60, -0.01));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingCapacityEffectParams(EffectMode.cell, 300, 0.5, 0.5, 0.7, 0.2, 60, 0.5));
	}

	@Test
	void flowToleranceHoldsTheAppliedFactorWithinItsBand() {
		KerbParkingCapacityEffectParams exact = KerbParkingCapacityEffectParams.defaults(EffectMode.link);
		assertEquals(0.99, exact.flowFactorToApply(1.0, 0.99), 0.0, "tolerance 0: every change applied");
		KerbParkingCapacityEffectParams p = exact.withFlowTolerance(0.05);
		assertEquals(1.0, p.flowFactorToApply(1.0, 0.96), 0.0, "0.04 below: held");
		assertEquals(0.95, p.flowFactorToApply(1.0, 0.95), 0.0, "0.05 below: applied");
		assertEquals(0.9, p.flowFactorToApply(0.9, 0.93), 0.0, "0.03 above: held");
		assertEquals(1.0, p.flowFactorToApply(0.98, 1.0), 0.0, "restoration to 1 is always applied");
	}
}
