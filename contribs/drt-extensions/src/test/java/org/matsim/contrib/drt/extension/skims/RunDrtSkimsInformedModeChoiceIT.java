package org.matsim.contrib.drt.extension.skims;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.estimator.DrtEstimator;
import org.matsim.contrib.drt.extension.DrtTestScenario;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.extension.modechoice.MultiModalDrtLegEstimator;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpMode;
import org.matsim.contrib.dvrp.run.DvrpModes;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.modechoice.InformedModeChoiceModule;
import org.matsim.modechoice.ModeOptions;
import org.matsim.modechoice.estimators.DefaultLegScoreEstimator;
import org.matsim.testcases.MatsimTestUtils;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;

/**
 * The direct-DRT path: trips made entirely by DRT, chosen by informed mode choice, never pass
 * through SwissRailRaptor. This runs the Kelheim test scenario with both skims configured and checks
 * that the estimator informed mode choice consults is the skim-backed one, and that after a few
 * iterations it answers observed pairs from measurement rather than from the constraint ceiling.
 *
 * @author Monash Healthy Active Cities
 */
public class RunDrtSkimsInformedModeChoiceIT {

	private static final int LAST_ITERATION = 2;

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void informedModeChoiceSeesObservedRideAndWaitForDirectDrtTrips() {
		Config config = DrtTestScenario.loadConfig(utils);
		// typed before anything materialises the parsed group, so the modal elements can carry skims
		config.addModule(new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new));
		config.controller().setLastIteration(LAST_ITERATION);
		config.controller().setWriteEventsInterval(0);

		Controler controler = MATSimApplication.prepare(
				new DrtTestScenario(RunDrtSkimsInformedModeChoiceIT::prepare, RunDrtSkimsInformedModeChoiceIT::prepare),
				config);
		controler.run();

		assertThat(new File(utils.getOutputDirectory(), "kelheim-mini-drt.drt_estimates_drt.csv"))
				.as("DrtEstimateAnalyzer installed by the skims module").exists().isNotEmpty();

		Map<DvrpMode, DrtEstimator> estimators = controler.getInjector()
				.getInstance(Key.get(new TypeLiteral<Map<DvrpMode, DrtEstimator>>() {}));
		DrtEstimator estimator = estimators.get(DvrpModes.mode("drt"));
		assertThat(estimator).as("the estimator informed mode choice consults")
				.isInstanceOf(SkimBackedDrtEstimator.class);

		// ask the estimator about pairs the fleet actually served in the last iteration: at least
		// some must now be answered below the ceiling, i.e. from measurement
		DrtEventSequenceCollector collector = controler.getInjector()
				.getInstance(Key.get(DrtEventSequenceCollector.class, DvrpModes.mode("drt")));
		DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(config).getModalElements().iterator().next();
		var constraints = drtCfg.addOrGetDrtOptimizationConstraintsParams().addOrGetDefaultDrtOptimizationConstraintsSet();
		double alpha = constraints.getMaxTravelTimeAlpha();
		double beta = constraints.getMaxTravelTimeBeta();

		List<DrtEventSequenceCollector.EventSequence> served = collector.getPerformedRequestSequences().values().stream()
				.filter(s -> s.getPersonEvents().values().stream().anyMatch(p -> p.getDroppedOff().isPresent()))
				.collect(Collectors.toList());
		assertThat(served).as("served DRT requests to probe").isNotEmpty();

		long belowCeiling = served.stream().filter(seq -> {
			DrtRoute route = new DrtRoute(seq.getSubmitted().getFromLinkId(), seq.getSubmitted().getToLinkId());
			route.setDirectRideTime(seq.getSubmitted().getUnsharedRideTime());
			route.setDistance(seq.getSubmitted().getUnsharedRideDistance());
			double ceiling = alpha * route.getDirectRideTime() + beta;
			return estimator.estimate(route, OptionalTime.defined(seq.getSubmitted().getTime())).rideTime() < ceiling - 1e-6;
		}).count();
		assertThat(belowCeiling).as("served pairs estimated from measurement rather than the ceiling, of %s", served.size())
				.isPositive();
	}

	private static void prepare(Config config) {
		List<ReplanningConfigGroup.StrategySettings> strategies = config.replanning().getStrategySettings().stream()
				.filter(s -> !s.getStrategyName().toLowerCase().contains("mode"))
				.collect(Collectors.toList());
		strategies.add(new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(InformedModeChoiceModule.SELECT_SUBTOUR_MODE_STRATEGY)
				.setSubpopulation("person")
				.setWeight(0.2));
		config.replanning().clearStrategySettings();
		strategies.forEach(config.replanning()::addStrategySettings);

		for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
			DrtWithExtensionsConfigGroup extended = (DrtWithExtensionsConfigGroup) drtCfg;
			DrtWaitTimeSkimParams wait = new DrtWaitTimeSkimParams();
			wait.setTimeBinSize(3600);
			((SquareGridZoneSystemParams) wait.addOrGetZoneSystemParams()).setCellSize(1000);
			extended.addParameterSet(wait);

			DrtRideTimeSkimParams ride = new DrtRideTimeSkimParams();
			ride.setTimeBinSize(3600);
			((SquareGridZoneSystemParams) ride.addOrGetZoneSystemParams()).setCellSize(2000);
			extended.addParameterSet(ride);
		}
	}

	private static void prepare(Controler controler) {
		controler.addOverridingModule(InformedModeChoiceModule.newBuilder()
				.withLegEstimator(DefaultLegScoreEstimator.class, ModeOptions.AlwaysAvailable.class, "bike", "walk", "pt")
				.withLegEstimator(DefaultLegScoreEstimator.class, ModeOptions.ConsiderYesAndNo.class, "car")
				.withLegEstimator(MultiModalDrtLegEstimator.class, ModeOptions.AlwaysAvailable.class, "drt", "av")
				.build());
		controler.addOverridingModule(new MultiModeDrtSkimsModule());
	}
}
