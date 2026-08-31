package org.matsim.contrib.drt.extension.waittime;

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
class WaitAwareRaptorIntermodalAccessEgressTest {

	private static final String DRT = "drt";
	private static final double MARGINAL_UTILITY_OF_WAITING_UTL_S = -0.002;
	private static final Id<Link> ORIGIN = Id.createLinkId("origin");
	private static final Id<Link> STOP = Id.createLinkId("stop");
	private static final double DEPARTURE_TIME = 8 * 3600;
	private static final double WAIT = 600;

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
	 * SwissRailRaptorCore charges an access-side wait once already, by shortening the slack the
	 * traveller would otherwise have spent waiting at the stop. At a neutral factor there is
	 * therefore nothing left for this class to add, and adding it anyway would double-count.
	 */
	@Test
	void atANeutralFactorAnAccessWaitAddsNoCostBecauseRaptorAlreadyChargesIt() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		RIntermodalAccessEgress result = subject(1.0)
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);

		assertThat(result.disutility).isCloseTo(baseline(legs, params, Direction.ACCESS).disutility, within(1e-9));
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

	@Test
	void aFactorAboveOneChargesOnlyTheExcessOnAccess() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		RIntermodalAccessEgress result = subject(2.5)
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);

		// 2.5 times as onerous as stop waiting, of which Raptor already charges 1.0
		double expected = baseline(legs, params, Direction.ACCESS).disutility
				+ WAIT * 1.5 * -MARGINAL_UTILITY_OF_WAITING_UTL_S;
		assertThat(result.disutility).isCloseTo(expected, within(1e-9));
	}

	@Test
	void aLongerWaitCostsMoreThanAShorterOneOnceWaitingIsPricedAsWorseThanStopWaiting() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		double prompt = withSkim(new ConstantSkim(60), 2.0)
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS).disutility;
		double laggard = withSkim(new ConstantSkim(1200), 2.0)
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS).disutility;

		assertThat(laggard).isGreaterThan(prompt);
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
		new WaitAwareRaptorIntermodalAccessEgress(Map.of(DRT, skim), factor(1.0))
				.calcIntermodalAccessEgress(List.of(drtLeg()), params(), null, Direction.ACCESS);

		assertThat(skim.lastLinkId).isEqualTo(ORIGIN);
		assertThat(skim.lastTime).isEqualTo(DEPARTURE_TIME);
	}

	@Test
	void anUndefinedDepartureTimeIsReportedAsUnknownRatherThanAsMidnight() {
		Leg leg = drtLeg();
		leg.setDepartureTimeUndefined();

		RecordingSkim skim = new RecordingSkim();
		new WaitAwareRaptorIntermodalAccessEgress(Map.of(DRT, skim), factor(1.0))
				.calcIntermodalAccessEgress(List.of(leg), params(), null, Direction.ACCESS);

		// zero would quietly resolve to the first time bin, which is a different claim entirely
		assertThat(skim.lastTime).isNaN();
	}

	private static RIntermodalAccessEgress baseline(List<Leg> legs, RaptorParameters params, Direction direction) {
		return new DefaultRaptorIntermodalAccessEgress().calcIntermodalAccessEgress(legs, params, null, direction);
	}

	private static RaptorIntermodalAccessEgress subject(double waitingCostFactor) {
		return withSkim(new ConstantSkim(WAIT), waitingCostFactor);
	}

	private static RaptorIntermodalAccessEgress withSkim(DrtWaitTimeSkim skim, double waitingCostFactor) {
		return new WaitAwareRaptorIntermodalAccessEgress(Map.of(DRT, skim), factor(waitingCostFactor));
	}

	private static WaitAwareRaptorIntermodalAccessEgress.WaitingCostFactor factor(double value) {
		return new WaitAwareRaptorIntermodalAccessEgress.WaitingCostFactor(value);
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
		params.setMarginalUtilityOfTravelTime_utl_s(DRT, -0.001);
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
