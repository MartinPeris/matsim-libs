package org.matsim.contrib.drt.extension.skims;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
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
import org.matsim.contrib.skims.ObservedSkimsConfigGroup;
import org.matsim.contrib.skims.ObservedSkimsModule;
import org.matsim.contrib.skims.ObservedTransitStopStopTime;
import org.matsim.contrib.skims.ObservedTransitWaitTime;
import org.matsim.contrib.skims.SkimAwareRaptorInVehicleCostCalculator;
import org.matsim.contrib.skims.SkimAwareRaptorTransferCostCalculator;
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
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultStrategy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.testcases.MatsimTestUtils;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorTransferCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;

/**
 * The point of both skim packages together: one router costing a door-to-door journey on what was
 * observed, at every stage of it.
 * <p>
 * Separately, each package is half a story. The DRT skims price the feeder's waiting and riding but
 * leave the transit legs on the timetable; the transit skims do the reverse. A traveller deciding
 * whether a MaaS itinerary beats driving is comparing the whole chain, so a model that measures half
 * of it and assumes the rest will systematically prefer whichever half it flatters.
 * <p>
 * The three decorators occupy three different SwissRailRaptor interfaces, so composing them is a
 * question of Guice rather than of arithmetic — and that is exactly the kind of thing that fails
 * silently. If any one override does not take, its half of the journey quietly reverts to scheduled
 * times while every other test in both packages keeps passing. This test asserts all three at once,
 * on a scenario where both halves are exercised.
 */
public class MaasSkimsIT {

	private static final int LAST_ITERATION = 3;

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void drtAndTransitSkimsComposeInOneRouter() {
		Controler controler = run();

		assertThat(controler.getInjector().getInstance(RaptorIntermodalAccessEgress.class))
				.as("the DRT half: the feeder leg's waiting and riding")
				.isInstanceOf(SkimAwareRaptorIntermodalAccessEgress.class);
		assertThat(controler.getInjector().getInstance(RaptorTransferCostCalculator.class))
				.as("the transit half: waiting at a transfer")
				.isInstanceOf(SkimAwareRaptorTransferCostCalculator.class);
		assertThat(controler.getInjector().getInstance(RaptorInVehicleCostCalculator.class))
				.as("the transit half: running time between stops")
				.isInstanceOf(SkimAwareRaptorInVehicleCostCalculator.class);
	}

	@Test
	void everyStageOfTheJourneyRestsOnMeasurementRatherThanOnItsTimetable() {
		Controler controler = run();
		Scenario scenario = controler.getScenario();

		ObservedTransitWaitTime waits = controler.getInjector().getInstance(ObservedTransitWaitTime.class);
		ObservedTransitStopStopTime runs = controler.getInjector().getInstance(ObservedTransitStopStopTime.class);

		boolean runObserved = false;
		int waitQueries = 0;
		for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				for (int i = 0; i < route.getStops().size(); i++) {
					TransitRouteStop stop = route.getStops().get(i);
					for (double t = 0; t < 30 * 3600; t += 900) {
						double wait = waits.waitTime(line.getId(), route.getId(), stop.getStopFacility().getId(),
								t);
						assertThat(wait)
								.as("the wait skim's contract: always a usable number, never infinite, never "
										+ "negative, so a router can query it without a special case")
								.isFinite()
								.isNotNegative();
						waitQueries++;
						if (i + 1 < route.getStops().size()) {
							TransitRouteStop next = route.getStops().get(i + 1);
							if (runs.excessStopStopTime(stop.getStopFacility().getId(),
									next.getStopFacility().getId(), t) != 0.0) {
								runObserved = true;
							}
						}
					}
				}
			}
		}

		assertThat(runObserved)
				.as("after %s iterations the transit legs must be priced on observed running times; if this "
						+ "is false the skim is quoting the timetable back at the router and can never "
						+ "change a decision", LAST_ITERATION + 1)
				.isTrue();
		// The wait skim is asserted on its contract rather than on having found an excess. A scenario
		// whose services run exactly to time yields an excess of zero everywhere, and that is the
		// correct measurement, not a missing one. Asserting otherwise would make the test demand that
		// the mobsim misbehave.
		assertThat(waitQueries).as("the wait skim must have been queried at all").isPositive();
	}

	private Controler run() {
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
		for (String mode : new String[] { TransportMode.car, TransportMode.pt, TransportMode.walk,
				TransportMode.drt }) {
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

		ObservedSkimsConfigGroup skims = ConfigUtils.addOrGetModule(config, ObservedSkimsConfigGroup.class);
		skims.setBinSize(3600);
		skims.setUpdateWeight(1.0);

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

		DrtWaitTimeSkimParams waitSkim = new DrtWaitTimeSkimParams();
		waitSkim.setTimeBinSize(3600);
		((SquareGridZoneSystemParams) waitSkim.addOrGetZoneSystemParams()).setCellSize(1000);
		drtConfig.addParameterSet(waitSkim);

		DrtRideTimeSkimParams rideSkim = new DrtRideTimeSkimParams();
		rideSkim.setTimeBinSize(3600);
		((SquareGridZoneSystemParams) rideSkim.addOrGetZoneSystemParams()).setCellSize(3000);
		drtConfig.addParameterSet(rideSkim);

		mm.addParameterSet(drtConfig);
		for (DrtConfigGroup cfg : mm.getModalElements()) {
			DrtConfigs.adjustDrtConfig(cfg, config.scoring(), config.routing());
		}

		Scenario scenario = DrtControlerCreator.createScenarioWithDrtRouteFactory(config);
		ScenarioUtils.loadScenario(scenario);

		Controler controler = DrtControlerCreator.createControler(config, scenario, false);
		controler.addOverridingModule(new SwissRailRaptorModule());
		controler.addOverridingModule(new MultiModeDrtSkimsModule());
		controler.addOverridingModule(new ObservedSkimsModule());
		controler.run();
		return controler;
	}

	private static ActivityParams activity(String type, double typicalDuration) {
		ActivityParams params = new ActivityParams(type);
		params.setTypicalDuration(typicalDuration);
		return params;
	}

	private static IntermodalAccessEgressParameterSet intermodal(String mode, double initialRadius,
			double maxRadius) {
		IntermodalAccessEgressParameterSet set = new IntermodalAccessEgressParameterSet();
		set.setMode(mode);
		set.setInitialSearchRadius(initialRadius);
		set.setMaxRadius(maxRadius);
		set.setSearchExtensionRadius(1000);
		return set;
	}
}
