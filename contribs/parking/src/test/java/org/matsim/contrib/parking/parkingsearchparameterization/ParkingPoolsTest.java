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
import org.matsim.contrib.parking.parkingsearchparameterization.ParkingCapacityInitializer.ParkingInitialCapacity;
import org.matsim.contrib.parking.parkingsearchparameterization.ParkingCapacityInitializer.ParkingInitialPools;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_OFF_STREET_SPOTS;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_ON_STREET_SPOTS;

/**
 * On-street and off-street parking as separate pools, with kerb-first allocation.
 * <p>
 * {@link ParkingCapacityInitializer#initialize()} keeps its summed semantics; the split lives in
 * {@link ParkingCapacityInitializer#initializePools()}, and the two always agree on the total.
 */
class ParkingPoolsTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	/**
	 * Resolved on every use rather than held in a {@code static final}. {@link MatsimTestUtils} resets MATSim's Id
	 * cache between tests ({@code Id.resetCaches()}), and an Id created once at class-load time predates that reset.
	 * With such a static Id, the observer's link lookup inside {@code handleEvent} returned null for the very link
	 * that its getters resolved fine, and the failures disappeared once the Id was created inside each test. The
	 * exact path through the Id cache and {@code IdMap} was not traced; creating Ids per test is the safe rule.
	 */
	private static Id<Link> linkId() {
		return Id.createLinkId("l");
	}

	// ---------------------------------------------------------------- initializers

	@Test
	void poolsKeepTheSplitThatInitializeCollapses() {
		ZeroParkingCapacityInitializer initializer = zero(3, 5, 1.0);

		assertEquals(new ParkingInitialCapacity(8, 0), initializer.initialize().get(linkId()),
			"the legacy view still sums the two attributes");
		assertEquals(new ParkingInitialPools(3, 5, 0), initializer.initializePools().get(linkId()),
			"the pools view keeps them apart");
	}

	@Test
	void poolsAlwaysSumToTheLegacyCapacity() {
		int[][] cases = {{3, 5}, {1, 0}, {5, 0}, {0, 8}, {4, 4}, {7, 3}, {0, 0}};
		double[] factors = {1.0, 0.5, 0.1, 0.3, 0.75};
		for (int[] c : cases) {
			for (double f : factors) {
				ZeroParkingCapacityInitializer initializer = zero(c[0], c[1], f);
				int legacy = initializer.initialize().get(linkId()).capacity();
				ParkingInitialPools pools = initializer.initializePools().get(linkId());
				assertEquals(legacy, pools.capacity(),
					"on=" + c[0] + " off=" + c[1] + " factor=" + f + ": pools must sum to the legacy total");
			}
		}
	}

	@Test
	void scalingRoundsTheTotalUpOnceAndGivesTheRemainderToOffStreet() {
		// 8 spaces at 0.5 -> total 4 (same as legacy). On-street 3*0.5 = 1.5 -> 2. Off-street takes 4 - 2 = 2.
		assertEquals(new ParkingInitialPools(2, 2, 0), zero(3, 5, 0.5).initializePools().get(linkId()));
		// Rounding each pool up separately would give 1 + 1 = 2 here; the legacy total is ceil(0.2) = 1.
		assertEquals(new ParkingInitialPools(1, 0, 0), zero(1, 1, 0.1).initializePools().get(linkId()),
			"the on-street pool takes the whole rounded total when both would round up to one");
	}

	@Test
	void defaultPoolsPutTheWholeLegacyCapacityOnStreet() {
		ParkingCapacityInitializer legacyOnly = () -> Map.of(linkId(), new ParkingInitialCapacity(6, 2));

		assertEquals(new ParkingInitialPools(6, 0, 2), legacyOnly.initializePools().get(linkId()),
			"an implementation that only knows the sum is treated as all-kerb, which reproduces today's behaviour");
	}

	@Test
	void planBasedPoolsCarryOccupancyAndSplit() {
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		Network network = singleLink(scenario, 2, 3);

		var pf = scenario.getPopulation().getFactory();
		var plan = pf.createPlan();
		plan.addActivity(pf.createActivityFromLinkId("h", linkId()));
		plan.addLeg(pf.createLeg("car"));
		plan.addActivity(pf.createActivityFromLinkId("w", linkId()));
		var person = pf.createPerson(Id.createPersonId("p"));
		person.addPlan(plan);
		scenario.getPopulation().addPerson(person);

		PlanBasedParkingCapacityInitializer initializer = new PlanBasedParkingCapacityInitializer(network, scenario.getPopulation(), config);

		assertEquals(new ParkingInitialCapacity(5, 1), initializer.initialize().get(linkId()));
		assertEquals(new ParkingInitialPools(2, 3, 1), initializer.initializePools().get(linkId()));
	}

	// ---------------------------------------------------------------- observer

	@Test
	void vehiclesTakeTheKerbFirstAndSpillToOffStreetWhenItIsFull() {
		ParkingOccupancyObserver observer = observer(1, 0);
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));

		park(observer, 10, "v1");
		assertEquals(1, observer.getOnStreetOccupancy(linkId()));
		assertEquals(0, observer.getOffStreetOccupancy(linkId()));

		park(observer, 10, "v2");
		assertEquals(1, observer.getOnStreetOccupancy(linkId()), "the single kerb space is taken");
		assertEquals(1, observer.getOffStreetOccupancy(linkId()), "so the second vehicle spills off-street");

		park(observer, 10, "v3");
		assertEquals(2, observer.getOffStreetOccupancy(linkId()), "off-street is not capped; nobody is refused");
	}

	@Test
	void totalsAreUnchangedByTheSplit() {
		ParkingOccupancyObserver observer = observer(1, 0);
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		park(observer, 10, "v1");
		park(observer, 10, "v2");
		park(observer, 10, "v3");
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 11));

		assertEquals(Map.of(linkId(), new ParkingCount(3, 1, 1.0)), observer.getParkingCount(11, Map.of(linkId(), 1.0)),
			"what the search-time functions see is the same summed occupancy and capacity as before");
	}

	@Test
	void departureReleasesThePoolTheVehicleActuallyParkedIn() {
		ParkingOccupancyObserver observer = observer(1, 0);
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		park(observer, 10, "kerb");
		park(observer, 10, "lot");

		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 20));
		unpark(observer, 20, "lot");
		assertEquals(1, observer.getOnStreetOccupancy(linkId()), "the kerb vehicle is still there");
		assertEquals(0, observer.getOffStreetOccupancy(linkId()), "the off-street vehicle left");

		unpark(observer, 20, "kerb");
		assertEquals(0, observer.getOnStreetOccupancy(linkId()));
		assertEquals(0, observer.getOffStreetOccupancy(linkId()));
	}

	@Test
	void aFreedKerbSpaceIsTakenByTheNextArrival() {
		ParkingOccupancyObserver observer = observer(1, 0);
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		park(observer, 10, "v1");
		park(observer, 10, "v2");

		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 20));
		unpark(observer, 20, "v1");
		park(observer, 20, "v3");

		assertEquals(1, observer.getOnStreetOccupancy(linkId()), "v3 takes the kerb space v1 freed");
		assertEquals(1, observer.getOffStreetOccupancy(linkId()), "v2 stays off-street; nobody is moved");
	}

	@Test
	void peakOffStreetOccupancyIsTheSupplyTheLinkWouldHaveNeeded() {
		ParkingOccupancyObserver observer = observer(1, 0);
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		park(observer, 10, "v1");
		park(observer, 10, "v2");
		park(observer, 10, "v3");
		assertEquals(2, observer.getOffStreetPeakOccupancy(linkId()));

		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 20));
		unpark(observer, 20, "v2");
		unpark(observer, 20, "v3");
		assertEquals(0, observer.getOffStreetOccupancy(linkId()));
		assertEquals(2, observer.getOffStreetPeakOccupancy(linkId()), "the peak is not lowered by departures");
	}

	@Test
	void initialOccupancyIsSeededKerbFirst() {
		ParkingCapacityInitializer seeded = new ParkingCapacityInitializer() {
			@Override
			public Map<Id<Link>, ParkingInitialCapacity> initialize() {
				return Map.of(linkId(), new ParkingInitialCapacity(2, 3));
			}

			@Override
			public Map<Id<Link>, ParkingInitialPools> initializePools() {
				return Map.of(linkId(), new ParkingInitialPools(2, 0, 3));
			}
		};
		ParkingOccupancyObserver observer = observer(seeded);

		assertEquals(2, observer.getOnStreetOccupancy(linkId()), "the kerb fills first");
		assertEquals(1, observer.getOffStreetOccupancy(linkId()), "the remainder is off-street");
		assertEquals(1, observer.getOffStreetPeakOccupancy(linkId()), "and already counts towards the peak");
	}

	@Test
	void unknownVehiclesDepartFromTheKerbWhileAnyIsOccupied() {
		ParkingCapacityInitializer seeded = new ParkingCapacityInitializer() {
			@Override
			public Map<Id<Link>, ParkingInitialCapacity> initialize() {
				return Map.of(linkId(), new ParkingInitialCapacity(1, 2));
			}

			@Override
			public Map<Id<Link>, ParkingInitialPools> initializePools() {
				return Map.of(linkId(), new ParkingInitialPools(1, 0, 2));
			}
		};
		ParkingOccupancyObserver observer = observer(seeded);
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));

		// Neither vehicle was ever seen parking, so their pool is unknown. Seeding was kerb-first, so release is too.
		unpark(observer, 10, "unknown1");
		assertEquals(0, observer.getOnStreetOccupancy(linkId()));
		assertEquals(1, observer.getOffStreetOccupancy(linkId()));

		unpark(observer, 10, "unknown2");
		assertEquals(0, observer.getOnStreetOccupancy(linkId()));
		assertEquals(0, observer.getOffStreetOccupancy(linkId()));

		// A third departure from an empty link is ignored, as before, and does not drive a pool negative.
		unpark(observer, 10, "unknown3");
		assertEquals(0, observer.getOnStreetOccupancy(linkId()));
		assertEquals(0, observer.getOffStreetOccupancy(linkId()));
	}

	@Test
	void poolsResetBetweenIterations() {
		ParkingOccupancyObserver observer = observer(1, 0);
		observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, 10));
		park(observer, 10, "v1");
		park(observer, 10, "v2");
		assertEquals(1, observer.getOffStreetPeakOccupancy(linkId()));

		observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 1, true));

		assertEquals(0, observer.getOnStreetOccupancy(linkId()));
		assertEquals(0, observer.getOffStreetOccupancy(linkId()));
		assertEquals(0, observer.getOffStreetPeakOccupancy(linkId()));
	}

	// ---------------------------------------------------------------- fixtures

	private static ZeroParkingCapacityInitializer zero(int onStreet, int offStreet, double storageCapFactor) {
		Config config = ConfigUtils.createConfig();
		config.qsim().setStorageCapFactor(storageCapFactor);
		Scenario scenario = ScenarioUtils.createScenario(config);
		return new ZeroParkingCapacityInitializer(singleLink(scenario, onStreet, offStreet), config);
	}

	private static Network singleLink(Scenario scenario, int onStreet, int offStreet) {
		Network network = scenario.getNetwork();
		Node from = NetworkUtils.createAndAddNode(network, Id.create("1", Node.class), new Coord(0, 0));
		Node to = NetworkUtils.createAndAddNode(network, Id.create("2", Node.class), new Coord(1000, 0));
		Link link = NetworkUtils.createAndAddLink(network, linkId(), from, to, 1000.0, 10.0, 1800.0, 1.0);
		link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, onStreet);
		link.getAttributes().putAttribute(LINK_OFF_STREET_SPOTS, offStreet);
		return network;
	}

	private ParkingOccupancyObserver observer(int onStreet, int offStreet) {
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		Network network = singleLink(scenario, onStreet, offStreet);
		return observer(network, new ZeroParkingCapacityInitializer(network, config));
	}

	private ParkingOccupancyObserver observer(ParkingCapacityInitializer initializer) {
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		return observer(singleLink(scenario, 0, 0), initializer);
	}

	private ParkingOccupancyObserver observer(Network network, ParkingCapacityInitializer initializer) {
		Config config = ConfigUtils.createConfig();
		config.controller().setOutputDirectory(utils.getOutputDirectory());
		OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(config);
		hierarchy.createIterationDirectory(0);
		hierarchy.createIterationDirectory(1);
		ParkingOccupancyObserver observer = new ParkingOccupancyObserver(network, initializer, config, hierarchy);
		observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
		return observer;
	}

	private static void park(ParkingOccupancyObserver observer, double time, String vehicle) {
		observer.handleEvent(new VehicleEndsParkingSearch(time, Id.createPersonId(vehicle), linkId(), Id.createVehicleId(vehicle), "car"));
	}

	private static void unpark(ParkingOccupancyObserver observer, double time, String vehicle) {
		observer.handleEvent(new VehicleEntersTrafficEvent(time, Id.createPersonId(vehicle), linkId(), Id.createVehicleId(vehicle), "car", 0));
	}
}
