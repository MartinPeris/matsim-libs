package org.matsim.contrib.parking.parkingsearchparameterization;

import org.matsim.api.core.v01.network.Link;

/**
 * Decides whether a link may carry on-street (kerb) parking when the network does not say so explicitly.
 * <p>
 * This is deliberately pluggable. The obvious rule, "two or more lanes", measures how a network was coded rather
 * than how wide its streets are: in the bundled scenarios it selects 0% of {@code chessboard}, 6% of
 * {@code kelheim} and 100% of {@code siouxfalls-2014}. Whichever rule is used must be reported with the results.
 */
public interface KerbParkingEligibility {

	boolean isEligible(Link link);

	/**
	 * Kerb parking on links with at least {@code minimumLanes} lanes.
	 * <p>
	 * The default is one lane, i.e. every car link. The earlier default of two assumed a single-lane street cannot
	 * give up a lane to parked vehicles; validation against hand-counted kerb supply in Berlin (Bischoff &amp; Nagel
	 * 2017) showed that rule under-derives supply about 14-fold, because the streets where people actually park
	 * are coded single-lane in OSM-derived networks, while a one-lane rule lands within 0.6 to 1.2 times the
	 * counts. Cars squeeze past parked vehicles; the capacity consequence belongs to the lane-consumption model, not
	 * to eligibility. Pass {@code 2.0} explicitly to reproduce the old behaviour.
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
