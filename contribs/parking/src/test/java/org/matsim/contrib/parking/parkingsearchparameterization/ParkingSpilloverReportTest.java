package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.VehicleEndsParkingSearch;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_OFF_STREET_SPOTS;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_ON_STREET_SPOTS;

/**
 * The spillover report reads the observer's pools and writes two CSVs: per link, and network totals per time bin.
 */
class ParkingSpilloverReportTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	private static Id<Link> a() {
		return Id.createLinkId("a");
	}

	private static Id<Link> b() {
		return Id.createLinkId("b");
	}

	@Test
	void perLinkFileHasPeakAndSpillCountsAndSkipsIrrelevantLinks() throws IOException {
		Fixture f = new Fixture();
		f.step(10);
		f.park(10, "v1");
		f.park(10, "v2");
		f.park(10, "v3");

		String file = utils.getOutputDirectory() + "per_link.csv";
		f.report.writePerLink(file);

		List<String> lines = read(file);
		assertEquals("linkId;onStreetCapacity;offStreetPeakOccupancy;spilloverEvents", lines.get(0));
		assertEquals(List.of("a;1;2;2", "b;1;0;0"), lines.subList(1, lines.size()),
			"link a: one kerb space, two spilled, peak two; link b: has capacity so it is listed; link c has nothing and is omitted");
	}

	@Test
	void networkFileHasOneRowPerBinWithTotals() throws IOException {
		Fixture f = new Fixture();
		f.report.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		f.step(0);
		f.reportStep(0);
		f.step(10);
		f.park(10, "v1");
		f.park(10, "v2");
		f.park(10, "v3");
		f.step(3600);
		f.reportStep(3600);
		f.step(7200);
		f.reportStep(7200);

		String file = utils.getOutputDirectory() + "network.csv";
		f.report.writeNetwork(file);

		List<String> lines = read(file);
		assertEquals("binStart;onStreetCapacity;onStreetOccupancy;offStreetOccupancy", lines.get(0));
		assertEquals(List.of("00:00:00;2;0;0", "01:00:00;2;1;2", "02:00:00;2;1;2"), lines.subList(1, lines.size()));
	}

	@Test
	void aJumpOverSeveralBinsStillWritesOneRowPerBin() throws IOException {
		Fixture f = new Fixture();
		f.report.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		f.step(0);
		f.reportStep(0);
		f.step(3 * 3600 + 5);
		f.reportStep(3 * 3600 + 5);

		String file = utils.getOutputDirectory() + "network_jump.csv";
		f.report.writeNetwork(file);

		List<String> lines = read(file);
		assertEquals(5, lines.size(), "header plus bins 0, 1, 2 and 3");
		assertEquals("03:00:00;2;0;0", lines.get(4));
	}

	@Test
	void binsResetBetweenIterations() throws IOException {
		Fixture f = new Fixture();
		f.report.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
		f.step(0);
		f.reportStep(0);
		f.step(3600);
		f.reportStep(3600);

		f.report.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false));
		f.observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, false));
		f.step(0);
		f.reportStep(0);

		String file = utils.getOutputDirectory() + "network_reset.csv";
		f.report.writeNetwork(file);

		assertEquals(2, read(file).size(), "header plus the single bin of the new iteration");
	}

	@Test
	void binSizeMustBePositive() {
		Fixture f = new Fixture();
		assertThrows(IllegalArgumentException.class,
			() -> new ParkingSpilloverReport(f.observer, f.network, f.hierarchy, f.config, 0.0));
	}

	private static List<String> read(String file) throws IOException {
		try (BufferedReader reader = IOUtils.getBufferedReader(file)) {
			return reader.lines().toList();
		}
	}

	/** Three links: a and b have one kerb space each, c has no parking at all. */
	private final class Fixture {
		final Config config = ConfigUtils.createConfig();
		final Network network;
		final OutputDirectoryHierarchy hierarchy;
		final ParkingOccupancyObserver observer;
		final ParkingSpilloverReport report;

		Fixture() {
			config.controller().setOutputDirectory(utils.getOutputDirectory());
			Scenario scenario = ScenarioUtils.createScenario(config);
			network = scenario.getNetwork();
			Node n0 = NetworkUtils.createAndAddNode(network, Id.create("0", Node.class), new Coord(0, 0));
			Node n1 = NetworkUtils.createAndAddNode(network, Id.create("1", Node.class), new Coord(100, 0));
			Node n2 = NetworkUtils.createAndAddNode(network, Id.create("2", Node.class), new Coord(200, 0));
			Node n3 = NetworkUtils.createAndAddNode(network, Id.create("3", Node.class), new Coord(300, 0));
			kerb(NetworkUtils.createAndAddLink(network, a(), n0, n1, 100, 10, 1800, 2), 1);
			kerb(NetworkUtils.createAndAddLink(network, b(), n1, n2, 100, 10, 1800, 2), 1);
			NetworkUtils.createAndAddLink(network, Id.createLinkId("c"), n2, n3, 100, 10, 1800, 2);

			hierarchy = new OutputDirectoryHierarchy(config);
			hierarchy.createIterationDirectory(0);
			hierarchy.createIterationDirectory(1);
			observer = new ParkingOccupancyObserver(network, new ZeroParkingCapacityInitializer(network, config), config, hierarchy);
			observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
			report = new ParkingSpilloverReport(observer, network, hierarchy, config);
		}

		private static void kerb(Link link, int spaces) {
			link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, spaces);
			link.getAttributes().putAttribute(LINK_OFF_STREET_SPOTS, 0);
		}

		void step(double time) {
			observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, time));
		}

		void reportStep(double time) {
			report.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, time));
		}

		void park(double time, String vehicle) {
			observer.handleEvent(new VehicleEndsParkingSearch(time, Id.createPersonId(vehicle), a(), Id.createVehicleId(vehicle), "car"));
		}
	}
}
