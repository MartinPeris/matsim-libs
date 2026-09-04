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

package org.matsim.contrib.drt.extension.skims;

import java.util.List;
import java.util.Map;

import com.google.inject.Inject;

import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.drt.routing.DrtRoute;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStopFinder.Direction;

/**
 * Corrects an intermodal access or egress leg with what DRT was actually observed to do, in both
 * elapsed time and cost. Two independent corrections, either of which may be absent.
 * <ul>
 * <li><b>Waiting</b>, from a {@link DrtWaitTimeSkim}. {@link DefaultRaptorIntermodalAccessEgress}
 * reads only {@code leg.getTravelTime()}, which contains no waiting term at all, so a feeder leg
 * whose vehicle arrives in twenty minutes reaches the stop at the same modelled moment, and for the
 * same price, as one whose vehicle is already at the kerb.</li>
 * <li><b>Ride time</b>, from a {@link DrtRideTimeSkim}. The leg's travel time is the DRT constraint
 * <em>ceiling</em>, {@code alpha * unshared + beta}, not an expected duration; see
 * {@link #observedRideTimeDelta}. For a short feeder leg that is several times the unshared ride.</li>
 * </ul>
 * Each applies only to legs whose mode has the corresponding skim registered, and each leaves the
 * leg untouched where nothing has been observed.
 *
 * <h2>Why the full charge applies in both directions</h2>
 *
 * The returned travel time becomes {@code InitialStop.accessTime}. It is tempting to think that on
 * access SwissRailRaptor already charges this wait, and that only the excess is ours to add. It
 * does not, and an earlier version of this class charged {@code (factor - 1)} on access for that
 * reason. That was a sign error, and it made a longer wait <i>cheaper</i>.
 * <p>
 * {@code SwissRailRaptorCore} computes arrival at the stop as {@code departureTime + accessTime}
 * and then charges the <i>platform</i> wait, {@code (boardingTime - arrival)}, at
 * {@link RaptorParameters#getMarginalUtilityOfWaitingPt_utl_s()}. Pushing the arrival later by the
 * DRT wait W therefore does not charge W — while the traveller still catches the same vehicle it
 * <i>shortens</i> the platform wait by exactly W, <i>refunding</i> {@code W * mu_wait}. So the
 * core's net contribution on access is {@code -W * mu_wait}, not {@code +W * mu_wait}.
 * <p>
 * The traveller's total waiting is unchanged by W; only its composition moves, from the platform
 * into the DRT vehicle. Pricing DRT waiting at {@code factor * mu_wait} and platform waiting at
 * {@code mu_wait} therefore requires adding the <b>full</b> {@code factor * W * mu_wait} here, in
 * both directions:
 *
 * <pre>
 * access: factor*W*mu (here) - W*mu (core refund) = (factor-1)*W*mu net  -> 0 at factor 1.0
 * egress: factor*W*mu (here) + 0  (no wait term)  =  factor   *W*mu net
 * </pre>
 *
 * Both are behaviourally right. On access the traveller swaps platform waiting for DRT waiting, so
 * at factor 1.0 nothing changes. On egress the wait is purely additional, so it is charged in full.
 * The asymmetry in the <i>outcome</i> comes from Raptor's structure, not from any asymmetry in what
 * this class charges.
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
public final class SkimAwareRaptorIntermodalAccessEgress implements RaptorIntermodalAccessEgress {

	private final RaptorIntermodalAccessEgress delegate;
	private final Map<String, DrtWaitTimeSkim> skimsByMode;
	private final Map<String, DrtRideTimeSkim> rideSkimsByMode;
	private final double waitingCostFactor;

	@Inject
	public SkimAwareRaptorIntermodalAccessEgress(Map<String, DrtWaitTimeSkim> skimsByMode,
			Map<String, DrtRideTimeSkim> rideSkimsByMode, WaitingCostFactor waitingCostFactor) {
		this(new DefaultRaptorIntermodalAccessEgress(), skimsByMode, rideSkimsByMode, waitingCostFactor.value());
	}

	public SkimAwareRaptorIntermodalAccessEgress(RaptorIntermodalAccessEgress delegate,
			Map<String, DrtWaitTimeSkim> skimsByMode, double waitingCostFactor) {
		this(delegate, skimsByMode, Map.of(), waitingCostFactor);
	}

	public SkimAwareRaptorIntermodalAccessEgress(RaptorIntermodalAccessEgress delegate,
			Map<String, DrtWaitTimeSkim> skimsByMode, Map<String, DrtRideTimeSkim> rideSkimsByMode,
			double waitingCostFactor) {
		this.delegate = delegate;
		this.skimsByMode = Map.copyOf(skimsByMode);
		this.rideSkimsByMode = Map.copyOf(rideSkimsByMode);
		this.waitingCostFactor = waitingCostFactor;
	}

	@Override
	public RIntermodalAccessEgress calcIntermodalAccessEgress(List<? extends PlanElement> legs,
			RaptorParameters params, Person person, Direction direction) {
		RIntermodalAccessEgress base = delegate.calcIntermodalAccessEgress(legs, params, person, direction);

		double waitTime = 0;
		double rideTimeDelta = 0;
		double rideCostDelta = 0;
		for (PlanElement planElement : legs) {
			if (!(planElement instanceof Leg leg) || leg.getRoute() == null) {
				continue;
			}
			double departureTime = leg.getDepartureTime().isDefined() ?
					leg.getDepartureTime().seconds() :
					Double.NaN;

			DrtWaitTimeSkim waitSkim = skimsByMode.get(leg.getMode());
			if (waitSkim != null) {
				waitTime += waitSkim.getWaitTime(leg.getRoute().getStartLinkId(), departureTime);
			}

			double delta = observedRideTimeDelta(leg, departureTime);
			if (delta != 0) {
				rideTimeDelta += delta;
				// the leg's own travel time is priced by the delegate at the mode's marginal utility
				// of travelling, so a correction to it must be priced the same way
				rideCostDelta += delta * -params.getMarginalUtilityOfTravelTime_utl_s(leg.getMode());
			}
		}

		if (waitTime <= 0 && rideTimeDelta == 0) {
			return base;
		}

		// see the class javadoc: the full wait charge applies in both directions. On access the core
		// does not charge the wait — it *refunds* an equal amount of platform waiting — so charging
		// anything less than the full factor makes a longer wait look cheaper.
		double disutility = base.disutility
				+ waitTime * waitingCostFactor * -params.getMarginalUtilityOfWaitingPt_utl_s()
				+ rideCostDelta;

		return new RIntermodalAccessEgress(base.routeParts, disutility,
				base.travelTime + waitTime + rideTimeDelta, base.direction);
	}

	/**
	 * How much the observed ride-time factor would move this leg's travel time, or zero to leave it
	 * alone.
	 * <p>
	 * A routed DRT leg carries {@code maxTravelDuration}, not an expected duration: {@code DrtRoute}
	 * sets its travel time from the constraint ceiling and {@code DefaultMainLegRouter} copies that
	 * onto the leg. Applying the observed factor to the route's own {@code directRideTime} replaces
	 * that ceiling with a measurement. Where nothing has been observed the leg is left exactly as it
	 * was, so installing the ride skim changes nothing until it has something to say.
	 * <p>
	 * The result is clamped into {@code [directRideTime, leg travel time]}. Below the unshared ride
	 * is physically impossible; above the constraint ceiling is a trip the operator would have
	 * rejected. A mean factor drawn from a coarse aggregate can land outside that range for an
	 * individual pair, and honouring it there would contradict the scenario's own constraints.
	 */
	private double observedRideTimeDelta(Leg leg, double departureTime) {
		DrtRideTimeSkim rideSkim = rideSkimsByMode.get(leg.getMode());
		if (rideSkim == null || !(leg.getRoute() instanceof DrtRoute route) || !leg.getTravelTime().isDefined()) {
			return 0;
		}
		double directRideTime = route.getDirectRideTime();
		if (!(directRideTime > 0)) {
			return 0;
		}
		DrtRideTimeSkim.Lookup lookup = rideSkim.lookup(route.getStartLinkId(), route.getEndLinkId(), departureTime);
		if (!lookup.isMeasured()) {
			return 0;
		}
		double ceiling = leg.getTravelTime().seconds();
		double observed = Math.min(Math.max(lookup.factor() * directRideTime, directRideTime), ceiling);
		return observed - ceiling;
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
