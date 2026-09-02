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
	 * Kerb parking on links with at least {@code minimumLanes} lanes. The default of two reflects the working
	 * assumption that a single-lane street cannot give up a lane to parked vehicles; see the interface note on how
	 * strongly this depends on network coding conventions.
	 */
	final class MinimumLanes implements KerbParkingEligibility {
		public static final double DEFAULT_MINIMUM_LANES = 2.0;

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
