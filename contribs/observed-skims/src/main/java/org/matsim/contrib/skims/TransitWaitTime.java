package org.matsim.contrib.skims;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

/**
 * How long a passenger actually waited at a stop for a route, by time of day.
 * <p>
 * The timetable says how long the wait should be. A passenger who cannot board a full vehicle, or whose
 * service ran late, waits longer, and routing that reads only the timetable never learns this. An
 * implementation of this interface reports what the mobsim observed, so a router can price the wait that
 * the traveller will really face.
 * <p>
 * Implementations must never return infinity or NaN. A stop and bin with nothing observed falls back to
 * the wait the timetable implies, so a caller always has a usable number and never needs a null check.
 * That guarantee is what lets a router consult the skim unconditionally, including in the first iteration
 * when nothing has been observed at all.
 */
public interface TransitWaitTime {

	/**
	 * @param time when the passenger arrives at the stop, in seconds since midnight
	 * @return seconds of waiting, never infinite or NaN
	 */
	double waitTime(Id<TransitLine> lineId, Id<TransitRoute> routeId, Id<TransitStopFacility> stopId, double time);

	/**
	 * How much longer the observed wait is than the timetable implies, at the same stop and time.
	 * <p>
	 * This, not {@link #waitTime}, is what a cost consumer wants. A router that already prices the wait
	 * its timetable predicts would double-charge if handed the absolute figure; the excess is the part
	 * it does not yet know about. Where nothing has been observed the excess is exactly zero, so
	 * installing a skim changes no cost until it has something to say.
	 * <p>
	 * The excess may be negative, when a service runs more reliably than its timetable promises. That is
	 * a real observation and is reported as such rather than floored at zero; a consumer that must not
	 * reward it can clamp, and should say why.
	 *
	 * @return observed minus scheduled seconds, never infinite or NaN
	 */
	double excessWaitTime(Id<TransitLine> lineId, Id<TransitRoute> routeId, Id<TransitStopFacility> stopId,
			double time);
}
