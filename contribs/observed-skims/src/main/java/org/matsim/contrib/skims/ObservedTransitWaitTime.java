package org.matsim.contrib.skims;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.collections.Tuple;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import com.google.inject.Singleton;

/**
 * Wait times measured from the mobsim's own events, per line, route, stop and time bin.
 * <p>
 * A wait is the interval between a passenger departing on a pt leg and entering a transit vehicle:
 *
 * <pre>
 * wait = PersonEntersVehicleEvent.time - PersonDepartureEvent.time
 * </pre>
 *
 * measured from the moment the traveller becomes ready to board rather than from any scheduled time, so
 * it captures both a late service and a passenger left behind by a full one. It is attributed to the bin
 * the passenger arrived in, not the one they boarded in, because that is the bin a router looks up when
 * it asks what waiting to expect for someone arriving then.
 * <p>
 * The measurement descends from Sergio Ordonez's {@code eventsBasedPTRouter} contrib by way of
 * {@code PSimWaitTimeCalculator}. What is new here is that the result is damped across iterations and
 * offered to routing rather than to a surrogate mobsim.
 *
 * <h2>The fallback is the point</h2>
 * A stop and bin nobody boarded at yields the wait the timetable implies for a passenger arriving at the
 * end of that bin, wrapping past midnight when the route has no later departure. Without it, a router
 * consulting the skim in the first iteration, or for an off-peak bin, would be told the wait is zero and
 * would send everyone through stops no vehicle serves. The fallback is what lets consumers query the
 * skim unconditionally, with no null checks and no special case for "not yet observed".
 *
 * <h2>Damping</h2>
 * The skim feeds the routing that generates the next iteration's traffic, so it sits inside a feedback
 * loop and can oscillate. Each bin is folded into its previous value at the end of the iteration with
 * {@code updateWeight}; see {@link MeanByTimeBin}. A bin with no observation this iteration keeps what
 * it had rather than decaying, because nothing was learned about it.
 *
 * @author Monash Healthy Active Cities
 */
@Singleton
public final class ObservedTransitWaitTime implements TransitWaitTime, PersonDepartureEventHandler,
		PersonEntersVehicleEventHandler, TransitDriverStartsEventHandler, VehicleArrivesAtFacilityEventHandler,
		IterationEndsListener {

	private static final double DAY = 24 * 3600.0;

	private final double binSize;
	private final int binCount;
	private final double updateWeight;

	private final Map<Tuple<Id<TransitLine>, Id<TransitRoute>>, Map<Id<TransitStopFacility>, MeanByTimeBin>> measured =
			new HashMap<>();
	private final Map<Tuple<Id<TransitLine>, Id<TransitRoute>>, Map<Id<TransitStopFacility>, double[]>> scheduled =
			new HashMap<>();

	private final Map<Id<Person>, Double> waitingSince = new HashMap<>();
	private final Map<Id<Vehicle>, Tuple<Id<TransitLine>, Id<TransitRoute>>> lineRouteOfVehicle = new HashMap<>();
	private final Map<Id<Vehicle>, Id<TransitStopFacility>> stopOfVehicle = new HashMap<>();

	public ObservedTransitWaitTime(TransitSchedule schedule, double binSize, double endTime, double updateWeight) {
		if (!(binSize > 0)) {
			throw new IllegalArgumentException("binSize must be positive, got " + binSize);
		}
		if (!(updateWeight > 0) || updateWeight > 1) {
			throw new IllegalArgumentException("updateWeight must be in (0, 1], got " + updateWeight);
		}
		this.binSize = binSize;
		this.updateWeight = updateWeight;
		this.binCount = Math.max(1, (int) Math.ceil(endTime / binSize));
		for (TransitLine line : schedule.getTransitLines().values()) {
			for (TransitRoute route : line.getRoutes().values()) {
				Tuple<Id<TransitLine>, Id<TransitRoute>> key = new Tuple<>(line.getId(), route.getId());
				measured.put(key, measuredStopsOf(route));
				scheduled.put(key, scheduledStopsOf(route));
			}
		}
	}

	private Map<Id<TransitStopFacility>, MeanByTimeBin> measuredStopsOf(TransitRoute route) {
		Map<Id<TransitStopFacility>, MeanByTimeBin> stops = new HashMap<>();
		for (TransitRouteStop stop : route.getStops()) {
			stops.put(stop.getStopFacility().getId(), new MeanByTimeBin(binCount));
		}
		return stops;
	}

	/**
	 * The wait a passenger arriving at a uniformly random moment within each bin expects, given the
	 * timetable.
	 * <p>
	 * Not the wait at the bin's edge. Sampling the sawtooth at one instant makes the answer depend on
	 * where the departures happen to fall relative to the bin boundary: a service every ten minutes read
	 * at the end of an hour-long bin gives a wait of exactly zero, every bin, which is the one answer
	 * that is certainly wrong. Averaging over the bin gives half the headway for a regular service,
	 * which is the textbook result and the number a router should be told when it has no observation.
	 */
	private Map<Id<TransitStopFacility>, double[]> scheduledStopsOf(TransitRoute route) {
		double[] departures = new double[route.getDepartures().size()];
		int index = 0;
		for (Departure departure : route.getDepartures().values()) {
			departures[index++] = departure.getDepartureTime();
		}
		Arrays.sort(departures);

		Map<Id<TransitStopFacility>, double[]> stops = new HashMap<>();
		for (TransitRouteStop stop : route.getStops()) {
			double offset = stop.getArrivalOffset().or(stop::getDepartureOffset).seconds();
			double[] waits = new double[binCount];
			for (int bin = 0; bin < binCount; bin++) {
				waits[bin] = meanScheduledWaitInBin(departures, offset, binSize * bin, binSize * (bin + 1));
			}
			stops.put(stop.getStopFacility().getId(), waits);
		}
		return stops;
	}

	/**
	 * Mean of {@code nextArrival(t) - t} over {@code t} uniform on {@code [binStart, binEnd)}.
	 * <p>
	 * The wait is a sawtooth, falling at unit rate and jumping up at each arrival, so the mean is
	 * integrated exactly rather than sampled: the bin is split at each arrival inside it and the
	 * triangle over each piece is added up. A route whose last service of the day has gone wraps to the
	 * first service of the next day, which is what a passenger arriving then actually faces.
	 */
	private static double meanScheduledWaitInBin(double[] sortedDepartures, double stopOffset, double binStart,
			double binEnd) {
		if (sortedDepartures.length == 0) {
			return 0.0;
		}
		double integral = 0.0;
		double from = binStart;
		while (from < binEnd) {
			double next = nextArrivalAtOrAfter(sortedDepartures, stopOffset, from);
			if (next < from) {
				// nextArrivalAtOrAfter promises a value at or after `from`. If that promise is ever
				// broken the loop below cannot terminate, so fail rather than hang: a spinning mobsim
				// is far harder to diagnose than a stack trace.
				throw new IllegalStateException(
						"next arrival " + next + " precedes the query time " + from + "; this is a bug");
			}
			double to = Math.min(next, binEnd);
			if (to <= from) {
				// An arrival exactly at `from`: step past it so the walk cannot stall.
				from = Math.nextUp(from);
				continue;
			}
			// integral of (next - t) dt over [from, to)
			integral += next * (to - from) - (to * to - from * from) / 2.0;
			from = to;
		}
		return integral / (binEnd - binStart);
	}

	/**
	 * The first arrival at or after {@code time}, wrapping to a following service day when today's
	 * services have all gone.
	 * <p>
	 * The wrap must be computed against {@code time}, not against midnight. A horizon that runs past
	 * 24 h, which MATSim's default 30 h qsim end time does, can put {@code time} beyond tomorrow's
	 * first service too; returning that earlier arrival would hand the caller a value in its past. The
	 * integration loop then makes no progress and spins for ever. This is not hypothetical: it hung a
	 * real scenario whose services start at 05:00, and no unit test caught it because the test schedule
	 * ran to the end of the horizon.
	 */
	private static double nextArrivalAtOrAfter(double[] sortedDepartures, double stopOffset, double time) {
		for (double departure : sortedDepartures) {
			double arrival = departure + stopOffset;
			if (arrival >= time) {
				return arrival;
			}
		}
		double firstOfDay = sortedDepartures[0] + stopOffset;
		double daysAhead = Math.ceil((time - firstOfDay) / DAY);
		return firstOfDay + daysAhead * DAY;
	}

	@Override
	public double waitTime(Id<TransitLine> lineId, Id<TransitRoute> routeId, Id<TransitStopFacility> stopId,
			double time) {
		Tuple<Id<TransitLine>, Id<TransitRoute>> key = new Tuple<>(lineId, routeId);
		Map<Id<TransitStopFacility>, MeanByTimeBin> stops = measured.get(key);
		if (stops == null) {
			// A line or route absent from the schedule this skim was built on. Nothing is known and no
			// timetable fallback exists, so claim nothing rather than invent a wait.
			return 0.0;
		}
		MeanByTimeBin waits = stops.get(stopId);
		if (waits == null) {
			return 0.0;
		}
		int bin = MeanByTimeBin.binOf(time, binSize, binCount);
		return waits.hasValue(bin) ? waits.mean(bin) : scheduled.get(key).get(stopId)[bin];
	}

	@Override
	public double excessWaitTime(Id<TransitLine> lineId, Id<TransitRoute> routeId, Id<TransitStopFacility> stopId,
			double time) {
		Tuple<Id<TransitLine>, Id<TransitRoute>> key = new Tuple<>(lineId, routeId);
		Map<Id<TransitStopFacility>, MeanByTimeBin> stops = measured.get(key);
		if (stops == null) {
			return 0.0;
		}
		MeanByTimeBin waits = stops.get(stopId);
		if (waits == null) {
			return 0.0;
		}
		int bin = MeanByTimeBin.binOf(time, binSize, binCount);
		if (!waits.hasValue(bin)) {
			// Nothing observed, so waitTime() returns the scheduled figure and the excess is zero by
			// construction. Saying so explicitly is cheaper and clearer than subtracting it from itself.
			return 0.0;
		}
		return waits.mean(bin) - scheduled.get(key).get(stopId)[bin];
	}

	/**
	 * Every bin with a published value, for writing out. A skim that cannot be inspected cannot be
	 * believed: without this, a run that changes nothing is indistinguishable from a run whose skim
	 * found nothing to say.
	 */
	List<SkimEntry> entries() {
		List<SkimEntry> out = new ArrayList<>();
		measured.forEach((lineRoute, stops) -> stops.forEach((stopId, waits) -> {
			for (int bin = 0; bin < binCount; bin++) {
				if (!waits.hasValue(bin)) {
					continue;
				}
				double scheduledWait = scheduled.get(lineRoute).get(stopId)[bin];
				out.add(new SkimEntry(lineRoute.getFirst().toString(), lineRoute.getSecond().toString(),
						stopId.toString(), bin * binSize, waits.count(bin), waits.mean(bin), scheduledWait));
			}
		}));
		return out;
	}

	/** One published bin: what was measured, what the timetable said, and how many observations back it. */
	record SkimEntry(String line, String route, String stop, double binStart, int observations, double observed,
			double scheduled) {
		double excess() {
			return observed - scheduled;
		}
	}

	/** Observations recorded this iteration, before damping. For tests and reporting. */
	int observationCount(Id<TransitLine> lineId, Id<TransitRoute> routeId, Id<TransitStopFacility> stopId,
			double time) {
		Map<Id<TransitStopFacility>, MeanByTimeBin> stops = measured.get(new Tuple<>(lineId, routeId));
		if (stops == null || stops.get(stopId) == null) {
			return 0;
		}
		return stops.get(stopId).count(MeanByTimeBin.binOf(time, binSize, binCount));
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		for (Map<Id<TransitStopFacility>, MeanByTimeBin> stops : measured.values()) {
			for (MeanByTimeBin waits : stops.values()) {
				waits.consolidate(updateWeight);
			}
		}
	}

	@Override
	public void reset(int iteration) {
		waitingSince.clear();
		lineRouteOfVehicle.clear();
		stopOfVehicle.clear();
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		lineRouteOfVehicle.put(event.getVehicleId(), new Tuple<>(event.getTransitLineId(), event.getTransitRouteId()));
	}

	@Override
	public void handleEvent(VehicleArrivesAtFacilityEvent event) {
		if (lineRouteOfVehicle.containsKey(event.getVehicleId())) {
			stopOfVehicle.put(event.getVehicleId(), event.getFacilityId());
		}
	}

	@Override
	public void handleEvent(PersonDepartureEvent event) {
		if (TransportMode.pt.equals(event.getLegMode())) {
			waitingSince.putIfAbsent(event.getPersonId(), event.getTime());
		}
	}

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {
		Double since = waitingSince.remove(event.getPersonId());
		if (since == null) {
			return;
		}
		Tuple<Id<TransitLine>, Id<TransitRoute>> lineRoute = lineRouteOfVehicle.get(event.getVehicleId());
		Id<TransitStopFacility> stopId = stopOfVehicle.get(event.getVehicleId());
		if (lineRoute == null || stopId == null) {
			// Not a transit vehicle, or one that has not yet called at a stop: no wait to attribute.
			return;
		}
		MeanByTimeBin waits = measured.get(lineRoute).get(stopId);
		if (waits != null) {
			waits.add(MeanByTimeBin.binOf(since, binSize, binCount), event.getTime() - since);
		}
	}
}
