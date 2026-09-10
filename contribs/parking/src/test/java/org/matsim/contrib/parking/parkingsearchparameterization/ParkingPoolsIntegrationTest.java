package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.qnetsimengine.ConstantArrivalTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kerb-first pools, the derived supply and the spillover report wired together through Guice on {@code equil}.
 * <p>
 * {@code equil} carries no parking attributes and every link has one lane, so this exercises the derivation path
 * with the lane threshold lowered to one. Content is covered by the unit tests; this checks the wiring produces the
 * files where the report says they are.
 */
class ParkingPoolsIntegrationTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void derivedSupplyAndSpilloverReportRunEndToEnd() throws IOException {
		Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("equil"), "config_plans1.xml"));
		config.controller().setOutputDirectory(utils.getOutputDirectory());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		config.controller().setCompressionType(ControllerConfigGroup.CompressionType.gzip);
		config.replanning().clearStrategySettings();
		config.routing().setAccessEgressConsistencyCheck(RoutingConfigGroup.AccessEgressConsistencyCheck.disable);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		Controller controller = ControllerUtils.createController(scenario);

		controller.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				addQSimComponentBinding("ParkingOccupancyOberserver").to(ParkingOccupancyObserver.class);
				addMobsimScopeEventHandlerBinding().to(ParkingOccupancyObserver.class);
				// One instance, bound both ways: the vehicle handler decides who parks, and it learns which vehicles
				// are transit from TransitDriverStartsEvent. Without the event binding it never learns, and transit
				// vehicles whose type has networkMode car are parked and counted as kerb demand.
				bind(ParkingVehicleHandler.class).in(Singleton.class);
				addVehicleHandlerBinding().to(ParkingVehicleHandler.class);
				addMobsimScopeEventHandlerBinding().to(ParkingVehicleHandler.class);
				addParkingSearchTimeCalculatorBinding().toInstance(new ConstantArrivalTime(1));
			}
		});
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(ParkingOccupancyObserver.class).in(Singleton.class);
				bind(ParkingCapacityInitializer.class).to(DerivedParkingCapacityInitializer.class);
				bind(KerbParkingEligibility.class).toInstance(new KerbParkingEligibility.MinimumLanes(1.0));
				bind(KerbParkingSupplyParams.class).toInstance(KerbParkingSupplyParams.defaults());
				bind(ParkingSpilloverReport.class).in(Singleton.class);
				addControllerListenerBinding().to(ParkingOccupancyObserver.class);
				addMobsimListenerBinding().to(ParkingOccupancyObserver.class);
				addControllerListenerBinding().to(ParkingSpilloverReport.class);
				addMobsimListenerBinding().to(ParkingSpilloverReport.class);
			}
		});
		controller.run();

		String iter = utils.getOutputDirectory() + "ITERS/it.0/0.";
		List<String> perLink = read(iter + ParkingSpilloverReport.PER_LINK_FILE);
		assertEquals("linkId;onStreetCapacity;offStreetPeakOccupancy;spilloverEvents;kerbParkingPermitted;nonParkablePeakOccupancy;nonParkableArrivals;endOnStreetOccupancy;endOffStreetOccupancy", perLink.get(0));
		assertTrue(perLink.size() > 1, "every equil link is eligible at one lane and long enough for kerb spaces, so rows are expected");
		for (String row : perLink.subList(1, perLink.size())) {
			String[] cols = row.split(";");
			assertEquals(9, cols.length, row);
			assertTrue(Integer.parseInt(cols[1]) > 0, "derived kerb capacity should be positive on " + cols[0]);
			assertEquals("true", cols[4], "every equil link is eligible, so none is a non-parkable link: " + row);
			assertEquals("0", cols[6], "and none can therefore have a non-parkable arrival: " + row);
		}

		List<String> networkRows = read(iter + ParkingSpilloverReport.NETWORK_FILE);
		assertEquals("binStart;onStreetCapacity;onStreetOccupancy;offStreetOccupancy;nonParkableOccupancy", networkRows.get(0));
		assertTrue(networkRows.size() > 1, "at least one hourly bin should have been sampled");
		assertEquals("00:00:00", networkRows.get(1).split(";")[0]);

		try (Stream<Path> files = Files.list(Path.of(utils.getOutputDirectory()))) {
			List<String> names = files.map(p -> p.getFileName().toString()).toList();
			assertTrue(names.stream().anyMatch(n -> n.endsWith(ParkingSpilloverReport.PER_LINK_FILE)), "per-link file copied to output dir: " + names);
			assertTrue(names.stream().anyMatch(n -> n.endsWith(ParkingSpilloverReport.NETWORK_FILE)), "network file copied to output dir: " + names);
		}
	}

	private static List<String> read(String file) throws IOException {
		try (BufferedReader reader = IOUtils.getBufferedReader(file)) {
			return reader.lines().toList();
		}
	}
}
