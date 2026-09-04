package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.parking.parkingsearchparameterization.ParkingCapacityInitializer.ParkingInitialPools;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_OFF_STREET_SPOTS;
import static org.matsim.contrib.parking.parkingsearchparameterization.ParkingUtils.LINK_ON_STREET_SPOTS;

/**
 * Kerb supply from attributes where present, from geometry and an eligibility rule where not.
 */
class DerivedParkingCapacityInitializerTest {

	private static final double LENGTH = 100.0;
	private static final double BAY = 6.0; // floor(100 / 6) = 16

	@Test
	void attributeWinsOverGeometry() {
		Fixture f = new Fixture(2.0);
		f.link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, 3);

		assertEquals(new ParkingInitialPools(3, 0, 0), f.derive(new KerbParkingEligibility.MinimumLanes()).get(f.id),
			"an explicit attribute is taken at face value, not derived");
		assertTrue(f.initializer(new KerbParkingEligibility.MinimumLanes()).isOnStreetFromAttribute(f.link));
	}

	@Test
	void eligibleLinkWithoutAttributeGetsFloorOfLengthOverBayLength() {
		Fixture f = new Fixture(2.0);

		assertEquals(new ParkingInitialPools(16, 0, 0), f.derive(new KerbParkingEligibility.MinimumLanes()).get(f.id));
		assertFalse(f.initializer(new KerbParkingEligibility.MinimumLanes()).isOnStreetFromAttribute(f.link));
	}

	@Test
	void defaultRuleAcceptsSingleLaneLinks() {
		Fixture f = new Fixture(1.0);

		assertEquals(new ParkingInitialPools(16, 0, 0), f.derive(new KerbParkingEligibility.MinimumLanes()).get(f.id),
			"the default is one lane: every car link may carry kerb parking");
	}

	@Test
	void ineligibleLinkWithoutAttributeGetsNoKerbParking() {
		Fixture f = new Fixture(1.0);

		assertEquals(new ParkingInitialPools(0, 0, 0), f.derive(new KerbParkingEligibility.MinimumLanes(2.0)).get(f.id),
			"a single-lane link is not eligible under an explicit two-lane rule");
	}

	@Test
	void anExplicitZeroAttributeIsHonouredEvenWhenGeometryWouldGiveSpaces() {
		Fixture f = new Fixture(2.0);
		f.link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, 0);

		assertEquals(new ParkingInitialPools(0, 0, 0), f.derive(new KerbParkingEligibility.MinimumLanes()).get(f.id),
			"zero means no kerb parking, not 'unknown, please derive'");
	}

	@Test
	void offStreetComesFromTheAttributeOrIsZero() {
		Fixture withAttr = new Fixture(2.0);
		withAttr.link.getAttributes().putAttribute(LINK_OFF_STREET_SPOTS, 40);
		assertEquals(new ParkingInitialPools(16, 40, 0), withAttr.derive(new KerbParkingEligibility.MinimumLanes()).get(withAttr.id));

		Fixture without = new Fixture(2.0);
		assertEquals(0, without.derive(new KerbParkingEligibility.MinimumLanes()).get(without.id).offStreetCapacity());
	}

	@Test
	void poolsSumToInitializeUnderScaling() {
		for (double factor : new double[]{1.0, 0.5, 0.3, 0.1}) {
			Fixture f = new Fixture(2.0, factor);
			f.link.getAttributes().putAttribute(LINK_OFF_STREET_SPOTS, 7);
			DerivedParkingCapacityInitializer initializer = f.initializer(new KerbParkingEligibility.MinimumLanes());

			int legacy = initializer.initialize().get(f.id).capacity();
			ParkingInitialPools pools = initializer.initializePools().get(f.id);
			assertEquals(legacy, pools.capacity(), "factor " + factor + ": pools must sum to initialize()");
			assertEquals((int) Math.ceil((16 + 7) * factor), legacy, "factor " + factor + ": total rounded up once");
		}
	}

	@Test
	void minimumLanesIsConfigurable() {
		Fixture f = new Fixture(1.0);

		assertEquals(0, f.derive(new KerbParkingEligibility.MinimumLanes(2.0)).get(f.id).onStreetCapacity());
		assertEquals(16, f.derive(new KerbParkingEligibility.MinimumLanes(1.0)).get(f.id).onStreetCapacity(),
			"lowering the threshold to one lane makes the same link eligible");
	}

	@Test
	void linkAttributeEligibilityAcceptsBooleanOrString() {
		Fixture bool = new Fixture(1.0);
		bool.link.getAttributes().putAttribute(KerbParkingEligibility.LinkAttribute.DEFAULT_ATTRIBUTE, true);
		assertEquals(16, bool.derive(new KerbParkingEligibility.LinkAttribute()).get(bool.id).onStreetCapacity());

		Fixture str = new Fixture(1.0);
		str.link.getAttributes().putAttribute(KerbParkingEligibility.LinkAttribute.DEFAULT_ATTRIBUTE, "true");
		assertEquals(16, str.derive(new KerbParkingEligibility.LinkAttribute()).get(str.id).onStreetCapacity());

		Fixture absent = new Fixture(2.0);
		assertEquals(0, absent.derive(new KerbParkingEligibility.LinkAttribute()).get(absent.id).onStreetCapacity(),
			"without the attribute nothing is eligible, regardless of lanes");
	}

	@Test
	void linksWithoutCarModeGetNoDerivedKerbParking() {
		Fixture f = new Fixture(2.0);
		f.link.setAllowedModes(java.util.Set.of("pt"));

		assertEquals(0, f.derive(new KerbParkingEligibility.MinimumLanes()).get(f.id).onStreetCapacity(),
			"a two-lane pt-only link is not kerb parking for cars, whatever the lane rule says");

		f.link.getAttributes().putAttribute(LINK_ON_STREET_SPOTS, 4);
		assertEquals(4, f.derive(new KerbParkingEligibility.MinimumLanes()).get(f.id).onStreetCapacity(),
			"an explicit attribute is still honoured; the car-mode check only gates derivation");
	}

	@Test
	void bayLengthMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingSupplyParams(0.0));
		assertThrows(IllegalArgumentException.class, () -> new KerbParkingSupplyParams(-1.0));
	}

	private static final class Fixture {
		final Id<Link> id = Id.createLinkId("l");
		final Config config;
		final Network network;
		final Link link;

		Fixture(double lanes) {
			this(lanes, 1.0);
		}

		Fixture(double lanes, double storageCapFactor) {
			config = ConfigUtils.createConfig();
			config.qsim().setStorageCapFactor(storageCapFactor);
			Scenario scenario = ScenarioUtils.createScenario(config);
			network = scenario.getNetwork();
			Node from = NetworkUtils.createAndAddNode(network, Id.create("1", Node.class), new Coord(0, 0));
			Node to = NetworkUtils.createAndAddNode(network, Id.create("2", Node.class), new Coord(LENGTH, 0));
			link = NetworkUtils.createAndAddLink(network, id, from, to, LENGTH, 10.0, 1800.0, lanes);
		}

		DerivedParkingCapacityInitializer initializer(KerbParkingEligibility eligibility) {
			return new DerivedParkingCapacityInitializer(network, config, eligibility, new KerbParkingSupplyParams(BAY));
		}

		Map<Id<Link>, ParkingInitialPools> derive(KerbParkingEligibility eligibility) {
			return initializer(eligibility).initializePools();
		}
	}
}
