package org.matsim.contrib.drt.extension.skims;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.application.MATSimApplication;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.estimator.DrtEstimator;
import org.matsim.contrib.drt.estimator.DrtEstimatorModule;
import org.matsim.contrib.drt.estimator.DrtEstimatorParams;
import org.matsim.contrib.drt.estimator.impl.DirectTripBasedDrtEstimator;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.ConstantRideDurationEstimator;
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
import org.matsim.core.controler.AbstractModule;
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

	private Controler run(boolean withSkims) {
		Config config = DrtTestScenario.loadConfig(utils);
		// typed before anything materialises the parsed group, so the modal elements can carry skims
		config.addModule(new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new));
		config.controller().setOutputDirectory(utils.getOutputDirectory() + (withSkims ? "skims/" : "baseline/"));
		config.controller().setLastIteration(LAST_ITERATION);
		config.controller().setWriteEventsInterval(0);

		Controler controler = MATSimApplication.prepare(new DrtTestScenario(
				withSkims ? RunDrtSkimsInformedModeChoiceIT::prepareWithSkims : RunDrtSkimsInformedModeChoiceIT::prepareBaseline,
				c -> prepare(c, withSkims)), config);
		controler.run();
		return controler;
	}

	@Test
	void informedModeChoiceSeesObservedRideAndWaitForDirectDrtTrips() {
		Controler controler = run(true);

		assertThat(new File(utils.getOutputDirectory(), "skims/kelheim-mini-drt.drt_estimates_drt.csv"))
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
		DrtConfigGroup drtCfg = MultiModeDrtConfigGroup.get(controler.getConfig()).getModalElements().iterator().next();
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

	/**
	 * The control. Same scenario and seed with a constant estimator fixed at the constraint ceiling,
	 * which is what the skim replaces and what a routed leg carries.
	 * <p>
	 * The two runs are <em>not</em> identical at iteration 0, and asserting so was wrong: Kelheim
	 * routes DRT as SwissRailRaptor intermodal access, and the skims module also installs
	 * {@link SkimAwareRaptorIntermodalAccessEgress}, which adds the wait skim's default to those legs
	 * before anything is observed. That shifts iteration-0 demand (38 vs 42 rides here). So each
	 * estimator is judged against its own run's mobsim, which is what {@code drt_estimates} measures.
	 * <p>
	 * What can be asserted: the skim estimator's error falls from its cold start, and ends lower than
	 * the ceiling's on both wait and ride. What cannot: that the ceiling's error stays put. It has
	 * nothing to learn, but the system under it still drifts, and in one run its ride error fell 11%
	 * for that reason alone. Iteration 0 is reproducible across reruns; later iterations vary
	 * run to run - the Kelheim config sets four threads and the extensive insertion search runs on a
	 * fork-join pool - so the margins here need to be wide, and were about 2x in every run seen.
	 */
	@Test
	void theSkimEstimatorPredictsTheMobsimBetterThanTheCeilingItReplaces() throws Exception {
		run(true);
		run(false);

		List<double[]> skims = readMae(Path.of(utils.getOutputDirectory(), "skims", "kelheim-mini-drt.drt_estimates_drt.csv"));
		List<double[]> baseline = readMae(Path.of(utils.getOutputDirectory(), "baseline", "kelheim-mini-drt.drt_estimates_drt.csv"));
		assertThat(skims).hasSize(LAST_ITERATION + 1);
		assertThat(baseline).hasSize(LAST_ITERATION + 1);

		System.out.println("### iteration | wait MAE skims / baseline | ride MAE skims / baseline");
		for (int i = 0; i <= LAST_ITERATION; i++) {
			System.out.printf("### %d | %.0f / %.0f | %.0f / %.0f%n", i,
					skims.get(i)[0], baseline.get(i)[0], skims.get(i)[1], baseline.get(i)[1]);
		}

		// the skim estimator learns: its error falls from the cold start
		assertThat(skims.get(LAST_ITERATION)[0]).as("skims wait MAE, last vs first").isLessThan(skims.get(0)[0]);
		assertThat(skims.get(LAST_ITERATION)[1]).as("skims ride MAE, last vs first").isLessThan(skims.get(0)[1]);

		// and the learned estimate ends up the better predictor
		assertThat(skims.get(LAST_ITERATION)[0]).as("last-iteration wait MAE").isLessThan(baseline.get(LAST_ITERATION)[0]);
		assertThat(skims.get(LAST_ITERATION)[1]).as("last-iteration ride MAE").isLessThan(baseline.get(LAST_ITERATION)[1]);
	}

	/** wait MAE (column 1) and travel-time MAE (column 5) per iteration. */
	private static List<double[]> readMae(Path csv) throws Exception {
		List<String> rows = Files.readAllLines(csv);
		return rows.subList(1, rows.size()).stream().map(r -> {
			String[] f = r.split(",");
			return new double[] { Double.parseDouble(f[1]), Double.parseDouble(f[5]) };
		}).collect(Collectors.toList());
	}

	private static void prepare(Config config, boolean withSkims) {
		List<ReplanningConfigGroup.StrategySettings> strategies = config.replanning().getStrategySettings().stream()
				.filter(s -> !s.getStrategyName().toLowerCase().contains("mode"))
				.collect(Collectors.toList());
		strategies.add(new ReplanningConfigGroup.StrategySettings()
				.setStrategyName(InformedModeChoiceModule.SELECT_SUBTOUR_MODE_STRATEGY)
				.setSubpopulation("person")
				.setWeight(0.2));
		config.replanning().clearStrategySettings();
		strategies.forEach(config.replanning()::addStrategySettings);

		if (!withSkims) {
			return;
		}
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

	private static void informedModeChoice(Controler controler) {
		controler.addOverridingModule(InformedModeChoiceModule.newBuilder()
				.withLegEstimator(DefaultLegScoreEstimator.class, ModeOptions.AlwaysAvailable.class, "bike", "walk", "pt")
				.withLegEstimator(DefaultLegScoreEstimator.class, ModeOptions.ConsiderYesAndNo.class, "car")
				.withLegEstimator(MultiModalDrtLegEstimator.class, ModeOptions.AlwaysAvailable.class, "drt", "av")
				.build());
	}

	private static void prepareWithSkims(Controler controler) {
		informedModeChoice(controler);
		controler.addOverridingModule(new MultiModeDrtSkimsModule());
	}

	/** A constant estimator at the constraint ceiling, plus the analyzer so the same CSV is written. */
	private static void prepareBaseline(Controler controler) {
		informedModeChoice(controler);
		controler.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(getConfig()).getModalElements()) {
					install(new DrtEstimatorModule(drtCfg.getMode(), drtCfg,
							drtCfg.getDrtEstimatorParams().orElseGet(DrtEstimatorParams::new)));
					var constraints = drtCfg.addOrGetDrtOptimizationConstraintsParams()
							.addOrGetDefaultDrtOptimizationConstraintsSet();
					DrtEstimatorModule.bindEstimator(binder(), drtCfg.getMode()).toInstance(
							new DirectTripBasedDrtEstimator.Builder()
									.setRideDurationEstimator(new ConstantRideDurationEstimator(
											constraints.getMaxTravelTimeAlpha(), constraints.getMaxTravelTimeBeta()))
									.build());
				}
			}
		});
	}
}
