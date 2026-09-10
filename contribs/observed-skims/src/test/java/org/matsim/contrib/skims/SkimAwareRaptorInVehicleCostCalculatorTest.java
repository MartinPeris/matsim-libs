package org.matsim.contrib.skims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import ch.sbb.matsim.routing.pt.raptor.CapacityDependentInVehicleCostCalculator;

/**
 * The cost arithmetic and the one combination that must be refused. The segment walk itself needs a
 * populated RouteSegmentIterator, which nothing outside SwissRailRaptor's package can build.
 */
class SkimAwareRaptorInVehicleCostCalculatorTest {

	/** In-vehicle utility as a disutility per second: -6 utils/hour. */
	private static final double MU = -6.0 / 3600;

	@Test
	void nothingObservedCostsNothing() {
		assertEquals(0.0, SkimAwareRaptorInVehicleCostCalculator.charge(0.0, 1.0, MU), 1e-12);
	}

	@Test
	void lostTimeIsChargedAtTheInVehicleUtility() {
		assertEquals(1.0, SkimAwareRaptorInVehicleCostCalculator.charge(600.0, 1.0, MU), 1e-12);
	}

	@Test
	void theFactorScalesTheCharge() {
		assertEquals(2.0, SkimAwareRaptorInVehicleCostCalculator.charge(600.0, 2.0, MU), 1e-12);
	}

	@Test
	void aServiceBeatingItsTimetableRefunds() {
		assertTrue(SkimAwareRaptorInVehicleCostCalculator.charge(-600.0, 1.0, MU) < 0);
	}

	/**
	 * The failure this guard prevents is silent, which is why it is a guard and not a note in the
	 * javadoc: both calculators walk the same single-pass iterator, so the second finds it exhausted
	 * and returns a cost of zero for the whole leg.
	 */
	@Test
	void stackingOnTheCapacityDependentCalculatorIsRefused() {
		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
			() -> new SkimAwareRaptorInVehicleCostCalculator(new CapacityDependentInVehicleCostCalculator(),
				new StubStopStopTime(), 1.0));
		assertTrue(thrown.getMessage().contains("single-pass"), thrown.getMessage());
	}

	@Test
	void aNegativeFactorIsRefused() {
		assertThrows(IllegalArgumentException.class,
			() -> new SkimAwareRaptorInVehicleCostCalculator(new StubStopStopTime(), -1.0));
	}

	private static final class StubStopStopTime implements TransitStopStopTime {
		@Override
		public double stopStopTime(Id<TransitStopFacility> from, Id<TransitStopFacility> to, double time) {
			return 0.0;
		}

		@Override
		public double excessStopStopTime(Id<TransitStopFacility> from, Id<TransitStopFacility> to, double time) {
			return 0.0;
		}
	}
}
