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
import org.matsim.contrib.parking.parkingsearchparameterization.KerbParkingCapacityEffectParams.EffectMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.BeforeMobsimEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkChangeEvent.ChangeType;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_ON_STREET_SPOTS;

/**
 * {@link KerbParkingCapacityEffect} driven by a real {@link ParkingOccupancyObserver} and a real time-variant network,
 * without a mobsim: events go into the network exactly as in a run, and are also recorded so their number and values
 * can be checked.
 * <p>
 * Network, all links 100 m long, coordinates in metres:
 * <ul>
 * <li>{@code a}: (0,0)-(100,0), 4 kerb spaces; midpoint in cell 0_0 at 300 m cells. Every link has 1800 veh/h, 2 lanes
 * and 10 m/s;</li>
 * <li>{@code b}: (100,0)-(200,0), 0 kerb spaces but kerb parking permitted; midpoint in cell 0_0;</li>
 * <li>{@code c}: (200,0)-(300,0), 2 kerb spaces; midpoint (250,0) in cell 0_0;</li>
 * <li>{@code d}: (600,0)-(700,0), 1 kerb space; cell 2_0;</li>
 * <li>{@code e}: (700,0)-(800,0), kerb parking not permitted (eligibility rule); cell 2_0;</li>
 * <li>{@code neg}: (-150,-150)-(-50,-50), permitted but no spaces; cell -1_-1.</li>
 * </ul>
 */
class KerbParkingCapacityEffectTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	private static final double BASE_CAPACITY = 1800.0;
	private static final double BASE_LANES = 2.0;
	private static final double BASE_FREESPEED = 10.0;

	// ---- link mode ----

	@Test
	void linkModeEmitsAbsoluteValuesFromTheBaseInCorrectUnits() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link).withUpdateIntervalSeconds(1));
		f.step(0);
		assertEquals(0, f.events.size(), "nobody parked: no event");

		f.park(0, "v1", "a");
		f.step(1);
		assertEquals(1, f.events.size());
		NetworkChangeEvent ev = f.events.get(0);
		assertEquals(1.0, ev.getStartTime());
		assertEquals(List.of(f.link("a")), List.copyOf(ev.getLinks()));
		// s = 1/4: f = 1 - 0.5/4 = 0.875, g = 1 - 0.2/4 = 0.95
		assertEquals(ChangeType.ABSOLUTE_IN_SI_UNITS, ev.getFlowCapacityChange().getType());
		assertEquals(BASE_CAPACITY / 3600.0 * 0.875, ev.getFlowCapacityChange().getValue(), 1e-12, "flow capacity in veh/s, full population");
		assertEquals(ChangeType.ABSOLUTE_IN_SI_UNITS, ev.getLanesChange().getType());
		assertEquals(BASE_LANES * 0.875, ev.getLanesChange().getValue(), 1e-12);
		assertEquals(ChangeType.ABSOLUTE_IN_SI_UNITS, ev.getFreespeedChange().getType());
		assertEquals(BASE_FREESPEED * 0.95, ev.getFreespeedChange().getValue(), 1e-12);

		// Applied to the network: the link reports the reduced values in its own units from then on, and the base before.
		Link a = f.link("a");
		assertEquals(BASE_CAPACITY * 0.875, a.getCapacity(1.0), 1e-9, "capacity per capacity period (veh/h), not 3600 times that");
		assertEquals(BASE_LANES * 0.875, a.getNumberOfLanes(1.0), 1e-12);
		assertEquals(BASE_FREESPEED * 0.95, a.getFreespeed(1.0), 1e-12);
		assertEquals(BASE_CAPACITY, a.getCapacity(0.0), 0.0);
		assertEquals(BASE_CAPACITY, a.getCapacity(), 0.0, "the base value itself is untouched");
		assertEquals(1.0, f.network.getLinks().get(Id.createLinkId("d")).getCapacity(1.0) / BASE_CAPACITY, 0.0, "other links unaffected");
	}

	@Test
	void linkModeScopeIsEligibleLinksWithPositiveCapacity() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link));
		f.step(0);
		assertTrue(f.effect.isInScope(Id.createLinkId("a")));
		assertFalse(f.effect.isInScope(Id.createLinkId("b")), "permitted but zero capacity");
		assertTrue(f.effect.isInScope(Id.createLinkId("c")));
		assertTrue(f.effect.isInScope(Id.createLinkId("d")));
		assertFalse(f.effect.isInScope(Id.createLinkId("e")), "kerb parking not permitted");
	}

	@Test
	void noEventWhenNothingChangesAndAtMostOnePerLinkPerStep() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link).withUpdateIntervalSeconds(1));
		f.step(0);
		f.park(0, "v1", "a");
		f.park(0, "v2", "a");
		f.park(0, "v3", "a");
		f.step(1);
		assertEquals(1, f.events.size(), "three cars parked in one step: one event for the link");
		assertEquals(BASE_CAPACITY / 3600.0 * (1 - 0.5 * 0.75), f.events.get(0).getFlowCapacityChange().getValue(), 1e-12);

		for (int t = 2; t < 100; t++) {
			f.step(t);
		}
		assertEquals(1, f.events.size(), "no further event while occupancy is unchanged");

		// a car leaves and another arrives in the same step: occupancy unchanged, factors unchanged, no event
		f.step(100);
		f.unpark(100, "v1", "a");
		f.park(100, "v4", "a");
		f.step(101);
		assertEquals(1, f.events.size(), "occupancy back where it was: factors unchanged, so no event");
	}

	@Test
	void restoresExactlyToBaseAndNeverCompounds() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link).withUpdateIntervalSeconds(1));
		Link a = f.link("a");
		f.step(0);
		f.park(0, "v1", "a");
		f.step(1);
		f.park(1, "v2", "a");
		f.step(2);
		// Second change computed from the base, not from the already-reduced value.
		assertEquals(BASE_CAPACITY * 0.75, a.getCapacity(2.0), 1e-9, "s = 2/4 gives 0.75 of the base, not 0.75 * 0.875");
		assertEquals(BASE_LANES * 0.75, a.getNumberOfLanes(2.0), 1e-12);
		assertEquals(BASE_FREESPEED * 0.9, a.getFreespeed(2.0), 1e-12);

		f.unpark(2, "v1", "a");
		f.unpark(2, "v2", "a");
		f.step(3);
		assertEquals(3, f.events.size());
		assertEquals(BASE_CAPACITY, a.getCapacity(3.0), 0.0, "bitwise the base value again");
		assertEquals(BASE_LANES, a.getNumberOfLanes(3.0), 0.0);
		assertEquals(BASE_FREESPEED, a.getFreespeed(3.0), 0.0);
		assertEquals(1.0, f.effect.appliedFlowFactor(a.getId()), 0.0);
		assertEquals(1.0, f.effect.appliedSpeedFactor(a.getId()), 0.0);

		// Ending the mobsim takes this mobsim's events off the network again.
		f.effect.finish(4);
		assertTrue(NetworkUtils.getNetworkChangeEvents(f.network).isEmpty());
		assertEquals(BASE_CAPACITY, a.getCapacity(2.0), 0.0, "after the mobsim the network is the input network");
	}

	@Test
	void seededOccupancyTakesEffectAtTheFirstStep() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link), "a", 2);
		f.step(0);
		assertEquals(1, f.events.size());
		assertEquals(0.0, f.events.get(0).getStartTime());
		assertEquals(0.75, f.effect.appliedFlowFactor(Id.createLinkId("a")), 1e-15, "2 of 4 seeded: s = 0.5");
	}

	// ---- cell mode ----

	@Test
	void cellModeAppliesTheCellShareToEveryEligibleLinkInTheCell() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.cell).withUpdateIntervalSeconds(1));
		f.step(0);
		assertTrue(f.effect.isInScope(Id.createLinkId("b")), "zero own capacity, but its cell has capacity");
		assertFalse(f.effect.isInScope(Id.createLinkId("e")), "kerb parking not permitted, so never in scope");

		f.park(0, "v1", "a");
		f.park(0, "v2", "c");
		f.park(0, "v3", "c");
		f.step(1);
		// cell 0_0 holds a (4), b (0) and c (2): s = 3/6 = 0.5, f = 0.75, g = 0.9 on all three
		assertEquals(3, f.events.size(), "one event per link of the cell, including the one without spaces");
		for (String id : List.of("a", "b", "c")) {
			assertEquals(0.75, f.effect.appliedFlowFactor(Id.createLinkId(id)), 1e-15, id);
			assertEquals(0.9, f.effect.appliedSpeedFactor(Id.createLinkId(id)), 1e-15, id);
		}
		assertEquals(1.0, f.effect.appliedFlowFactor(Id.createLinkId("d")), 0.0, "other cell untouched");

		// c is full on its own, but the cell is not: link mode would give c f = 0.5, cell mode gives it 0.75 (above).
		// d is alone in its cell with one space, as e does not count.
		f.park(1, "v4", "d");
		f.step(2);
		assertEquals(4, f.events.size());
		assertEquals(0.5, f.effect.appliedFlowFactor(Id.createLinkId("d")), 0.0, "cell 2_0: d full (1/1), e not counted");
	}

	@Test
	void cellOfUsesTheMidpointAndTheCellSize() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.cell));
		assertEquals("0_0", f.effect.cellOf(f.link("c")), "midpoint (250, 0)");
		assertEquals("2_0", f.effect.cellOf(f.link("d")), "midpoint (650, 0)");
		Fixture g = new Fixture(new KerbParkingCapacityEffectParams(EffectMode.cell, 100, 0.5, 0.5, 0.7, 0.2, 60));
		assertEquals("2_0", g.effect.cellOf(g.link("c")));
		assertEquals("-1_-1", g.effect.cellOf(g.network.getLinks().get(Id.createLinkId("neg"))), "floor, not truncation, below zero");
	}

	@Test
	void flowToleranceSkipsSmallCapacityChangesButNotSpeedOrRestoration() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link).withUpdateIntervalSeconds(1).withFlowTolerance(0.2));
		Link a = f.link("a");
		f.step(0);
		f.park(0, "v1", "a");
		f.step(1);
		// s = 0.25: target f = 0.875 is within 0.2 of the applied 1, so only the speed changes
		assertEquals(1, f.events.size());
		assertNull(f.events.get(0).getFlowCapacityChange(), "flow change below the tolerance is skipped");
		assertNull(f.events.get(0).getLanesChange());
		assertEquals(BASE_FREESPEED * 0.95, f.events.get(0).getFreespeedChange().getValue(), 1e-12);
		assertEquals(BASE_CAPACITY, a.getCapacity(1.0), 0.0);
		assertEquals(0, f.effect.getCapacityChangeEvents());

		f.park(1, "v2", "a");
		f.step(2);
		// s = 0.5: target 0.75 is 0.25 below the applied 1: applied
		assertEquals(0.75, f.effect.appliedFlowFactor(a.getId()), 1e-15);
		assertEquals(BASE_CAPACITY * 0.75, a.getCapacity(2.0), 1e-9);
		assertEquals(1, f.effect.getCapacityChangeEvents());
		f.park(2, "v3", "a");
		f.step(3);
		// s = 0.75: target 0.625 is only 0.125 below the applied 0.75: held, speed still exact
		assertEquals(0.75, f.effect.appliedFlowFactor(a.getId()), 1e-15);
		assertEquals(BASE_CAPACITY * 0.75, a.getCapacity(3.0), 1e-9);
		assertEquals(BASE_FREESPEED * 0.85, a.getFreespeed(3.0), 1e-12, "speed exact throughout");
		assertEquals(1, f.effect.getCapacityChangeEvents());
		assertEquals(3, f.events.size(), "three events, one of them changing capacity");

		f.unpark(3, "v1", "a");
		f.unpark(3, "v2", "a");
		f.unpark(3, "v3", "a");
		f.step(4);
		assertEquals(BASE_CAPACITY, a.getCapacity(4.0), 0.0, "s = 0 restores the base however small the step");
		assertEquals(BASE_LANES, a.getNumberOfLanes(4.0), 0.0);
		assertEquals(BASE_FREESPEED, a.getFreespeed(4.0), 0.0);
	}

	// ---- update interval and lag ----

	@Test
	void lagIsOneStepWithAOneSecondInterval() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link).withUpdateIntervalSeconds(1));
		Link a = f.link("a");
		for (int t = 0; t <= 10; t++) {
			f.step(t);
		}
		f.park(10, "v1", "a");
		assertEquals(BASE_FREESPEED, a.getFreespeed(10.0), 0.0, "the step in which the car parks still has the base");
		f.step(11);
		assertEquals(BASE_FREESPEED * 0.95, a.getFreespeed(11.0), 1e-12, "the next step has the reduction");
		assertEquals(11.0, f.events.get(0).getStartTime());
	}

	@Test
	void lagIsBoundedByTheUpdateInterval() {
		double interval = 60;
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link).withUpdateIntervalSeconds(interval));
		Link a = f.link("a");
		double parkedAt = 10;
		for (int t = 0; t <= 200; t++) {
			f.step(t);
			if (t == parkedAt) {
				f.park(t, "v1", "a");
			}
		}
		assertEquals(1, f.events.size());
		double appliedAt = f.events.get(0).getStartTime();
		assertEquals(60.0, appliedAt, "updates at 0, 60, 120, ...: the first after the parking event");
		assertTrue(appliedAt - parkedAt >= 1 && appliedAt - parkedAt <= interval + 1, "lag " + (appliedAt - parkedAt) + " outside [1 step, interval + 1 step]");
		assertEquals(BASE_FREESPEED, a.getFreespeed(59.0), 0.0);
		assertEquals(BASE_FREESPEED * 0.95, a.getFreespeed(60.0), 1e-12);

		// Changes within one interval coalesce into one event per link.
		f.park(200, "v2", "a");
		f.unpark(200, "v1", "a");
		f.park(200, "v3", "a");
		for (int t = 201; t <= 240; t++) {
			f.step(t);
		}
		assertEquals(2, f.events.size());
		assertEquals(240.0, f.events.get(1).getStartTime());
		assertEquals(0.75, f.effect.appliedFlowFactor(a.getId()), 1e-15, "two parked at the update: s = 0.5");
	}

	// ---- outputs ----

	@Test
	void outputsSummariseTheAppliedEffect() throws IOException {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link).withUpdateIntervalSeconds(1));
		f.step(0);
		f.park(0, "v1", "a");
		f.park(0, "v2", "a");
		f.park(0, "v3", "a");
		f.park(0, "v4", "a");
		for (int t = 1; t <= 1800; t++) {
			f.step(t);
		}
		f.unpark(1800, "v1", "a");
		f.unpark(1800, "v2", "a");
		f.unpark(1800, "v3", "a");
		f.unpark(1800, "v4", "a");
		for (int t = 1801; t <= 7200; t++) {
			f.step(t);
		}
		f.effect.finish(7200);

		String net = utils.getOutputDirectory() + "effect_network.csv";
		f.effect.writeNetwork(net);
		List<String> rows = read(net);
		assertEquals("binStart;coveredSeconds;effectMode;linksInScope;laneKmInScope;meanKerbShare;meanFlowFactor;meanSpeedFactor;meanLinksWithReducedFlow;maxLinksWithReducedFlow;changeEvents;capacityChangeEvents", rows.get(0));
		assertEquals(3, rows.size(), rows.toString());
		// In scope: a, c, d with 0.2 lane-km each. a is full (f = 0.5, g = 0.8) from 1 s to 1801 s.
		// Hour 0: a reduced for 1800 of 3600 s. Mean f = (3 * 3600 - 0.5 * 0.2 * 1800 / 0.2 ... ) per lane-km:
		// sum over links of laneKm * f, time-averaged, over 0.6 lane-km = 1 - (0.2 * 0.5 * 1800 / 3600) / 0.6
		String[] h0 = rows.get(1).split(";");
		assertEquals("00:00:00", h0[0]);
		assertEquals("3600.000000", h0[1]);
		assertEquals("link", h0[2]);
		assertEquals("3", h0[3]);
		assertEquals(0.6, Double.parseDouble(h0[4]), 1e-9);
		assertEquals((0.2 * 1.0 * 1800 / 3600) / 0.6, Double.parseDouble(h0[5]), 1e-6, "mean share");
		assertEquals(1 - (0.2 * 0.5 * 1800 / 3600) / 0.6, Double.parseDouble(h0[6]), 1e-6, "mean flow factor");
		assertEquals(1 - (0.2 * 0.2 * 1800 / 3600) / 0.6, Double.parseDouble(h0[7]), 1e-6, "mean speed factor");
		assertEquals(0.5, Double.parseDouble(h0[8]), 1e-6, "one link reduced for half the hour");
		assertEquals("1", h0[9]);
		assertEquals("2", h0[10], "one reduction and one restoration");
		assertEquals("2", h0[11], "both change capacity");
		String[] h1 = rows.get(2).split(";");
		assertEquals("01:00:00", h1[0]);
		assertEquals(1.0, Double.parseDouble(h1[6]), 1e-12);
		assertEquals("0", h1[9]);
		assertEquals("0", h1[10]);

		String perLink = utils.getOutputDirectory() + "effect_per_link.csv";
		f.effect.writePerLink(perLink);
		List<String> linkRows = read(perLink);
		assertEquals("linkId;group;baseCapacity;baseLanes;baseFreespeed;laneKm;secondsWithReducedFlow;minFlowFactor;minSpeedFactor;changeEvents;capacityChangeEvents", linkRows.get(0));
		assertEquals(List.of("a;a;1800.000000;2.000000;10.000000;0.200000;1800.000000;0.500000;0.800000;2;2"), linkRows.subList(1, linkRows.size()));
	}

	@Test
	void preconditionsRejectAnUnusableSetup() {
		Config config = ConfigUtils.createConfig();
		KerbParkingCapacityEffectParams link = KerbParkingCapacityEffectParams.defaults(EffectMode.link);
		assertThrows(IllegalStateException.class, () -> KerbParkingCapacityEffect.checkPreconditions(config, link), "network not time-variant");
		config.network().setTimeVariantNetwork(true);
		KerbParkingCapacityEffect.checkPreconditions(config, link);
		assertThrows(IllegalStateException.class, () -> KerbParkingCapacityEffect.checkPreconditions(config, KerbParkingCapacityEffectParams.off()));
		config.eventsManager().setNumberOfThreads(2);
		config.eventsManager().setSynchronizeOnSimSteps(false);
		assertThrows(IllegalStateException.class, () -> KerbParkingCapacityEffect.checkPreconditions(config, link), "events not synchronised on steps");
		// null is allowed by the setter and makes EventsManagerModule bind the unsynchronised ParallelEventsManagerImpl
		config.eventsManager().setSynchronizeOnSimSteps(null);
		assertThrows(IllegalStateException.class, () -> KerbParkingCapacityEffect.checkPreconditions(config, link), "synchronizeOnSimSteps null means unsynchronised");
		config.eventsManager().setSynchronizeOnSimSteps(true);
		KerbParkingCapacityEffect.checkPreconditions(config, link);
		config.eventsManager().setOneThreadPerHandler(true);
		assertThrows(IllegalStateException.class, () -> KerbParkingCapacityEffect.checkPreconditions(config, link));
	}

	@Test
	void observerReportsOnlyOnStreetChanges() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link));
		f.observerStep(0);
		List<Integer> changed = new ArrayList<>();
		f.observer.drainOnStreetChanges(changed::add);
		assertEquals(List.of(), changed, "seeding is not a change");
		f.park(0, "v1", "d");
		f.park(0, "v2", "d"); // d has one space: the second goes off-street
		f.park(0, "v3", "e"); // not permitted: off-street
		f.observer.drainOnStreetChanges(changed::add);
		assertEquals(List.of(f.observer.indexOf(Id.createLinkId("d"))), changed);
		changed.clear();
		f.observer.drainOnStreetChanges(changed::add);
		assertEquals(List.of(), changed, "drained");
		f.observerStep(1);
		f.unpark(1, "v2", "d"); // off-street release
		f.observer.drainOnStreetChanges(changed::add);
		assertEquals(List.of(), changed, "an off-street release is not an on-street change");
	}

	private static List<String> read(String file) throws IOException {
		try (BufferedReader reader = IOUtils.getBufferedReader(file)) {
			return reader.lines().toList();
		}
	}

	private final class Fixture {
		final Config config = ConfigUtils.createConfig();
		final Network network;
		final ParkingOccupancyObserver observer;
		final KerbParkingCapacityEffect effect;
		final List<NetworkChangeEvent> events = new ArrayList<>();

		Fixture(KerbParkingCapacityEffectParams params) {
			this(params, null, 0);
		}

		Fixture(KerbParkingCapacityEffectParams params, String seededLink, int seeded) {
			config.controller().setOutputDirectory(utils.getOutputDirectory());
			config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
			config.network().setTimeVariantNetwork(true);
			Scenario scenario = ScenarioUtils.createScenario(config);
			network = scenario.getNetwork();
			Node n0 = node("0", 0, 0);
			Node n1 = node("1", 100, 0);
			Node n2 = node("2", 200, 0);
			Node n3 = node("3", 300, 0);
			Node n6 = node("6", 600, 0);
			Node n7 = node("7", 700, 0);
			Node n8 = node("8", 800, 0);
			Node m1 = node("m1", -150, -150);
			Node m2 = node("m2", -50, -50);
			kerb(link("a", n0, n1), 4);
			kerb(link("b", n1, n2), 0);
			kerb(link("c", n2, n3), 2);
			kerb(link("d", n6, n7), 1);
			link("e", n7, n8);
			link("neg", m1, m2);

			OutputDirectoryHierarchy hierarchy = new OutputDirectoryHierarchy(config);
			hierarchy.createIterationDirectory(0);
			ParkingCapacityInitializer initializer = new ZeroParkingCapacityInitializer(network, config);
			if (seededLink != null) {
				ParkingCapacityInitializer base = initializer;
				initializer = new ParkingCapacityInitializer() {
					@Override
					public java.util.Map<Id<Link>, ParkingInitialCapacity> initialize() {
						return base.initialize();
					}

					@Override
					public java.util.Map<Id<Link>, ParkingInitialPools> initializePools() {
						java.util.Map<Id<Link>, ParkingInitialPools> pools = new java.util.HashMap<>(base.initializePools());
						ParkingInitialPools p = pools.get(Id.createLinkId(seededLink));
						pools.put(Id.createLinkId(seededLink), new ParkingInitialPools(p.onStreetCapacity(), p.offStreetCapacity(), seeded));
						return pools;
					}
				};
			}
			observer = new ParkingOccupancyObserver(network, initializer, config, hierarchy);
			// e is not a kerb parking place; the others carry the attribute and are places by declaration
			observer.setKerbParkingEligibility(l -> !"e".equals(l.getId().toString()));
			observer.notifyBeforeMobsim(new BeforeMobsimEvent(null, 0, false));
			effect = new KerbParkingCapacityEffect(observer, network, params, hierarchy, config);
			effect.reset(event -> {
				events.add(event);
				NetworkUtils.addNetworkChangeEvent(network, event);
			});
		}

		private Node node(String id, double x, double y) {
			return NetworkUtils.createAndAddNode(network, Id.create(id, Node.class), new Coord(x, y));
		}

		private Link link(String id, Node from, Node to) {
			return NetworkUtils.createAndAddLink(network, Id.createLinkId(id), from, to, 100, BASE_FREESPEED, BASE_CAPACITY, BASE_LANES);
		}

		private void kerb(Link link, int spaces) {
			link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, spaces);
		}

		Link link(String id) {
			return network.getLinks().get(Id.createLinkId(id));
		}

		/** One time step as the QSim runs it: observer and effect see the start of the step, then events follow. */
		void step(double time) {
			observerStep(time);
			effect.step(time);
		}

		void observerStep(double time) {
			observer.notifyMobsimBeforeSimStep(new MobsimBeforeSimStepEvent(null, time));
		}

		void park(double time, String vehicle, String link) {
			observer.handleEvent(new VehicleEndsParkingSearch(time, Id.createPersonId(vehicle), Id.createLinkId(link),
				Id.createVehicleId(vehicle), "car"));
		}

		void unpark(double time, String vehicle, String link) {
			observer.handleEvent(new VehicleEntersTrafficEvent(time, Id.createPersonId(vehicle), Id.createLinkId(link),
				Id.createVehicleId(vehicle), "car", 1.0));
		}
	}

	@Test
	void fixtureSanity() {
		Fixture f = new Fixture(KerbParkingCapacityEffectParams.defaults(EffectMode.link));
		assertNull(f.link("e").getAttributes().getAttribute(LINK_ON_STREET_SPOTS));
		assertEquals(4, f.observer.getOnStreetCapacity(Id.createLinkId("a")));
		assertEquals(0, f.observer.getOnStreetCapacity(Id.createLinkId("b")));
	}
}
