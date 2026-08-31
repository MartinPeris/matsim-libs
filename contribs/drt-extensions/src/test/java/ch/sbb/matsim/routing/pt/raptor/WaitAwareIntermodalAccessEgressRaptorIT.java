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
import org.matsim.contrib.drt.extension.waittime.DrtWaitTimeSkim;
import org.matsim.contrib.drt.extension.waittime.WaitAwareRaptorIntermodalAccessEgress;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress.RIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder.Direction;

/**
 * Drives {@link WaitAwareRaptorIntermodalAccessEgress} through {@link SwissRailRaptorCore} and
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
public class WaitAwareIntermodalAccessEgressRaptorIT {

	/** Chosen so the baseline platform wait (960 s) comfortably exceeds WAIT: same vehicle either way. */
	private static final double DEPARTURE_TIME = 6.0 * 3600 - 900;
	private static final double RIDE_TIME = 300;
	private static final double WAIT = 600;

	private static final String DRT = "drt";
	private static final Id<Link> ORIGIN = Id.createLinkId("origin");
	private static final Id<Link> STOP_LINK = Id.createLinkId("stop");
	private static final Id<TransitStopFacility> FROM = Id.create("0", TransitStopFacility.class);
	private static final Id<TransitStopFacility> TO = Id.create("16", TransitStopFacility.class);

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

		/** No skim registered for the mode: the decorator is a pass-through, so no wait is added. */
		Routed routeWithoutSkim() {
			return route(new WaitAwareRaptorIntermodalAccessEgress(Map.of(), factor(1.0)));
		}

		Routed route(double waitTime, double waitingCostFactor) {
			return route(new WaitAwareRaptorIntermodalAccessEgress(
					Map.of(DRT, constantSkim(waitTime)), factor(waitingCostFactor)));
		}

		private Routed route(RaptorIntermodalAccessEgress accessEgress) {
			List<Leg> routeParts = List.of(drtLeg());
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

	private static WaitAwareRaptorIntermodalAccessEgress.WaitingCostFactor factor(double value) {
		return new WaitAwareRaptorIntermodalAccessEgress.WaitingCostFactor(value);
	}

	private static DrtWaitTimeSkim constantSkim(double waitTime) {
		return (fromLinkId, time) -> new DrtWaitTimeSkim.Lookup(waitTime, DrtWaitTimeSkim.Source.ZONE_TIME_BIN);
	}

	private static Leg drtLeg() {
		Leg leg = PopulationUtils.createLeg(DRT);
		leg.setRoute(RouteUtils.createGenericRouteImpl(ORIGIN, STOP_LINK));
		leg.setTravelTime(RIDE_TIME);
		leg.setDepartureTime(DEPARTURE_TIME);
		return leg;
	}

}
