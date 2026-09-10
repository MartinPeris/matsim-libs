package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_ON_STREET_SPOTS;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.kerbParkingPermitted;

/**
 * Whether a link is a kerb parking place at all, which is a different question from how many spaces it has.
 * The initializer and the observer both ask it, and must agree.
 */
class ParkingUtilsTest {

	@Test
	void anEligibleCarLinkIsAParkingPlace() {
		assertTrue(kerbParkingPermitted(link(2.0, null), new KerbParkingEligibility.MinimumLanes()));
	}

	@Test
	void anIneligibleLinkIsNot() {
		assertFalse(kerbParkingPermitted(link(1.0, null), new KerbParkingEligibility.MinimumLanes(2.0)));
	}

	@Test
	void aLinkNoCarCanUseIsNot() {
		Link link = link(2.0, null);
		link.setAllowedModes(Set.of("pt"));

		assertFalse(kerbParkingPermitted(link, new KerbParkingEligibility.MinimumLanes()),
			"a two-lane pt-only link carries no kerb parking whatever the lane rule says");
	}

	@Test
	void anExplicitSpotsAttributeMakesItAParkingPlaceWhateverTheRuleSays() {
		assertTrue(kerbParkingPermitted(link(1.0, 4), new KerbParkingEligibility.MinimumLanes(2.0)),
			"a declared kerb supply is taken at face value, exactly as the initializer takes it");
	}

	@Test
	void anExplicitZeroStillMakesItAParkingPlace() {
		assertTrue(kerbParkingPermitted(link(2.0, 0), new KerbParkingEligibility.MinimumLanes()),
			"zero declared spaces says the kerb is full-up-front, not that the street is not a place to park; "
				+ "arrivals there are real unmet demand, not a snapping artefact");
	}

	@Test
	void theMotorwayExclusionCarriesThrough() {
		Link link = link(2.0, null);
		link.getAttributes().putAttribute(KerbParkingEligibility.OsmHighwayType.DEFAULT_ATTRIBUTE, "highway.motorway");

		assertFalse(kerbParkingPermitted(link, KerbParkingEligibility.defaults(1.0)),
			"a motorway that receives an arrival is an activity coordinate snapping, not parking demand");
	}

	private static Link link(double lanes, Integer onStreetSpots) {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		Network network = scenario.getNetwork();
		Node from = NetworkUtils.createAndAddNode(network, Id.create("1", Node.class), new Coord(0, 0));
		Node to = NetworkUtils.createAndAddNode(network, Id.create("2", Node.class), new Coord(100, 0));
		Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId("l"), from, to, 100.0, 10.0, 1800.0, lanes);
		if (onStreetSpots != null) {
			link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, onStreetSpots);
		}
		return link;
	}
}
