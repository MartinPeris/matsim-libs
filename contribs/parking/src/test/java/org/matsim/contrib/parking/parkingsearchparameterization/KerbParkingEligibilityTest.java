package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kerb eligibility rules and their default composition.
 */
class KerbParkingEligibilityTest {

	@Test
	void motorwayTrunkAndLinkRoadsAreExcludedByDefault() {
		KerbParkingEligibility rule = new KerbParkingEligibility.OsmHighwayType();
		for (String cls : new String[]{"highway.motorway", "highway.trunk", "highway.motorway_link", "highway.trunk_link",
			"highway.primary_link", "highway.secondary_link", "highway.tertiary_link"}) {
			assertFalse(rule.isEligible(link(2, cls)), cls + " must not get kerb parking");
		}
	}

	@Test
	void ordinaryRoadClassesAreEligible() {
		KerbParkingEligibility rule = new KerbParkingEligibility.OsmHighwayType();
		for (String cls : new String[]{"highway.residential", "highway.primary", "highway.secondary", "highway.tertiary",
			"highway.unclassified", "highway.living_street", "highway.service"}) {
			assertTrue(rule.isEligible(link(1, cls)), cls + " is left to the other rules");
		}
	}

	@Test
	void prefixAndCaseDoNotMatter() {
		KerbParkingEligibility rule = new KerbParkingEligibility.OsmHighwayType();
		assertFalse(rule.isEligible(link(2, "motorway")), "bare class without prefix");
		assertFalse(rule.isEligible(link(2, "highway:trunk")), "colon prefix");
		assertFalse(rule.isEligible(link(2, " Highway.Motorway_Link ")), "case and whitespace");
	}

	@Test
	void linksWithoutTheAttributeAreLeftToOtherRules() {
		Link plain = link(1, null);
		assertTrue(new KerbParkingEligibility.OsmHighwayType().isEligible(plain),
			"no type attribute means this rule has nothing to say, so it does not veto");
	}

	@Test
	void attributeNameAndExclusionsAreConfigurable() {
		KerbParkingEligibility rule = new KerbParkingEligibility.OsmHighwayType("osm_highway", Set.of("residential"), false);
		Link l = link(1, null);
		l.getAttributes().putAttribute("osm_highway", "highway.residential");
		assertFalse(rule.isEligible(l), "custom attribute and custom class");

		Link motorwayLink = link(2, null);
		motorwayLink.getAttributes().putAttribute("osm_highway", "highway.motorway_link");
		assertTrue(rule.isEligible(motorwayLink), "link roads are not excluded when excludeLinkRoads is false and the class is not listed");
	}

	@Test
	void allOfRequiresEveryRule() {
		KerbParkingEligibility both = KerbParkingEligibility.allOf(
			new KerbParkingEligibility.OsmHighwayType(), new KerbParkingEligibility.MinimumLanes(2.0));
		assertTrue(both.isEligible(link(2, "highway.residential")));
		assertFalse(both.isEligible(link(1, "highway.residential")), "fails the lane rule");
		assertFalse(both.isEligible(link(3, "highway.motorway")), "fails the class rule despite three lanes");
	}

	@Test
	void defaultsExcludeMotorwaysAndAdmitEverySingleLaneStreet() {
		KerbParkingEligibility rule = KerbParkingEligibility.defaults();
		assertTrue(rule.isEligible(link(1, "highway.residential")));
		assertTrue(rule.isEligible(link(1, null)), "networks without the type attribute fall back to the lane rule");
		assertFalse(rule.isEligible(link(3, "highway.motorway")));
		assertFalse(rule.isEligible(link(2, "highway.trunk_link")));
		assertFalse(KerbParkingEligibility.defaults(2.0).isEligible(link(1, "highway.residential")),
			"an explicit lane threshold still applies within the default composition");
	}

	private static Link link(double lanes, String type) {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.create("a", Node.class), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(network, Id.create("b", Node.class), new Coord(100, 0));
		Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId("l"), a, b, 100, 10, 1800, lanes);
		if (type != null) {
			link.getAttributes().putAttribute("type", type);
		}
		return link;
	}
}
