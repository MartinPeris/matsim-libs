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

import com.google.inject.multibindings.MapBinder;

import jakarta.annotation.Nullable;

import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.common.zones.ZoneSystem;
import org.matsim.contrib.common.zones.ZoneSystemUtils;
import org.matsim.contrib.drt.analysis.DrtEventSequenceCollector;
import org.matsim.contrib.drt.estimator.DrtEstimatorModule;
import org.matsim.contrib.drt.estimator.DrtEstimatorParams;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsSetImpl;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.dvrp.run.AbstractDvrpModeModule;
import org.matsim.core.controler.MatsimServices;

/**
 * Wires the observed wait-time and ride-time skims for a single DRT mode: builds its zone system, binds the skim
 * modally, registers it as a controller listener so it refreshes at the end of each iteration, and
 * publishes it into the mode-keyed map that
 * {@link SkimAwareRaptorIntermodalAccessEgress} consumes.
 * <p>
 * Install it through {@link MultiModeDrtSkimsModule} rather than directly, so that the
 * Raptor binding is overridden exactly once.
 *
 * @author Monash Healthy Active Cities
 */
public final class DrtSkimsModule extends AbstractDvrpModeModule {

	private final DrtConfigGroup drtCfg;
	@Nullable
	private final DrtWaitTimeSkimParams waitParams;
	@Nullable
	private final DrtRideTimeSkimParams rideParams;

	public DrtSkimsModule(DrtConfigGroup drtCfg, @Nullable DrtWaitTimeSkimParams waitParams,
			@Nullable DrtRideTimeSkimParams rideParams) {
		super(drtCfg.getMode());
		this.drtCfg = drtCfg;
		this.waitParams = waitParams;
		this.rideParams = rideParams;
	}

	@Override
	public void install() {
		if (waitParams != null) {
			bindModal(ZonalDrtWaitTimeSkim.class).toProvider(modalProvider(getter -> {
				Network network = getter.getModal(Network.class);
				ZoneSystem zoneSystem = ZoneSystemUtils.createZoneSystem(getConfig().getContext(), network,
						waitParams.addOrGetZoneSystemParams());
				return new ZonalDrtWaitTimeSkim(getMode(), waitParams, zoneSystem, network,
						getter.getModal(DrtEventSequenceCollector.class), getter.get(MatsimServices.class),
						getConfig().global().getDefaultDelimiter());
			})).asEagerSingleton();

			addControllerListenerBinding().to(modalKey(ZonalDrtWaitTimeSkim.class));

			MapBinder.newMapBinder(binder(), String.class, DrtWaitTimeSkim.class)
					.addBinding(getMode())
					.to(modalKey(ZonalDrtWaitTimeSkim.class));
		}

		if (rideParams != null) {
			bindModal(ZonalDrtRideTimeSkim.class).toProvider(modalProvider(getter -> {
				Network network = getter.getModal(Network.class);
				// a separate zone system: ride time is keyed on pairs, so it usually wants a coarser
				// resolution than wait time to keep cells from going empty
				ZoneSystem zoneSystem = ZoneSystemUtils.createZoneSystem(getConfig().getContext(), network,
						rideParams.addOrGetZoneSystemParams());
				return new ZonalDrtRideTimeSkim(getMode(), rideParams, zoneSystem, network,
						getter.getModal(DrtEventSequenceCollector.class), getter.get(MatsimServices.class),
						getConfig().global().getDefaultDelimiter());
			})).asEagerSingleton();

			addControllerListenerBinding().to(modalKey(ZonalDrtRideTimeSkim.class));

			MapBinder.newMapBinder(binder(), String.class, DrtRideTimeSkim.class)
					.addBinding(getMode())
					.to(modalKey(ZonalDrtRideTimeSkim.class));
		}

		if (waitParams != null || rideParams != null) {
			installSkimBackedEstimator();
		}
	}

	/**
	 * The second consumer: a {@link org.matsim.contrib.drt.estimator.DrtEstimator} for trips made
	 * entirely by DRT, which never pass through SwissRailRaptor. Under {@code fullSimulation}
	 * {@code DrtModeModule} does not install the estimator infrastructure, so it is installed here;
	 * under {@code estimateAndTeleport} it already has, and only the estimator itself is bound.
	 */
	private void installSkimBackedEstimator() {
		if (drtCfg.getSimulationType() != DrtConfigGroup.SimulationType.estimateAndTeleport) {
			install(new DrtEstimatorModule(getMode(), drtCfg,
					drtCfg.getDrtEstimatorParams().orElseGet(DrtEstimatorParams::new)));
		}
		DrtOptimizationConstraintsSetImpl constraints = drtCfg.addOrGetDrtOptimizationConstraintsParams()
				.addOrGetDefaultDrtOptimizationConstraintsSet();
		DrtEstimatorModule.bindEstimator(binder(), getMode()).toProvider(modalProvider(getter ->
				SkimBackedDrtEstimator.create(
						waitParams != null ? getter.getModal(ZonalDrtWaitTimeSkim.class) : null,
						rideParams != null ? getter.getModal(ZonalDrtRideTimeSkim.class) : null,
						constraints.getMaxTravelTimeAlpha(), constraints.getMaxTravelTimeBeta())))
				.asEagerSingleton();
	}
}
