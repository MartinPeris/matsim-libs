/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package ch.sbb.matsim.routing.pt.raptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.contrib.drt.extension.skims.DrtRideTimeSkim;
import org.matsim.contrib.drt.extension.skims.DrtWaitTimeSkim;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.extension.skims.SkimAwareRaptorIntermodalAccessEgress;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress.RIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder.Direction;

/**
 * Drives {@link SkimAwareRaptorIntermodalAccessEgress} through {@link SwissRailRaptorCore} and
 * asserts on the <em>route</em> cost the core produces, rather than on what the decorator returns.
 * <p>
 * This lives here, in SwissRailRaptor's own package, because it needs the package-private
 * {@link Fixture} from matsim's test-jar. It exists because the decorator's own unit tests cannot
 * see the defect it guards: they inspect only the value handed to the core, and the core's
 * treatment of that value is precisely what was misunderstood.
 * <p>
 * The claim under test: adding W seconds of DRT wait to {@code InitialStop.accessTime}
 * <em>reduces</em> the platform wait the core charges by exactly W, whenever the traveller still
 * catches the same vehicle. The wait must therefore be charged in full on access. Charging only
 * {@code (factor - 1)} of it, as an earlier version did, leaves that refund uncancelled and makes a
 * longer wait strictly cheaper — the failure this test exists to catch.
 *
 * @author Monash Healthy Active Cities
 */
public class SkimAwareIntermodalAccessEgressRaptorIT {

	/** Chosen so the baseline platform wait (960 s) comfortably exceeds WAIT: same vehicle either way. */
	private static final double DEPARTURE_TIME = 6.0 * 3600 - 900;
	private static final double RIDE_TIME = 300;
	private static final double WAIT = 600;
	private static final double DIRECT_RIDE_TIME = 200;
	/** 1.5 * 200 + 600 */
	private static final double CEILING = 900;

	private static final String DRT = "drt";
	private static final Id<Link> ORIGIN = Id.createLinkId("origin");
	private static final Id<Link> STOP_LINK = Id.createLinkId("stop");
	private static final Id<TransitStopFacility> FROM = Id.create("0", TransitStopFacility.class);
	private static final Id<TransitStopFacility> TO = Id.create("16", TransitStopFacility.class);

	/**
	 * The ride-time half, asserted on the route cost rather than on the decorator's return value.
	 * <p>
	 * The unambiguous gain is in <em>time</em>: a routed DRT leg carries the constraint ceiling, so
	 * replacing it with an observed factor times the unshared ride puts the traveller at the stop
	 * much earlier, where they can catch departures the ceiling had them missing.
	 */
	@Test
	void anObservedRideFactorPutsTheTravellerAtTheStopEarlier() {
		Harness h = new Harness();

		Routed ceiling = h.routeAtCeilingWithoutRideSkim();
		Routed observed = h.routeWithRideFactor(1.16);

		double expectedRide = 1.16 * DIRECT_RIDE_TIME;
		assertThat(observed.waitingTime)
				.as("arriving %s s earlier turns into that much more slack at the stop",
						CEILING - expectedRide)
				.isCloseTo(ceiling.waitingTime + (CEILING - expectedRide), within(1e-6));
	}

	/**
	 * On access the cost effect is <em>not</em> automatically a saving, and this is the same trap the
	 * wait term set. Cutting the modelled ride does not delete that time, it moves it out of the DRT
	 * vehicle and onto the platform, where the core charges it at {@code mu_wait} instead of at the
	 * mode's {@code mu_travel}. The route cost therefore moves by
	 * {@code delta * (mu_travel - mu_wait)}, which is a saving only where riding is dearer than
	 * waiting. Under MATSim's defaults {@code marginalUtlOfWaitingPt} falls back to the pt mode's
	 * {@code marginalUtilityOfTraveling}, so the sign depends entirely on the scenario's scoring.
	 * <p>
	 * Asserted as an identity rather than as a direction, because the direction is not the point.
	 */
	@Test
	void onAccessTheRideCorrectionTradesInVehicleTimeForPlatformWaitingAtTheirRespectiveRates() {
		Harness h = new Harness();
		double muTravel = h.marginalUtilityOfDrtTravelPerSecond();
		double muWait = h.marginalUtilityOfWaitingPerSecond();

		Routed ceiling = h.routeAtCeilingWithoutRideSkim();
		Routed observed = h.routeWithRideFactor(1.16);

		double delta = 1.16 * DIRECT_RIDE_TIME - CEILING;   // negative: a shorter modelled ride
		assertThat(observed.total() - ceiling.total())
				.isCloseTo(delta * (muTravel - muWait), within(1e-6));
	}

	@Test
	void theCorrectedRideNeverClaimsToBeatTheUnsharedRide() {
		Harness h = new Harness();

		// a factor below 1 is clamped to the unshared ride, so it cannot buy a faster trip
		assertThat(h.routeWithRideFactor(0.1).waitingTime)
				.isCloseTo(h.routeWithRideFactor(1.0).waitingTime, within(1e-9));
	}

	@Test
	void addingWaitToAccessTimeRefundsAnEqualAmountOfPlatformWaiting() {
		Harness h = new Harness();

		Routed noWait = h.routeWithoutSkim();
		Routed withWait = h.route(WAIT, 1.0);

		assertThat(withWait.ptArrivalTime)
				.as("same vehicle, so the comparison isolates the cost effect")
				.isEqualTo(noWait.ptArrivalTime);
		assertThat(withWait.waitingTime)
				.as("the core charges W less platform waiting; it does not charge W more")
				.isCloseTo(noWait.waitingTime - WAIT, within(1e-9));
	}

	/**
	 * The regression proper. At the neutral factor the route cost must be unchanged by a DRT wait
	 * that does not cost a connection: the traveller does the same total waiting, just split
	 * differently. Under the old {@code (factor - 1)} form this came out cheaper by {@code W*mu}.
	 */
	@Test
	void atTheNeutralFactorAWaitThatKeepsTheConnectionLeavesTheRouteCostUnchanged() {
		Harness h = new Harness();

		double baseline = h.routeWithoutSkim().total();
		double neutral = h.route(WAIT, 1.0).total();

		assertThat(neutral).isCloseTo(baseline, within(1e-9));
	}

	/**
	 * And it must never come out <em>cheaper</em>, at any factor a user might configure. This is
	 * the direction of the old defect, stated as an invariant.
	 */
	@Test
	void aWaitNeverMakesTheRouteCheaperAtAnyFactor() {
		Harness h = new Harness();
		double baseline = h.routeWithoutSkim().total();

		for (double factor : new double[] { 1.0, 1.5, 2.0, 3.0 }) {
			assertThat(h.route(WAIT, factor).total())
					.as("route cost at waitingCostFactor %s", factor)
					.isGreaterThanOrEqualTo(baseline - 1e-9);
		}
	}

	@Test
	void aFactorAboveOneMakesTheRouteCostRiseWithTheWait() {
		Harness h = new Harness();
		double mu = h.marginalUtilityOfWaitingPerSecond();
		double factor = 2.0;

		double baseline = h.routeWithoutSkim().total();
		double charged = h.route(WAIT, factor).total();

		// factor*W*mu charged here, W*mu refunded by the core as shortened platform waiting
		assertThat(charged).isCloseTo(baseline + (factor - 1.0) * WAIT * mu, within(1e-9));
		assertThat(charged).isGreaterThan(baseline);
	}

	private record Routed(double accessCost, double waitingTime, double waitingCost, double travelCost,
						  double ptArrivalTime) {
		double total() {
			return accessCost + waitingCost + travelCost;
		}
	}

	private static final class Harness {
		private final RaptorParameters params;
		private final SwissRailRaptorCore core;
		private final TransitStopFacility from;

		Harness() {
			Fixture f = new Fixture();
			f.init();
			this.params = RaptorUtils.createParameters(f.config);
			this.params.setMarginalUtilityOfTravelTime_utl_s(DRT, -0.001);
			SwissRailRaptorData data = SwissRailRaptorData.create(f.schedule, null,
					RaptorUtils.createStaticConfig(f.config), f.network, null);
			this.core = new SwissRailRaptorCore(data, new DefaultRaptorInVehicleCostCalculator(),
					new DefaultRaptorTransferCostCalculator());
			this.from = f.schedule.getFacilities().get(FROM);
		}

		double marginalUtilityOfWaitingPerSecond() {
			return -params.getMarginalUtilityOfWaitingPt_utl_s();
		}

		double marginalUtilityOfDrtTravelPerSecond() {
			return -params.getMarginalUtilityOfTravelTime_utl_s(DRT);
		}

		/** No skim registered for the mode: the decorator is a pass-through, so no wait is added. */
		Routed routeWithoutSkim() {
			return route(new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(),
					Map.of(), 1.0));
		}

		Routed route(double waitTime, double waitingCostFactor) {
			return route(new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(),
					Map.of(DRT, constantSkim(waitTime)), waitingCostFactor));
		}

		/**
		 * A leg carrying the constraint ceiling, as a routed DRT leg really does, with an observed
		 * ride-time factor available for its origin-destination pair.
		 */
		Routed routeWithRideFactor(double factor) {
			DrtRideTimeSkim rideSkim = (from, to, time) ->
					new DrtRideTimeSkim.Lookup(factor, DrtRideTimeSkim.Source.ZONE_PAIR_TIME_BIN);
			return route(new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(),
					Map.of(), Map.of(DRT, rideSkim), 1.0), List.of(drtLegAtCeiling()));
		}

		Routed routeAtCeilingWithoutRideSkim() {
			return route(new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(),
					Map.of(), 1.0), List.of(drtLegAtCeiling()));
		}

		private Routed route(RaptorIntermodalAccessEgress accessEgress) {
			return route(accessEgress, List.of(drtLeg()));
		}

		private Routed route(RaptorIntermodalAccessEgress accessEgress, List<Leg> routeParts) {
			// exactly what DefaultRaptorStopFinder does with the decorator's return value
			RIntermodalAccessEgress ae =
					accessEgress.calcIntermodalAccessEgress(routeParts, params, null, Direction.ACCESS);
			InitialStop stop = new InitialStop(from, ae.disutility, ae.travelTime, ae.routeParts);

			Map<Id<TransitStopFacility>, SwissRailRaptorCore.TravelInfo> tree =
					core.calcLeastCostTree(DEPARTURE_TIME, List.of(stop), params, null);
			SwissRailRaptorCore.TravelInfo ti = tree.get(TO);
			assertThat(ti).as("destination stop %s must be reachable", TO).isNotNull();
			return new Routed(ti.accessCost, ti.waitingTime, ti.waitingCost, ti.travelCost, ti.ptArrivalTime);
		}
	}


	private static DrtWaitTimeSkim constantSkim(double waitTime) {
		return (fromLinkId, time) -> new DrtWaitTimeSkim.Lookup(waitTime, DrtWaitTimeSkim.Source.ZONE_TIME_BIN);
	}

	/** {@code alpha * direct + beta} on the leg, which is what DrtRoute actually puts there. */
	private static Leg drtLegAtCeiling() {
		DrtRoute route = new DrtRoute(ORIGIN, STOP_LINK);
		route.setDirectRideTime(DIRECT_RIDE_TIME);
		route.setTravelTime(CEILING);
		Leg leg = PopulationUtils.createLeg(DRT);
		leg.setRoute(route);
		leg.setTravelTime(CEILING);
		leg.setDepartureTime(DEPARTURE_TIME);
		return leg;
	}

	private static Leg drtLeg() {
		Leg leg = PopulationUtils.createLeg(DRT);
		leg.setRoute(RouteUtils.createGenericRouteImpl(ORIGIN, STOP_LINK));
		leg.setTravelTime(RIDE_TIME);
		leg.setDepartureTime(DEPARTURE_TIME);
		return leg;
	}

}
