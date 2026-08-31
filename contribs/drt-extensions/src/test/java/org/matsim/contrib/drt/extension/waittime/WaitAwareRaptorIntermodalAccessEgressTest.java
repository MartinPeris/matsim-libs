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

	@Test
	void waitingIsAddedToBothTravelTimeAndDisutility() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		RIntermodalAccessEgress baseline = new DefaultRaptorIntermodalAccessEgress() //
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);
		RIntermodalAccessEgress withWait = subject(new ConstantSkim(240)) //
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);

		assertThat(withWait.travelTime).isEqualTo(baseline.travelTime + 240);
		assertThat(withWait.disutility) //
				.isCloseTo(baseline.disutility + 240 * -MARGINAL_UTILITY_OF_WAITING_UTL_S, within(1e-9));
	}

	@Test
	void aFeederWithALongWaitScoresWorseThanAnOtherwiseIdenticalOneWithout() {
		List<Leg> legs = List.of(drtLeg());
		RaptorParameters params = params();

		double promptDisutility = subject(new ConstantSkim(60)) //
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS).disutility;
		double laggardDisutility = subject(new ConstantSkim(1200)) //
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS).disutility;

		assertThat(laggardDisutility).isGreaterThan(promptDisutility);
	}

	@Test
	void legsOfModesWithoutASkimAreLeftAlone() {
		Leg walkLeg = PopulationUtils.createLeg(TransportMode.walk);
		walkLeg.setRoute(RouteUtils.createGenericRouteImpl(ORIGIN, STOP));
		walkLeg.setTravelTime(180);
		walkLeg.setDepartureTime(DEPARTURE_TIME);
		List<Leg> legs = List.of(walkLeg);
		RaptorParameters params = params();

		RIntermodalAccessEgress baseline = new DefaultRaptorIntermodalAccessEgress() //
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);
		RIntermodalAccessEgress result = subject(new ConstantSkim(600)) //
				.calcIntermodalAccessEgress(legs, params, null, Direction.ACCESS);

		assertThat(result.travelTime).isEqualTo(baseline.travelTime);
		assertThat(result.disutility).isEqualTo(baseline.disutility);
	}

	@Test
	void theSkimIsQueriedAtTheLegsOwnOriginAndDepartureTime() {
		RecordingSkim skim = new RecordingSkim();
		subject(skim).calcIntermodalAccessEgress(List.of(drtLeg()), params(), null, Direction.ACCESS);

		assertThat(skim.lastLinkId).isEqualTo(ORIGIN);
		assertThat(skim.lastTime).isEqualTo(DEPARTURE_TIME);
	}

	@Test
	void anUndefinedDepartureTimeIsReportedAsUnknownRatherThanAsMidnight() {
		Leg leg = drtLeg();
		leg.setDepartureTimeUndefined();

		RecordingSkim skim = new RecordingSkim();
		subject(skim).calcIntermodalAccessEgress(List.of(leg), params(), null, Direction.ACCESS);

		// zero would quietly resolve to the first time bin, which is a different claim entirely
		assertThat(skim.lastTime).isNaN();
	}

	private static RaptorIntermodalAccessEgress subject(DrtWaitTimeSkim skim) {
		return new WaitAwareRaptorIntermodalAccessEgress(Map.of(DRT, skim));
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
			return new Lookup(120, Source.ZONE_TIME_BIN);
		}
	}
}
