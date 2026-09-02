package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.mobsim.qsim.qnetsimengine.QVehicleImpl;
import org.matsim.core.mobsim.qsim.qnetsimengine.vehicle_handler.VehicleHandler.VehicleArrival;
import org.matsim.core.network.NetworkUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Transit vehicles must not be parked, even when their vehicle type has network mode car, which is the common
 * configuration. That only works if the handler sees {@code TransitDriverStartsEvent}.
 */
class ParkingVehicleHandlerTest {

	@Test
	void carIsParked() {
		ParkingVehicleHandler handler = new ParkingVehicleHandler();
		assertEquals(VehicleArrival.PARKING, handler.handleVehicleArrival(car("v1"), link()));
	}

	@Test
	void nonCarNetworkModeIsNotParked() {
		ParkingVehicleHandler handler = new ParkingVehicleHandler();
		assertEquals(VehicleArrival.ALLOWED, handler.handleVehicleArrival(vehicle("bike", TransportMode.bike), link()));
	}

	@Test
	void transitVehicleWithCarNetworkModeIsNotParkedOnceItsDriverStarted() {
		ParkingVehicleHandler handler = new ParkingVehicleHandler();
		QVehicleImpl bus = car("bus");

		assertEquals(VehicleArrival.PARKING, handler.handleVehicleArrival(bus, link()),
			"before any transit driver event the bus is indistinguishable from a car: this is the trap");

		handler.handleEvent(new TransitDriverStartsEvent(0, Id.create("d", Person.class), bus.getId(),
			Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class), Id.create("dep", Departure.class)));

		assertEquals(VehicleArrival.ALLOWED, handler.handleVehicleArrival(bus, link()),
			"once the handler has seen the transit driver start, the bus is left alone");
	}

	@Test
	void knownTransitVehiclesAreForgottenBetweenMobsims() {
		ParkingVehicleHandler handler = new ParkingVehicleHandler();
		QVehicleImpl bus = car("bus");
		handler.handleEvent(new TransitDriverStartsEvent(0, Id.create("d", Person.class), bus.getId(),
			Id.create("line", TransitLine.class), Id.create("route", TransitRoute.class), Id.create("dep", Departure.class)));
		assertEquals(VehicleArrival.ALLOWED, handler.handleVehicleArrival(bus, link()));

		handler.cleanupAfterMobsim(0);

		assertEquals(VehicleArrival.PARKING, handler.handleVehicleArrival(bus, link()),
			"the set is per mobsim; a fresh iteration re-learns transit vehicles from its own events");
	}

	private static QVehicleImpl car(String id) {
		return vehicle(id, TransportMode.car);
	}

	private static QVehicleImpl vehicle(String id, String networkMode) {
		VehicleType type = VehicleUtils.createVehicleType(Id.create(networkMode + "-type", VehicleType.class));
		type.setNetworkMode(networkMode);
		Vehicle vehicle = VehicleUtils.createVehicle(Id.create(id, Vehicle.class), type);
		return new QVehicleImpl(vehicle);
	}

	private static Link link() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.create("a", Node.class), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(network, Id.create("b", Node.class), new Coord(100, 0));
		return NetworkUtils.createAndAddLink(network, Id.createLinkId("l"), a, b, 100, 10, 1800, 1);
	}
}
