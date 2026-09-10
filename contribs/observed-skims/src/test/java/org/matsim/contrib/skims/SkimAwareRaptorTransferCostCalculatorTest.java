package org.matsim.contrib.skims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The cost arithmetic only. The wiring, that Raptor actually consults this class and that the charge
 * lands on the right itinerary, cannot be reached from outside SwissRailRaptor's package because
 * {@code Transfer}'s fields are package-private, so it is covered by an integration test instead.
 */
class SkimAwareRaptorTransferCostCalculatorTest {

	/** MATSim's waiting utility is a disutility, so negative. -6 utils/hour in seconds. */
	private static final double MU_WAIT = -6.0 / 3600;

	@Test
	void nothingObservedCostsNothing() {
		assertEquals(0.0, SkimAwareRaptorTransferCostCalculator.charge(0.0, 1.0, MU_WAIT), 1e-12,
			"a skim that has learned nothing must leave every route's cost exactly as it found it");
	}

	@Test
	void excessWaitingIsChargedAtTheWaitingUtility() {
		// Ten minutes of unpredicted waiting at 6 utils per hour is one util.
		assertEquals(1.0, SkimAwareRaptorTransferCostCalculator.charge(600.0, 1.0, MU_WAIT), 1e-12);
	}

	@Test
	void theFactorScalesTheCharge() {
		assertEquals(1.5, SkimAwareRaptorTransferCostCalculator.charge(600.0, 1.5, MU_WAIT), 1e-12,
			"a factor above one prices unpredictable waiting above timetabled waiting");
		assertEquals(0.0, SkimAwareRaptorTransferCostCalculator.charge(600.0, 0.0, MU_WAIT), 1e-12,
			"and a factor of zero disables the charge without needing the skim uninstalled");
	}

	@Test
	void aServiceMoreReliableThanItsTimetableRefunds() {
		assertTrue(SkimAwareRaptorTransferCostCalculator.charge(-600.0, 1.0, MU_WAIT) < 0,
			"observing shorter waits than the timetable promises is a real finding and is reported as one; "
				+ "flooring it at zero would make the skim able to punish but never reward");
	}

	@Test
	void aNegativeFactorIsRefused() {
		assertThrows(IllegalArgumentException.class,
			() -> new SkimAwareRaptorTransferCostCalculator(new StubWaitTime(), -0.5));
	}

	private static final class StubWaitTime implements TransitWaitTime {
		@Override
		public double waitTime(org.matsim.api.core.v01.Id<org.matsim.pt.transitSchedule.api.TransitLine> lineId,
				org.matsim.api.core.v01.Id<org.matsim.pt.transitSchedule.api.TransitRoute> routeId,
				org.matsim.api.core.v01.Id<org.matsim.pt.transitSchedule.api.TransitStopFacility> stopId,
				double time) {
			return 0.0;
		}

		@Override
		public double excessWaitTime(org.matsim.api.core.v01.Id<org.matsim.pt.transitSchedule.api.TransitLine> lineId,
				org.matsim.api.core.v01.Id<org.matsim.pt.transitSchedule.api.TransitRoute> routeId,
				org.matsim.api.core.v01.Id<org.matsim.pt.transitSchedule.api.TransitStopFacility> stopId,
				double time) {
			return 0.0;
		}
	}
}
