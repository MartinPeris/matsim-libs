package org.matsim.contrib.skims.run;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.LinkedHashSet;
import java.util.Set;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.skims.ObservedSkimsConfigGroup;
import org.matsim.contrib.skims.ObservedSkimsModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultSelector;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule.DefaultStrategy;
import org.matsim.core.scenario.ScenarioUtils;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;

/**
 * Runs a transit scenario with the observed skims on or off, so the two can be compared.
 * <p>
 * The comparison is the whole point. A skim that changes nothing is not obviously distinguishable from
 * a skim that is not installed, and both look like a successful run. Two runs from the same config,
 * differing in one flag, is the smallest experiment that can tell them apart.
 * <p>
 * Usage: {@code RunObservedSkimsScenario <config.xml> <outputDir> <iterations> <on|off>
 * [waitingCostFactor] [unreliabilityCostFactor]}
 * <p>
 * Replanning strategies are taken from the config. Where a config declares none, as scenarios that
 * expect their runner to supply them do, a standard set is added and logged: re-routing plus
 * change-expected-beta. Without replanning the skims cannot change anything at all, since nothing ever
 * re-routes, so a run with no strategies is refused rather than reported as a null result.
 *
 * @author Monash Healthy Active Cities
 */
public final class RunObservedSkimsScenario {

	private static final Logger log = LogManager.getLogger(RunObservedSkimsScenario.class);

	private RunObservedSkimsScenario() {
	}

	public static void main(String[] args) {
		if (args.length < 4) {
			throw new IllegalArgumentException("usage: <config.xml> <outputDir> <iterations> <on|off> "
					+ "[waitingCostFactor] [unreliabilityCostFactor]");
		}
		String configFile = args[0];
		String outputDir = args[1];
		int iterations = Integer.parseInt(args[2]);
		boolean skimsOn = parseOnOff(args[3]);
		double waitingCostFactor = args.length > 4 ? Double.parseDouble(args[4]) : 1.0;
		double unreliabilityCostFactor = args.length > 5 ? Double.parseDouble(args[5]) : 1.0;

		if (iterations < 1) {
			throw new IllegalArgumentException("iterations must be at least 1: with none, no plan is ever "
					+ "re-routed and a skim cannot change anything, which would look like a null result "
					+ "rather than the absence of an experiment");
		}

		Config config = ConfigUtils.loadConfig(configFile, new SwissRailRaptorConfigGroup(),
				new ObservedSkimsConfigGroup());
		config.controller().setOutputDirectory(outputDir);
		config.controller().setLastIteration(iterations - 1);
		config.controller().setOverwriteFileSetting(
				OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		ObservedSkimsConfigGroup skims = ConfigUtils.addOrGetModule(config, ObservedSkimsConfigGroup.class);
		skims.setWaitTimeEnabled(skimsOn);
		skims.setStopStopTimeEnabled(skimsOn);
		skims.setWaitingCostFactor(waitingCostFactor);
		skims.setUnreliabilityCostFactor(unreliabilityCostFactor);
		log.info("Observed transit skims {}: binSize={}s, updateWeight={}, waitingCostFactor={}, "
				+ "unreliabilityCostFactor={}, iterations={}", skimsOn ? "ON" : "OFF", skims.getBinSize(),
				skims.getUpdateWeight(), waitingCostFactor, unreliabilityCostFactor, iterations);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		addDefaultStrategiesIfNoneDeclared(config, scenario);

		Controler controler = new Controler(scenario);
		// Installed whether or not the skims are on. With them off it binds nothing, so the control run
		// differs from the treatment run in configuration alone and not in which modules were present.
		controler.addOverridingModule(new ObservedSkimsModule());
		controler.run();
	}

	private static boolean parseOnOff(String value) {
		if ("on".equalsIgnoreCase(value)) {
			return true;
		}
		if ("off".equalsIgnoreCase(value)) {
			return false;
		}
		throw new IllegalArgumentException("expected 'on' or 'off', got '" + value + "'");
	}

	/**
	 * Adds a standard set of strategies for every subpopulation the plans actually contain.
	 * <p>
	 * Per subpopulation, because MATSim resolves strategies by subpopulation and refuses to replan one
	 * it has none for. A set added without a subpopulation covers only agents that have none, so on a
	 * scenario like Open Berlin, whose agents are all in "person", nothing would match and the run
	 * would die on the first replanning step. Reading the subpopulations off the loaded population
	 * rather than guessing them is the only way to be right about this for an arbitrary scenario.
	 */
	private static void addDefaultStrategiesIfNoneDeclared(Config config, Scenario scenario) {
		ReplanningConfigGroup replanning = config.replanning();
		if (!replanning.getStrategySettings().isEmpty()) {
			return;
		}
		Set<String> subpopulations = new LinkedHashSet<>();
		for (Person person : scenario.getPopulation().getPersons().values()) {
			Object subpopulation = person.getAttributes().getAttribute("subpopulation");
			subpopulations.add(subpopulation == null ? null : subpopulation.toString());
		}
		if (subpopulations.isEmpty()) {
			subpopulations.add(null);
		}
		for (String subpopulation : subpopulations) {
			ReplanningConfigGroup.StrategySettings reRoute = new ReplanningConfigGroup.StrategySettings();
			reRoute.setStrategyName(DefaultStrategy.ReRoute);
			reRoute.setWeight(0.15);
			reRoute.setSubpopulation(subpopulation);
			replanning.addStrategySettings(reRoute);

			ReplanningConfigGroup.StrategySettings changeExpBeta = new ReplanningConfigGroup.StrategySettings();
			changeExpBeta.setStrategyName(DefaultSelector.ChangeExpBeta);
			changeExpBeta.setWeight(0.85);
			changeExpBeta.setSubpopulation(subpopulation);
			replanning.addStrategySettings(changeExpBeta);
		}
		log.warn("The config declares no replanning strategies, so a standard set was added for each of "
				+ "the subpopulations found in the plans {}: ReRoute at 0.15 and ChangeExpBeta at 0.85. "
				+ "Quote this with any result: the share of agents re-routing bounds how far any skim can "
				+ "move the outcome.", subpopulations);
	}
}
