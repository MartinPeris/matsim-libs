package org.matsim.contrib.parking.parkingsearchparameterization;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;

public class ParkingUtils {
	public static final String LINK_ON_STREET_SPOTS = "onstreet_spots";
	public static final String LINK_OFF_STREET_SPOTS = "offstreet_spots";
	public static final String PARKING_INITIAL_FILE = "parking_initial_occupancy.csv";

	/**
	 * Whether kerb parking is conceivable on this link at all, regardless of how many spaces it ends up with.
	 * <p>
	 * A link carrying an explicit {@link #LINK_ON_STREET_SPOTS} attribute is a parking place by declaration, even
	 * when the declared number is zero: an explicit zero means "no kerb parking here", which is a statement about
	 * supply, not about whether the street is a place one could park. Otherwise a link is a parking place if a car
	 * can drive on it and the eligibility rule allows it.
	 * <p>
	 * This is the same test {@link DerivedParkingCapacityInitializer} applies when deriving supply, kept here so the
	 * observer can tell "no kerb space left" from "not a kerb parking place" without the two definitions drifting
	 * apart. The distinction matters because activity coordinates snap to the nearest car link, so a motorway can
	 * receive arrivals it would never receive in reality; counting those as unmet off-street demand overstates the
	 * off-street supply a city needs.
	 */
	public static boolean kerbParkingPermitted(Link link, KerbParkingEligibility eligibility) {
		if (link.getAttributes().getAttribute(LINK_ON_STREET_SPOTS) != null) {
			return true;
		}
		return link.getAllowedModes().contains(TransportMode.car) && eligibility.isEligible(link);
	}
}
