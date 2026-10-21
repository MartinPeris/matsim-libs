package org.matsim.contrib.parking.parkingsearchparameterization;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.contrib.parking.parkingsearchparameterization.KerbParkingCapacityEffectParams.EffectMode;
import org.matsim.core.network.NetworkUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runner's command line: the old positional arguments still work, the new options are optional.
 */
class RunKerbFirstParkingOptionsTest {

	@Test
	void positionalArgumentsAloneBehaveAsBefore() {
		RunKerbFirstParking.Options o = RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "0", "1"});
		assertEquals("c.xml", o.configFile());
		assertEquals("out", o.outputDirectory());
		assertEquals(0, o.iterations());
		assertEquals(1.0, o.minimumLanes());
		assertEquals(KerbParkingSupplyParams.DEFAULT_BAY_LENGTH_METRES, o.bayLength());
		assertEquals(EffectMode.off, o.effect().mode(), "the effect is off unless asked for");
		assertTrue(o.bannedClasses().isEmpty());

		RunKerbFirstParking.Options minimal = RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out"});
		assertEquals(0, minimal.iterations());
		assertEquals(KerbParkingEligibility.MinimumLanes.DEFAULT_MINIMUM_LANES, minimal.minimumLanes());
	}

	@Test
	void optionsSetTheEffectAndTheBan() {
		RunKerbFirstParking.Options o = RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "0", "1", "6",
			"--effectMode=cell", "--cellSize=250", "--fMin=0.4", "--w=0.6", "--sMin=0.75", "--v=0.25",
			"--updateInterval=120", "--flowTolerance=0.05", "--banKerbParkingOn=primary, highway.secondary"});
		assertEquals(new KerbParkingCapacityEffectParams(EffectMode.cell, 250, 0.4, 0.6, 0.75, 0.25, 120, 0.05), o.effect());
		assertEquals(Set.of("primary", "secondary"), o.bannedClasses());
		assertEquals(6.0, o.bayLength());
		assertTrue(o.describe().contains("banKerbParkingOn=primary,secondary"), o.describe());
		assertTrue(o.describe().contains("effectMode=cell"), o.describe());

		RunKerbFirstParking.Options anywhere = RunKerbFirstParking.Options.parse(new String[]{"--effectMode=link", "c.xml", "out"});
		assertEquals(EffectMode.link, anywhere.effect().mode());
		assertEquals("c.xml", anywhere.configFile());
	}

	@Test
	void badCommandLinesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> RunKerbFirstParking.Options.parse(new String[]{"c.xml"}));
		assertThrows(IllegalArgumentException.class, () -> RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "--nonsense=1"}));
		assertThrows(IllegalArgumentException.class, () -> RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "--effectMode"}));
		assertThrows(IllegalArgumentException.class, () -> RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "--effectMode=everywhere"}));
		assertThrows(IllegalArgumentException.class, () -> RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "--fMin=2"}));
		IllegalArgumentException badMode = assertThrows(IllegalArgumentException.class,
			() -> RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "--effectMode=everywhere"}));
		assertTrue(badMode.getMessage().contains("everywhere") && badMode.getMessage().contains(RunKerbFirstParking.USAGE), badMode.getMessage());
	}

	@Test
	void effectOptionsWithTheEffectOffAreWarnedAbout() {
		assertEquals(null, RunKerbFirstParking.ignoredEffectOptionsWarning(EffectMode.off, List.of()));
		assertEquals(null, RunKerbFirstParking.ignoredEffectOptionsWarning(EffectMode.cell, List.of("--cellSize")));
		String one = RunKerbFirstParking.ignoredEffectOptionsWarning(EffectMode.off, List.of("--cellSize"));
		assertTrue(one.startsWith("--cellSize given") && one.contains("effect is off"), one);
		String two = RunKerbFirstParking.ignoredEffectOptionsWarning(EffectMode.off, List.of("--w", "--v"));
		assertTrue(two.startsWith("--w, --v given") && two.contains("they have no effect"), two);
		// parsing still succeeds: the warning is a warning
		RunKerbFirstParking.Options o = RunKerbFirstParking.Options.parse(new String[]{"c.xml", "out", "--cellSize=250"});
		assertEquals(EffectMode.off, o.effect().mode());
		assertEquals(Set.of("cellSize", "fMin", "w", "sMin", "v", "updateInterval", "flowTolerance"), RunKerbFirstParking.EFFECT_PARAMETER_OPTIONS);
	}

	@Test
	void bannedClassesAddToTheDefaultExclusions() {
		Network network = NetworkUtils.createNetwork();
		Node a = NetworkUtils.createAndAddNode(network, Id.createNodeId("a"), new Coord(0, 0));
		Node b = NetworkUtils.createAndAddNode(network, Id.createNodeId("b"), new Coord(100, 0));
		Link primary = typed(network, "p", a, b, "highway.primary");
		Link secondary = typed(network, "s", a, b, "highway.secondary");
		Link residential = typed(network, "r", a, b, "highway.residential");
		Link motorway = typed(network, "m", a, b, "highway.motorway");
		Link primaryLink = typed(network, "pl", a, b, "highway.primary_link");

		KerbParkingEligibility defaults = RunKerbFirstParking.eligibility(1.0, Set.of());
		assertTrue(defaults.isEligible(primary));
		assertTrue(defaults.isEligible(secondary));
		assertFalse(defaults.isEligible(motorway));
		assertFalse(defaults.isEligible(primaryLink));

		KerbParkingEligibility ban = RunKerbFirstParking.eligibility(1.0, Set.of("primary", "secondary"));
		assertFalse(ban.isEligible(primary));
		assertFalse(ban.isEligible(secondary));
		assertTrue(ban.isEligible(residential));
		assertFalse(ban.isEligible(motorway), "the defaults still apply");
		assertFalse(ban.isEligible(primaryLink), "link roads still excluded");
	}

	private static Link typed(Network network, String id, Node from, Node to, String type) {
		Link link = NetworkUtils.createAndAddLink(network, Id.createLinkId(id), from, to, 100, 10, 1000, 1);
		link.getAttributes().putAttribute("type", type);
		return link;
	}
}
