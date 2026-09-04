package org.matsim.contrib.drt.extension.skims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.estimator.DrtEstimator;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.utils.misc.OptionalTime;

/**
 * @author Monash Healthy Active Cities
 */
class SkimBackedDrtEstimatorTest {

	private static final Id<Link> FROM = Id.createLinkId("from");
	private static final Id<Link> TO = Id.createLinkId("to");
	private static final double DIRECT = 200;
	private static final double ALPHA = 1.5;
	private static final double BETA = 600;
	private static final OptionalTime AT_EIGHT = OptionalTime.defined(8 * 3600);

	@Test
	void aMeasuredPairIsEstimatedFromTheObservedFactorNotTheCeiling() {
		DrtEstimator estimator = SkimBackedDrtEstimator.create(null, constantFactor(1.16), ALPHA, BETA);

		DrtEstimator.Estimate estimate = estimator.estimate(route(), AT_EIGHT);

		assertThat(estimate.rideTime()).isCloseTo(1.16 * DIRECT, within(1e-9));
		assertThat(estimate.rideTime()).isLessThan(ALPHA * DIRECT + BETA);
	}

	/** An unobserved pair is estimated exactly as a routed leg is today, so nothing changes until measured. */
	@Test
	void anUnmeasuredPairFallsBackToTheScenariosOwnCeiling() {
		DrtEstimator estimator = SkimBackedDrtEstimator.create(null, unmeasured(), ALPHA, BETA);

		assertThat(estimator.estimate(route(), AT_EIGHT).rideTime()).isCloseTo(ALPHA * DIRECT + BETA, within(1e-9));
	}

	@Test
	void withoutARideSkimEveryPairUsesTheCeiling() {
		DrtEstimator estimator = SkimBackedDrtEstimator.create(null, null, ALPHA, BETA);

		assertThat(estimator.estimate(route(), AT_EIGHT).rideTime()).isCloseTo(ALPHA * DIRECT + BETA, within(1e-9));
	}

	@Test
	void theEstimatedRideNeverBeatsTheUnsharedRide() {
		DrtEstimator estimator = SkimBackedDrtEstimator.create(null, constantFactor(0.1), ALPHA, BETA);

		assertThat(estimator.estimate(route(), AT_EIGHT).rideTime()).isCloseTo(DIRECT, within(1e-9));
	}

	@Test
	void theWaitComesFromTheWaitSkimAtTheOriginAndDepartureTime() {
		RecordingWaitSkim waitSkim = new RecordingWaitSkim(420);
		DrtEstimator estimator = SkimBackedDrtEstimator.create(waitSkim, null, ALPHA, BETA);

		DrtEstimator.Estimate estimate = estimator.estimate(route(), AT_EIGHT);

		assertThat(estimate.waitingTime()).isEqualTo(420);
		assertThat(waitSkim.lastLinkId).isEqualTo(FROM);
		assertThat(waitSkim.lastTime).isEqualTo(8 * 3600);
	}

	@Test
	void anUndefinedDepartureTimeIsPassedOnAsUnknownNotAsMidnight() {
		RecordingWaitSkim waitSkim = new RecordingWaitSkim(1);
		SkimBackedDrtEstimator.create(waitSkim, null, ALPHA, BETA).estimate(route(), OptionalTime.undefined());

		assertThat(waitSkim.lastTime).isNaN();
	}

	private static DrtRoute route() {
		DrtRoute route = new DrtRoute(FROM, TO);
		route.setDirectRideTime(DIRECT);
		route.setDistance(1000);
		return route;
	}

	private static DrtRideTimeSkim constantFactor(double factor) {
		return (from, to, time) -> new DrtRideTimeSkim.Lookup(factor, DrtRideTimeSkim.Source.ZONE_PAIR_TIME_BIN);
	}

	private static DrtRideTimeSkim unmeasured() {
		return (from, to, time) -> new DrtRideTimeSkim.Lookup(Double.NaN, DrtRideTimeSkim.Source.DEFAULT);
	}

	private static final class RecordingWaitSkim implements DrtWaitTimeSkim {
		private final double waitTime;
		private Id<Link> lastLinkId;
		private double lastTime;

		RecordingWaitSkim(double waitTime) {
			this.waitTime = waitTime;
		}

		@Override
		public Lookup lookup(Id<Link> fromLinkId, double time) {
			this.lastLinkId = fromLinkId;
			this.lastTime = time;
			return new Lookup(waitTime, Source.ZONE_TIME_BIN);
		}
	}
}
