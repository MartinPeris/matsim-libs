package org.matsim.contrib.parking.parkingsearchparameterization;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.mobsim.qsim.qnetsimengine.QVehicle;
import org.matsim.core.mobsim.qsim.qnetsimengine.vehicle_handler.VehicleHandler;
import org.matsim.vehicles.Vehicle;

import java.util.HashSet;
import java.util.Set;

/**
 * Parks every car on arrival, except transit vehicles.
 * <p>
 * Transit vehicles are recognised from {@code TransitDriverStartsEvent}, so this handler only works when it is
 * registered for events as well as as a vehicle handler, and as the same instance for both: bind it in
 * {@code Singleton} scope, then {@code addVehicleHandlerBinding().to(...)} and
 * {@code addMobsimScopeEventHandlerBinding().to(...)}. Bound as a vehicle handler alone it never learns which
 * vehicles are transit, and buses whose vehicle type has network mode car are parked and counted as demand.
 */
public class ParkingVehicleHandler implements VehicleHandler, TransitDriverStartsEventHandler, MobsimScopeEventHandler {
	private final Set<Id<Vehicle>> knownPtVehicles = new HashSet<>();

	@Override
	public void handleVehicleDeparture(QVehicle vehicle, Link link) {

	}

	@Override
	public VehicleArrival handleVehicleArrival(QVehicle vehicle, Link link) {
		if (!vehicle.getVehicle().getType().getNetworkMode().equals(TransportMode.car)) {
			// If vehicle is no car, do not park it
			return VehicleArrival.ALLOWED;
		}

		if (knownPtVehicles.contains(vehicle.getId())) {
			// if vehicle is pt vehicle, do not park it
			return VehicleArrival.ALLOWED;
		}

		// otherwise force parking
		return VehicleArrival.PARKING;
	}

	@Override
	public void handleInitialVehicleArrival(QVehicle vehicle, Link link) {

	}

	@Override
	public void cleanupAfterMobsim(int iteration) {
		knownPtVehicles.clear();
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		knownPtVehicles.add(event.getVehicleId());
	}
}
