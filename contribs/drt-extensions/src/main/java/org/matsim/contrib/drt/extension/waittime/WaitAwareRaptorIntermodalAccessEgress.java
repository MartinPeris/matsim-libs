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

package org.matsim.contrib.drt.extension.waittime;

import java.util.List;
import java.util.Map;

import com.google.inject.Inject;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder.Direction;

/**
 * Adds the expected DRT waiting time to an intermodal access or egress leg, in both elapsed time
 * and cost.
 * <p>
 * {@link DefaultRaptorIntermodalAccessEgress} reads only {@code leg.getTravelTime()}, so a feeder
 * leg whose vehicle arrives in twenty minutes reaches the stop at the same modelled moment, and for
 * the same price, as one whose vehicle is already at the kerb. This decorator adds the observed
 * wait from a {@link DrtWaitTimeSkim} for every leg whose mode has one registered.
 *
 * <h2>Why the cost is direction-dependent</h2>
 *
 * The returned travel time becomes {@code InitialStop.accessTime}, and SwissRailRaptor does not
 * treat that quantity the same way at both ends of a trip.
 * <p>
 * On <b>access</b>, {@code SwissRailRaptorCore} computes the arrival time at the stop as
 * {@code departureTime + accessTime} and then charges {@code (nextDeparture - arrival)} at
 * {@link RaptorParameters#getMarginalUtilityOfWaitingPt_utl_s()}. Pushing the arrival later by the
 * DRT wait therefore <i>already</i> costs the traveller that wait: it eats into the slack they
 * would otherwise have spent waiting on the platform, at exactly the marginal utility of waiting.
 * Adding a full second charge on top would double-count it — and, less obviously, charging it in
 * both places at the same rate cancels out exactly, leaving the route cost unchanged. So on access
 * this class charges only the <i>excess</i> onerousness of waiting for a DRT vehicle over waiting
 * at a stop.
 * <p>
 * On <b>egress</b>, the core adds {@code accessTime} to the arrival time and {@code accessCost} to
 * the cost with no waiting term at all, so there is no implicit charge and the full cost applies.
 * <p>
 * {@link DrtWaitTimeSkimParams#getWaitingCostFactor()} is that relative onerousness: 1.0 means a
 * minute waiting for a DRT vehicle is worth exactly a minute waiting at a stop, which is the
 * neutral modelling position and the default. Above 1.0 makes on-demand waiting worse, which is
 * what stated-preference work on unscheduled waiting generally finds; the number is a modelling
 * choice this class does not make for you.
 * <p>
 * Whatever the factor, the trip's elapsed time always grows by the wait, so a long feeder wait
 * still costs the traveller a missed connection when it exceeds the slack at the stop, and still
 * shows up as a slower trip to whatever compares this option against a non-transit alternative.
 *
 * <h2>Known limitation</h2>
 *
 * Inherited from {@link ch.sbb.matsim.routing.pt.raptor.DefaultRaptorStopFinder}: on egress the
 * departure time carried by the leg is the trip's original departure time, which the stop finder
 * itself documents as wrong. Egress waits are therefore looked up in a time bin that can be badly
 * off on a long trip. Access legs carry the correct time.
 *
 * @author Monash Healthy Active Cities
 */
public final class WaitAwareRaptorIntermodalAccessEgress implements RaptorIntermodalAccessEgress {

	private final RaptorIntermodalAccessEgress delegate;
	private final Map<String, DrtWaitTimeSkim> skimsByMode;
	private final double waitingCostFactor;

	@Inject
	public WaitAwareRaptorIntermodalAccessEgress(Map<String, DrtWaitTimeSkim> skimsByMode,
			WaitingCostFactor waitingCostFactor) {
		this(new DefaultRaptorIntermodalAccessEgress(), skimsByMode, waitingCostFactor.value());
	}

	public WaitAwareRaptorIntermodalAccessEgress(RaptorIntermodalAccessEgress delegate,
			Map<String, DrtWaitTimeSkim> skimsByMode, double waitingCostFactor) {
		this.delegate = delegate;
		this.skimsByMode = Map.copyOf(skimsByMode);
		this.waitingCostFactor = waitingCostFactor;
	}

	@Override
	public RIntermodalAccessEgress calcIntermodalAccessEgress(List<? extends PlanElement> legs,
			RaptorParameters params, Person person, Direction direction) {
		RIntermodalAccessEgress base = delegate.calcIntermodalAccessEgress(legs, params, person, direction);

		double waitTime = 0;
		for (PlanElement planElement : legs) {
			if (!(planElement instanceof Leg leg)) {
				continue;
			}
			DrtWaitTimeSkim skim = skimsByMode.get(leg.getMode());
			if (skim == null || leg.getRoute() == null) {
				continue;
			}
			double departureTime = leg.getDepartureTime().isDefined() ?
					leg.getDepartureTime().seconds() :
					Double.NaN;
			waitTime += skim.getWaitTime(leg.getRoute().getStartLinkId(), departureTime);
		}

		if (waitTime <= 0) {
			return base;
		}

		// see the class javadoc: on access the core already charges the wait once, by shortening
		// the slack at the stop, so only the excess is ours to add
		double chargeableFactor = direction == Direction.ACCESS ?
				waitingCostFactor - 1.0 :
				waitingCostFactor;
		double disutility = base.disutility
				+ waitTime * chargeableFactor * -params.getMarginalUtilityOfWaitingPt_utl_s();

		return new RIntermodalAccessEgress(base.routeParts, disutility, base.travelTime + waitTime,
				base.direction);
	}

	/**
	 * Carries the configured relative onerousness of DRT waiting into Guice without binding a bare
	 * {@code double}. Per-mode factors are deliberately not supported: the factor describes how a
	 * traveller feels about waiting for an on-demand vehicle, and a scenario that needs one value
	 * per DRT mode is asking a different question.
	 */
	public record WaitingCostFactor(double value) {
	}
}
