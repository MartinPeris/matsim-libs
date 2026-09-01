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

package org.matsim.contrib.parking.parkingsearchparameterization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_OFF_STREET_SPOTS;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_ON_STREET_SPOTS;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.parking.parkingsearchparameterization.ParkingCapacityInitializer.ParkingInitialCapacity;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;

/**
 * Characterises {@link ZeroParkingCapacityInitializer} before it is changed.
 * <p>
 * The headline behaviour is that the two link attributes {@code onstreet_spots} and {@code offstreet_spots} are read
 * separately and then <em>added together</em> into a single capacity figure. Nothing downstream can therefore
 * distinguish kerb supply from off-street supply, order one before the other, or report how much of each was used.
 *
 * @author pieterfourie
 */
class ZeroParkingCapacityInitializerTest {

	private static final Id<Link> LINK_ID = Id.createLinkId("1");

	/**
	 * The gap this work exists to close: the two pools are summed and the distinction is discarded at this point.
	 */
	@Test
	void onStreetAndOffStreetSpotsAreSummedIntoOneCapacity() {
		Map<Id<Link>, ParkingInitialCapacity> result = initialize(3, 5, 1.0);

		assertEquals(new ParkingInitialCapacity(8, 0), result.get(LINK_ID),
			"three kerb spaces and five off-street spaces should collapse into a single capacity of eight");
	}

	/**
	 * Consequence of the summing: different splits of the same total are indistinguishable downstream.
	 */
	@Test
	void differentSplitsOfTheSameTotalAreIndistinguishable() {
		ParkingInitialCapacity allKerb = initialize(8, 0, 1.0).get(LINK_ID);
		ParkingInitialCapacity allOffStreet = initialize(0, 8, 1.0).get(LINK_ID);
		ParkingInitialCapacity mixed = initialize(4, 4, 1.0).get(LINK_ID);

		assertEquals(allKerb, allOffStreet,
			"a link with only kerb parking is indistinguishable from one with only off-street parking");
		assertEquals(allKerb, mixed, "and both are indistinguishable from an even split");
	}

	/**
	 * Capacity is scaled by the qsim storage capacity factor, so a sampled population gets a sampled parking supply.
	 * The result is rounded up, which matters for small counts: a 10% sample of one space is still one space.
	 */
	@Test
	void capacityIsScaledByStorageCapFactorAndRoundedUp() {
		assertEquals(new ParkingInitialCapacity(4, 0), initialize(3, 5, 0.5).get(LINK_ID),
			"eight spaces at a storage capacity factor of 0.5 should give four");
		assertEquals(new ParkingInitialCapacity(1, 0), initialize(1, 0, 0.1).get(LINK_ID),
			"one space at a factor of 0.1 should round up to one, not down to zero");
		assertEquals(new ParkingInitialCapacity(3, 0), initialize(5, 0, 0.5).get(LINK_ID),
			"an odd count should round up rather than truncate");
	}

	/**
	 * Links carrying neither attribute get no parking at all rather than unlimited parking. Any supply-derivation rule
	 * has to fill these in explicitly.
	 */
	@Test
	void linksWithoutAttributesGetZeroCapacity() {
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		Network network = buildSingleLinkNetwork(scenario);
		// deliberately set no attributes on the link

		Map<Id<Link>, ParkingInitialCapacity> result = new ZeroParkingCapacityInitializer(network, config).initialize();

		assertEquals(new ParkingInitialCapacity(0, 0), result.get(LINK_ID),
			"a link with neither attribute should get zero capacity, not unlimited");
	}

	/**
	 * The "Zero" in the class name refers to occupancy: every link starts empty regardless of its capacity. The
	 * alternative, {@code PlanBasedParkingCapacityInitializer}, is what seeds a non-zero starting occupancy.
	 */
	@Test
	void initialOccupancyIsAlwaysZero() {
		assertEquals(0, initialize(10, 10, 1.0).get(LINK_ID).occupancy(),
			"initial occupancy should be zero even on a link with ample capacity");
	}

	/**
	 * Every link in the network is present in the result, so downstream code can index by link without null checks.
	 */
	@Test
	void everyLinkAppearsInTheResult() {
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.createScenario(config);
		Network network = buildSingleLinkNetwork(scenario);

		Map<Id<Link>, ParkingInitialCapacity> result = new ZeroParkingCapacityInitializer(network, config).initialize();

		assertEquals(network.getLinks().size(), result.size(), "the result should cover every link in the network");
	}

	private static Map<Id<Link>, ParkingInitialCapacity> initialize(int onStreet, int offStreet,
		double storageCapFactor) {
		Config config = ConfigUtils.createConfig();
		config.qsim().setStorageCapFactor(storageCapFactor);

		Scenario scenario = ScenarioUtils.createScenario(config);
		Network network = buildSingleLinkNetwork(scenario);
		Link link = network.getLinks().get(LINK_ID);
		link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, onStreet);
		link.getAttributes().putAttribute(LINK_OFF_STREET_SPOTS, offStreet);

		return new ZeroParkingCapacityInitializer(network, config).initialize();
	}

	private static Network buildSingleLinkNetwork(Scenario scenario) {
		Network network = scenario.getNetwork();
		Node from = NetworkUtils.createAndAddNode(network, Id.create("1", Node.class), new Coord(0, 0));
		Node to = NetworkUtils.createAndAddNode(network, Id.create("2", Node.class), new Coord(1000, 0));
		NetworkUtils.createAndAddLink(network, LINK_ID, from, to, 1000.0, 10.0, 1800.0, 1.0);
		return network;
	}
}
