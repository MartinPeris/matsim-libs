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
 * A queryable, time-dependent estimate of how long a passenger waits for a DRT vehicle after
 * becoming ready to depart, observed from the previous mobsim iterations rather than assumed.
 * <p>
 * The estimate is keyed on a zone (resolved from the origin link) and a time bin, so that the
 * table stays bounded regardless of the DRT
 * {@link org.matsim.contrib.drt.run.DrtConfigGroup.OperationalScheme} in use: a stop-keyed table
 * degenerates badly under {@code serviceAreaBased}, where every link inside the service area is a
 * stop, and is undefined under {@code door2door}, where there are no stops at all.
 * <p>
 * A lookup always returns a number. Where the requested zone and time bin were never observed, the
 * implementation falls back through progressively coarser aggregates and reports which one it used
 * via {@link Lookup#source()}, so that a value resting on a configured constant is distinguishable
 * from one resting on measurement. Callers that care should log or assert on the source rather than
 * trusting the number blindly.
 *
 * @author Monash Healthy Active Cities
 */
public interface DrtWaitTimeSkim {

	/**
	 * Which aggregate a returned wait time actually came from, coarsening left to right.
	 */
	enum Source {
		/** The requested zone and time bin were observed in the iteration just finished. */
		ZONE_TIME_BIN,
		/**
		 * The requested zone and time bin carry a value, but it was last observed in an earlier
		 * iteration and has only been carried forward since. Still measurement, but stale, and
		 * arbitrarily so: values do not expire.
		 */
		ZONE_TIME_BIN_CARRIED,
		/** The zone was observed, but not in this time bin; the zone's all-day mean was used. */
		ZONE_MEAN,
		/** The zone was never observed; the system-wide mean for this time bin was used. */
		GLOBAL_TIME_BIN,
		/** Nothing was observed for this zone or time bin; the system-wide all-day mean was used. */
		GLOBAL_MEAN,
		/** Nothing was observed at all; the configured default was used. */
		DEFAULT
	}

	/**
	 * A wait time together with the aggregation level it was drawn from.
	 */
	record Lookup(double waitTime, Source source) {
	}

	/**
	 * @param fromLinkId the link the passenger departs from; resolved to a zone internally
	 * @param time       departure time in seconds, or {@link Double#NaN} when the caller does not
	 *                   know it, in which case the time-binned levels are skipped rather than
	 *                   silently answered from bin zero
	 */
	Lookup lookup(Id<Link> fromLinkId, double time);

	default double getWaitTime(Id<Link> fromLinkId, double time) {
		return lookup(fromLinkId, time).waitTime();
	}
}
