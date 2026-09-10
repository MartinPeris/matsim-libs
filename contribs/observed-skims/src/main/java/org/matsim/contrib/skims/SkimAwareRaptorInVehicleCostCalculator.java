package org.matsim.contrib.skims;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;

import ch.sbb.matsim.routing.pt.raptor.CapacityDependentInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorInVehicleCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;

/**
 * Charges an in-vehicle leg for the running time the timetable does not predict.
 * <p>
 * SwissRailRaptor costs time in a vehicle from the schedule. Where a service is habitually slower than
 * its timetable, because it shares road space with traffic it cannot overtake, the router keeps
 * offering a journey nobody experiences. This walks the segments of the leg, asks
 * {@link TransitStopStopTime} how much longer each really took, and adds the difference.
 *
 * <h2>Only the excess, again</h2>
 * The scheduled duration is already in {@code inVehicleTime} and already priced by the delegate.
 * Adding the observed time in full would charge it twice. See
 * {@link TransitStopStopTime#excessStopStopTime}.
 *
 * <h2>The iterator is single-pass, which constrains what this can wrap</h2>
 * {@code RouteSegmentIterator} can be walked once. This class walks it, so a delegate that also walks
 * it would find it exhausted and silently cost the leg at zero.
 * {@link DefaultRaptorInVehicleCostCalculator} ignores the iterator entirely and is safe;
 * {@link CapacityDependentInVehicleCostCalculator} consumes it and is not, so it is rejected in the
 * constructor rather than left to fail as a wrong number. Pricing both crowding and unreliability
 * needs one implementation that walks the segments once and applies both, not two stacked decorators.
 * <p>
 * Segments whose stops the iterator cannot name are skipped. {@code getFromStop()} and
 * {@code getToStop()} are defaulted to null on that interface so implementations predating them keep
 * compiling, so a null is a normal answer here and not an error.
 *
 * @author Monash Healthy Active Cities
 */
public final class SkimAwareRaptorInVehicleCostCalculator implements RaptorInVehicleCostCalculator {

	private final RaptorInVehicleCostCalculator delegate;
	private final TransitStopStopTime stopStopTime;
	private final double unreliabilityCostFactor;

	public SkimAwareRaptorInVehicleCostCalculator(TransitStopStopTime stopStopTime, double unreliabilityCostFactor) {
		this(new DefaultRaptorInVehicleCostCalculator(), stopStopTime, unreliabilityCostFactor);
	}

	public SkimAwareRaptorInVehicleCostCalculator(RaptorInVehicleCostCalculator delegate,
			TransitStopStopTime stopStopTime, double unreliabilityCostFactor) {
		if (delegate instanceof CapacityDependentInVehicleCostCalculator) {
			throw new IllegalArgumentException("CapacityDependentInVehicleCostCalculator also consumes the "
					+ "single-pass RouteSegmentIterator, so stacking the two would silently cost every leg at "
					+ "zero. Pricing crowding and unreliability together needs one calculator that walks the "
					+ "segments once and applies both.");
		}
		if (!(unreliabilityCostFactor >= 0)) {
			throw new IllegalArgumentException(
					"unreliabilityCostFactor must be non-negative, got " + unreliabilityCostFactor);
		}
		this.delegate = delegate;
		this.stopStopTime = stopStopTime;
		this.unreliabilityCostFactor = unreliabilityCostFactor;
	}

	@Override
	public double getInVehicleCost(double inVehicleTime, double marginalUtility_utl_s, Person person, Vehicle vehicle,
			RaptorParameters parameters, RouteSegmentIterator iterator) {
		double excess = 0.0;
		while (iterator.hasNext()) {
			iterator.next();
			Id<TransitStopFacility> from = iterator.getFromStop();
			Id<TransitStopFacility> to = iterator.getToStop();
			if (from == null || to == null) {
				continue;
			}
			excess += stopStopTime.excessStopStopTime(from, to, iterator.getTimeOfDay());
		}
		return delegate.getInVehicleCost(inVehicleTime, marginalUtility_utl_s, person, vehicle, parameters, iterator)
				+ charge(excess, unreliabilityCostFactor, marginalUtility_utl_s);
	}

	/**
	 * The cost of {@code excess} seconds of unpredicted running time, at the in-vehicle utility of the
	 * mode the leg is on. Kept separate so it can be pinned by a unit test; the walk above cannot be,
	 * because nothing outside SwissRailRaptor's package can build a populated iterator.
	 */
	static double charge(double excess, double unreliabilityCostFactor, double marginalUtility_utl_s) {
		if (excess == 0.0) {
			return 0.0;
		}
		return unreliabilityCostFactor * excess * -marginalUtility_utl_s;
	}
}
