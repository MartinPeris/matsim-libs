package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.VehicleEndsParkingSearch;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
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
		assertEquals("linkId;onStreetCapacity;offStreetPeakOccupancy;spilloverEvents;kerbParkingPermitted;nonParkablePeakOccupancy;nonParkableArrivals;endOnStreetOccupancy;endOffStreetOccupancy", lines.get(0));
		assertEquals(List.of("a;1;2;2;true;0;0;1;2", "b;1;0;0;true;0;0;0;0"), lines.subList(1, lines.size()),
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
		assertEquals("binStart;onStreetCapacity;onStreetOccupancy;offStreetOccupancy;nonParkableOccupancy", lines.get(0));
		assertEquals(List.of("00:00:00;2;0;0;0", "01:00:00;2;1;2;0", "02:00:00;2;1;2;0"), lines.subList(1, lines.size()));
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
		assertEquals("03:00:00;2;0;0;0", lines.get(4));
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


	/**
	 * The point of the extra columns. A motorway that receives an arrival only because an activity coordinate
	 * snapped to it must not read as demand for off-street parking.
	 */
	@Test
	void arrivalsWhereKerbParkingIsImpossibleAreReportedApartFromOffStreetDemand() throws IOException {
		Fixture f = new Fixture();
		f.observer.setKerbParkingEligibility(link -> !"c".equals(link.getId().toString()));
		f.observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
		f.report.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		f.step(0);
		f.reportStep(0);
		f.step(10);
		f.park(10, "v1");        // link a, takes its one kerb space
		f.park(10, "v2");        // link a, kerb full, genuine off-street demand
		f.parkOn(10, "v3", "c"); // link c, kerb parking impossible: an artefact, not demand
		f.step(3600);
		f.reportStep(3600);

		String perLink = utils.getOutputDirectory() + "per_link_nonparkable.csv";
		f.report.writePerLink(perLink);
		assertEquals(List.of("a;1;1;1;true;0;0;1;1", "b;1;0;0;true;0;0;0;0", "c;0;1;1;false;1;1;0;1"),
			read(perLink).subList(1, read(perLink).size()),
			"link a spilled once as real demand; link c's arrival is counted only in the non-parkable columns");

		String network = utils.getOutputDirectory() + "network_nonparkable.csv";
		f.report.writeNetwork(network);
		assertEquals("01:00:00;2;1;2;1", read(network).get(2),
			"two off-street of which one is on a link where kerb parking is impossible, so off-street demand is one");
	}

	@Test
	void withoutAnEligibilityRuleEveryLinkCountsAsParkable() throws IOException {
		Fixture f = new Fixture();
		f.step(10);
		f.parkOn(10, "v1", "c");

		String file = utils.getOutputDirectory() + "per_link_default.csv";
		f.report.writePerLink(file);
		assertEquals("c;0;1;1;true;0;0;0;1", read(file).get(3),
			"the default rule permits everything, so nothing is ever reported as non-parkable");
	}

	@Test
	void aVehicleLeavingANonParkableLinkReleasesTheNonParkableCount() {
		Fixture f = new Fixture();
		f.observer.setKerbParkingEligibility(link -> !"c".equals(link.getId().toString()));
		f.observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		f.step(10);
		f.parkOn(10, "v1", "c");
		assertEquals(1, f.observer.getPoolTotals().nonParkableOccupancy());

		f.step(20);
		f.observer.handleEvent(new VehicleEntersTrafficEvent(20, Id.createPersonId("v1"), Id.createLinkId("c"),
			Id.createVehicleId("v1"), "car", 1.0));

		assertEquals(0, f.observer.getPoolTotals().nonParkableOccupancy(), "the vehicle left, so the count drops");
		assertEquals(0, f.observer.getPoolTotals().offStreetOccupancy());
		assertEquals(1, f.observer.getNonParkablePeakOccupancy(Id.createLinkId("c")), "but the peak it reached stands");
	}

	@Test
	void nonParkableOccupancyNeverExceedsOffStreetOccupancy() {
		Fixture f = new Fixture();
		f.observer.setKerbParkingEligibility(link -> !"c".equals(link.getId().toString()));
		f.observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		double time = 0;
		for (int i = 0; i < 12; i++) {
			f.step(++time);
			f.park(time, "a" + i);
			f.parkOn(time, "c" + i, "c");
			ParkingOccupancyObserver.PoolTotals totals = f.observer.getPoolTotals();
			assertTrue(totals.nonParkableOccupancy() <= totals.offStreetOccupancy(),
				"the non-parkable count is a subset of off-street, never larger");
		}
		for (int i = 0; i < 12; i++) {
			f.step(++time);
			f.observer.handleEvent(new VehicleEntersTrafficEvent(time, Id.createPersonId("c" + i),
				Id.createLinkId("c"), Id.createVehicleId("c" + i), "car", 1.0));
			ParkingOccupancyObserver.PoolTotals totals = f.observer.getPoolTotals();
			assertTrue(totals.nonParkableOccupancy() <= totals.offStreetOccupancy(),
				"and still a subset as they leave");
		}
		assertEquals(0, f.observer.getPoolTotals().nonParkableOccupancy());
	}

	/**
	 * A link seeded with initial occupancy has no remembered pool, so departure infers it. On a link where kerb
	 * parking is impossible the kerb capacity is zero, so the inference must land on off-street and take the
	 * non-parkable count down with it.
	 */
	@Test
	void seededOccupancyOnANonParkableLinkIsReleasedFromTheNonParkableCount() {
		Fixture f = new Fixture();
		f.seedOffStreetOccupancy("c", 2);
		f.observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));

		assertEquals(2, f.observer.getPoolTotals().nonParkableOccupancy(), "seeded off-street on a non-parkable link");

		f.step(10);
		f.observer.handleEvent(new VehicleEntersTrafficEvent(10, Id.createPersonId("seed"), Id.createLinkId("c"),
			Id.createVehicleId("seed"), "car", 1.0));

		assertEquals(1, f.observer.getPoolTotals().nonParkableOccupancy());
		assertEquals(1, f.observer.getPoolTotals().offStreetOccupancy());
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
		ParkingOccupancyObserver observer;
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
			parkOn(time, vehicle, "a");
		}

		void parkOn(double time, String vehicle, String link) {
			observer.handleEvent(new VehicleEndsParkingSearch(time, Id.createPersonId(vehicle), Id.createLinkId(link),
				Id.createVehicleId(vehicle), "car"));
		}

		/** Replaces the observer with one whose link starts with vehicles already parked off-street. */
		void seedOffStreetOccupancy(String link, int vehicles) {
			Id<Link> id = Id.createLinkId(link);
			ParkingCapacityInitializer seeded = new ParkingCapacityInitializer() {
				@Override
				public java.util.Map<Id<Link>, ParkingInitialCapacity> initialize() {
					return java.util.Map.of(id, new ParkingInitialCapacity(vehicles, vehicles));
				}

				@Override
				public java.util.Map<Id<Link>, ParkingInitialPools> initializePools() {
					return java.util.Map.of(id, new ParkingInitialPools(0, vehicles, vehicles));
				}
			};
			observer = new ParkingOccupancyObserver(network, seeded, config, hierarchy);
			observer.setKerbParkingEligibility(l -> !"c".equals(l.getId().toString()));
		}
	}
}
