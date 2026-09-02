package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class ZeroParkingCapacityInitializer implements ParkingCapacityInitializer {
	private final Network network;
	private final Config config;

	@Inject
	ZeroParkingCapacityInitializer(Network network, Config config) {
		this.network = network;
		this.config = config;
	}

	@Override
	public Map<Id<Link>, ParkingInitialCapacity> initialize() {
		Map<Id<Link>, ParkingInitialCapacity> res = new HashMap<>(network.getLinks().size());
		for (Link link : network.getLinks().values()) {
			int onStreet = (int) Optional.ofNullable(link.getAttributes().getAttribute(ParkingUtils.LINK_ON_STREET_SPOTS)).orElse(0);
			int offStreet = (int) Optional.ofNullable(link.getAttributes().getAttribute(ParkingUtils.LINK_OFF_STREET_SPOTS)).orElse(0);

			int newCapacity = (int) Math.ceil((onStreet + offStreet) * config.qsim().getStorageCapFactor());
			res.put(link.getId(), new ParkingInitialCapacity(newCapacity, 0));
		}
		return res;
	}

	/**
	 * Splits the link attributes into on-street and off-street pools. The pools always sum to exactly the value that
	 * {@link #initialize()} reports, so the scaled total is computed first and the off-street pool takes the remainder.
	 * Scaling each pool separately and rounding both up could otherwise produce a total one larger than before.
	 */
	@Override
	public Map<Id<Link>, ParkingInitialPools> initializePools() {
		Map<Id<Link>, ParkingInitialPools> res = new HashMap<>(network.getLinks().size());
		double factor = config.qsim().getStorageCapFactor();
		for (Link link : network.getLinks().values()) {
			int onStreet = (int) Optional.ofNullable(link.getAttributes().getAttribute(ParkingUtils.LINK_ON_STREET_SPOTS)).orElse(0);
			int offStreet = (int) Optional.ofNullable(link.getAttributes().getAttribute(ParkingUtils.LINK_OFF_STREET_SPOTS)).orElse(0);
			int total = (int) Math.ceil((onStreet + offStreet) * factor);
			int scaledOnStreet = Math.min(total, (int) Math.ceil(onStreet * factor));
			res.put(link.getId(), new ParkingInitialPools(scaledOnStreet, total - scaledOnStreet, 0));
		}
		return res;
	}
}
