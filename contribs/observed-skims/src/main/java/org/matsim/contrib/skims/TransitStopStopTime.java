package org.matsim.contrib.skims;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * How long a vehicle actually took between two consecutive stops, by time of day.
 * <p>
 * The counterpart of {@link TransitWaitTime} for the in-vehicle part of a trip. A bus stuck in the same
 * congestion as the cars it shares a lane with takes longer than its timetable claims, every day, and a
 * router reading the timetable will keep sending people onto it.
 * <p>
 * Implementations must never return infinity or NaN; an unobserved pair falls back to the scheduled time.
 */
public interface TransitStopStopTime {

	/**
	 * @param time when the vehicle departs the first stop, in seconds since midnight
	 * @return seconds between departing {@code fromStopId} and arriving at {@code toStopId}, never
	 *         infinite or NaN
	 */
	double stopStopTime(Id<TransitStopFacility> fromStopId, Id<TransitStopFacility> toStopId, double time);

	/**
	 * How much longer the observed run between two stops is than the timetable implies.
	 * <p>
	 * As with {@link TransitWaitTime#excessWaitTime}, this and not the absolute figure is what a cost
	 * consumer wants: the router already prices the scheduled duration, so charging the whole observed
	 * time would count it twice. Zero where nothing has been observed, so a fresh skim changes nothing.
	 * Negative where a service beats its timetable, which is reported rather than floored.
	 *
	 * @return observed minus scheduled seconds, never infinite or NaN
	 */
	double excessStopStopTime(Id<TransitStopFacility> fromStopId, Id<TransitStopFacility> toStopId, double time);
}
