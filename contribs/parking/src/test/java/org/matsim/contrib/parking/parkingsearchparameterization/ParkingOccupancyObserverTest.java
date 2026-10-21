package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
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
import org.matsim.core.utils.io.IOUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParkingOccupancyObserverTest {
	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	void test_initial() {
		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();

		Id<Link> linkId = Id.createLinkId("l");
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 0));
		Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(0, Map.of(linkId, 1.0));
		assertEquals(Map.of(linkId, new ParkingCount(0, 2, 1.0)), parkingCount);

		checkFile();
	}

	@Test
	void test_unpark() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(10, Id.createPersonId("p"), linkId, Id.createVehicleId("v"), "car", 0));

		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 11));
		Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(11, Map.of(linkId, 1.0));
		assertEquals(Map.of(linkId, new ParkingCount(0, 2, 1.0)), parkingCount);

		checkFile();
	}

	@Test
	void test_park() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		parkingOccupancyObserver.handleEvent(new VehicleEndsParkingSearch(10, Id.createPersonId("p"), linkId, Id.createVehicleId("v"), "car"));
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 11));

		Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(11, Map.of(linkId, 1.0));
		assertEquals(Map.of(linkId, new ParkingCount(1, 2, 1.0)), parkingCount);

		checkFile();
	}

	@Test
	void test_parkUnpark() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		parkingOccupancyObserver.handleEvent(new VehicleEndsParkingSearch(10, Id.createPersonId("p"), linkId, Id.createVehicleId("v"), "car"));
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 20));
		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(20, Id.createPersonId("p"), linkId, Id.createVehicleId("v"), "car", 0));

		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 21));
		Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(21, Map.of(linkId, 1.0));
		assertEquals(Map.of(linkId, new ParkingCount(0, 2, 1.0)), parkingCount);

		checkFile();
	}

	@Test
	void test_reset() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		parkingOccupancyObserver.handleEvent(new VehicleEndsParkingSearch(10, Id.createPersonId("p"), linkId, Id.createVehicleId("v"), "car"));

		{
			parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 11));
			Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(11, Map.of(linkId, 1.0));
			assertEquals(Map.of(linkId, new ParkingCount(1, 2, 1.0)), parkingCount);
		}
		checkFile(0);

		parkingOccupancyObserver.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, true));

		{
			parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 0));
			Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(0, Map.of(linkId, 1.0));
			assertEquals(Map.of(linkId, new ParkingCount(0, 2, 1.0)), parkingCount);
		}

		checkFile(1);
	}

	/**
	 * Capacity here is an input to a search-time penalty, not a constraint. The observer counts parking events as they
	 * arrive and never refuses one, so occupancy is free to exceed capacity.
	 * <p>
	 * Any model that needs a hard kerb limit has to enforce it before the event is emitted; adding the check here
	 * would change the meaning of every existing scenario.
	 */
	@Test
	void occupancyIsAllowedToExceedCapacity() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		for (int i = 0; i < 5; i++) {
			parkingOccupancyObserver.handleEvent(
				new VehicleEndsParkingSearch(10, Id.createPersonId("p" + i), linkId, Id.createVehicleId("v" + i), "car"));
		}
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 11));

		Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(11, Map.of(linkId, 1.0));
		assertEquals(Map.of(linkId, new ParkingCount(5, 2, 1.0)), parkingCount,
			"five vehicles should park on a link with capacity two; occupancy is observed, not enforced");
	}

	/**
	 * The opposite direction is guarded: more departures than arrivals cannot drive occupancy negative. This matters
	 * because a scenario may start with vehicles already parked that the initializer did not account for.
	 */
	@Test
	void occupancyIsClampedAtZeroOnExcessDepartures() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		for (int i = 0; i < 3; i++) {
			parkingOccupancyObserver.handleEvent(
				new VehicleEntersTrafficEvent(10, Id.createPersonId("p" + i), linkId, Id.createVehicleId("v" + i), "car", 0));
		}
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 11));

		Map<Id<Link>, ParkingCount> parkingCount = parkingOccupancyObserver.getParkingCount(11, Map.of(linkId, 1.0));
		assertEquals(Map.of(linkId, new ParkingCount(0, 2, 1.0)), parkingCount,
			"three departures from an empty link should leave occupancy at zero, not negative");
	}

	/**
	 * The city starts empty, so a vehicle never seen parking holds no space. A car's first departure of the day must
	 * not free the space of a car that is still parked on the same link.
	 */
	@Test
	void emptyCityDepartureByNeverParkedVehicleReleasesNothing() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		parkingOccupancyObserver.handleEvent(new VehicleEndsParkingSearch(10, Id.createPersonId("p"), linkId, Id.createVehicleId("parked"), "car"));
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 20));
		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(20, Id.createPersonId("q"), linkId, Id.createVehicleId("firstDeparture"), "car", 0));

		assertEquals(1, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "the parked car still holds its kerb space");
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(linkId));
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 21));
		assertEquals(Map.of(linkId, new ParkingCount(1, 2, 1.0)), parkingOccupancyObserver.getParkingCount(21, Map.of(linkId, 1.0)));
	}

	/**
	 * A parked car releases the pool it parked in, on the link where it parked, even if it enters traffic elsewhere.
	 */
	@Test
	void parkedCarReleasesItsOwnPool() {
		Id<Link> linkId = Id.createLinkId("l");
		Id<Link> otherLinkId = Id.createLinkId("m");

		Network network = createNetwork();
		Node node2 = NetworkUtils.createNode(Id.createNodeId("2"), new Coord(0, 200));
		network.addNode(node2);
		network.addLink(NetworkUtils.createLink(otherLinkId, network.getNodes().get(Id.createNodeId("1")), node2, network, 100, 10, 10, 1));
		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver(network, new ZeroParkingCapacityInitializer(network, ConfigUtils.createConfig()));

		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		for (String v : new String[]{"kerb1", "kerb2", "lot"}) {
			parkingOccupancyObserver.handleEvent(new VehicleEndsParkingSearch(10, Id.createPersonId(v), linkId, Id.createVehicleId(v), "car"));
		}
		assertEquals(2, parkingOccupancyObserver.getOnStreetOccupancy(linkId));
		assertEquals(1, parkingOccupancyObserver.getOffStreetOccupancy(linkId));

		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 20));
		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(20, Id.createPersonId("lot"), otherLinkId, Id.createVehicleId("lot"), "car", 0));
		assertEquals(2, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "the kerb cars are still there");
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(linkId), "the off-street car left the link it parked on");
		assertEquals(0, parkingOccupancyObserver.getOnStreetOccupancy(otherLinkId));
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(otherLinkId));

		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(20, Id.createPersonId("kerb1"), linkId, Id.createVehicleId("kerb1"), "car", 0));
		assertEquals(1, parkingOccupancyObserver.getOnStreetOccupancy(linkId));
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(linkId));
	}

	/**
	 * Seeded initial occupancy has no vehicle ids, so a vehicle never seen parking may release it, kerb first as it
	 * was seeded, until the link's seeded spaces are used up. It never releases a space taken by a car seen parking.
	 */
	@Test
	void seededOccupancyCanStillBeReleased() {
		Id<Link> linkId = Id.createLinkId("l");

		Network network = createNetwork();
		ParkingCapacityInitializer seeded = new ParkingCapacityInitializer() {
			@Override
			public Map<Id<Link>, ParkingInitialCapacity> initialize() {
				return Map.of(linkId, new ParkingInitialCapacity(1, 2));
			}

			@Override
			public Map<Id<Link>, ParkingInitialPools> initializePools() {
				return Map.of(linkId, new ParkingInitialPools(1, 0, 2));
			}
		};
		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver(network, seeded);
		assertEquals(1, parkingOccupancyObserver.getOnStreetOccupancy(linkId));
		assertEquals(1, parkingOccupancyObserver.getOffStreetOccupancy(linkId));

		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(10, Id.createPersonId("s1"), linkId, Id.createVehicleId("seed1"), "car", 0));
		assertEquals(0, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "the seeded kerb space is released first");
		assertEquals(1, parkingOccupancyObserver.getOffStreetOccupancy(linkId));

		parkingOccupancyObserver.handleEvent(new VehicleEndsParkingSearch(10, Id.createPersonId("p"), linkId, Id.createVehicleId("parked"), "car"));
		assertEquals(1, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "the parked car takes the freed kerb space");

		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(10, Id.createPersonId("s2"), linkId, Id.createVehicleId("seed2"), "car", 0));
		assertEquals(1, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "the parked car keeps its kerb space");
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(linkId), "the remaining seeded space was off-street");

		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(10, Id.createPersonId("s3"), linkId, Id.createVehicleId("stranger"), "car", 0));
		assertEquals(1, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "no seeded space is left to release");
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(linkId));

		parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(10, Id.createPersonId("p"), linkId, Id.createVehicleId("parked"), "car", 0));
		assertEquals(0, parkingOccupancyObserver.getOnStreetOccupancy(linkId));
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(linkId));
	}

	/**
	 * Trucks and freight vehicles enter traffic but never park in this model. Their departures must not free spaces
	 * held by parked cars, whichever pool those cars are in.
	 */
	@Test
	void truckCannotFreeAParkedCarsSpace() {
		Id<Link> linkId = Id.createLinkId("l");

		ParkingOccupancyObserver parkingOccupancyObserver = getParkingObserver();
		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		for (String v : new String[]{"kerb1", "kerb2", "lot"}) {
			parkingOccupancyObserver.handleEvent(new VehicleEndsParkingSearch(10, Id.createPersonId(v), linkId, Id.createVehicleId(v), "car"));
		}

		parkingOccupancyObserver.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 20));
		for (int i = 0; i < 3; i++) {
			parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(20, Id.createPersonId("driver" + i), linkId, Id.createVehicleId("truck" + i), "truck", 0));
		}
		assertEquals(2, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "the trucks freed no kerb space");
		assertEquals(1, parkingOccupancyObserver.getOffStreetOccupancy(linkId), "nor the off-street space");

		for (String v : new String[]{"lot", "kerb1", "kerb2"}) {
			parkingOccupancyObserver.handleEvent(new VehicleEntersTrafficEvent(20, Id.createPersonId(v), linkId, Id.createVehicleId(v), "car", 0));
		}
		assertEquals(0, parkingOccupancyObserver.getOnStreetOccupancy(linkId), "each car released its own space");
		assertEquals(0, parkingOccupancyObserver.getOffStreetOccupancy(linkId));
	}

	private ParkingOccupancyObserver getParkingObserver() {
		Network network = createNetwork();
		return getParkingObserver(network, new ZeroParkingCapacityInitializer(network, ConfigUtils.createConfig()));
	}

	private static Network createNetwork() {
		Network network = NetworkUtils.createNetwork();

		Node node0 = NetworkUtils.createNode(Id.createNodeId("0"), new Coord(0, 0));
		Node node1 = NetworkUtils.createNode(Id.createNodeId("1"), new Coord(0, 100));

		Id<Link> linkId = Id.createLinkId("l");
		Link l = NetworkUtils.createLink(linkId, node0, node1, network, 100, 10, 10, 1);
		l.getAttributes().putAttribute("onstreet_spots", 2);

		network.addNode(node0);
		network.addNode(node1);
		network.addLink(l);
		return network;
	}

	private ParkingOccupancyObserver getParkingObserver(Network network, ParkingCapacityInitializer parkingCapacityInitializer) {
		Config config = ConfigUtils.createConfig();
		config.controller().setOutputDirectory(utils.getOutputDirectory());

		OutputDirectoryHierarchy outputDirectoryHierarchy = new OutputDirectoryHierarchy(config);
		outputDirectoryHierarchy.createIterationDirectory(0);
		outputDirectoryHierarchy.createIterationDirectory(1);

		ParkingOccupancyObserver parkingOccupancyObserver = new ParkingOccupancyObserver(network, parkingCapacityInitializer, config, outputDirectoryHierarchy);
		parkingOccupancyObserver.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
		return parkingOccupancyObserver;
	}

	private void checkFile() {
		checkFile(0);
	}

	private void checkFile(int iteration) {
		BufferedReader bufferedReader = IOUtils.getBufferedReader(utils.getOutputDirectory() + "ITERS/it." + iteration + "/" + iteration + ".parking_initial_occupancy.csv");
		try {
			assertEquals("linkId;capacity;occupancy", bufferedReader.readLine());
			assertEquals("l;2;0", bufferedReader.readLine());
			assertEquals(null, bufferedReader.readLine());
		} catch (IOException e) {
			Assertions.fail();
		}
	}
}
