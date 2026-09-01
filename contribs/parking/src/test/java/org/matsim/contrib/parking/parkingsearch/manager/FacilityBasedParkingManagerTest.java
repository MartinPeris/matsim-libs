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

package org.matsim.contrib.parking.parkingsearch.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.parking.parkingsearch.ParkingSearchUtils;
import org.matsim.contrib.parking.parkingsearch.sim.ParkingSearchConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.ActivityOption;
import org.matsim.vehicles.Vehicle;

/**
 * Characterises how {@link FacilityBasedParkingManager} divides parking supply, before that division is changed.
 * <p>
 * The class already separates parking into two kinds, which is the structure a kerb-parking model needs -- but it
 * assigns the scarcity to the opposite side. Facilities are capacity-constrained; parking at the roadside is the
 * unlimited fallback. Its own comment on the matter reads: <em>"Either parks the vehicle at a link freely (no capacity
 * constraint) or at a facility (capacity constraint)."</em> Bischoff and Nagel (2017) state the same assumption for
 * the model this contrib implements: <em>"For links without this information, a direct on-street parking spot is
 * assumed."</em>
 * <p>
 * A kerb-first model needs the inverse: the roadside is the scarce pool and is consumed first, with off-street as the
 * unbounded overflow. These tests pin the current behaviour so that inversion is a visible, deliberate change.
 *
 * @author pieterfourie
 */
class FacilityBasedParkingManagerTest {

	private static final Id<Link> LINK_WITH_FACILITY = Id.createLinkId("withFacility");
	private static final Id<Link> LINK_WITHOUT_FACILITY = Id.createLinkId("withoutFacility");
	private static final Id<ActivityFacility> FACILITY_ID = Id.create("garage", ActivityFacility.class);

	/**
	 * Roadside parking is unbounded: an arbitrary number of vehicles can reserve and park on a link that has no
	 * parking facility attached to it.
	 */
	@Test
	void parkingAtTheRoadsideIsUnlimited() {
		FacilityBasedParkingManager manager = managerWithFacilityCapacity(1);

		for (int i = 0; i < 50; i++) {
			Id<Vehicle> vehicleId = Id.createVehicleId("v" + i);
			assertTrue(manager.reserveSpaceIfVehicleCanParkHere(vehicleId, LINK_WITHOUT_FACILITY),
				"reservation " + i + " at the roadside should succeed; there is no capacity to exhaust");
			assertTrue(manager.parkVehicleHere(vehicleId, LINK_WITHOUT_FACILITY, 3600.0),
				"parking " + i + " at the roadside should succeed");
		}

		assertEquals(LINK_WITHOUT_FACILITY, manager.getVehicleParkingLocation(Id.createVehicleId("v49")),
			"the fiftieth vehicle should still be recorded as parked on that link");
	}

	/**
	 * Facility parking is the constrained kind: once the declared capacity is taken, further reservations are refused.
	 */
	@Test
	void parkingAtAFacilityIsCapacityConstrained() {
		FacilityBasedParkingManager manager = managerWithFacilityCapacity(1);

		assertTrue(manager.reserveSpaceIfVehicleCanParkHere(Id.createVehicleId("first"), LINK_WITH_FACILITY),
			"the first vehicle should get the single facility space");
		assertFalse(manager.reserveSpaceIfVehicleCanParkHere(Id.createVehicleId("second"), LINK_WITH_FACILITY),
			"the second vehicle should be refused: the facility holds one vehicle");
	}

	/**
	 * The constraint is the declared {@link ActivityOption} capacity, so it scales as expected rather than being a
	 * fixed limit.
	 */
	@Test
	void facilityCapacityIsTakenFromTheActivityOption() {
		FacilityBasedParkingManager manager = managerWithFacilityCapacity(3);

		for (int i = 0; i < 3; i++) {
			assertTrue(manager.reserveSpaceIfVehicleCanParkHere(Id.createVehicleId("v" + i), LINK_WITH_FACILITY),
				"vehicle " + i + " should fit in a facility of capacity three");
		}
		assertFalse(manager.reserveSpaceIfVehicleCanParkHere(Id.createVehicleId("overflow"), LINK_WITH_FACILITY),
			"the fourth vehicle should be refused");
	}

	/**
	 * Freeing a facility space returns it to the pool, confirming the constraint tracks occupancy rather than a
	 * cumulative arrival count.
	 */
	@Test
	void unparkingReleasesAFacilitySpace() {
		FacilityBasedParkingManager manager = managerWithFacilityCapacity(1);
		Id<Vehicle> first = Id.createVehicleId("first");

		manager.reserveSpaceIfVehicleCanParkHere(first, LINK_WITH_FACILITY);
		manager.parkVehicleHere(first, LINK_WITH_FACILITY, 3600.0);
		manager.unParkVehicleHere(first, LINK_WITH_FACILITY, 7200.0);

		assertTrue(manager.reserveSpaceIfVehicleCanParkHere(Id.createVehicleId("second"), LINK_WITH_FACILITY),
			"after the first vehicle leaves, the space should be reservable again");
	}

	/**
	 * The one existing switch that makes the roadside scarce is all-or-nothing: it forbids roadside parking entirely
	 * rather than giving it a finite capacity. There is no setting that expresses "this kerb holds four cars".
	 */
	@Test
	void canParkOnlyAtFacilitiesForbidsTheRoadsideOutright() {
		FacilityBasedParkingManager manager = managerWithFacilityCapacity(1, true);

		assertFalse(manager.reserveSpaceIfVehicleCanParkHere(Id.createVehicleId("v"), LINK_WITHOUT_FACILITY),
			"with canParkOnlyAtFacilities the roadside offers no spaces at all, rather than a limited number");
		assertTrue(manager.reserveSpaceIfVehicleCanParkHere(Id.createVehicleId("w"), LINK_WITH_FACILITY),
			"the facility link is unaffected by that switch");
	}

	private static FacilityBasedParkingManager managerWithFacilityCapacity(double capacity) {
		return managerWithFacilityCapacity(capacity, false);
	}

	private static FacilityBasedParkingManager managerWithFacilityCapacity(double capacity,
		boolean canParkOnlyAtFacilities) {
		Config config = ConfigUtils.createConfig();
		// FacilityBasedParkingManager sizes its reporting bins from the qsim end time, which has no default
		config.qsim().setEndTime(30 * 3600.0);
		ParkingSearchConfigGroup parkingConfig = new ParkingSearchConfigGroup();
		parkingConfig.setCanParkOnlyAtFacilities(canParkOnlyAtFacilities);
		config.addModule(parkingConfig);

		Scenario scenario = ScenarioUtils.createScenario(config);

		Network network = scenario.getNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.create("a", Node.class), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(network, Id.create("b", Node.class), new Coord(1000, 0));
		Node c = NetworkUtils.createAndAddNode(network, Id.create("c", Node.class), new Coord(2000, 0));
		NetworkUtils.createAndAddLink(network, LINK_WITH_FACILITY, a, b, 1000.0, 10.0, 1800.0, 1.0);
		NetworkUtils.createAndAddLink(network, LINK_WITHOUT_FACILITY, b, c, 1000.0, 10.0, 1800.0, 1.0);

		ActivityFacilities facilities = scenario.getActivityFacilities();
		ActivityFacility facility = facilities.getFactory().createActivityFacility(FACILITY_ID, LINK_WITH_FACILITY);
		ActivityOption option = facilities.getFactory()
			.createActivityOption(ParkingSearchUtils.ParkingStageInteractionType);
		option.setCapacity(capacity);
		facility.addActivityOption(option);
		facilities.addActivityFacility(facility);

		return new FacilityBasedParkingManager(scenario);
	}
}
