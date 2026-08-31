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

import com.google.inject.multibindings.MapBinder;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.common.zones.ZoneSystemUtils;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.controler.MatsimServices;

/**
 * Wires the observed wait-time skim for a single DRT mode: builds its zone system, binds the skim
 * modally, registers it as a controller listener so it refreshes at the end of each iteration, and
 * publishes it into the mode-keyed map that
 * {@link WaitAwareRaptorIntermodalAccessEgress} consumes.
 * <p>
 * Install it through {@link MultiModeDrtWaitTimeSkimModule} rather than directly, so that the
 * Raptor binding is overridden exactly once.
 *
 * @author Monash Healthy Active Cities
 */
public final class DrtWaitTimeSkimModule extends AbstractDvrpModeModule {

	private final DrtWaitTimeSkimParams skimParams;

	public DrtWaitTimeSkimModule(DrtConfigGroup drtCfg, DrtWaitTimeSkimParams skimParams) {
		super(drtCfg.getMode());
		this.skimParams = skimParams;
	}

	@Override
	public void install() {
		bindModal(ZonalDrtWaitTimeSkim.class).toProvider(modalProvider(getter -> {
			Network network = getter.getModal(Network.class);
			ZoneSystem zoneSystem = ZoneSystemUtils.createZoneSystem(getConfig().getContext(), network,
					skimParams.addOrGetZoneSystemParams());
			return new ZonalDrtWaitTimeSkim(getMode(), skimParams, zoneSystem,
					getter.getModal(DrtEventSequenceCollector.class), getter.get(MatsimServices.class),
					getConfig().global().getDefaultDelimiter());
		})).asEagerSingleton();

		addControllerListenerBinding().to(modalKey(ZonalDrtWaitTimeSkim.class));

		MapBinder.newMapBinder(binder(), String.class, DrtWaitTimeSkim.class)
				.addBinding(getMode())
				.to(modalKey(ZonalDrtWaitTimeSkim.class));
	}
}
