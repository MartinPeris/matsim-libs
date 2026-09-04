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

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.matsim.contrib.common.util.ReflectiveConfigGroupWithConfigurableParameterSets;
import org.matsim.contrib.common.zones.ZoneSystemParams;
import org.matsim.contrib.common.zones.ZoneSystemUtils;
import org.matsim.contrib.common.zones.systems.grid.square.SquareGridZoneSystemParams;
import org.matsim.core.config.Config;
import org.matsim.core.config.ReflectiveConfigGroup.Comment;

/**
 * Configuration for the observed DRT wait-time skim and for feeding it into the SwissRailRaptor
 * intermodal access/egress cost.
 * <p>
 * Add this parameter set to a {@link org.matsim.contrib.drt.extension.DrtWithExtensionsConfigGroup}
 * for every DRT mode whose waiting time should be routed and scored on. A nested zone system
 * parameter set controls the spatial resolution of the skim; if none is given, a square grid is
 * used.
 *
 * @author Monash Healthy Active Cities
 */
public final class DrtWaitTimeSkimParams extends ReflectiveConfigGroupWithConfigurableParameterSets {

	public static final String SET_NAME = "waitTimeSkim";

	@Parameter
	@Comment("Width of a time bin in seconds. Smaller bins track the peak better but need more"
			+ " observations per bin to be meaningful.")
	@Positive
	private double timeBinSize = 900;

	@Parameter
	@Comment("Horizon in seconds covered by the skim. Departures later than this are answered from"
			+ " the last bin.")
	@Positive
	private double horizon = 30 * 3600;

	@Parameter
	@Comment("Weight given to the newest iteration when blending it into the running estimate,"
			+ " in [0,1]. 1.0 replaces the estimate outright, which is what makes an events-based"
			+ " skim oscillate between iterations; lower values damp it.")
	@PositiveOrZero
	private double smoothingWeight = 0.5;

	@Parameter
	@Comment("Minimum number of observations before a zone/time-bin value is trusted. Bins below"
			+ " this threshold fall through to the next coarser aggregate.")
	@Positive
	private int minObservations = 1;

	@Parameter
	@Comment("Wait time in seconds returned when nothing at all has been observed yet, e.g. in the"
			+ " first iteration. This is a last resort, not a model: check the reported"
			+ " DrtWaitTimeSkim.Source before relying on results that rest on it.")
	@PositiveOrZero
	private double defaultWaitTime = 300;

	@Parameter
	@Comment("How onerous waiting for a DRT vehicle is relative to waiting at a transit stop."
			+ " 1.0, the default, means they are equally onerous, which is the neutral position:"
			+ " the wait is charged in full at both ends, but on access SwissRailRaptor refunds an"
			+ " equal amount of platform waiting (arriving later leaves less of it), so the net"
			+ " effect on an access leg that still makes its connection is zero. Above 1.0 prices"
			+ " unscheduled waiting as worse than waiting for a timetabled service.")
	@PositiveOrZero
	private double waitingCostFactor = 1.0;

	@Parameter
	@Comment("Write the skim to a CSV in each iteration directory.")
	private boolean writeSkimCsv = true;

	private ZoneSystemParams zoneSystemParams;

	public DrtWaitTimeSkimParams() {
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

	public double getDefaultWaitTime() {
		return defaultWaitTime;
	}

	public void setDefaultWaitTime(double defaultWaitTime) {
		this.defaultWaitTime = defaultWaitTime;
	}

	public double getWaitingCostFactor() {
		return waitingCostFactor;
	}

	public void setWaitingCostFactor(double waitingCostFactor) {
		this.waitingCostFactor = waitingCostFactor;
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
