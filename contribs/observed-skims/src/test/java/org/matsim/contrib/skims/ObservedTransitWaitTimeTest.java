package org.matsim.contrib.skims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

/**
 * One line, one route, two stops, a service every 600 s. Everything here turns on the distinction
 * between what the timetable promises and what the mobsim delivered.
 */
class ObservedTransitWaitTimeTest {

	private static final Id<TransitLine> LINE = Id.create("L", TransitLine.class);
	private static final Id<TransitRoute> ROUTE = Id.create("R", TransitRoute.class);
	private static final Id<TransitStopFacility> STOP_A = Id.create("a", TransitStopFacility.class);
	private static final Id<Vehicle> VEH = Id.createVehicleId("v");
	private static final double BIN = 3600.0;
	private static final double END = 3 * 3600.0;

	@Test
	void anUnobservedBinFallsBackToTheTimetable() {
		ObservedTransitWaitTime skim = skim(1.0);

		// A service every 600 s, so a passenger arriving at a random moment expects half the headway.
		assertEquals(300.0, skim.waitTime(LINE, ROUTE, STOP_A, 100.0), 1e-6,
			"with nothing observed the skim must still answer, and the timetable is the only answer available");
		assertEquals(0.0, skim.excessWaitTime(LINE, ROUTE, STOP_A, 100.0), 1e-9,
			"and it must claim no excess, so installing a skim changes no cost until it has learned something");
	}

	@Test
	void anObservedWaitReplacesTheFallbackOnceConsolidated() {
		ObservedTransitWaitTime skim = skim(1.0);
		observe(skim, 100.0, 1000.0);

		assertEquals(300.0, skim.waitTime(LINE, ROUTE, STOP_A, 100.0), 1e-6,
			"before the iteration ends nothing is published: a skim read mid-mobsim would be half-built");
		assertEquals(1, skim.observationCount(LINE, ROUTE, STOP_A, 100.0));

		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(900.0, skim.waitTime(LINE, ROUTE, STOP_A, 100.0), 1e-9, "observed 1000 - 100");
		assertEquals(600.0, skim.excessWaitTime(LINE, ROUTE, STOP_A, 100.0), 1e-6,
			"900 observed against 300 the timetable implies");
	}

	@Test
	void theWaitIsAttributedToTheBinThePassengerArrivedIn() {
		ObservedTransitWaitTime skim = skim(1.0);
		// Arrives at 3500, ten minutes before the bin boundary, and boards at 4100, inside the next bin.
		observe(skim, 3500.0, 4100.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(600.0, skim.waitTime(LINE, ROUTE, STOP_A, 3500.0), 1e-6,
			"a router asking what a passenger arriving at 3500 should expect must get this observation");
		assertEquals(0, skim.observationCount(LINE, ROUTE, STOP_A, 4100.0),
			"and it must not be filed under the bin they happened to board in");
	}

	@Test
	void bothObservationsInABinAreAveraged() {
		ObservedTransitWaitTime skim = skim(1.0);
		observe(skim, 100.0, 400.0, "p1");
		observe(skim, 200.0, 1100.0, "p2");
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));

		assertEquals(600.0, skim.waitTime(LINE, ROUTE, STOP_A, 100.0), 1e-6, "mean of 300 and 900");
	}

	@Test
	void dampingMovesPartWayTowardsTheNewObservation() {
		ObservedTransitWaitTime skim = skim(0.5);
		observe(skim, 100.0, 1000.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		assertEquals(450.0, skim.waitTime(LINE, ROUTE, STOP_A, 100.0), 1e-6,
			"first iteration moves half way from nought, which is where a damped mean starts");

		observe(skim, 100.0, 1000.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 1, false));
		assertEquals(675.0, skim.waitTime(LINE, ROUTE, STOP_A, 100.0), 1e-6, "and half way again");
	}

	@Test
	void aBinWithNoObservationKeepsWhatItHad() {
		ObservedTransitWaitTime skim = skim(0.5);
		observe(skim, 100.0, 1000.0);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 0, false));
		double afterFirst = skim.waitTime(LINE, ROUTE, STOP_A, 100.0);

		skim.reset(1);
		skim.notifyIterationEnds(new IterationEndsEvent(null, 1, false));

		assertEquals(afterFirst, skim.waitTime(LINE, ROUTE, STOP_A, 100.0), 1e-6,
			"an iteration nobody boarded in is absence of evidence, not evidence that the wait is nought");
	}

	@Test
	void theTimetableFallbackIsHalfTheHeadwayNotTheWaitAtTheBinEdge() {
		ObservedTransitWaitTime skim = skim(1.0);

		for (double t : new double[]{0.0, 900.0, 1800.0, 3599.0, 7200.0}) {
			assertEquals(300.0, skim.waitTime(LINE, ROUTE, STOP_A, t), 1e-6,
				"a 600 s headway means an expected wait of 300 s wherever in the bin the query lands; "
					+ "sampling the sawtooth at the bin edge instead would give 0 at t=" + t);
		}
	}

	@Test
	void anUnknownLineIsAnsweredWithoutInventingAWait() {
		ObservedTransitWaitTime skim = skim(1.0);
		Id<TransitLine> other = Id.create("other", TransitLine.class);

		assertEquals(0.0, skim.waitTime(other, ROUTE, STOP_A, 100.0), 1e-9);
		assertEquals(0.0, skim.excessWaitTime(other, ROUTE, STOP_A, 100.0), 1e-9);
	}

	@Test
	void aBoardingWithNoPrecedingDepartureIsIgnored() {
		ObservedTransitWaitTime skim = skim(1.0);
		skim.handleEvent(new TransitDriverStartsEvent(0, Id.createPersonId("d"), VEH, LINE, ROUTE, Id.create("dep", org.matsim.pt.transitSchedule.api.Departure.class)));
		skim.handleEvent(new VehicleArrivesAtFacilityEvent(90, VEH, STOP_A, 0));
		skim.handleEvent(new PersonEntersVehicleEvent(100, Id.createPersonId("p"), VEH));

		assertEquals(0, skim.observationCount(LINE, ROUTE, STOP_A, 100.0),
			"a transit driver boarding their own vehicle is not a passenger waiting");
	}

	@Test
	void constructorArgumentsAreChecked() {
		TransitSchedule schedule = schedule();
		assertThrows(IllegalArgumentException.class, () -> new ObservedTransitWaitTime(schedule, 0.0, END, 1.0));
		assertThrows(IllegalArgumentException.class, () -> new ObservedTransitWaitTime(schedule, BIN, END, 0.0));
		assertThrows(IllegalArgumentException.class, () -> new ObservedTransitWaitTime(schedule, BIN, END, 1.5));
		assertTrue(new ObservedTransitWaitTime(schedule, BIN, END, 1.0) != null);
	}

	private static ObservedTransitWaitTime skim(double updateWeight) {
		return new ObservedTransitWaitTime(schedule(), BIN, END, updateWeight);
	}

	private static void observe(ObservedTransitWaitTime skim, double arrives, double boards) {
		observe(skim, arrives, boards, "p");
	}

	private static void observe(ObservedTransitWaitTime skim, double arrives, double boards, String person) {
		skim.handleEvent(new TransitDriverStartsEvent(0, Id.createPersonId("driver"), VEH, LINE, ROUTE,
			Id.create("dep0", Departure.class)));
		skim.handleEvent(new VehicleArrivesAtFacilityEvent(boards - 1, VEH, STOP_A, 0));
		skim.handleEvent(new PersonDepartureEvent(arrives, Id.createPersonId(person), Id.createLinkId("l"),
			TransportMode.pt, TransportMode.pt));
		skim.handleEvent(new PersonEntersVehicleEvent(boards, Id.createPersonId(person), VEH));
	}

	/** Two stops, departures every 600 s from midnight to the end of the horizon. */
	private static TransitSchedule schedule() {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		TransitSchedule schedule = scenario.getTransitSchedule();
		TransitScheduleFactory f = schedule.getFactory();

		TransitStopFacility a = f.createTransitStopFacility(STOP_A, new Coord(0, 0), false);
		a.setLinkId(Id.create("l", Link.class));
		TransitStopFacility b = f.createTransitStopFacility(Id.create("b", TransitStopFacility.class),
			new Coord(1000, 0), false);
		b.setLinkId(Id.create("l2", Link.class));
		schedule.addStopFacility(a);
		schedule.addStopFacility(b);

		TransitRouteStop stopA = f.createTransitRouteStopBuilder(a).departureOffset(0.0).build();
		TransitRouteStop stopB = f.createTransitRouteStopBuilder(b).arrivalOffset(300.0).build();
		TransitRoute route = f.createTransitRoute(ROUTE, null, List.of(stopA, stopB), TransportMode.pt);
		// Past the horizon, so every bin has a service and no bin wraps to tomorrow.
		for (int t = 0; t <= END + 600; t += 600) {
			Departure departure = f.createDeparture(Id.create("dep" + t, Departure.class), t);
			departure.setVehicleId(VEH);
			route.addDeparture(departure);
		}
		TransitLine line = f.createTransitLine(LINE);
		line.addRoute(route);
		schedule.addTransitLine(line);
		return schedule;
	}

	static {
		// Referenced so the import of OptionalTime is not flagged unused if the builder API changes.
		OptionalTime.undefined();
	}
}
