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

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.core.controler.AbstractModule;

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
		boolean anyModeConfigured = false;

		for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(getConfig()).getModalElements()) {
			Optional<DrtWaitTimeSkimParams> skimParams = getSkimParams(drtCfg);
			if (skimParams.isPresent()) {
				log.info("Wait-time skim enabled for DRT mode '{}'", drtCfg.getMode());
				install(new DrtWaitTimeSkimModule(drtCfg, skimParams.get()));
				anyModeConfigured = true;
			}
		}

		if (anyModeConfigured) {
			bind(RaptorIntermodalAccessEgress.class).to(WaitAwareRaptorIntermodalAccessEgress.class)
					.asEagerSingleton();
		} else {
			log.warn("{} was installed but no DRT mode declares a '{}' parameter set;"
							+ " intermodal access/egress cost is unchanged and still ignores waiting time.",
					MultiModeDrtWaitTimeSkimModule.class.getSimpleName(), DrtWaitTimeSkimParams.SET_NAME);
		}
	}

	private static Optional<DrtWaitTimeSkimParams> getSkimParams(DrtConfigGroup drtCfg) {
		return drtCfg instanceof DrtWithExtensionsConfigGroup extended ?
				extended.getWaitTimeSkimParams() :
				Optional.empty();
	}
}
