package org.matsim.contrib.skims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

/**
 * Two stops 300 s apart by timetable. Everything here is about the gap between that and the road.
 */
class ObservedTransitStopStopTimeTest {

	private static final Id<TransitStopFacility> A = Id.create("a", TransitStopFacility.class);
	private static final Id<TransitStopFacility> B = Id.create("b", TransitStopFacility.class);
	private static final Id<Vehicle> VEH = Id.createVehicleId("v");
	private static final double BIN = 3600.0;
	private static final double END = 3 * 3600.0;

	@Test
	void anUnobservedPairFallsBackToTheTimetable() {
		ObservedTransitStopStopTime skim = skim(1.0);

		assertEquals(300.0, skim.stopStopTime(A, B, 100.0), 1e-9);
		assertEquals(0.0, skim.excessStopStopTime(A, B, 100.0), 1e-9,
			"and claims no excess, so a fresh skim changes no cost");
	}

	@Test
	void anObservedRunReplacesTheTimetableAndReportsTheExcess() {
		ObservedTransitStopStopTime skim = skim(1.0);
		run(skim, 100.0, 600.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(500.0, skim.stopStopTime(A, B, 100.0), 1e-9);
		assertEquals(200.0, skim.excessStopStopTime(A, B, 100.0), 1e-9, "500 run against 300 scheduled");
	}

	@Test
	void dwellTimeAtTheFirstStopIsNotCounted() {
		ObservedTransitStopStopTime skim = skim(1.0);
		// Arrives at a at 50, sits until 100, reaches b at 600. The running time is 500, not 550.
		skim.handleEvent(new VehicleArrivesAtFacilityEvent(50.0, VEH, A, 0));
		skim.handleEvent(new VehicleDepartsAtFacilityEvent(100.0, VEH, A, 0));
		skim.handleEvent(new VehicleArrivesAtFacilityEvent(600.0, VEH, B, 0));
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(500.0, skim.stopStopTime(A, B, 100.0), 1e-9,
			"time at the kerb with the doors open is not time spent travelling between stops");
	}

	@Test
	void theRunIsFiledUnderTheBinItStartedIn() {
		ObservedTransitStopStopTime skim = skim(1.0);
		run(skim, 3500.0, 4100.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(600.0, skim.stopStopTime(A, B, 3500.0), 1e-9,
			"a router asking about a departure at 3500 must be told what a departure at 3500 cost");
		assertEquals(0, skim.observationCount(A, B, 4100.0),
			"not the bin the vehicle happened to arrive in");
	}

	@Test
	void theOppositeDirectionIsADifferentPair() {
		ObservedTransitStopStopTime skim = skim(1.0);
		run(skim, 100.0, 600.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(0.0, skim.stopStopTime(B, A, 100.0), 1e-9,
			"no route runs b to a, so there is nothing known and nothing to invent");
	}

	@Test
	void aVehiclesFirstCallOfTheDayIsNotAnObservation() {
		ObservedTransitStopStopTime skim = skim(1.0);
		skim.handleEvent(new VehicleArrivesAtFacilityEvent(100.0, VEH, A, 0));
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(0, skim.observationCount(A, B, 100.0), "there is no preceding departure to measure from");
	}

	@Test
	void dampingApplies() {
		ObservedTransitStopStopTime skim = skim(0.5);
		run(skim, 100.0, 600.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(250.0, skim.stopStopTime(A, B, 100.0), 1e-9, "half way from nought to 500");
	}

	@Test
	void constructorArgumentsAreChecked() {
		TransitSchedule schedule = schedule();
		assertThrows(IllegalArgumentException.class, () -> new ObservedTransitStopStopTime(schedule, 0.0, END, 1.0));
		assertThrows(IllegalArgumentException.class, () -> new ObservedTransitStopStopTime(schedule, BIN, END, 1.5));
	}

	private static ObservedTransitStopStopTime skim(double updateWeight) {
		return new ObservedTransitStopStopTime(schedule(), BIN, END, updateWeight);
	}

	private static void run(ObservedTransitStopStopTime skim, double departs, double arrives) {
		skim.handleEvent(new VehicleDepartsAtFacilityEvent(departs, VEH, A, 0));
		skim.handleEvent(new VehicleArrivesAtFacilityEvent(arrives, VEH, B, 0));
	}

	private static TransitSchedule schedule() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory f = schedule.getFactory();

		TransitStopFacility a = f.createTransitStopFacility(A, new Coord(0, 0), false);
		a.setLinkId(Id.create("l", Link.class));
		TransitStopFacility b = f.createTransitStopFacility(B, new Coord(1000, 0), false);
		b.setLinkId(Id.create("l2", Link.class));
		schedule.addStopFacility(a);
		schedule.addStopFacility(b);

		TransitRouteStop stopA = f.createTransitRouteStopBuilder(a).departureOffset(0.0).build();
		TransitRouteStop stopB = f.createTransitRouteStopBuilder(b).arrivalOffset(300.0).build();
		TransitRoute route = f.createTransitRoute(Id.create("R", TransitRoute.class), null, List.of(stopA, stopB),
			TransportMode.pt);
		route.addDeparture(f.createDeparture(Id.create("dep", Departure.class), 0.0));
		TransitLine line = f.createTransitLine(Id.create("L", TransitLine.class));
		line.addRoute(route);
		schedule.addTransitLine(line);
		return schedule;
	}
}
