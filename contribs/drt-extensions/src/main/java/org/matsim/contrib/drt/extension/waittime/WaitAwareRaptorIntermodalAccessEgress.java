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
 * Adds the expected DRT waiting time to the cost of an intermodal access or egress leg.
 * <p>
 * {@link DefaultRaptorIntermodalAccessEgress} reads only {@code leg.getTravelTime()}, so a feeder
 * leg whose vehicle arrives in twenty minutes scores exactly like one whose vehicle is already at
 * the kerb. This decorator adds, for every leg whose mode has a registered
 * {@link DrtWaitTimeSkim}, the observed wait for that leg's origin and departure time, both to the
 * reported travel time and to the disutility.
 * <p>
 * Waiting is charged at {@link RaptorParameters#getMarginalUtilityOfWaitingPt_utl_s()}, MATSim's
 * existing marginal utility of waiting for public transport, rather than at a new mode-specific
 * rate. That keeps a minute spent waiting for a DRT vehicle and a minute spent waiting for a bus
 * priced identically, and means the behaviour is tuned with a knob operators already understand.
 * <p>
 * Known limitation, inherited from
 * {@link ch.sbb.matsim.routing.pt.raptor.DefaultRaptorStopFinder}: for egress legs the departure
 * time carried by the leg is the trip's original departure time, which the stop finder itself
 * documents as wrong. Egress waits are therefore looked up at a time that can be badly off in a
 * long trip. Access legs, where the intermodal wait usually matters most, carry the correct time.
 *
 * @author Monash Healthy Active Cities
 */
public final class WaitAwareRaptorIntermodalAccessEgress implements RaptorIntermodalAccessEgress {

	private final RaptorIntermodalAccessEgress delegate;
	private final Map<String, DrtWaitTimeSkim> skimsByMode;

	@Inject
	public WaitAwareRaptorIntermodalAccessEgress(Map<String, DrtWaitTimeSkim> skimsByMode) {
		this(new DefaultRaptorIntermodalAccessEgress(), skimsByMode);
	}

	public WaitAwareRaptorIntermodalAccessEgress(RaptorIntermodalAccessEgress delegate,
			Map<String, DrtWaitTimeSkim> skimsByMode) {
		this.delegate = delegate;
		this.skimsByMode = Map.copyOf(skimsByMode);
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

		double disutility = base.disutility + waitTime * -params.getMarginalUtilityOfWaitingPt_utl_s();
		return new RIntermodalAccessEgress(base.routeParts, disutility, base.travelTime + waitTime,
				base.direction);
	}
}
