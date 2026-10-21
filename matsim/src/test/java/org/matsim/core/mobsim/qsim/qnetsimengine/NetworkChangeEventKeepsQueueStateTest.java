/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2026 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.core.mobsim.qsim.qnetsimengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.controler.PrepareForSimUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.mobsim.qsim.QSimBuilder;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkChangeEvent.ChangeType;
import org.matsim.core.network.NetworkChangeEvent.ChangeValue;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.testcases.MatsimTestUtils;

/**
 * A network change event that sets a queued link's flow capacity and lanes to the values they already have must not
 * change what the link lets out.
 * <p>
 * {@link QueueWithBuffer#recalcTimeVariantAttributes()} used to set the outflow accumulator to one full time step and,
 * under {@code withHoles} and {@code kinematicWaves}, the holes ledger to the full storage. On a link whose flow
 * capacity is well under one vehicle per time step (here 0.05 veh/s, a 1800 veh/h link at a 10% sample) every change
 * therefore forgave the debt the last departing vehicle left and released the next queued vehicle early. With a no-op
 * change every 10 s, 110 to 113 vehicles left the bottleneck in the 20-minute window against 60 without changes. Since
 * the fix the accumulator keeps its debt and the holes ledger is shifted by the change in storage, so the leave times
 * are the same with and without the no-op changes. With the accumulator fixed but the holes ledger still reset, the
 * counts matched but the leave times under {@code withHoles} and {@code kinematicWaves} did not.
 */
class NetworkChangeEventKeepsQueueStateTest {

	@RegisterExtension
	private MatsimTestUtils utils = new MatsimTestUtils();

	private static final double SAMPLE = 0.1;
	private static final double WINDOW_START = 600;
	private static final double WINDOW_END = 1800;

	@ParameterizedTest
	@CsvSource({"queue,true", "queue,false", "kinematicWaves,true", "kinematicWaves,false", "withHoles,true"})
	void noOpCapacityChangesDoNotReleaseQueuedVehicles(QSimConfigGroup.TrafficDynamics dynamics, boolean fastCapacityUpdate) {
		List<Double> without = run(dynamics, fastCapacityUpdate, false);
		List<Double> with = run(dynamics, fastCapacityUpdate, true);

		long countWithout = without.stream().filter(t -> t >= WINDOW_START && t < WINDOW_END).count();
		long countWith = with.stream().filter(t -> t >= WINDOW_START && t < WINDOW_END).count();
		// 1200 s at 1800 veh/h * 0.1
		assertEquals(60, countWithout, 1, "the bottleneck runs at capacity without change events");
		assertTrue(countWith <= countWithout + 1,
			dynamics + ", fast=" + fastCapacityUpdate + ": " + countWith + " vehicles left the bottleneck with no-op change events, " + countWithout + " without");
		assertEquals(without, with, "no-op change events must not change the leave times");
	}

	private List<Double> run(QSimConfigGroup.TrafficDynamics dynamics, boolean fastCapacityUpdate, boolean withChanges) {
		Config config = utils.loadConfig((String) null);
		config.routing().setNetworkRouteConsistencyCheck(RoutingConfigGroup.NetworkRouteConsistencyCheck.disable);
		config.network().setTimeVariantNetwork(true);
		config.qsim().setFlowCapFactor(SAMPLE);
		config.qsim().setStorageCapFactor(SAMPLE);
		config.qsim().setTrafficDynamics(dynamics);
		config.qsim().setUsingFastCapacityUpdate(fastCapacityUpdate);
		config.qsim().setStuckTime(36000);
		Scenario scenario = ScenarioUtils.createScenario(config);

		Network network = scenario.getNetwork();
		network.setCapacityPeriod(3600.0);
		Node n1 = NetworkUtils.createAndAddNode(network, Id.createNodeId("1"), new Coord(0, 0));
		Node n2 = NetworkUtils.createAndAddNode(network, Id.createNodeId("2"), new Coord(100, 0));
		Node n3 = NetworkUtils.createAndAddNode(network, Id.createNodeId("3"), new Coord(1100, 0));
		Node n4 = NetworkUtils.createAndAddNode(network, Id.createNodeId("4"), new Coord(1200, 0));
		Link link1 = NetworkUtils.createAndAddLink(network, Id.createLinkId("1"), n1, n2, 100, 10, 36000, 4);
		Link link2 = NetworkUtils.createAndAddLink(network, Id.createLinkId("2"), n2, n3, 1000, 10, 1800, 1);
		Link link3 = NetworkUtils.createAndAddLink(network, Id.createLinkId("3"), n3, n4, 100, 10, 36000, 4);

		if (withChanges) {
			for (double t = 300; t < 2400; t += 10) {
				NetworkChangeEvent change = new NetworkChangeEvent(t);
				change.addLink(link2);
				change.setFlowCapacityChange(new ChangeValue(ChangeType.ABSOLUTE_IN_SI_UNITS, 1800.0 / 3600));
				change.setLanesChange(new ChangeValue(ChangeType.ABSOLUTE_IN_SI_UNITS, 1));
				NetworkUtils.addNetworkChangeEvent(network, change);
			}
		}

		for (int i = 0; i < 150; i++) {
			Person person = PopulationUtils.getFactory().createPerson(Id.createPersonId(i));
			Plan plan = PersonUtils.createAndAddPlan(person, true);
			Activity home = PopulationUtils.createAndAddActivityFromLinkId(plan, "h", link1.getId());
			home.setEndTime(i);
			Leg leg = PopulationUtils.createAndAddLeg(plan, TransportMode.car);
			leg.setDepartureTime(i);
			NetworkRoute route = RouteUtils.createLinkNetworkRouteImpl(link1.getId(), link3.getId());
			route.setLinkIds(link1.getId(), List.of(link2.getId()), link3.getId());
			leg.setRoute(route);
			PopulationUtils.createAndAddActivityFromLinkId(plan, "w", link3.getId());
			scenario.getPopulation().addPerson(person);
		}

		List<Double> leaves = new ArrayList<>();
		EventsManager events = EventsUtils.createEventsManager();
		events.addHandler((LinkLeaveEventHandler) (LinkLeaveEvent event) -> {
			if (event.getLinkId().equals(link2.getId())) {
				leaves.add(event.getTime());
			}
		});
		PrepareForSimUtils.createDefaultPrepareForSim(scenario).run();
		new QSimBuilder(config).useDefaults().build(scenario, events).run();
		return leaves;
	}
}
