package org.matsim.contrib.drt.extension.waittime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneImpl;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.extension.waittime.DrtRideTimeSkim.Source;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerDroppedOffEvent;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerWaitingEvent;

/**
 * @author Monash Healthy Active Cities
 */
class ZonalDrtRideTimeSkimTest {

	private static final String MODE = "drt";
	private static final Id<Link> LINK_IN_ZONE_A = Id.createLinkId("a1");
	private static final Id<Link> LINK_IN_ZONE_B = Id.createLinkId("b1");
	private static final Id<Link> LINK_NOWHERE = Id.createLinkId("z1");
	private static final Id<DvrpVehicle> VEHICLE = Id.create("veh", DvrpVehicle.class);
	private static final Id<Zone> ZONE_A = Id.create("A", Zone.class);
	private static final Id<Zone> ZONE_B = Id.create("B", Zone.class);

	@Test
	void theObservedFactorIsTheRideOverTheUnsharedRide() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		// quoted 200 s unshared, actually took 300 s
		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 200, 300);
		f.skim.update();

		DrtRideTimeSkim.Lookup lookup = f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0);
		assertThat(lookup.factor()).isEqualTo(1.5);
		assertThat(lookup.source()).isEqualTo(Source.ZONE_PAIR_TIME_BIN);
	}

	/**
	 * The direction of travel is part of the key. A pair observed one way says nothing about the
	 * other way, which may cross a different bottleneck.
	 */
	@Test
	void thePairIsOrderedSoTheReverseDirectionIsADifferentCell() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 200, 400);
		f.skim.update();

		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).source()).isEqualTo(Source.ZONE_PAIR_TIME_BIN);
		// the reverse pair was never travelled, so it falls through rather than borrowing the value
		assertThat(f.skim.lookup(LINK_IN_ZONE_B, LINK_IN_ZONE_A, 0).source()).isEqualTo(Source.GLOBAL_TIME_BIN);
	}

	/**
	 * A same-zone ride is an ordinary key, not an edge case: short internal trips are exactly what a
	 * DRT feeder does. The stop-keyed ancestor of this design threw on a same-key query.
	 */
	@Test
	void aRideThatStartsAndEndsInOneZoneIsAnOrdinaryKey() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_A, 0, 100, 120);
		f.skim.update();

		DrtRideTimeSkim.Lookup lookup = f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_A, 0);
		assertThat(lookup.factor()).isEqualTo(1.2);
		assertThat(lookup.source()).isEqualTo(Source.ZONE_PAIR_TIME_BIN);
	}

	@Test
	void withNothingObservedTheLookupSaysSoAndOffersNoNumber() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		DrtRideTimeSkim.Lookup lookup = f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0);
		assertThat(lookup.source()).isEqualTo(Source.DEFAULT);
		assertThat(lookup.isMeasured()).isFalse();
		// NaN, not 1.0: a caller ignoring the source must not silently scale by an unmeasured number
		assertThat(lookup.factor()).isNaN();
	}

	@Test
	void unobservedPairsAndBinsFallThroughCoarserAggregates() {
		DrtRideTimeSkimParams params = new DrtRideTimeSkimParams();
		params.setTimeBinSize(900);
		Fixture f = new Fixture(params);

		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 200, 300);
		f.skim.update();

		// same pair, a bin never observed -> the pair's own mean
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 5 * 900).source())
				.isEqualTo(Source.ZONE_PAIR_MEAN);
		// a pair never observed, in a bin that was -> the system-wide value for that bin
		assertThat(f.skim.lookup(LINK_IN_ZONE_B, LINK_IN_ZONE_A, 0).source()).isEqualTo(Source.GLOBAL_TIME_BIN);
		// neither -> the system-wide mean
		assertThat(f.skim.lookup(LINK_IN_ZONE_B, LINK_IN_ZONE_A, 5 * 900).source()).isEqualTo(Source.GLOBAL_MEAN);
	}

	/**
	 * A request picked up but never dropped off has no ride to measure. The collector counts such a
	 * sequence as performed, so it has to be excluded here rather than relied upon.
	 */
	@Test
	void aRequestWithoutADropOffIsNotCounted() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		f.pickedUpButNeverDroppedOff("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 200);
		f.skim.update();

		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).source()).isEqualTo(Source.DEFAULT);
	}

	@Test
	void aRequestWithNoUsableUnsharedRideTimeIsNotCounted() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		// a zero reference would make the ratio infinite
		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 0, 300);
		f.skim.update();

		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).source()).isEqualTo(Source.DEFAULT);
	}

	@Test
	void ridesEndingOutsideTheZoneSystemAreNotCounted() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_NOWHERE, 0, 200, 300);
		f.skim.update();

		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).source()).isEqualTo(Source.DEFAULT);
	}

	@Test
	void iterationToIterationSwingsAreDamped() {
		DrtRideTimeSkimParams params = new DrtRideTimeSkimParams();
		params.setSmoothingWeight(0.5);
		Fixture f = new Fixture(params);

		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 100, 200);
		f.skim.update();
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).factor()).isEqualTo(2.0);

		f.collector.reset(1);
		f.ride("r2", "p2", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 100, 100);
		f.skim.update();
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).factor()).isEqualTo(1.5);
	}

	@Test
	void aValueNotRefreshedThisIterationIsReportedAsCarriedForward() {
		Fixture f = new Fixture(new DrtRideTimeSkimParams());

		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 200, 300);
		f.skim.update();
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).source()).isEqualTo(Source.ZONE_PAIR_TIME_BIN);

		f.collector.reset(1);
		f.skim.update();
		DrtRideTimeSkim.Lookup carried = f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0);
		assertThat(carried.factor()).isEqualTo(1.5);
		assertThat(carried.source()).isEqualTo(Source.ZONE_PAIR_TIME_BIN_CARRIED);
	}

	@Test
	void binsThinnerThanMinObservationsFallThrough() {
		DrtRideTimeSkimParams params = new DrtRideTimeSkimParams();
		params.setMinObservations(2);
		Fixture f = new Fixture(params);

		f.ride("r1", "p1", LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0, 200, 300);
		f.skim.update();

		assertThat(f.skim.lookup(LINK_IN_ZONE_A, LINK_IN_ZONE_B, 0).source()).isEqualTo(Source.DEFAULT);
	}

	private static final class Fixture {
		private final DrtEventSequenceCollector collector = new DrtEventSequenceCollector(MODE);
		private final ZonalDrtRideTimeSkim skim;

		Fixture(DrtRideTimeSkimParams params) {
			this.skim = new ZonalDrtRideTimeSkim(MODE, params, new TwoZoneSystem(), null, collector, null, ";");
		}

		void ride(String requestId, String personId, Id<Link> fromLink, Id<Link> toLink, double readyTime,
				double unsharedRideTime, double actualRideTime) {
			Id<Person> p = submit(requestId, personId, fromLink, toLink, readyTime, unsharedRideTime);
			Id<Request> rq = Id.create(requestId, Request.class);
			collector.handleEvent(new PassengerPickedUpEvent(readyTime + 60, MODE, rq, p, VEHICLE));
			collector.handleEvent(
					new PassengerDroppedOffEvent(readyTime + 60 + actualRideTime, MODE, rq, p, VEHICLE));
		}

		void pickedUpButNeverDroppedOff(String requestId, String personId, Id<Link> fromLink, Id<Link> toLink,
				double readyTime, double unsharedRideTime) {
			Id<Person> p = submit(requestId, personId, fromLink, toLink, readyTime, unsharedRideTime);
			collector.handleEvent(new PassengerPickedUpEvent(readyTime + 60, MODE,
					Id.create(requestId, Request.class), p, VEHICLE));
		}

		private Id<Person> submit(String requestId, String personId, Id<Link> fromLink, Id<Link> toLink,
				double readyTime, double unsharedRideTime) {
			Id<Request> rq = Id.create(requestId, Request.class);
			Id<Person> p = Id.createPersonId(personId);
			collector.handleEvent(new PersonDepartureEvent(readyTime, p, fromLink, MODE, MODE));
			collector.handleEvent(new PassengerWaitingEvent(readyTime, MODE, rq, List.of(p)));
			collector.handleEvent(new DrtRequestSubmittedEvent(readyTime, MODE, rq, List.of(p), fromLink, toLink,
					unsharedRideTime, 0, readyTime, readyTime + 600, readyTime + 1800, 1800, null, null));
			return p;
		}
	}

	private static final class TwoZoneSystem implements ZoneSystem {
		private final Map<Id<Zone>, Zone> zones = Map.of( //
				ZONE_A, new ZoneImpl(ZONE_A, null, new Coord(0, 0), "test"), //
				ZONE_B, new ZoneImpl(ZONE_B, null, new Coord(1000, 0), "test"));

		@Override
		public Optional<Zone> getZoneForLinkId(Id<Link> linkId) {
			if (linkId.toString().startsWith("a")) {
				return Optional.of(zones.get(ZONE_A));
			}
			if (linkId.toString().startsWith("b")) {
				return Optional.of(zones.get(ZONE_B));
			}
			return Optional.empty();
		}

		@Override
		public Optional<Zone> getZoneForNodeId(Id<Node> nodeId) {
			return Optional.empty();
		}

		@Override
		public List<Link> getLinksForZoneId(Id<Zone> zone) {
			return List.of();
		}

		@Override
		public Map<Id<Zone>, Zone> getZones() {
			return zones;
		}
	}
}
