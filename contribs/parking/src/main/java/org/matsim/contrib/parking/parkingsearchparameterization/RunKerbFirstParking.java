package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.qnetsimengine.ConstantArrivalTime;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Runs a scenario with kerb-first parking accounting and writes the spillover report.
 * <p>
 * Usage: {@code RunKerbFirstParking <config.xml> <outputDir> [iterations=0] [minimumLanes=2] [bayLengthMetres=6]}.
 * <p>
 * Every car arrival parks: on the kerb if a derived or attributed space is free, off-street otherwise, never refused.
 * Plans, routes and scores are unaffected; this is measurement only. The eligibility rule and bay length used are
 * logged and should be quoted with any result, because the kerb supply they derive depends on how the network was
 * coded (see {@link KerbParkingEligibility}).
 */
public final class RunKerbFirstParking {
	private static final Logger log = LogManager.getLogger(RunKerbFirstParking.class);

	private RunKerbFirstParking() {
	}

	public static void main(String[] args) {
		if (args.length < 2) {
			throw new IllegalArgumentException("usage: <config.xml> <outputDir> [iterations=0] [minimumLanes=2] [bayLengthMetres=6]");
		}
		int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 0;
		double minimumLanes = args.length > 3 ? Double.parseDouble(args[3]) : KerbParkingEligibility.MinimumLanes.DEFAULT_MINIMUM_LANES;
		double bayLength = args.length > 4 ? Double.parseDouble(args[4]) : KerbParkingSupplyParams.DEFAULT_BAY_LENGTH_METRES;

		Config config = ConfigUtils.loadConfig(args[0]);
		config.controller().setOutputDirectory(args[1]);
		config.controller().setLastIteration(iterations);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		logEligibility(scenario, minimumLanes);

		Controller controller = ControllerUtils.createController(scenario);
		controller.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				addQSimComponentBinding("ParkingOccupancyObserver").to(ParkingOccupancyObserver.class);
				addMobsimScopeEventHandlerBinding().to(ParkingOccupancyObserver.class);
				// One instance, bound both ways: the vehicle handler decides who parks, and it learns which vehicles
				// are transit from TransitDriverStartsEvent. Without the event binding it never learns, and transit
				// vehicles whose type has networkMode car are parked and counted as kerb demand.
				bind(ParkingVehicleHandler.class).in(Singleton.class);
				addVehicleHandlerBinding().to(ParkingVehicleHandler.class);
				addMobsimScopeEventHandlerBinding().to(ParkingVehicleHandler.class);
				addParkingSearchTimeCalculatorBinding().toInstance(new ConstantArrivalTime(0));
			}
		});
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(ParkingOccupancyObserver.class).in(Singleton.class);
				bind(ParkingCapacityInitializer.class).to(DerivedParkingCapacityInitializer.class);
				bind(KerbParkingEligibility.class).toInstance(new KerbParkingEligibility.MinimumLanes(minimumLanes));
				bind(KerbParkingSupplyParams.class).toInstance(new KerbParkingSupplyParams(bayLength));
				bind(ParkingSpilloverReport.class).in(Singleton.class);
				addControllerListenerBinding().to(ParkingOccupancyObserver.class);
				addMobsimListenerBinding().to(ParkingOccupancyObserver.class);
				addControllerListenerBinding().to(ParkingSpilloverReport.class);
				addMobsimListenerBinding().to(ParkingSpilloverReport.class);
			}
		});
		controller.run();
	}

	private static void logEligibility(Scenario scenario, double minimumLanes) {
		KerbParkingEligibility eligibility = new KerbParkingEligibility.MinimumLanes(minimumLanes);
		long total = scenario.getNetwork().getLinks().size();
		long carLinks = scenario.getNetwork().getLinks().values().stream()
			.filter(l -> l.getAllowedModes().contains(TransportMode.car)).count();
		long eligible = scenario.getNetwork().getLinks().values().stream()
			.filter(l -> l.getAllowedModes().contains(TransportMode.car)).filter(eligibility::isEligible).count();
		long attributed = scenario.getNetwork().getLinks().values().stream()
			.filter(l -> l.getAttributes().getAttribute(ParkingUtils.LINK_ON_STREET_SPOTS) != null).count();
		log.info("Kerb parking eligibility: minimumLanes={} -> {} of {} car links eligible ({}%; network has {} links in total); {} links carry an explicit {} attribute",
			minimumLanes, eligible, carLinks, String.format("%.1f", 100.0 * eligible / Math.max(1, carLinks)), total, attributed, ParkingUtils.LINK_ON_STREET_SPOTS);
	}

}
