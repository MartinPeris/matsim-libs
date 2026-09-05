package org.matsim.contrib.parking.parkingsearchparameterization;

import org.matsim.api.core.v01.network.Link;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a link may carry on-street (kerb) parking when the network does not say so explicitly.
 * <p>
 * This is deliberately pluggable. The obvious rule, "two or more lanes", measures how a network was coded rather
 * than how wide its streets are: in the bundled scenarios it selects 0% of {@code chessboard}, 6% of
 * {@code kelheim} and 100% of {@code siouxfalls-2014}. Whichever rule is used must be reported with the results.
 * <p>
 * {@link #defaults()} is the recommended composition: {@link OsmHighwayType} (no kerb parking on motorways, trunk
 * roads and their link roads) and {@link MinimumLanes} (one lane, i.e. every remaining car link).
 */
public interface KerbParkingEligibility {

	boolean isEligible(Link link);

	/**
	 * Eligible only if every rule agrees.
	 */
	static KerbParkingEligibility allOf(KerbParkingEligibility... rules) {
		List<KerbParkingEligibility> list = List.of(rules);
		return link -> {
			for (KerbParkingEligibility rule : list) {
				if (!rule.isEligible(link)) {
					return false;
				}
			}
			return true;
		};
	}

	/**
	 * The recommended default: {@link OsmHighwayType} with its default exclusions, then {@link MinimumLanes} with
	 * its default of one lane.
	 */
	static KerbParkingEligibility defaults() {
		return defaults(MinimumLanes.DEFAULT_MINIMUM_LANES);
	}

	/**
	 * {@link #defaults()} with an explicit lane threshold.
	 */
	static KerbParkingEligibility defaults(double minimumLanes) {
		return allOf(new OsmHighwayType(), new MinimumLanes(minimumLanes));
	}

	/**
	 * Kerb parking on links with at least {@code minimumLanes} lanes.
	 * <p>
	 * The default is one lane, i.e. every car link. The earlier default of two assumed a single-lane street cannot
	 * give up a lane to parked vehicles; validation against hand-counted kerb supply in Berlin (Bischoff &amp; Nagel
	 * 2017) showed that rule under-derives supply about 14-fold, because the streets where people actually park
	 * are coded single-lane in OSM-derived networks, while a one-lane rule lands within 0.6 to 1.2 times the
	 * counts. Cars squeeze past parked vehicles; the capacity consequence belongs to the lane-consumption model, not
	 * to eligibility. Pass {@code 2.0} explicitly to reproduce the old behaviour.
	 * <p>
	 * On its own this rule admits motorways; combine it with {@link OsmHighwayType}, as {@link #defaults()} does.
	 */
	final class MinimumLanes implements KerbParkingEligibility {
		public static final double DEFAULT_MINIMUM_LANES = 1.0;

		private final double minimumLanes;

		public MinimumLanes() {
			this(DEFAULT_MINIMUM_LANES);
		}

		public MinimumLanes(double minimumLanes) {
			this.minimumLanes = minimumLanes;
		}

		@Override
		public boolean isEligible(Link link) {
			return link.getNumberOfLanes() >= minimumLanes;
		}
	}

	/**
	 * No kerb parking on road classes that never have it, read from the OSM {@code highway} class that OSM-derived
	 * networks (pt2matsim, the osm contrib, Open Berlin) carry as the link attribute {@code type}, e.g.
	 * {@code highway.motorway}. Values are matched case-insensitively with or without the {@code highway.} or
	 * {@code highway:} prefix.
	 * <p>
	 * Default exclusions: {@code motorway}, {@code trunk}, and every {@code *_link} class (motorway, trunk, primary,
	 * secondary and tertiary link roads). On Open Berlin v6.4 these classes would otherwise receive 440,154 of
	 * 8,136,734 derived kerb spaces (5.4%) under a one-lane rule, none of it real. Links without the attribute are
	 * left to the other rules, so this is safe to compose onto networks that lack the attribute.
	 */
	final class OsmHighwayType implements KerbParkingEligibility {
		public static final String DEFAULT_ATTRIBUTE = "type";
		public static final Set<String> DEFAULT_EXCLUDED_CLASSES = Set.of("motorway", "trunk");

		private final String attribute;
		private final Set<String> excludedClasses;
		private final boolean excludeLinkRoads;

		public OsmHighwayType() {
			this(DEFAULT_ATTRIBUTE, DEFAULT_EXCLUDED_CLASSES, true);
		}

		/**
		 * @param attribute        link attribute holding the OSM highway class
		 * @param excludedClasses  classes that never have kerb parking, without the {@code highway.} prefix
		 * @param excludeLinkRoads whether every {@code *_link} class is excluded as well
		 */
		public OsmHighwayType(String attribute, Set<String> excludedClasses, boolean excludeLinkRoads) {
			this.attribute = attribute;
			this.excludedClasses = excludedClasses.stream().map(OsmHighwayType::normalise).collect(java.util.stream.Collectors.toUnmodifiableSet());
			this.excludeLinkRoads = excludeLinkRoads;
		}

		@Override
		public boolean isEligible(Link link) {
			Object value = link.getAttributes().getAttribute(attribute);
			if (value == null) {
				return true;
			}
			String cls = normalise(value.toString());
			if (excludedClasses.contains(cls)) {
				return false;
			}
			return !(excludeLinkRoads && cls.endsWith("_link"));
		}

		/** Lower-case, trimmed, without a leading {@code highway.} or {@code highway:}. */
		static String normalise(String raw) {
			String s = raw.trim().toLowerCase(Locale.ROOT);
			for (String prefix : Arrays.asList("highway.", "highway:")) {
				if (s.startsWith(prefix)) {
					return s.substring(prefix.length());
				}
			}
			return s;
		}
	}

	/**
	 * Kerb parking wherever the link carries a boolean attribute set to {@code true}. Independent of network coding,
	 * at the cost of requiring the attribute to be supplied.
	 */
	final class LinkAttribute implements KerbParkingEligibility {
		public static final String DEFAULT_ATTRIBUTE = "kerbParking";

		private final String attribute;

		public LinkAttribute() {
			this(DEFAULT_ATTRIBUTE);
		}

		public LinkAttribute(String attribute) {
			this.attribute = attribute;
		}

		@Override
		public boolean isEligible(Link link) {
			Object value = link.getAttributes().getAttribute(attribute);
			return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(value.toString());
		}
	}
}
