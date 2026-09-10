/* *********************************************************************** *
 * project: org.matsim.* 												   *
 *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2023 by the members listed in the COPYING,        *
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
package ch.sbb.matsim.routing.pt.raptor;

import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import org.matsim.api.core.v01.population.Person;
import org.matsim.vehicles.Vehicle;

/**
 * @author mrieser / Simunto GmbH
 */
public interface RaptorInVehicleCostCalculator {

	double getInVehicleCost(double inVehicleTime, double marginalUtility_utl_s, Person person, Vehicle vehicle, RaptorParameters paramters, RouteSegmentIterator iterator);

	interface RouteSegmentIterator {
		boolean hasNext();
		void next();
		double getInVehicleTime();
		double getPassengerCount();
		double getTimeOfDay();

		/**
		 * The stop this segment leaves from, or null where the implementation cannot say.
		 * <p>
		 * Without this, a cost calculator can see how long a segment takes but not which segment it is,
		 * so it cannot consult anything keyed by stop: observed inter-stop travel times, a per-corridor
		 * penalty, a link-specific charge. Defaulted to null rather than made abstract so that existing
		 * implementations outside this package keep compiling; a caller must handle null by falling back
		 * to whatever it would have done anyway.
		 */
		default Id<TransitStopFacility> getFromStop() {
			return null;
		}

		/** The stop this segment arrives at, or null. See {@link #getFromStop()}. */
		default Id<TransitStopFacility> getToStop() {
			return null;
		}
	}

}
