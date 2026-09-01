package org.matsim.contrib.drt.extension.skims;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.Key;
import com.google.inject.TypeLiteral;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.DrtControlerCreator;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import org.matsim.core.config.groups.VspExperimentalConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultStrategy;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;

/**
 * Runs the wait-time skim inside a real intermodal DRT+PT mobsim.
 * <p>
 * The unit tests exercise the skim's arithmetic and the decorator's return value; neither touches
 * the Guice wiring, which is where an overriding module that replaces a SwissRailRaptor binding can
 * fail without any of them noticing. This drives a full controler and asserts on what the run
 * actually produced: that the decorator really did replace {@code RaptorIntermodalAccessEgress},
 * that a skim CSV lands in every iteration directory with observed values in it, and that after a
 * few iterations of observation lookups are answered from measurement rather than from the
 * configured constant.
 * <p>
 * The scenario is contribs/av's intermodal taxi+PT example with DRT substituted for taxi.
 *
 * @author Monash Healthy Active Cities
 */
public class RunDrtSkimsIT {

	private static final int LAST_ITERATION = 3;

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void waitTimesAreObservedAndFedIntoIntermodalRouting() throws Exception {
		String in = utils.getPackageInputDirectory();

		Config config = ConfigUtils.createConfig();
		config.network().setInputFile(in + "network.xml");
		config.plans().setInputFile(in + "population100.xml");
		config.transit().setUseTransit(true);
		config.transit().setTransitScheduleFile(in + "transitschedule.xml");
		config.transit().setVehiclesFile(in + "transitVehicles.xml");

		config.controller().setOutputDirectory(utils.getOutputDirectory());
		config.controller().setLastIteration(LAST_ITERATION);
		config.controller().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setCompressionType(ControllerConfigGroup.CompressionType.none);
		config.global().setCoordinateSystem("Atlantis");
		config.global().setNumberOfThreads(1);
		config.qsim().setStartTime(0);
		config.qsim().setEndTime(30 * 3600);
		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);
		config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);
		config.vspExperimental().setVspDefaultsCheckingLevel(VspExperimentalConfigGroup.VspDefaultsCheckingLevel.warn);

		config.scoring().addActivityParams(activity("h", 12 * 3600));
		config.scoring().addActivityParams(activity("w", 8 * 3600));
		for (String mode : new String[] { TransportMode.car, TransportMode.pt, TransportMode.walk, TransportMode.drt }) {
			ModeParams modeParams = new ModeParams(mode);
			modeParams.setMarginalUtilityOfTraveling(-6);
			config.scoring().addModeParams(modeParams);
		}

		ReplanningConfigGroup.StrategySettings reRoute = new ReplanningConfigGroup.StrategySettings();
		reRoute.setStrategyName(DefaultStrategy.ReRoute);
		reRoute.setWeight(0.3);
		config.replanning().addStrategySettings(reRoute);
		ReplanningConfigGroup.StrategySettings changeExp = new ReplanningConfigGroup.StrategySettings();
		changeExp.setStrategyName(DefaultSelector.ChangeExpBeta);
		changeExp.setWeight(0.7);
		config.replanning().addStrategySettings(changeExp);

		SwissRailRaptorConfigGroup raptor = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);
		raptor.setUseIntermodalAccessEgress(true);
		raptor.addIntermodalAccessEgress(intermodal(TransportMode.drt, 15000, 20000));
		raptor.addIntermodalAccessEgress(intermodal(TransportMode.walk, 1000, 1000));

		ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);

		MultiModeDrtConfigGroup mm = new MultiModeDrtConfigGroup(DrtWithExtensionsConfigGroup::new);
		config.addModule(mm);
		DrtWithExtensionsConfigGroup drtConfig = new DrtWithExtensionsConfigGroup();
		drtConfig.setMode(TransportMode.drt);
		drtConfig.setVehiclesFile(in + "drt_vehicles.xml");
		drtConfig.setStopDuration(60.);
		DrtOptimizationConstraintsSetImpl constraints = drtConfig.addOrGetDrtOptimizationConstraintsParams()
				.addOrGetDefaultDrtOptimizationConstraintsSet();
		constraints.setMaxTravelTimeAlpha(1.5);
		constraints.setMaxTravelTimeBeta(10. * 60.);
		constraints.setMaxWaitTime(15. * 60.);
		constraints.setRejectRequestIfMaxWaitOrTravelTimeViolated(true);
		drtConfig.addParameterSet(new ExtensiveInsertionSearchParams());

		DrtWaitTimeSkimParams skimParams = new DrtWaitTimeSkimParams();
		skimParams.setTimeBinSize(3600);
		((SquareGridZoneSystemParams) skimParams.addOrGetZoneSystemParams()).setCellSize(1000);
		drtConfig.addParameterSet(skimParams);

		DrtRideTimeSkimParams rideSkimParams = new DrtRideTimeSkimParams();
		rideSkimParams.setTimeBinSize(3600);
		// pairs, not single zones, so the cells are coarser to keep them from going empty
		((SquareGridZoneSystemParams) rideSkimParams.addOrGetZoneSystemParams()).setCellSize(3000);
		drtConfig.addParameterSet(rideSkimParams);

		mm.addParameterSet(drtConfig);
		for (DrtConfigGroup cfg : mm.getModalElements()) {
			DrtConfigs.adjustDrtConfig(cfg, config.scoring(), config.routing());
		}

		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);

		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new SwissRailRaptorModule());
		controler.addOverridingModule(new MultiModeDrtSkimsModule());
		controler.run();

		// the overriding module must actually have replaced SwissRailRaptor's binding; if it did
		// not, everything below still passes while routing silently ignores waiting
		assertThat(controler.getInjector().getInstance(RaptorIntermodalAccessEgress.class))
				.isInstanceOf(SkimAwareRaptorIntermodalAccessEgress.class);

		for (int iteration = 0; iteration <= LAST_ITERATION; iteration++) {
			Path csv = Path.of(utils.getOutputDirectory(), "ITERS", "it." + iteration,
					iteration + ".drtWaitTimeSkim_drt.csv");
			assertThat(csv).as("skim CSV for iteration %s", iteration).exists();
			assertThat(Files.readAllLines(csv)).as("skim CSV for iteration %s", iteration).hasSizeGreaterThan(1);
		}

		// the published skim must rest on measurement, not on defaultWaitTime
		Map<String, DrtWaitTimeSkim> skims = controler.getInjector()
				.getInstance(Key.get(new TypeLiteral<Map<String, DrtWaitTimeSkim>>() {}));
		DrtWaitTimeSkim skim = skims.get(TransportMode.drt);
		assertThat(skim).isNotNull();

		Map<DrtWaitTimeSkim.Source, Integer> tally = new EnumMap<>(DrtWaitTimeSkim.Source.class);
		for (Link link : scenario.getNetwork().getLinks().values()) {
			for (double time : new double[] { 7 * 3600, 8 * 3600 + 1800, 12 * 3600, 15 * 3600 + 1800 }) {
				tally.merge(skim.lookup(link.getId(), time).source(), 1, Integer::sum);
			}
		}

		assertThat(tally.getOrDefault(DrtWaitTimeSkim.Source.DEFAULT, 0))
				.as("lookups resting on the configured constant, after %s iterations of observation: %s",
						LAST_ITERATION + 1, tally)
				.isZero();
		assertThat(tally.getOrDefault(DrtWaitTimeSkim.Source.ZONE_TIME_BIN, 0))
				.as("lookups answered from this zone and bin: %s", tally)
				.isPositive();

		// --- the ride-time skim -----------------------------------------------------------------

		for (int iteration = 0; iteration <= LAST_ITERATION; iteration++) {
			Path csv = Path.of(utils.getOutputDirectory(), "ITERS", "it." + iteration,
					iteration + ".drtRideTimeSkim_drt.csv");
			assertThat(csv).as("ride skim CSV for iteration %s", iteration).exists();
		}
		Path lastRideCsv = Path.of(utils.getOutputDirectory(), "ITERS", "it." + LAST_ITERATION,
				LAST_ITERATION + ".drtRideTimeSkim_drt.csv");
		assertThat(Files.readAllLines(lastRideCsv)).as("observed ride-time factors").hasSizeGreaterThan(1);

		Map<String, DrtRideTimeSkim> rideSkims = controler.getInjector()
				.getInstance(Key.get(new TypeLiteral<Map<String, DrtRideTimeSkim>>() {}));
		DrtRideTimeSkim rideSkim = rideSkims.get(TransportMode.drt);
		assertThat(rideSkim).isNotNull();

		// every observed factor must be a real ride against a real unshared quote, so at least 1.0;
        // a value below that would mean the reference was wrong rather than the ride fast
		List<String> rows = Files.readAllLines(lastRideCsv);
		for (String row : rows.subList(1, rows.size())) {
			String[] fields = row.split(";");
			double factor = Double.parseDouble(fields[6]);
			assertThat(factor).as("ride-time factor in row %s", row).isGreaterThanOrEqualTo(1.0);
		}
	}

	private static ActivityParams activity(String type, double typicalDuration) {
		ActivityParams params = new ActivityParams(type);
		params.setTypicalDuration(typicalDuration);
		return params;
	}

	private static IntermodalAccessEgressParameterSet intermodal(String mode, double initialRadius, double maxRadius) {
		IntermodalAccessEgressParameterSet set = new IntermodalAccessEgressParameterSet();
		set.setMode(mode);
		set.setInitialSearchRadius(initialRadius);
		set.setSearchExtensionRadius(0.1);
		set.setMaxRadius(maxRadius);
		return set;
	}
}
