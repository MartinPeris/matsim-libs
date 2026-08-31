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

import jakarta.validation.constraints.Positive;

import org.matsim.contrib.common.util.ReflectiveConfigGroupWithConfigurableParameterSets;
import org.matsim.contrib.common.zones.ZoneSystemParams;
import org.matsim.contrib.common.zones.ZoneSystemUtils;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup.Comment;

/**
 * Configuration for the observed DRT ride-time skim and for feeding it into the SwissRailRaptor
 * intermodal access/egress cost.
 * <p>
 * Add this parameter set to a {@link org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup}
 * for every DRT mode whose ride time should be routed and scored on. A nested zone system parameter
 * set controls the spatial resolution; if none is given, a square grid is used.
 * <p>
 * There is deliberately no {@code defaultRideTimeFactor}. Where nothing has been observed the caller
 * already holds a better number than any constant this package could invent — the leg's own routed
 * estimate — so an unobserved lookup leaves the leg alone instead of scaling it by a guess.
 *
 * @author Monash Healthy Active Cities
 */
public final class DrtRideTimeSkimParams extends ReflectiveConfigGroupWithConfigurableParameterSets {

	public static final String SET_NAME = "rideTimeSkim";

	@Parameter
	@Comment("Width of a time bin in seconds. Smaller bins track the peak better but need more"
			+ " observations per bin to be meaningful. Ride-time observations are spread over"
			+ " origin-destination zone pairs rather than single zones, so a bin is thinner here"
			+ " than in the wait skim for the same demand.")
	@Positive
	private double timeBinSize = 3600;

	@Parameter
	@Comment("Horizon in seconds covered by the skim. Departures later than this are answered from"
			+ " the last bin.")
	@Positive
	private double horizon = 30 * 3600;

	@Parameter
	@Comment("Weight given to the newest iteration when blending it into the running estimate,"
			+ " in (0,1]. 1.0 replaces the estimate outright, which is what makes an events-based"
			+ " skim oscillate between iterations; lower values damp it.")
	@Positive
	private double smoothingWeight = 0.5;

	@Parameter
	@Comment("Minimum number of observations before an origin-destination/time-bin value is trusted."
			+ " Cells below this threshold fall through to the next coarser aggregate.")
	@Positive
	private int minObservations = 1;

	@Parameter
	@Comment("Write the skim to a CSV in each iteration directory.")
	private boolean writeSkimCsv = true;

	private ZoneSystemParams zoneSystemParams;

	public DrtRideTimeSkimParams() {
		super(SET_NAME);
		ZoneSystemUtils.registerDefaultZoneSystems(this::addDefinition, //
				params -> zoneSystemParams = params, //
				() -> zoneSystemParams);
	}

	@Override
	protected void checkConsistency(Config config) {
		super.checkConsistency(config);
		if (smoothingWeight <= 0 || smoothingWeight > 1.0) {
			// zero would leave the first iteration's estimate frozen for the whole run while still
			// reporting itself as measured
			throw new IllegalArgumentException(
					SET_NAME + ".smoothingWeight must be in (0,1] but is " + smoothingWeight);
		}
	}

	/**
	 * Returns the configured zone system, creating a square-grid default on first use.
	 */
	public ZoneSystemParams addOrGetZoneSystemParams() {
		if (zoneSystemParams == null) {
			addParameterSet(new SquareGridZoneSystemParams());
		}
		return zoneSystemParams;
	}

	public double getTimeBinSize() {
		return timeBinSize;
	}

	public void setTimeBinSize(double timeBinSize) {
		this.timeBinSize = timeBinSize;
	}

	public double getHorizon() {
		return horizon;
	}

	public void setHorizon(double horizon) {
		this.horizon = horizon;
	}

	public double getSmoothingWeight() {
		return smoothingWeight;
	}

	public void setSmoothingWeight(double smoothingWeight) {
		this.smoothingWeight = smoothingWeight;
	}

	public int getMinObservations() {
		return minObservations;
	}

	public void setMinObservations(int minObservations) {
		this.minObservations = minObservations;
	}

	public boolean isWriteSkimCsv() {
		return writeSkimCsv;
	}

	public void setWriteSkimCsv(boolean writeSkimCsv) {
		this.writeSkimCsv = writeSkimCsv;
	}

	/**
	 * Number of time bins covering the configured horizon.
	 */
	public int getBinCount() {
		return Math.max(1, (int)Math.ceil(horizon / timeBinSize));
	}
}
