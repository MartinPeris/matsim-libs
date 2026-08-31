package org.matsim.contrib.drt.extension.waittime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.common.zones.Zone;
import org.matsim.contrib.common.zones.ZoneImpl;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.extension.waittime.DrtWaitTimeSkim.Source;
import org.matsim.contrib.drt.passenger.events.DrtRequestSubmittedEvent;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.optimizer.Request;
import org.matsim.contrib.dvrp.passenger.PassengerPickedUpEvent;
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

	private static final class Fixture {
		private final DrtEventSequenceCollector collector = new DrtEventSequenceCollector(MODE);
		private final ZonalDrtWaitTimeSkim skim;

		Fixture(DrtWaitTimeSkimParams params) {
			this.skim = new ZonalDrtWaitTimeSkim(MODE, params, new TwoZoneSystem(), collector, null, ";");
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
				ZONE_A, new ZoneImpl(ZONE_A, null, "test"), //
				ZONE_B, new ZoneImpl(ZONE_B, null, "test"));

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
