package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;

import java.util.HashMap;
import java.util.Map;

/**
 * Parking capacity from link attributes where they exist, derived from link geometry where they do not.
 * <p>
 * A link that carries {@code onstreet_spots} is taken at face value, exactly as {@link ZeroParkingCapacityInitializer}
 * does. A link without it gets {@code floor(length / bayLength)} kerb spaces if {@link KerbParkingEligibility} says
 * it may have kerb parking, and none otherwise. {@code offstreet_spots} is read from the attribute if present and is
 * zero otherwise; in the kerb-first model the off-street pool is unbounded anyway, so this number only matters when a
 * capacity constraint is applied to it.
 * <p>
 * Scaling by {@code storageCapFactor} follows the same rule as {@link ZeroParkingCapacityInitializer}: the total is
 * rounded up once and the off-street pool takes the remainder, so the pools always sum to {@link #initialize()}.
 */
public class DerivedParkingCapacityInitializer implements ParkingCapacityInitializer {
	private final Network network;
	private final Config config;
	private final KerbParkingEligibility eligibility;
	private final KerbParkingSupplyParams params;

	@Inject
	public DerivedParkingCapacityInitializer(Network network, Config config, KerbParkingEligibility eligibility,
			KerbParkingSupplyParams params) {
		this.network = network;
		this.config = config;
		this.eligibility = eligibility;
		this.params = params;
	}

	@Override
	public Map<Id<Link>, ParkingInitialCapacity> initialize() {
		Map<Id<Link>, ParkingInitialPools> pools = initializePools();
		Map<Id<Link>, ParkingInitialCapacity> res = new HashMap<>(pools.size());
		pools.forEach((id, p) -> res.put(id, new ParkingInitialCapacity(p.capacity(), p.occupancy())));
		return res;
	}

	@Override
	public Map<Id<Link>, ParkingInitialPools> initializePools() {
		Map<Id<Link>, ParkingInitialPools> res = new HashMap<>(network.getLinks().size());
		double factor = config.qsim().getStorageCapFactor();
		for (Link link : network.getLinks().values()) {
			int onStreet = unscaledOnStreet(link);
			int offStreet = attribute(link, ParkingUtils.LINK_OFF_STREET_SPOTS);
			int total = (int) Math.ceil((onStreet + offStreet) * factor);
			int scaledOnStreet = Math.min(total, (int) Math.ceil(onStreet * factor));
			res.put(link.getId(), new ParkingInitialPools(scaledOnStreet, total - scaledOnStreet, 0));
		}
		return res;
	}

	/** Whether the link's kerb supply came from an attribute (true) or was derived from geometry (false). */
	public boolean isOnStreetFromAttribute(Link link) {
		return link.getAttributes().getAttribute(ParkingUtils.LINK_ON_STREET_SPOTS) != null;
	}

	private int unscaledOnStreet(Link link) {
		if (isOnStreetFromAttribute(link)) {
			return attribute(link, ParkingUtils.LINK_ON_STREET_SPOTS);
		}
		if (!eligibility.isEligible(link)) {
			return 0;
		}
		return (int) Math.floor(link.getLength() / params.bayLengthMetres());
	}

	private static int attribute(Link link, String name) {
		Object value = link.getAttributes().getAttribute(name);
		return value == null ? 0 : ((Number) value).intValue();
	}
}
