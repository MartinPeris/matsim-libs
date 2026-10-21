package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.VehicleEndsParkingSearch;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEndsParkingSearchEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.contrib.parking.parkingsearchparameterization.KerbParkingCapacityEffectParams.EffectMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ControllerConfigGroup;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.qnetsimengine.ConstantArrivalTime;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.examples.ExamplesUtils;
import org.matsim.testcases.MatsimTestUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capacity effect inside a running QSim.
 */
class KerbParkingCapacityEffectIntegrationTest {
	private static final Logger log = LogManager.getLogger(KerbParkingCapacityEffectIntegrationTest.class);

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	// ---- mode off is the existing wiring ----

	/**
	 * Mode off through the new runner wiring writes the same bytes as the wiring {@link ParkingPoolsIntegrationTest}
	 * uses (the kerb-first wiring before the effect existed), for every parking report and for the events.
	 */
	@Test
	void effectOffWritesByteIdenticalOutputsToTheKerbFirstWiring() throws IOException {
		String before = utils.getOutputDirectory() + "before/";
		String off = utils.getOutputDirectory() + "off/";

		Controller a = equilController(before);
		a.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				addQSimComponentBinding("ParkingOccupancyOberserver").to(ParkingOccupancyObserver.class);
				addMobsimScopeEventHandlerBinding().to(ParkingOccupancyObserver.class);
				bind(ParkingVehicleHandler.class).in(Singleton.class);
				addVehicleHandlerBinding().to(ParkingVehicleHandler.class);
				addMobsimScopeEventHandlerBinding().to(ParkingVehicleHandler.class);
				addParkingSearchTimeCalculatorBinding().toInstance(new ConstantArrivalTime(1));
			}
		});
		a.addOverridingModule(new AbstractModule() {
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
		a.run();

		Controller b = equilController(off);
		RunKerbFirstParking.install(b, new KerbParkingEligibility.MinimumLanes(1.0), KerbParkingSupplyParams.defaults(),
			KerbParkingCapacityEffectParams.off(), 1);
		b.run();

		for (String file : List.of("ITERS/it.0/0." + ParkingSpilloverReport.PER_LINK_FILE,
			"ITERS/it.0/0." + ParkingSpilloverReport.NETWORK_FILE,
			"ITERS/it.0/0." + ParkingUtils.PARKING_INITIAL_FILE,
			"output_events.xml.gz")) {
			String x = slurp(before + file);
			String y = slurp(off + file);
			assertTrue(x.length() > 100, file + " is too short to be a meaningful comparison");
			assertEquals(x, y, file + " differs between the kerb-first wiring and the new wiring with the effect off");
		}
		assertFalse(new File(off + "ITERS/it.0/0." + KerbParkingCapacityEffect.NETWORK_FILE).exists(), "mode off binds nothing");
	}

	private Controller equilController(String outputDirectory) {
		Config config = ConfigUtils.loadConfig(IOUtils.extendUrl(ExamplesUtils.getTestScenarioURL("equil"), "config_plans1.xml"));
		config.controller().setOutputDirectory(outputDirectory);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		config.controller().setCompressionType(ControllerConfigGroup.CompressionType.gzip);
		config.replanning().clearStrategySettings();
		config.routing().setAccessEgressConsistencyCheck(RoutingConfigGroup.AccessEgressConsistencyCheck.disable);
		return ControllerUtils.createController(ScenarioUtils.loadScenario(config));
	}

	// ---- the effect in the QSim ----

	/**
	 * Four cars park on {@code street} at 06:00 (two kerb spaces after scaling by 0.5, so the kerb is full: s = 1,
	 * f = 0.5, g = 0.8). At 07:00 a platoon of 20 cars drives through.
	 * <ul>
	 * <li>Travel time of the first through car on the 1000 m street: 100 s at 10 m/s with the effect off, 125 s at
	 * 8 m/s with it on.</li>
	 * <li>Outflow headway once the platoon queues: 1 / (1800/3600 * f * flowCapacityFactor), i.e. 4 s off and 8 s on.
	 * If the effect had applied the sample factor itself the headway would be 16 s; had it written veh/h where the
	 * network expects veh/s, capacity would be 3600 times too high and the headway set by the inflow instead.</li>
	 * </ul>
	 */
	@Test
	void parkedCarsSlowAndThrottleTheirStreetThroughTheSampleFactors() {
		StreetRun off = runStreet("off", KerbParkingCapacityEffectParams.off(), 0.5, 4, 0, linkModeAgents());
		StreetRun on = runStreet("on", KerbParkingCapacityEffectParams.defaults(EffectMode.link), 0.5, 4, 0, linkModeAgents());

		double ttOff = off.travelTimeOfFirstThroughCar();
		double ttOn = on.travelTimeOfFirstThroughCar();
		log.info("first through car on street: {} s off, {} s on", ttOff, ttOn);
		// the QSim lets a car leave in the step after its free-flow time has passed: 100 + 1 and 125 + 1
		assertEquals(101.0, ttOff, 0.0);
		assertEquals(126.0, ttOn, 0.0);

		double hOff = off.medianHeadway();
		double hOn = on.medianHeadway();
		log.info("median outflow headway: {} s off, {} s on", hOff, hOn);
		assertEquals(4.0, hOff, 0.5);
		assertEquals(8.0, hOn, 0.5);

		assertEquals(0, off.changeEvents());
		// The kerb fills in two steps (s = 0.5, then 1; the third and fourth car go off-street), which fall into one or
		// two update intervals.
		assertTrue(on.changeEvents() >= 1 && on.changeEvents() <= 2, "change events: " + on.changeEvents());
	}

	private static AgentSpec linkModeAgents() {
		return population -> {
			for (int i = 0; i < 4; i++) {
				addTrip(population, "parker_" + i, "in", "street", 6 * 3600 + 10 * i);
			}
			for (int i = 0; i < 20; i++) {
				addTrip(population, "through_" + i, "in", "out", 7 * 3600);
			}
		};
	}

	/**
	 * A queue on {@code street} while cars park one by one on {@code plink}, in the same cell. Each capacity change
	 * on {@code street} resets its outflow accumulator in {@code QueueWithBuffer.recalcTimeVariantAttributes}, which
	 * can release a queued vehicle early. This test measures that and checks it stays within its bound: the extra
	 * vehicles over what the applied capacity alone allows is at most the number of capacity changes.
	 * <p>
	 * At a 10% sample the street's 1800 veh/h is 180 veh/h, one car per 20 s with the effect off. Sixty cars park on
	 * {@code plink} 30 s apart from 07:00 (60 scaled kerb spaces), so the cell's share rises from 0 to 1 over 30 min
	 * and {@code f} from 1 to 0.5.
	 */
	@Test
	void capacityChangesOnAQueuedLinkStayWithinTheirBound() {
		AgentSpec agents = population -> {
			for (int i = 0; i < 60; i++) {
				addTrip(population, "parker_" + i, "pin", "plink", 7 * 3600 + 30 * i);
			}
			for (int i = 0; i < 160; i++) {
				addTrip(population, "through_" + i, "in", "out", 7 * 3600 - 120);
			}
		};
		double windowStart = 7 * 3600 + 5 * 60;
		double windowEnd = 7 * 3600 + 35 * 60;
		KerbParkingCapacityEffectParams cell = new KerbParkingCapacityEffectParams(EffectMode.cell, 10_000, 0.5, 0.5, 0.7, 0.2, 60);

		StreetRun off = runStreet("off", KerbParkingCapacityEffectParams.off(), 0.1, 0, 600, agents);
		int throughputOff = off.leftStreetBetween(windowStart, windowEnd);
		log.info("bottleneck: effect off: {} vehicles left street in the window", throughputOff);
		assertEquals(90, throughputOff, 2, "30 min at 180 veh/h");

		// Re-evaluating every second lets the resets win: more traffic gets through than with no effect at all. This is
		// the reason for the update interval and is recorded here, not asserted, as it is a property of the QSim.
		StreetRun everySecond = runStreet("cell-u1", cell.withUpdateIntervalSeconds(1), 0.1, 0, 600, agents);
		log.info("bottleneck: cell-u1: {} vehicles left street ({} capacity changes)", everySecond.leftStreetBetween(windowStart, windowEnd),
			everySecond.capacityChangeEvents());

		for (KerbParkingCapacityEffectParams p : List.of(cell, cell.withFlowTolerance(0.05), cell.withUpdateIntervalSeconds(300))) {
			String name = "cell-u" + (int) p.updateIntervalSeconds() + "-tol" + p.flowTolerance();
			StreetRun on = runStreet(name, p, 0.1, 0, 600, agents);
			int throughput = on.leftStreetBetween(windowStart, windowEnd);
			double allowed = on.capacityAllowedBetween(windowStart, windowEnd, 1800.0 / 3600 * 0.1);
			long capacityChanges = on.capacityChangeEvents();
			log.info("bottleneck: {}: {} vehicles left street; applied capacity allows {}; {} change events, {} changing capacity; excess {}",
				name, throughput, String.format("%.1f", allowed), on.changeEvents(), capacityChanges, String.format("%.1f", throughput - allowed));
			assertTrue(throughput - allowed <= capacityChanges + 2,
				name + ": " + throughput + " vehicles, applied capacity allows " + allowed + ", only " + capacityChanges + " capacity changes to explain the excess");
			assertTrue(throughput < throughputOff, name + ": the effect must not let more through than no effect");
		}
	}

	// ---- scenario ----

	interface AgentSpec {
		void add(Population population);
	}

	private static void addTrip(Population population, String id, String fromLink, String toLink, double departure) {
		PopulationFactory pf = population.getFactory();
		Person person = pf.createPerson(Id.createPersonId(id));
		Plan plan = pf.createPlan();
		Activity home = pf.createActivityFromLinkId("home", Id.createLinkId(fromLink));
		home.setEndTime(departure);
		plan.addActivity(home);
		plan.addLeg(pf.createLeg(TransportMode.car));
		plan.addActivity(pf.createActivityFromLinkId("work", Id.createLinkId(toLink)));
		person.addPlan(plan);
		population.addPerson(person);
	}

	/**
	 * Main road {@code in} (100 m) - {@code street} (1000 m, 1800 veh/h, 1 lane) - {@code out} (100 m), and a side road
	 * {@code pin} (100 m) - {@code plink} (1000 m) in parallel, 100 m away. Everything but {@code street} has
	 * 36000 veh/h and 4 lanes; all at 10 m/s. Two links nobody uses ({@code x1}, {@code x2}) join the two roads into a
	 * loop, because the router requires a strongly connected network. Kerb parking only where an
	 * {@code onstreet_spots} attribute is given.
	 */
	private StreetRun runStreet(String name, KerbParkingCapacityEffectParams effect, double sample, int streetKerb,
								int plinkKerb, AgentSpec agents) {
		String dir = utils.getOutputDirectory() + name + "/";
		Config config = ConfigUtils.createConfig();
		config.controller().setOutputDirectory(dir);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setLastIteration(0);
		config.controller().setWriteEventsInterval(0);
		config.replanning().clearStrategySettings();
		config.routing().setAccessEgressConsistencyCheck(RoutingConfigGroup.AccessEgressConsistencyCheck.disable);
		config.qsim().setFlowCapFactor(sample);
		config.qsim().setStorageCapFactor(sample);
		config.qsim().setTrafficDynamics(QSimConfigGroup.TrafficDynamics.kinematicWaves);
		config.qsim().setStuckTime(36000);
		config.qsim().setEndTime(12 * 3600);
		for (String type : List.of("home", "work")) {
			ScoringConfigGroup.ActivityParams params = new ScoringConfigGroup.ActivityParams(type);
			params.setTypicalDuration(8 * 3600);
			config.scoring().addActivityParams(params);
		}
		if (effect.mode() != EffectMode.off) {
			config.network().setTimeVariantNetwork(true);
		}
		Scenario scenario = ScenarioUtils.createScenario(config);
		Network network = scenario.getNetwork();
		Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n0"), new Coord(0, 0));
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(100, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(1100, 0));
		Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n3"), new Coord(1200, 0));
		Node m0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("m0"), new Coord(0, 100));
		Node m1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("m1"), new Coord(100, 100));
		Node m2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("m2"), new Coord(1100, 100));
		NetworkUtils.createAndAddLink(network, Id.createLinkId("in"), n0, n1, 100, 10, 36000, 4);
		Link street = NetworkUtils.createAndAddLink(network, Id.createLinkId("street"), n1, n2, 1000, 10, 1800, 1);
		NetworkUtils.createAndAddLink(network, Id.createLinkId("out"), n2, n3, 100, 10, 36000, 4);
		NetworkUtils.createAndAddLink(network, Id.createLinkId("pin"), m0, m1, 100, 10, 36000, 4);
		Link plink = NetworkUtils.createAndAddLink(network, Id.createLinkId("plink"), m1, m2, 1000, 10, 36000, 4);
		// Closing the loop so the car network is strongly connected; no route uses these.
		NetworkUtils.createAndAddLink(network, Id.createLinkId("x1"), n3, m0, 1300, 10, 36000, 4);
		NetworkUtils.createAndAddLink(network, Id.createLinkId("x2"), m2, n0, 1200, 10, 36000, 4);
		street.getAttributes().putAttribute(ParkingUtils.LINK_ON_STREET_SPOTS, streetKerb);
		if (plinkKerb > 0) {
			plink.getAttributes().putAttribute(ParkingUtils.LINK_ON_STREET_SPOTS, plinkKerb);
		}
		agents.add(scenario.getPopulation());

		StreetObserver observer = new StreetObserver();
		Controller controller = ControllerUtils.createController(scenario);
		RunKerbFirstParking.install(controller, link -> false, KerbParkingSupplyParams.defaults(), effect, 0);
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(observer);
			}
		});
		controller.run();
		return new StreetRun(dir, observer, effect);
	}

	private static final class StreetObserver implements LinkEnterEventHandler, LinkLeaveEventHandler, VehicleEndsParkingSearchEventHandler {
		final Map<String, Double> enter = new HashMap<>();
		final Map<String, Double> leave = new HashMap<>();
		final List<Double> parkedOnPlink = new ArrayList<>();

		@Override
		public void handleEvent(LinkEnterEvent event) {
			if (event.getLinkId().toString().equals("street")) {
				enter.put(event.getVehicleId().toString(), event.getTime());
			}
		}

		@Override
		public void handleEvent(LinkLeaveEvent event) {
			if (event.getLinkId().toString().equals("street")) {
				leave.put(event.getVehicleId().toString(), event.getTime());
			}
		}

		@Override
		public void handleEvent(VehicleEndsParkingSearch event) {
			if (event.getLinkId().toString().equals("plink")) {
				parkedOnPlink.add(event.getTime());
			}
		}
	}

	private record StreetRun(String dir, StreetObserver observer, KerbParkingCapacityEffectParams effect) {
		/** The first through car to enter the street meets no queue: its time is the free-flow time. */
		double travelTimeOfFirstThroughCar() {
			String first = observer.enter.entrySet().stream().filter(e -> e.getKey().startsWith("through"))
				.min(Map.Entry.comparingByValue()).orElseThrow().getKey();
			return observer.leave.get(first) - observer.enter.get(first);
		}

		double medianHeadway() {
			double[] times = observer.leave.entrySet().stream().filter(e -> e.getKey().startsWith("through"))
				.mapToDouble(Map.Entry::getValue).sorted().toArray();
			double[] gaps = new double[times.length - 1];
			for (int i = 1; i < times.length; i++) {
				gaps[i - 1] = times[i] - times[i - 1];
			}
			Arrays.sort(gaps);
			return gaps[gaps.length / 2];
		}

		int leftStreetBetween(double from, double to) {
			return (int) observer.leave.values().stream().filter(t -> t >= from && t < to).count();
		}

		/**
		 * Vehicles the applied flow capacity lets through between two times, with the sample factor: the integral of
		 * {@code capacityPerSecond * f(t)} where {@code f} follows the kerb share exactly (60 scaled spaces), lagged
		 * to the update times. Without the outflow accumulator reset this is what a saturated queue achieves.
		 */
		double capacityAllowedBetween(double from, double to, double capacityPerSecond) {
			List<Double> parked = observer.parkedOnPlink.stream().sorted().toList();
			double total = 0;
			for (double t = from; t < to; t++) {
				double lagged = Math.floor((t - 0) / effect.updateIntervalSeconds()) * effect.updateIntervalSeconds();
				final double cut = lagged - 1; // parked by the step before the update
				long n = parked.stream().filter(p -> p <= cut).count();
				double target = effect.flowFactor(Math.min(1.0, n / 60.0));
				total += capacityPerSecond * target;
			}
			return total;
		}

		long changeEvents() {
			return sumColumn(10);
		}

		long capacityChangeEvents() {
			return sumColumn(11);
		}

		private long sumColumn(int column) {
			File file = new File(dir + "ITERS/it.0/0." + KerbParkingCapacityEffect.NETWORK_FILE);
			if (!file.exists()) {
				return 0;
			}
			try (BufferedReader reader = IOUtils.getBufferedReader(file.getPath())) {
				return reader.lines().skip(1).mapToLong(l -> Long.parseLong(l.split(";")[column])).sum();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private static String slurp(String file) throws IOException {
		try (BufferedReader reader = IOUtils.getBufferedReader(file)) {
			StringBuilder sb = new StringBuilder();
			char[] buf = new char[1 << 16];
			int n;
			while ((n = reader.read(buf)) > 0) {
				sb.append(buf, 0, n);
			}
			return sb.toString();
		}
	}
}
