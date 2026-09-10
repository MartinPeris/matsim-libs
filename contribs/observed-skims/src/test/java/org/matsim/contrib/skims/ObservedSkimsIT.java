package org.matsim.contrib.skims;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.testcases.MatsimTestUtils;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.RaptorTransferCostCalculator;

/**
 * Runs a real controler on a scenario whose travellers change lines, which is the only level at which
 * the Guice wiring is exercised.
 * <p>
 * The unit tests can check that the arithmetic is right and that the measurement is right, and both
 * would keep passing if the overriding module failed to replace SwissRailRaptor's transfer cost
 * calculator, leaving routing to ignore waiting entirely. That failure has exactly one symptom, and it
 * only appears here.
 */
class ObservedSkimsIT {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void theModuleReplacesRaptorsTransferCostAndTheSkimLearnsFromTheMobsim() {
		Controler controler = controler(2);
		controler.run();

		RaptorTransferCostCalculator bound = controler.getInjector().getInstance(RaptorTransferCostCalculator.class);
		assertInstanceOf(SkimAwareRaptorTransferCostCalculator.class, bound,
			"the overriding module must actually replace the binding; if it does not, every other test "
				+ "here still passes while routing silently ignores waiting");

		ObservedTransitWaitTime skim = controler.getInjector().getInstance(ObservedTransitWaitTime.class);
		assertNotNull(skim);
		assertTrue(observedSomewhere(controler.getScenario(), skim),
			"two iterations of a pt scenario must leave the skim with at least one measured wait, "
				+ "otherwise it is reporting the timetable back to itself and can never change a decision");
	}

	@Test
	void switchingTheSkimOffLeavesRaptorExactlyAsItWas() {
		Controler controler = controler(1);
		ConfigUtils.addOrGetModule(controler.getConfig(), ObservedSkimsConfigGroup.class).setWaitTimeEnabled(false);
		controler.run();

		RaptorTransferCostCalculator bound = controler.getInjector().getInstance(RaptorTransferCostCalculator.class);
		assertTrue(!(bound instanceof SkimAwareRaptorTransferCostCalculator),
			"a disabled skim must bind nothing at all, so installing the module unconditionally is safe");
	}

	/** True once any line, route and stop has a wait that differs from what the timetable implies. */
	private static boolean observedSomewhere(Scenario scenario, ObservedTransitWaitTime skim) {
		for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (TransitRouteStop stop : route.getStops()) {
					for (double t = 0; t < 30 * 3600; t += 900) {
						if (skim.observationCount(line.getId(), route.getId(), stop.getStopFacility().getId(), t) > 0
							|| skim.excessWaitTime(line.getId(), route.getId(), stop.getStopFacility().getId(), t)
								!= 0.0) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private Controler controler(int iterations) {
		Config config = ConfigUtils.loadConfig(
			ExamplesUtils.getTestScenarioURL("pt-simple-lineswitch").toString() + "config.xml",
			new SwissRailRaptorConfigGroup(), new ObservedSkimsConfigGroup());
		config.controller().setOutputDirectory(utils.getOutputDirectory());
		config.controller().setLastIteration(iterations - 1);
		config.controller().setOverwriteFileSetting(
			OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		Controler controler = new Controler(scenario);
		controler.addOverridingModule(new ObservedSkimsModule());
		return controler;
	}
}
