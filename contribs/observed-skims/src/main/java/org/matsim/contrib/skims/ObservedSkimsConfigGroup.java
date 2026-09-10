package org.matsim.contrib.skims;

import java.util.Map;

import org.matsim.core.config.ReflectiveConfigGroup;

/**
 * Settings for the observed transit skims.
 *
 * @author Monash Healthy Active Cities
 */
public final class ObservedSkimsConfigGroup extends ReflectiveConfigGroup {

	public static final String GROUP_NAME = "observedSkims";

	private double binSize = 900.0;
	private double updateWeight = 0.5;
	private double waitingCostFactor = 1.0;
	private boolean waitTimeEnabled = true;

	public ObservedSkimsConfigGroup() {
		super(GROUP_NAME);
	}

	@Override
	public Map<String, String> getComments() {
		Map<String, String> comments = super.getComments();
		comments.put("binSize",
			"Width of a time-of-day bin, seconds. Narrow bins follow the peak but are observed less often, "
				+ "so more of the table falls back to the timetable. 900 s resolves a peak shoulder while "
				+ "keeping a city-scale run's bins populated.");
		comments.put("updateWeight",
			"How far each iteration's observation moves the stored value, in (0, 1]. The skim feeds the "
				+ "routing that produces the next iteration's traffic, so an undamped skim can oscillate: "
				+ "everyone deserts the stop that was slow, making it fast, then crowds back. 1.0 disables "
				+ "damping and is useful only for a single-iteration measurement run.");
		comments.put("waitingCostFactor",
			"Multiplies the unpredicted part of a wait before it is priced. 1.0, the default, says a minute "
				+ "of unpredicted waiting costs exactly what a minute of timetabled waiting costs and claims "
				+ "nothing more. Above 1.0 says unreliable waiting is worse, which stated-preference work "
				+ "generally supports but which is a claim about a population and belongs in your config, "
				+ "not in a library default. This is NOT the knob for 'waiting is worse than riding': that "
				+ "is scoring.waitingPt, and it applies to every wait rather than only the unpredicted part.");
		comments.put("waitTimeEnabled",
			"Whether to measure transit wait times and charge transfers for the part the timetable does not "
				+ "predict.");
		return comments;
	}

	@StringGetter("binSize")
	public double getBinSize() {
		return binSize;
	}

	@StringSetter("binSize")
	public void setBinSize(double binSize) {
		if (!(binSize > 0)) {
			throw new IllegalArgumentException("binSize must be positive, got " + binSize);
		}
		this.binSize = binSize;
	}

	@StringGetter("updateWeight")
	public double getUpdateWeight() {
		return updateWeight;
	}

	@StringSetter("updateWeight")
	public void setUpdateWeight(double updateWeight) {
		if (!(updateWeight > 0) || updateWeight > 1) {
			throw new IllegalArgumentException("updateWeight must be in (0, 1], got " + updateWeight);
		}
		this.updateWeight = updateWeight;
	}

	@StringGetter("waitingCostFactor")
	public double getWaitingCostFactor() {
		return waitingCostFactor;
	}

	@StringSetter("waitingCostFactor")
	public void setWaitingCostFactor(double waitingCostFactor) {
		if (!(waitingCostFactor >= 0)) {
			throw new IllegalArgumentException("waitingCostFactor must be non-negative, got " + waitingCostFactor);
		}
		this.waitingCostFactor = waitingCostFactor;
	}

	@StringGetter("waitTimeEnabled")
	public boolean isWaitTimeEnabled() {
		return waitTimeEnabled;
	}

	@StringSetter("waitTimeEnabled")
	public void setWaitTimeEnabled(boolean waitTimeEnabled) {
		this.waitTimeEnabled = waitTimeEnabled;
	}
}
