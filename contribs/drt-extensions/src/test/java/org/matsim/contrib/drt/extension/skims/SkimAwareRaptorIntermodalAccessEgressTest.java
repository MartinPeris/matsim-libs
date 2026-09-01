package org.matsim.contrib.drt.extension.skims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.population.PopulationUtils;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.population.routes.RouteUtils;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress.RIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder.Direction;

/**
 * @author Monash Healthy Active Cities
 */
class SkimAwareRaptorIntermodalAccessEgressTest {

	private static final String DRT = "drt";
	private static final double MARGINAL_UTILITY_OF_WAITING_UTL_S = -0.002;
	private static final Id<Link> ORIGIN = Id.createLinkId("origin");
	private static final Id<Link> STOP = Id.createLinkId("stop");
	private static final double DEPARTURE_TIME = 8 * 3600;
	private static final double WAIT = 600;
	private static final double MARGINAL_UTILITY_OF_TRAVELLING_UTL_S = -0.001;
	private static final double DIRECT_RIDE_TIME = 200;
	/** 1.5 * 200 + 600, the shape DrtRoute gets from the default constraints. */
	private static final double CEILING = 900;

	@Test
	void theWaitLengthensTheLegWhicheverDirectionItIs() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();
		RIntermodalAccessEgress baseline = baseline(legs, params, Direction.ACCESS);

		for (Direction direction : Direction.values()) {
			RIntermodalAccessEgress result = subject(1.0).calcIntermodalAccessEgress(legs, params, null, direction);
			assertThat(result.travelTime).as("travel time for %s", direction)
					.isEqualTo(baseline.travelTime + WAIT);
		}
	}

	/**
	 * The full charge applies on access too. SwissRailRaptorCore does not charge the DRT wait on
	 * access — arriving later <em>shortens</em> the platform wait, refunding an equal amount — so
	 * charging only the excess here would leave a longer wait looking cheaper. The neutrality of
	 * factor 1.0 on access emerges from that refund cancelling this charge inside the core, not
	 * from this class declining to charge; see
	 * {@code ch.sbb.matsim.routing.pt.raptor.SkimAwareIntermodalAccessEgressRaptorIT}, which
	 * asserts it on the route cost the core actually produces.
	 */
	@Test
	void atANeutralFactorAnAccessWaitIsChargedInFullBecauseTheCoreRefundsPlatformWaiting() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		RIntermodalAccessEgress result = subject(1.0)
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);

		double expected = baseline(legs, params, Direction.ACCESS).disutility
				+ WAIT * -MARGINAL_UTILITY_OF_WAITING_UTL_S;
		assertThat(result.disutility).isCloseTo(expected, within(1e-9));
	}

	/**
	 * On egress the core adds the access time to the arrival time and the access cost to the total
	 * with no waiting term at all, so the full cost is ours to charge.
	 */
	@Test
	void atANeutralFactorAnEgressWaitIsChargedInFull() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		RIntermodalAccessEgress result = subject(1.0)
				.calcIntermodalAccessEgress(legs, params, null, Direction.EGRESS);

		double expected = baseline(legs, params, Direction.EGRESS).disutility
				+ WAIT * -MARGINAL_UTILITY_OF_WAITING_UTL_S;
		assertThat(result.disutility).isCloseTo(expected, within(1e-9));
	}

	/**
	 * The charge does not depend on direction: the same factor is applied at both ends. What
	 * differs is only what the core does with it afterwards.
	 */
	@Test
	void aFactorAboveOneChargesTheFullFactorInBothDirections() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		for (Direction direction : Direction.values()) {
			RIntermodalAccessEgress result = subject(2.5)
					.calcIntermodalAccessEgress(legs, params, null, direction);

			double expected = baseline(legs, params, direction).disutility
					+ WAIT * 2.5 * -MARGINAL_UTILITY_OF_WAITING_UTL_S;
			assertThat(result.disutility).as("disutility for %s", direction)
					.isCloseTo(expected, within(1e-9));
		}
	}

	/**
	 * Holds even at the neutral factor. This is what the earlier {@code (factor - 1)} form got
	 * wrong: at 1.0 it returned the same disutility whatever the wait, so the core's platform-wait
	 * refund was left uncancelled and a longer wait came out strictly cheaper.
	 */
	@Test
	void aLongerWaitCostsMoreThanAShorterOneAtEveryFactor() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		for (double factor : new double[] { 1.0, 2.0 }) {
			double prompt = withSkim(new ConstantSkim(60), factor)
					.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS).disutility;
			double laggard = withSkim(new ConstantSkim(1200), factor)
					.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS).disutility;

			assertThat(laggard).as("disutility at factor %s", factor).isGreaterThan(prompt);
		}
	}

	@Test
	void legsOfModesWithoutASkimAreLeftAlone() {
		Leg walkLeg = PopulationUtils.createLeg(TransportMode.walk);
		walkLeg.setRoute(RouteUtils.createGenericRouteImpl(ORIGIN, STOP));
		walkLeg.setTravelTime(180);
		walkLeg.setDepartureTime(DEPARTURE_TIME);
		List<Leg> legs = List.of(walkLeg);
		RaptorParameters params = params();

		RIntermodalAccessEgress baseline = baseline(legs, params, Direction.ACCESS);
		RIntermodalAccessEgress result = subject(3.0)
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);

		assertThat(result.travelTime).isEqualTo(baseline.travelTime);
		assertThat(result.disutility).isEqualTo(baseline.disutility);
	}

	@Test
	void theSkimIsQueriedAtTheLegsOwnOriginAndDepartureTime() {
		RecordingSkim skim = new RecordingSkim();
		new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(), Map.of(DRT, skim), 1.0)
				.calcIntermodalAccessEgress(List.of(drtLeg()), params(), null, Direction.ACCESS);

		assertThat(skim.lastLinkId).isEqualTo(ORIGIN);
		assertThat(skim.lastTime).isEqualTo(DEPARTURE_TIME);
	}

	@Test
	void anUndefinedDepartureTimeIsReportedAsUnknownRatherThanAsMidnight() {
		Leg leg = drtLeg();
		leg.setDepartureTimeUndefined();

		RecordingSkim skim = new RecordingSkim();
		new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(), Map.of(DRT, skim), 1.0)
				.calcIntermodalAccessEgress(List.of(leg), params(), null, Direction.ACCESS);

		// zero would quietly resolve to the first time bin, which is a different claim entirely
		assertThat(skim.lastTime).isNaN();
	}

	// --- observed ride time ---------------------------------------------------------------------

	/**
	 * A routed DRT leg carries {@code maxTravelDuration}, not an expected duration. An observed
	 * factor applied to the route's own {@code directRideTime} replaces that ceiling.
	 */
	@Test
	void anObservedFactorReplacesTheLegsConstraintCeilingWithAMeasurement() {
		Leg leg = drtLegWithRoute(DIRECT_RIDE_TIME, CEILING);
		RaptorParameters params = params();

		RIntermodalAccessEgress result = withRideSkim(constantFactor(1.4))
				.calcIntermodalAccessEgress(List.of(leg), params, null, Direction.ACCESS);

		double expectedRide = 1.4 * DIRECT_RIDE_TIME;   // 280, well inside [200, 900]
		double delta = expectedRide - CEILING;
		assertThat(result.travelTime).isCloseTo(CEILING + delta, within(1e-9));
		assertThat(result.disutility).isCloseTo(
				baseline(List.of(leg), params, Direction.ACCESS).disutility
						+ delta * -MARGINAL_UTILITY_OF_TRAVELLING_UTL_S, within(1e-9));
		// the whole point: routing now sees far less than the ceiling
		assertThat(result.travelTime).isLessThan(CEILING);
	}

	/**
	 * With nothing observed the leg must be left exactly as it was, so installing the ride skim
	 * changes nothing until it has something to say.
	 */
	@Test
	void anUnmeasuredRideLookupLeavesTheLegAlone() {
		Leg leg = drtLegWithRoute(DIRECT_RIDE_TIME, CEILING);
		RaptorParameters params = params();

		RIntermodalAccessEgress result = withRideSkim(
				(from, to, time) -> new DrtRideTimeSkim.Lookup(Double.NaN, DrtRideTimeSkim.Source.DEFAULT))
				.calcIntermodalAccessEgress(List.of(leg), params, null, Direction.ACCESS);

		RIntermodalAccessEgress baseline = baseline(List.of(leg), params, Direction.ACCESS);
		assertThat(result.travelTime).isEqualTo(baseline.travelTime);
		assertThat(result.disutility).isEqualTo(baseline.disutility);
	}

	/**
	 * A factor drawn from a coarse aggregate can imply a ride shorter than the unshared one, which
	 * is physically impossible, or longer than the ceiling, which the operator would have rejected.
	 */
	@Test
	void theCorrectedRideIsClampedBetweenTheUnsharedRideAndTheCeiling() {
		RaptorParameters params = params();

		RIntermodalAccessEgress tooFast = withRideSkim(constantFactor(0.1))
				.calcIntermodalAccessEgress(List.of(drtLegWithRoute(DIRECT_RIDE_TIME, CEILING)), params, null,
						Direction.ACCESS);
		assertThat(tooFast.travelTime).as("never faster than the unshared ride")
				.isCloseTo(DIRECT_RIDE_TIME, within(1e-9));

		RIntermodalAccessEgress tooSlow = withRideSkim(constantFactor(99))
				.calcIntermodalAccessEgress(List.of(drtLegWithRoute(DIRECT_RIDE_TIME, CEILING)), params, null,
						Direction.ACCESS);
		assertThat(tooSlow.travelTime).as("never beyond the constraint ceiling")
				.isCloseTo(CEILING, within(1e-9));
	}

	@Test
	void aLegWhoseRouteCarriesNoDirectRideTimeIsLeftAlone() {
		// a plain generic route has no directRideTime to scale, so there is nothing to correct
		Leg leg = drtLeg();
		RaptorParameters params = params();

		RIntermodalAccessEgress result = withRideSkim(constantFactor(1.4))
				.calcIntermodalAccessEgress(List.of(leg), params, null, Direction.ACCESS);

		assertThat(result.travelTime).isEqualTo(baseline(List.of(leg), params, Direction.ACCESS).travelTime);
	}

	private static RaptorIntermodalAccessEgress withRideSkim(DrtRideTimeSkim rideSkim) {
		return new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(), Map.of(),
				Map.of(DRT, rideSkim), 1.0);
	}

	private static DrtRideTimeSkim constantFactor(double factor) {
		return (from, to, time) -> new DrtRideTimeSkim.Lookup(factor, DrtRideTimeSkim.Source.ZONE_PAIR_TIME_BIN);
	}

	private static Leg drtLegWithRoute(double directRideTime, double ceiling) {
		DrtRoute route = new DrtRoute(ORIGIN, STOP);
		route.setDirectRideTime(directRideTime);
		route.setTravelTime(ceiling);
		Leg leg = PopulationUtils.createLeg(DRT);
		leg.setRoute(route);
		leg.setTravelTime(ceiling);
		leg.setDepartureTime(DEPARTURE_TIME);
		return leg;
	}

	private static RIntermodalAccessEgress baseline(List<Leg> legs, RaptorParameters params, Direction direction) {
		return new DefaultRaptorIntermodalAccessEgress().calcIntermodalAccessEgress(legs, params, null, direction);
	}

	private static RaptorIntermodalAccessEgress subject(double waitingCostFactor) {
		return withSkim(new ConstantSkim(WAIT), waitingCostFactor);
	}

	private static RaptorIntermodalAccessEgress withSkim(DrtWaitTimeSkim skim, double waitingCostFactor) {
		return new SkimAwareRaptorIntermodalAccessEgress(new DefaultRaptorIntermodalAccessEgress(),
				Map.of(DRT, skim), waitingCostFactor);
	}


	private static Leg drtLeg() {
		Leg leg = PopulationUtils.createLeg(DRT);
		leg.setRoute(RouteUtils.createGenericRouteImpl(ORIGIN, STOP));
		leg.setTravelTime(300);
		leg.setDepartureTime(DEPARTURE_TIME);
		return leg;
	}

	private static RaptorParameters params() {
		RaptorParameters params = new RaptorParameters(new SwissRailRaptorConfigGroup());
		params.setMarginalUtilityOfTravelTime_utl_s(DRT, MARGINAL_UTILITY_OF_TRAVELLING_UTL_S);
		params.setMarginalUtilityOfTravelTime_utl_s(TransportMode.walk, -0.001);
		params.setMarginalUtilityOfWaitingPt_utl_s(MARGINAL_UTILITY_OF_WAITING_UTL_S);
		return params;
	}

	private record ConstantSkim(double waitTime) implements DrtWaitTimeSkim {
		@Override
		public Lookup lookup(Id<Link> fromLinkId, double time) {
			return new Lookup(waitTime, Source.ZONE_TIME_BIN);
		}
	}

	private static final class RecordingSkim implements DrtWaitTimeSkim {
		private Id<Link> lastLinkId;
		private double lastTime;

		@Override
		public Lookup lookup(Id<Link> fromLinkId, double time) {
			this.lastLinkId = fromLinkId;
			this.lastTime = time;
			return new Lookup(WAIT, Source.ZONE_TIME_BIN);
		}
	}
}
