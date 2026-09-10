package org.matsim.contrib.skims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Defaults and the validation that keeps a mistyped config from becoming a silently wrong result.
 */
class ObservedSkimsConfigGroupTest {

	@Test
	void theDefaultsClaimNothingBeyondMeasurement() {
		ObservedSkimsConfigGroup params = new ObservedSkimsConfigGroup();

		assertEquals(1.0, params.getWaitingCostFactor(), 1e-12,
			"1.0 is the only value that embeds no behavioural claim: a minute is a minute");
		assertEquals(1.0, params.getUnreliabilityCostFactor(), 1e-12);
		assertEquals(900.0, params.getBinSize(), 1e-12);
		assertEquals(0.5, params.getUpdateWeight(), 1e-12);
		assertTrue(params.isWaitTimeEnabled());
		assertTrue(params.isStopStopTimeEnabled());
	}

	@Test
	void everyKnobIsDocumented() {
		ObservedSkimsConfigGroup params = new ObservedSkimsConfigGroup();

		for (String key : new String[]{"binSize", "updateWeight", "waitingCostFactor",
			"unreliabilityCostFactor", "waitTimeEnabled", "stopStopTimeEnabled"}) {
			assertTrue(params.getComments().containsKey(key),
				key + " has no comment, so a config file written from this group would not explain it");
		}
	}

	@Test
	void anUnusableBinSizeIsRefusedAtTheConfigRatherThanAtTheSkim() {
		ObservedSkimsConfigGroup params = new ObservedSkimsConfigGroup();

		assertThrows(IllegalArgumentException.class, () -> params.setBinSize(0.0));
		assertThrows(IllegalArgumentException.class, () -> params.setBinSize(-900.0));
	}

	@Test
	void anUpdateWeightOutsideItsRangeIsRefused() {
		ObservedSkimsConfigGroup params = new ObservedSkimsConfigGroup();

		assertThrows(IllegalArgumentException.class, () -> params.setUpdateWeight(0.0),
			"a weight of nought would mean the skim never learns, which is a silent no-op");
		assertThrows(IllegalArgumentException.class, () -> params.setUpdateWeight(1.5),
			"and above one it would overshoot every observation, diverging rather than damping");
		params.setUpdateWeight(1.0);
		assertEquals(1.0, params.getUpdateWeight(), 1e-12, "exactly one is undamped, which is legitimate");
	}

	@Test
	void negativeCostFactorsAreRefused() {
		ObservedSkimsConfigGroup params = new ObservedSkimsConfigGroup();

		assertThrows(IllegalArgumentException.class, () -> params.setWaitingCostFactor(-1.0),
			"a negative factor would pay travellers to wait");
		assertThrows(IllegalArgumentException.class, () -> params.setUnreliabilityCostFactor(-1.0));
	}

	@Test
	void eitherSkimCanBeSwitchedOffIndependently() {
		ObservedSkimsConfigGroup params = new ObservedSkimsConfigGroup();

		params.setWaitTimeEnabled(false);
		assertFalse(params.isWaitTimeEnabled());
		assertTrue(params.isStopStopTimeEnabled(), "switching one off must not switch the other off");

		params.setStopStopTimeEnabled(false);
		assertFalse(params.isStopStopTimeEnabled());
	}
}
