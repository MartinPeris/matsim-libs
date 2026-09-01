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

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

/**
 * A queryable, time-dependent estimate of how much longer a DRT ride actually takes than the
 * unshared ride it was quoted against, observed from previous mobsim iterations rather than assumed.
 *
 * <h2>Why a factor and not a duration</h2>
 *
 * Ride time is strongly distance-dependent, so an absolute mean is not something that can be
 * meaningfully aggregated: falling back to "the system-wide mean ride time" would tell a ten-kilometre
 * trip that it takes as long as the average two-kilometre one, which is worse than the estimate it
 * replaced. What this skim records instead is the dimensionless ratio
 *
 * <pre>
 * factor = (droppedOff - pickedUp) / unsharedRideTime
 * </pre>
 *
 * which is comparable across origin-destination pairs of different lengths, so the coarser fallback
 * levels stay meaningful. Both terms come from events the DRT analysis module already collects, and
 * {@code unsharedRideTime} is a pure network property carried on
 * {@link org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent}. Normalising against the
 * <em>constraint</em> instead would bake {@code maxTravelTimeAlpha} and {@code maxTravelTimeBeta}
 * into the recorded number, so changing those would silently invalidate the skim.
 *
 * <h2>What it is for</h2>
 *
 * A routed DRT leg does not carry an expected duration. {@code DrtRoute.setConstraints} calls
 * {@code setTravelTime(constraints.maxTravelDuration())}, and {@code DefaultMainLegRouter} copies
 * that onto the leg, so routing and scoring see {@code alpha * unshared + beta} — the ceiling above
 * which the operator would reject the request, not an expectation of it. For a short intermodal
 * feeder leg that ceiling is dominated by {@code beta}: at the defaults used in this package's
 * integration test a 203-second unshared ride reaches routing as 904.5 seconds. Applying an observed
 * factor to the leg's own {@code directRideTime} replaces that ceiling with a measurement.
 *
 * <h2>Bounding</h2>
 *
 * Keying on zone <em>pairs</em> is what made the earlier stop-keyed attempt in this lineage
 * intractable: an all-pairs table over a link-derived stop set is O(n²) in stops. Implementations
 * here must store observed pairs sparsely, so the table grows with the origin-destination pairs a
 * scenario actually uses rather than with the square of the zone count.
 *
 * @author Monash Healthy Active Cities
 */
public interface DrtRideTimeSkim {

	/**
	 * Which aggregate a returned factor came from, coarsening left to right.
	 */
	enum Source {
		/** This origin-destination zone pair and time bin were observed in the iteration just finished. */
		ZONE_PAIR_TIME_BIN,
		/**
		 * This pair and bin carry a value, but it was last observed in an earlier iteration and has
		 * only been carried forward since. Still measurement, but stale, and arbitrarily so.
		 */
		ZONE_PAIR_TIME_BIN_CARRIED,
		/** The pair was observed, but not in this bin; the pair's all-day mean was used. */
		ZONE_PAIR_MEAN,
		/** The pair was never observed; the system-wide mean for this bin was used. */
		GLOBAL_TIME_BIN,
		/** Neither pair nor bin was observed; the system-wide all-day mean was used. */
		GLOBAL_MEAN,
		/**
		 * Nothing has been observed at all. Unlike the wait skim there is no configured constant to
		 * fall back to, because the caller already holds a better number than any this class could
		 * invent — the leg's own routed estimate. A lookup reporting this must be left alone.
		 */
		DEFAULT
	}

	/**
	 * @param factor observed ride time as a multiple of the unshared ride time. {@link Double#NaN}
	 *               when {@code source} is {@link Source#DEFAULT}, so that a caller which ignores
	 *               the source cannot silently scale by a number that was never measured.
	 */
	record Lookup(double factor, Source source) {

		public boolean isMeasured() {
			return source != Source.DEFAULT;
		}
	}

	/**
	 * @param fromLinkId link the ride starts on; resolved to a zone internally
	 * @param toLinkId   link the ride ends on; resolved to a zone internally
	 * @param time       departure time in seconds, or {@link Double#NaN} when the caller does not
	 *                   know it, in which case the time-binned levels are skipped rather than
	 *                   silently answered from bin zero
	 */
	Lookup lookup(Id<Link> fromLinkId, Id<Link> toLinkId, double time);
}
