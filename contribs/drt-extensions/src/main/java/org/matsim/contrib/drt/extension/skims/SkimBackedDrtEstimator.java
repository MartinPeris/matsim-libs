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

package org.matsim.contrib.drt.extension.skims;

import jakarta.annotation.Nullable;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.drt.estimator.DrtEstimator;
import org.matsim.contrib.drt.estimator.impl.DirectTripBasedDrtEstimator;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.ConstantRideDurationEstimator;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.RideDurationEstimator;
import org.matsim.contrib.drt.estimator.impl.waiting_time_estimation.WaitingTimeEstimator;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.utils.misc.OptionalTime;

/**
 * A {@link DrtEstimator} that answers from the observed skims, for the <em>direct</em> DRT path.
 * <p>
 * {@link SkimAwareRaptorIntermodalAccessEgress} corrects DRT legs only where they are an
 * intermodal access or egress to transit. A trip made entirely by DRT never passes through
 * SwissRailRaptor, so until now nothing on that path knew which origin-destination pairs the
 * fleet serves well. This class is the second consumer of the same two skims. It plugs into the
 * existing estimator architecture rather than beside it:
 * <ul>
 * <li>{@code MultiModalDrtLegEstimator} (informed mode choice) scores a DRT alternative from a
 * {@code DrtEstimator}, so with this bound, mode choice sees an OD-specific expected ride and wait
 * instead of the constraint ceiling and a constant.</li>
 * <li>{@code EstimationRoutingModule} ({@code estimateAndTeleport}) teleports the leg on the same
 * estimate.</li>
 * <li>{@code DrtEstimateAnalyzer} writes {@code drt_estimates_<mode>.csv}, the error of these
 * estimates against what the mobsim then did, per iteration. That is the convergence check for
 * free.</li>
 * </ul>
 * Composition is via {@link DirectTripBasedDrtEstimator}, whose {@link RideDurationEstimator} and
 * {@link WaitingTimeEstimator} already have exactly the skims' shape.
 *
 * <h2>Fallbacks</h2>
 *
 * Where the ride skim has nothing measured for a pair, the ride falls back to the scenario's own
 * {@code maxTravelTimeAlpha * direct + maxTravelTimeBeta}, which is what a routed leg carries
 * today, so an unobserved pair is estimated exactly as before. The wait skim always answers, and
 * reports {@link DrtWaitTimeSkim.Source#DEFAULT} when it is only repeating its configured constant.
 * <p>
 * The rejection rate is left at the builder's default of zero. The wait skim counts rejections but
 * does not yet publish a damped rate; folding one in is the open item noted in the README, and the
 * informed-mode-choice consumer does not read it in any case.
 *
 * @author Monash Healthy Active Cities
 */
public final class SkimBackedDrtEstimator implements DrtEstimator {

	private final DrtEstimator delegate;

	private SkimBackedDrtEstimator(DrtEstimator delegate) {
		this.delegate = delegate;
	}

	/**
	 * @param waitSkim      may be null when no wait skim is configured for the mode; the builder's
	 *                      constant waiting time then applies
	 * @param rideSkim      may be null when no ride skim is configured for the mode; the fallback
	 *                      ceiling then applies to every pair
	 * @param fallbackAlpha the scenario's {@code maxTravelTimeAlpha}
	 * @param fallbackBeta  the scenario's {@code maxTravelTimeBeta}
	 */
	public static SkimBackedDrtEstimator create(@Nullable DrtWaitTimeSkim waitSkim, @Nullable DrtRideTimeSkim rideSkim,
			double fallbackAlpha, double fallbackBeta) {
		DirectTripBasedDrtEstimator.Builder builder = new DirectTripBasedDrtEstimator.Builder()
				.setRideDurationEstimator(new SkimRideDuration(rideSkim,
						new ConstantRideDurationEstimator(fallbackAlpha, fallbackBeta)));
		if (waitSkim != null) {
			builder.setWaitingTimeEstimator(new SkimWaitingTime(waitSkim));
		}
		return new SkimBackedDrtEstimator(builder.build());
	}

	@Override
	public Estimate estimate(DrtRoute route, OptionalTime departureTime) {
		return delegate.estimate(route, departureTime);
	}

	private static double seconds(OptionalTime time) {
		return time.isDefined() ? time.seconds() : Double.NaN;
	}

	/**
	 * Observed factor times the unshared ride, never below the unshared ride itself; the fallback
	 * where nothing was measured for the pair.
	 */
	static final class SkimRideDuration implements RideDurationEstimator {
		@Nullable
		private final DrtRideTimeSkim skim;
		private final RideDurationEstimator fallback;

		SkimRideDuration(@Nullable DrtRideTimeSkim skim, RideDurationEstimator fallback) {
			this.skim = skim;
			this.fallback = fallback;
		}

		@Override
		public double getEstimatedRideDuration(Id<Link> fromLinkId, Id<Link> toLinkId, OptionalTime departureTime,
				double directTripDuration) {
			if (skim != null && directTripDuration > 0) {
				DrtRideTimeSkim.Lookup lookup = skim.lookup(fromLinkId, toLinkId, seconds(departureTime));
				if (lookup.isMeasured()) {
					return Math.max(lookup.factor() * directTripDuration, directTripDuration);
				}
			}
			return fallback.getEstimatedRideDuration(fromLinkId, toLinkId, departureTime, directTripDuration);
		}
	}

	static final class SkimWaitingTime implements WaitingTimeEstimator {
		private final DrtWaitTimeSkim skim;

		SkimWaitingTime(DrtWaitTimeSkim skim) {
			this.skim = skim;
		}

		@Override
		public double estimateWaitTime(Id<Link> fromLinkId, Id<Link> toLinkId, OptionalTime departureTime) {
			return skim.getWaitTime(fromLinkId, seconds(departureTime));
		}
	}
}
