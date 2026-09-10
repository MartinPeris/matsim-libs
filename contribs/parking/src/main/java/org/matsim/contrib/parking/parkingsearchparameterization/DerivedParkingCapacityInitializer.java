package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parking capacity from link attributes where they exist, derived from link geometry where they do not.
 * <p>
 * A link that carries {@code onstreet_spots} is taken at face value, exactly as {@link ZeroParkingCapacityInitializer}
 * does. A link without it gets {@code floor(length / bayLength)} kerb spaces if it allows the car mode and
 * {@link KerbParkingEligibility} says it may have kerb parking, and none otherwise. {@code offstreet_spots} is read from the attribute if present and is
 * zero otherwise; in the kerb-first model the off-street pool is unbounded anyway, so this number only matters when a
 * capacity constraint is applied to it.
 * <h2>Scaling by storageCapFactor</h2>
 * A sampled population gets a sampled parking supply. Unlike {@link ZeroParkingCapacityInitializer}, which rounds each
 * link up on its own, this class carries the remainder from link to link: the network keeps the correct total and the
 * whole numbers are distributed over the links. Rounding every link up instead inflates a small sample badly, because
 * every link with any supply at all keeps at least one space however small its share: at Berlin's 1% sample that turns
 * an exact 81,367 kerb spaces into 233,607, which understates how much off-street parking the sample needs.
 * <p>
 * The links are visited in id order, so a scenario always produces the same answer. Which particular links receive the
 * carried space is nevertheless arbitrary. Only the network total is meaningful under scaling; a single link's 1 rather
 * than 0 in {@code parking_pools_per_link.csv} is an artefact of the traversal order, not a statement about that street.
 * Unscaled runs ({@code storageCapFactor} = 1) are unaffected, every link getting exactly its derived supply.
 * <p>
 * On-street and off-street are carried separately, so each pool's network total is right. The pools still sum to
 * {@link #initialize()} per link, because {@code initialize()} is derived from them.
 */
public class DerivedParkingCapacityInitializer implements ParkingCapacityInitializer {
	private static final Logger log = LogManager.getLogger(DerivedParkingCapacityInitializer.class);

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
		double factor = config.qsim().getStorageCapFactor();
		List<? extends Link> links = network.getLinks().values().stream()
			.sorted(Comparator.comparing(link -> link.getId().toString()))
			.toList();

		Map<Id<Link>, ParkingInitialPools> res = new HashMap<>(links.size());
		RemainderCarry onStreetCarry = new RemainderCarry(factor);
		RemainderCarry offStreetCarry = new RemainderCarry(factor);
		for (Link link : links) {
			int onStreet = unscaledOnStreet(link);
			int offStreet = attribute(link, ParkingUtils.LINK_OFF_STREET_SPOTS);
			res.put(link.getId(), new ParkingInitialPools(onStreetCarry.take(onStreet), offStreetCarry.take(offStreet), 0));
		}
		if (factor != 1.0) {
			log.info("Parking supply scaled by storageCapFactor={}: on-street {} -> {} spaces, off-street {} -> {} spaces "
					+ "(remainder carried across links in id order; per-link numbers are not individually meaningful)",
				factor, onStreetCarry.unscaledTotal(), onStreetCarry.allocated(),
				offStreetCarry.unscaledTotal(), offStreetCarry.allocated());
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
		// Only links a car can drive on can have derived kerb parking. Pt-only links in a merged network often
		// carry two or more lanes and would otherwise be handed kerb spaces no car can ever reach.
		if (!ParkingUtils.kerbParkingPermitted(link, eligibility)) {
			return 0;
		}
		return (int) Math.floor(link.getLength() / params.bayLengthMetres());
	}

	private static int attribute(Link link, String name) {
		Object value = link.getAttributes().getAttribute(name);
		return value == null ? 0 : ((Number) value).intValue();
	}

	/**
	 * Hands out whole spaces so that the running total never drifts from the exact scaled supply by a whole space.
	 * <p>
	 * Each link is given {@code floor(cumulativeUnscaled * factor)} minus what has already been handed out, so the
	 * fractional parts accumulate and are paid out as soon as they make up a space. A link with no unscaled supply
	 * never receives one, whatever the accumulated remainder: ineligible links stay at zero.
	 */
	private static final class RemainderCarry {
		/**
		 * Guards a product that should be a whole number but lands an ulp below one. The product is recomputed from
		 * an exact {@code long} rather than accumulated, so its error stays at one ulp however many links there are:
		 * about 3e-11 at Berlin's ~2e5 spaces, far inside this margin. Nothing is promoted that should not be, since
		 * a factor such as 0.01 or 0.1 never puts a true value within 1e-9 below a whole number.
		 */
		private static final double EPSILON = 1e-9;

		private final double factor;
		private long cumulativeUnscaled;
		private long allocated;

		RemainderCarry(double factor) {
			this.factor = factor;
		}

		int take(int unscaled) {
			cumulativeUnscaled += unscaled;
			long target = (long) Math.floor(cumulativeUnscaled * factor + EPSILON);
			int give = (int) (target - allocated);
			allocated = target;
			return give;
		}

		long unscaledTotal() {
			return cumulativeUnscaled;
		}

		long allocated() {
			return allocated;
		}
	}
}
