package org.matsim.contrib.parking.parkingsearchparameterization;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;

import java.util.Map;

public interface ParkingCapacityInitializer {
	Map<Id<Link>, ParkingInitialCapacity> initialize();

	/**
	 * Initial capacity and occupancy per link, with on-street and off-street parking kept as separate pools.
	 * <p>
	 * The default derives the pools from {@link #initialize()} by treating the whole legacy capacity as on-street, so
	 * existing implementations keep working unchanged. Implementations that know the split override this.
	 * <p>
	 * Invariant: {@code onStreetCapacity() + offStreetCapacity()} equals {@link ParkingInitialCapacity#capacity()} for
	 * the same link, so code reading the summed capacity sees the same numbers whichever method it calls.
	 */
	default Map<Id<Link>, ParkingInitialPools> initializePools() {
		Map<Id<Link>, ParkingInitialCapacity> summed = initialize();
		Map<Id<Link>, ParkingInitialPools> pools = new java.util.HashMap<>(summed.size());
		for (Map.Entry<Id<Link>, ParkingInitialCapacity> e : summed.entrySet()) {
			pools.put(e.getKey(), new ParkingInitialPools(e.getValue().capacity(), 0, e.getValue().occupancy()));
		}
		return pools;
	}

	/**
	 * On-street (kerb) and off-street capacity as separate pools, plus the initial occupancy across both.
	 */
	record ParkingInitialPools(int onStreetCapacity, int offStreetCapacity, int occupancy) {
		public int capacity() {
			return onStreetCapacity + offStreetCapacity;
		}
	}

	record ParkingInitialCapacity(int capacity, int occupancy) {
	}
}
