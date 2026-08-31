package org.matsim.contrib.drt.extension.waittime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneImpl;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystem;

import jakarta.annotation.Nullable;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.extension.waittime.DrtWaitTimeSkim.Source;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
import org.matsim.contrib.dvrp.passenger.PassengerRequestRejectedEvent;
import org.matsim.contrib.dvrp.passenger.PassengerWaitingEvent;

/**
 * @author Monash Healthy Active Cities
 */
class ZonalDrtWaitTimeSkimTest {

	private static final String MODE = "drt";
	private static final Id<Link> LINK_IN_ZONE_A = Id.createLinkId("a1");
	private static final Id<Link> LINK_IN_ZONE_B = Id.createLinkId("b1");
	private static final Id<Link> TO_LINK = Id.createLinkId("dest");
	private static final Id<DvrpVehicle> VEHICLE = Id.create("veh", DvrpVehicle.class);
	private static final Id<Zone> ZONE_A = Id.create("A", Zone.class);
	private static final Id<Zone> ZONE_B = Id.create("B", Zone.class);

	@Test
	void observedWaitIsReturnedForItsOwnZoneAndTimeBin() {
		Fixture f = new Fixture(new DrtWaitTimeSkimParams());
		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 300);
		f.skim.update();

		DrtWaitTimeSkim.Lookup lookup = f.skim.lookup(LINK_IN_ZONE_A, 0);
		assertThat(lookup.waitTime()).isEqualTo(300.0);
		assertThat(lookup.source()).isEqualTo(Source.ZONE_TIME_BIN);
	}

	@Test
	void withNothingObservedTheConfiguredDefaultIsUsedAndSaysSo() {
		DrtWaitTimeSkimParams params = new DrtWaitTimeSkimParams();
		params.setDefaultWaitTime(420);
		Fixture f = new Fixture(params);

		DrtWaitTimeSkim.Lookup lookup = f.skim.lookup(LINK_IN_ZONE_A, 0);
		assertThat(lookup.waitTime()).isEqualTo(420.0);
		assertThat(lookup.source()).isEqualTo(Source.DEFAULT);
	}

	@Test
	void unobservedZonesAndBinsFallThroughCoarserAggregatesRatherThanToAConstant() {
		DrtWaitTimeSkimParams params = new DrtWaitTimeSkimParams();
		params.setTimeBinSize(900);
		params.setDefaultWaitTime(999);
		Fixture f = new Fixture(params);

		// zone A, bin 0 only
		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 240);
		f.skim.update();

		// same zone, a bin never observed -> the zone's own mean
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, 5 * 900).source()).isEqualTo(Source.ZONE_MEAN);
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, 5 * 900).waitTime()).isEqualTo(240.0);

		// a zone never observed, in a bin that was -> the system-wide value for that bin
		assertThat(f.skim.lookup(LINK_IN_ZONE_B, 0).source()).isEqualTo(Source.GLOBAL_TIME_BIN);
		assertThat(f.skim.lookup(LINK_IN_ZONE_B, 0).waitTime()).isEqualTo(240.0);

		// neither zone nor bin observed -> the system-wide mean, still not the constant
		assertThat(f.skim.lookup(LINK_IN_ZONE_B, 5 * 900).source()).isEqualTo(Source.GLOBAL_MEAN);
		assertThat(f.skim.lookup(LINK_IN_ZONE_B, 5 * 900).waitTime()).isEqualTo(240.0);
	}

	@Test
	void anUnknownDepartureTimeSkipsTheTimeBinnedLevelsRatherThanAnsweringFromBinZero() {
		DrtWaitTimeSkimParams params = new DrtWaitTimeSkimParams();
		params.setTimeBinSize(900);
		Fixture f = new Fixture(params);

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 120);
		f.request("r2", "p2", LINK_IN_ZONE_A, 3600, 480);
		f.skim.update();

		// bin 0 alone would say 120; with no time known the zone mean over both bins is the honest answer
		DrtWaitTimeSkim.Lookup lookup = f.skim.lookup(LINK_IN_ZONE_A, Double.NaN);
		assertThat(lookup.source()).isEqualTo(Source.ZONE_MEAN);
		assertThat(lookup.waitTime()).isEqualTo(300.0);
	}

	@Test
	void iterationToIterationSwingsAreDamped() {
		DrtWaitTimeSkimParams params = new DrtWaitTimeSkimParams();
		params.setSmoothingWeight(0.5);
		Fixture f = new Fixture(params);

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 600);
		f.skim.update();
		assertThat(f.skim.getWaitTime(LINK_IN_ZONE_A, 0)).isEqualTo(600.0);

		// next iteration reports no wait at all; the estimate must not simply follow it
		f.collector.reset(1);
		f.request("r2", "p2", LINK_IN_ZONE_A, 0, 0);
		f.skim.update();
		assertThat(f.skim.getWaitTime(LINK_IN_ZONE_A, 0)).isEqualTo(300.0);
	}

	@Test
	void aWeightOfOneRestoresUndampedReplacement() {
		DrtWaitTimeSkimParams params = new DrtWaitTimeSkimParams();
		params.setSmoothingWeight(1.0);
		Fixture f = new Fixture(params);

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 600);
		f.skim.update();
		f.collector.reset(1);
		f.request("r2", "p2", LINK_IN_ZONE_A, 0, 0);
		f.skim.update();

		assertThat(f.skim.getWaitTime(LINK_IN_ZONE_A, 0)).isEqualTo(0.0);
	}

	@Test
	void binsThinnerThanMinObservationsFallThrough() {
		DrtWaitTimeSkimParams params = new DrtWaitTimeSkimParams();
		params.setMinObservations(2);
		Fixture f = new Fixture(params);

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 300);
		f.skim.update();

		// one observation is not enough for the bin, nor for the zone or the system
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, 0).source()).isEqualTo(Source.DEFAULT);
	}

	@Test
	void waitIsMeasuredFromReadinessNotFromSubmission() {
		Fixture f = new Fixture(new DrtWaitTimeSkimParams());

		// prebooked at t=0 for a ride that may not start before t=600, picked up at t=900
		Id<Request> requestId = Id.create("r1", Request.class);
		Id<Person> personId = Id.createPersonId("p1");
		f.collector.handleEvent(new DrtRequestSubmittedEvent(0, MODE, requestId, List.of(personId),
				LINK_IN_ZONE_A, TO_LINK, 0, 0, 600, 1200, 2400, 1800, null, null));
		f.collector.handleEvent(new PersonDepartureEvent(600, personId, LINK_IN_ZONE_A, MODE, MODE));
		f.collector.handleEvent(new PassengerWaitingEvent(600, MODE, requestId, List.of(personId)));
		f.collector.handleEvent(new PassengerPickedUpEvent(900, MODE, requestId, personId, VEHICLE));
		f.skim.update();

		// 300s of waiting, not the 900s since the booking was made
		assertThat(f.skim.getWaitTime(LINK_IN_ZONE_A, 600)).isEqualTo(300.0);
	}

	@Test
	void aValueNotRefreshedThisIterationIsReportedAsCarriedForwardRatherThanAsObserved() {
		Fixture f = new Fixture(new DrtWaitTimeSkimParams());

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 300);
		f.skim.update();
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, 0).source()).isEqualTo(Source.ZONE_TIME_BIN);

		// an iteration in which zone A saw nothing: the value survives, but it is no longer a
		// measurement of the iteration just finished and must not claim to be one
		f.collector.reset(1);
		f.skim.update();
		DrtWaitTimeSkim.Lookup carried = f.skim.lookup(LINK_IN_ZONE_A, 0);
		assertThat(carried.waitTime()).isEqualTo(300.0);
		assertThat(carried.source()).isEqualTo(Source.ZONE_TIME_BIN_CARRIED);
	}

	@Test
	void rejectionsDoNotEnterTheMean() {
		Fixture f = new Fixture(new DrtWaitTimeSkimParams());

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 120);
		f.rejectedRequest("r2", "p2", LINK_IN_ZONE_A, 0);
		f.skim.update();

		// a rejection is an unbounded wait, not a long one: averaging it in would need a number
		// this class has no basis to invent, so the served request stands alone
		assertThat(f.skim.getWaitTime(LINK_IN_ZONE_A, 0)).isEqualTo(120.0);
	}

	@Test
	void aZoneSeenButNeverQualifyingDoesNotRetainAnEmptyRow() {
		DrtWaitTimeSkimParams params = new DrtWaitTimeSkimParams();
		params.setMinObservations(5);
		Fixture f = new Fixture(params);

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 300);
		f.skim.update();

		// nothing qualified, so nothing should have been published at any level
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, 0).source()).isEqualTo(Source.DEFAULT);
		assertThat(f.skim.lookup(LINK_IN_ZONE_A, Double.NaN).source()).isEqualTo(Source.DEFAULT);
	}

	/**
	 * The zone system is built on the mode's <em>filtered</em> network, but the interface promises
	 * that a lookup always returns a number. A caller holding a link from elsewhere in the scenario
	 * — the documented programmatic use is exactly that — must get the fallback chain, not a
	 * NullPointerException from {@code SquareGridZoneSystem} dereferencing an unknown link.
	 */
	@Test
	void aLinkOutsideTheModalNetworkFallsThroughInsteadOfThrowing() {
		// the real SquareGridZoneSystem, because it is the one that throws: it dereferences the
		// link against its own network without checking
		Network network = twoLinkNetwork();
		ZoneSystem zoneSystem = new SquareGridZoneSystem(network, 1000, zone -> true);
		Fixture f = new Fixture(new DrtWaitTimeSkimParams(), zoneSystem, network);

		f.request("r1", "p1", LINK_IN_ZONE_A, 0, 300);
		f.skim.update();

		Id<Link> foreign = Id.createLinkId("not-in-this-modes-network");

		assertThatCode(() -> f.skim.lookup(foreign, 0)).doesNotThrowAnyException();
		assertThat(f.skim.lookup(foreign, 0).source()).isEqualTo(Source.GLOBAL_TIME_BIN);
		assertThat(f.skim.lookup(foreign, 0).waitTime()).isEqualTo(300);
	}

	private static Network twoLinkNetwork() {
		Network network = NetworkUtils.createNetwork();
		Node n0 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n0"), new Coord(0, 0));
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n1"), new Coord(100, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("n2"), new Coord(5000, 0));
		NetworkUtils.createAndAddLink(network, LINK_IN_ZONE_A, n0, n1, 100, 10, 1000, 1);
		NetworkUtils.createAndAddLink(network, LINK_IN_ZONE_B, n1, n2, 4900, 10, 1000, 1);
		return network;
	}

	private static final class Fixture {
		private final DrtEventSequenceCollector collector = new DrtEventSequenceCollector(MODE);
		private final ZonalDrtWaitTimeSkim skim;

		Fixture(DrtWaitTimeSkimParams params) {
			this(params, new TwoZoneSystem(), null);
		}

		Fixture(DrtWaitTimeSkimParams params, ZoneSystem zoneSystem, @Nullable Network network) {
			this.skim = new ZonalDrtWaitTimeSkim(MODE, params, zoneSystem, network, collector, null, ";");
		}

		void rejectedRequest(String requestId, String personId, Id<Link> fromLink, double readyTime) {
			Id<Request> rq = Id.create(requestId, Request.class);
			Id<Person> p = Id.createPersonId(personId);
			collector.handleEvent(new PersonDepartureEvent(readyTime, p, fromLink, MODE, MODE));
			collector.handleEvent(new PassengerWaitingEvent(readyTime, MODE, rq, List.of(p)));
			collector.handleEvent(new DrtRequestSubmittedEvent(readyTime, MODE, rq, List.of(p), fromLink, TO_LINK,
					0, 0, readyTime, readyTime + 600, readyTime + 1800, 1800, null, null));
			collector.handleEvent(
					new PassengerRequestRejectedEvent(readyTime, MODE, rq, List.of(p), "no vehicle available"));
		}

		void request(String requestId, String personId, Id<Link> fromLink, double readyTime, double waitTime) {
			Id<Request> rq = Id.create(requestId, Request.class);
			Id<Person> p = Id.createPersonId(personId);
			collector.handleEvent(new PersonDepartureEvent(readyTime, p, fromLink, MODE, MODE));
			collector.handleEvent(new PassengerWaitingEvent(readyTime, MODE, rq, List.of(p)));
			collector.handleEvent(new DrtRequestSubmittedEvent(readyTime, MODE, rq, List.of(p), fromLink, TO_LINK,
					0, 0, readyTime, readyTime + 600, readyTime + 1800, 1800, null, null));
			collector.handleEvent(new PassengerPickedUpEvent(readyTime + waitTime, MODE, rq, p, VEHICLE));
		}
	}

	/**
	 * A deliberately trivial zone system: link "a*" is in zone A, link "b*" in zone B. Using a real
	 * grid here would test the grid, not the skim.
	 */
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
