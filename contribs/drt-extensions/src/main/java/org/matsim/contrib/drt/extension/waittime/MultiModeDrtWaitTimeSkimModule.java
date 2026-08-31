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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.controler.AbstractModule;

import com.google.inject.multibindings.MapBinder;

import ch.sbb.matsim.routing.pt.raptor.RaptorIntermodalAccessEgress;

/**
 * Entry point for wait-aware intermodal routing.
 * <p>
 * Installs a {@link DrtWaitTimeSkimModule} for every DRT mode that declares a
 * {@link DrtWaitTimeSkimParams} parameter set, and replaces SwissRailRaptor's default intermodal
 * access/egress cost with one that charges for waiting. Because it overrides a binding made by
 * {@code SwissRailRaptorModule}, add it as an overriding module:
 *
 * <pre>{@code
 * controler.addOverridingModule(new MultiModeDrtWaitTimeSkimModule());
 * }</pre>
 *
 * If no mode declares the parameter set, nothing is bound and SwissRailRaptor keeps its default
 * behaviour, so installing this module unconditionally is safe.
 *
 * @author Monash Healthy Active Cities
 */
public final class MultiModeDrtWaitTimeSkimModule extends AbstractModule {

	private static final Logger log = LogManager.getLogger(MultiModeDrtWaitTimeSkimModule.class);

	@Override
	public void install() {
		Map<String, DrtWaitTimeSkimParams> waitConfigured = new LinkedHashMap<>();
		boolean anyRideConfigured = false;

		for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(getConfig()).getModalElements()) {
			Optional<DrtWaitTimeSkimParams> waitParams = getWaitSkimParams(drtCfg);
			Optional<DrtRideTimeSkimParams> rideParams = getRideSkimParams(drtCfg);
			if (waitParams.isEmpty() && rideParams.isEmpty()) {
				continue;
			}
			waitParams.ifPresent(p -> log.info("Wait-time skim enabled for DRT mode '{}'", drtCfg.getMode()));
			rideParams.ifPresent(p -> log.info("Ride-time skim enabled for DRT mode '{}'", drtCfg.getMode()));

			install(new DrtWaitTimeSkimModule(drtCfg, waitParams.orElse(null), rideParams.orElse(null)));
			waitParams.ifPresent(p -> waitConfigured.put(drtCfg.getMode(), p));
			anyRideConfigured |= rideParams.isPresent();
		}

		if (waitConfigured.isEmpty() && !anyRideConfigured) {
			log.warn("{} was installed but no DRT mode declares a '{}' or '{}' parameter set;"
							+ " intermodal access/egress cost is unchanged and still ignores both waiting"
							+ " time and observed ride time.",
					MultiModeDrtWaitTimeSkimModule.class.getSimpleName(), DrtWaitTimeSkimParams.SET_NAME,
					DrtRideTimeSkimParams.SET_NAME);
			return;
		}

		// declare both maps even when only one kind of skim is configured: the decorator injects
		// both, and a MapBinder that no module ever declares is not an empty map to Guice, it is a
		// missing binding
		MapBinder.newMapBinder(binder(), String.class, DrtWaitTimeSkim.class);
		MapBinder.newMapBinder(binder(), String.class, DrtRideTimeSkim.class);

		bind(RaptorIntermodalAccessEgress.class).to(WaitAwareRaptorIntermodalAccessEgress.class)
				.asEagerSingleton();
		bind(WaitAwareRaptorIntermodalAccessEgress.WaitingCostFactor.class)
				.toInstance(new WaitAwareRaptorIntermodalAccessEgress.WaitingCostFactor(
						resolveWaitingCostFactor(waitConfigured)));
	}

	/**
	 * The waiting cost factor describes how a traveller feels about waiting for an on-demand
	 * vehicle, so it is a property of the scenario rather than of a mode. Modes may not disagree
	 * about it; a config that tries to is a mistake worth failing on rather than silently resolving.
	 */
	private static double resolveWaitingCostFactor(Map<String, DrtWaitTimeSkimParams> configured) {
		if (configured.isEmpty()) {
			// only ride-time skims are in play; the factor is never consulted
			return 1.0;
		}
		Map<Double, String> byValue = new LinkedHashMap<>();
		configured.forEach((mode, params) -> byValue.putIfAbsent(params.getWaitingCostFactor(), mode));
		if (byValue.size() > 1) {
			throw new IllegalStateException(
					"DRT modes declare conflicting " + DrtWaitTimeSkimParams.SET_NAME + ".waitingCostFactor values: "
							+ byValue + ". The factor describes the traveller, not the mode, so it must agree"
							+ " across modes.");
		}
		return byValue.keySet().iterator().next();
	}

	private static Optional<DrtWaitTimeSkimParams> getWaitSkimParams(DrtConfigGroup drtCfg) {
		return drtCfg instanceof DrtWithExtensionsConfigGroup extended ?
				extended.getWaitTimeSkimParams() :
				Optional.empty();
	}

	private static Optional<DrtRideTimeSkimParams> getRideSkimParams(DrtConfigGroup drtCfg) {
		return drtCfg instanceof DrtWithExtensionsConfigGroup extended ?
				extended.getRideTimeSkimParams() :
				Optional.empty();
	}
}
