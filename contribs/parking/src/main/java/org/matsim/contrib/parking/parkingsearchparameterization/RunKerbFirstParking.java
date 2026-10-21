package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.ControllerUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.mobsim.qsim.AbstractQSimModule;
import org.matsim.core.mobsim.qsim.qnetsimengine.ConstantArrivalTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.contrib.parking.parkingsearchparameterization.KerbParkingCapacityEffectParams.EffectMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs a scenario with kerb-first parking accounting and writes the spillover report, optionally with parked cars
 * taking capacity and speed from their street.
 * <p>
 * Usage: {@code RunKerbFirstParking <config.xml> <outputDir> [iterations=0] [minimumLanes=1] [bayLengthMetres=6] [--option=value ...]}.
 * The positional arguments are unchanged from before; options may come anywhere after the first two:
 * <ul>
 * <li>{@code --effectMode=off|link|cell} (default off): see {@link KerbParkingCapacityEffectParams}. Anything but
 * off switches the network to time-variant before the scenario is loaded.</li>
 * <li>{@code --cellSize=300}, {@code --fMin=0.5}, {@code --w=0.5}, {@code --sMin=0.7}, {@code --v=0.2},
 * {@code --updateInterval=60} (seconds), {@code --flowTolerance=0}: the effect's parameters.</li>
 * <li>{@code --banKerbParkingOn=primary,secondary}: further OSM highway classes on which kerb parking is banned, on top
 * of motorway, trunk and the link roads. Applies to supply, occupancy and effect alike, in every mode.</li>
 * </ul>
 * Every car arrival parks: on the kerb if a derived or attributed space is free, off-street otherwise, never refused.
 * Eligibility is {@link KerbParkingEligibility#defaults(double)}: no kerb parking on motorway, trunk and link roads,
 * then the lane threshold.
 * With the effect off, plans, routes and scores are unaffected and this is measurement only. The settings used are
 * logged to the console and to the run's logfile and should be quoted with any result, because the kerb supply they
 * derive depends on how the network was coded (see {@link KerbParkingEligibility}).
 */
public final class RunKerbFirstParking {
	private static final Logger log = LogManager.getLogger(RunKerbFirstParking.class);

	private RunKerbFirstParking() {
	}

	/** Parsed command line. */
	record Options(String configFile, String outputDirectory, int iterations, double minimumLanes, double bayLength,
				   Set<String> bannedClasses, KerbParkingCapacityEffectParams effect) {

		static Options parse(String[] args) {
			List<String> positional = new ArrayList<>();
			EffectMode mode = EffectMode.off;
			double cellSize = KerbParkingCapacityEffectParams.DEFAULT_CELL_SIZE_METRES;
			double fMin = KerbParkingCapacityEffectParams.DEFAULT_F_MIN;
			double w = KerbParkingCapacityEffectParams.DEFAULT_W;
			double sMin = KerbParkingCapacityEffectParams.DEFAULT_S_MIN;
			double v = KerbParkingCapacityEffectParams.DEFAULT_V;
			double updateInterval = KerbParkingCapacityEffectParams.DEFAULT_UPDATE_INTERVAL_SECONDS;
			double flowTolerance = KerbParkingCapacityEffectParams.DEFAULT_FLOW_TOLERANCE;
			Set<String> banned = new HashSet<>();
			for (String arg : args) {
				if (!arg.startsWith("--")) {
					positional.add(arg);
					continue;
				}
				int eq = arg.indexOf('=');
				if (eq < 0) {
					throw new IllegalArgumentException("option without value: " + arg + "; " + USAGE);
				}
				String key = arg.substring(2, eq);
				String value = arg.substring(eq + 1);
				switch (key) {
					case "effectMode" -> mode = EffectMode.valueOf(value.trim().toLowerCase(Locale.ROOT));
					case "cellSize" -> cellSize = Double.parseDouble(value);
					case "fMin" -> fMin = Double.parseDouble(value);
					case "w" -> w = Double.parseDouble(value);
					case "sMin" -> sMin = Double.parseDouble(value);
					case "v" -> v = Double.parseDouble(value);
					case "updateInterval" -> updateInterval = Double.parseDouble(value);
					case "flowTolerance" -> flowTolerance = Double.parseDouble(value);
					case "banKerbParkingOn" -> Arrays.stream(value.split(","))
						.map(String::trim).filter(c -> !c.isEmpty())
						.map(KerbParkingEligibility.OsmHighwayType::normalise)
						.forEach(banned::add);
					default -> throw new IllegalArgumentException("unknown option --" + key + "; " + USAGE);
				}
			}
			if (positional.size() < 2 || positional.size() > 5) {
				throw new IllegalArgumentException(USAGE);
			}
			int iterations = positional.size() > 2 ? Integer.parseInt(positional.get(2)) : 0;
			double minimumLanes = positional.size() > 3 ? Double.parseDouble(positional.get(3)) : KerbParkingEligibility.MinimumLanes.DEFAULT_MINIMUM_LANES;
			double bayLength = positional.size() > 4 ? Double.parseDouble(positional.get(4)) : KerbParkingSupplyParams.DEFAULT_BAY_LENGTH_METRES;
			return new Options(positional.get(0), positional.get(1), iterations, minimumLanes, bayLength, Set.copyOf(banned),
				new KerbParkingCapacityEffectParams(mode, cellSize, fMin, w, sMin, v, updateInterval, flowTolerance));
		}

		KerbParkingEligibility eligibility() {
			return RunKerbFirstParking.eligibility(minimumLanes, bannedClasses);
		}

		String describe() {
			return String.format(Locale.ROOT, "iterations=%d minimumLanes=%s bayLength=%s m banKerbParkingOn=%s (in addition to motorway, trunk, *_link); %s",
				iterations, minimumLanes, bayLength,
				bannedClasses.isEmpty() ? "none" : bannedClasses.stream().sorted().collect(Collectors.joining(",")),
				effect.describe());
		}
	}

	static final String USAGE = "usage: <config.xml> <outputDir> [iterations=0] [minimumLanes=1] [bayLengthMetres=6] "
		+ "[--effectMode=off|link|cell] [--cellSize=300] [--fMin=0.5] [--w=0.5] [--sMin=0.7] [--v=0.2] [--updateInterval=60] "
		+ "[--flowTolerance=0] [--banKerbParkingOn=primary,secondary]";

	/**
	 * {@link KerbParkingEligibility#defaults(double)} with further OSM highway classes banned. With no extra classes
	 * this is exactly the default rule.
	 */
	static KerbParkingEligibility eligibility(double minimumLanes, Set<String> bannedClasses) {
		if (bannedClasses.isEmpty()) {
			return KerbParkingEligibility.defaults(minimumLanes);
		}
		Set<String> excluded = new HashSet<>(KerbParkingEligibility.OsmHighwayType.DEFAULT_EXCLUDED_CLASSES);
		excluded.addAll(bannedClasses);
		return KerbParkingEligibility.allOf(
			new KerbParkingEligibility.OsmHighwayType(KerbParkingEligibility.OsmHighwayType.DEFAULT_ATTRIBUTE, excluded, true),
			new KerbParkingEligibility.MinimumLanes(minimumLanes));
	}

	public static void main(String[] args) {
		Options options = Options.parse(args);

		Config config = ConfigUtils.loadConfig(options.configFile());
		config.controller().setOutputDirectory(options.outputDirectory());
		config.controller().setLastIteration(options.iterations());
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		if (options.effect().mode() != EffectMode.off) {
			// Must precede loading: the network factory decides whether links can carry change events.
			config.network().setTimeVariantNetwork(true);
		}

		String settings = "Kerb-first parking settings: " + options.describe();
		log.info(settings);

		Scenario scenario = ScenarioUtils.loadScenario(config);
		KerbParkingEligibility eligibility = options.eligibility();
		String eligibilitySummary = describeEligibility(scenario, eligibility, options.minimumLanes());
		log.info(eligibilitySummary);

		Controller controller = ControllerUtils.createController(scenario);
		install(controller, eligibility, new KerbParkingSupplyParams(options.bayLength()), options.effect(), 0);
		// Logged again once the run's logfile exists, so the settings are in the output directory too.
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				addControllerListenerBinding().toInstance((StartupListener) event -> {
					log.info(settings);
					log.info(eligibilitySummary);
				});
			}
		});
		controller.run();
	}

	/**
	 * The kerb-first wiring: occupancy observer, derived supply, vehicle handler, spillover report and, unless the
	 * mode is off, the capacity effect. With the effect off this binds exactly what it bound before the effect existed.
	 *
	 * @param parkingSearchSeconds constant time between arrival at the link and parking
	 */
	static void install(Controller controller, KerbParkingEligibility eligibility, KerbParkingSupplyParams supply,
						KerbParkingCapacityEffectParams effect, double parkingSearchSeconds) {
		controller.addOverridingQSimModule(new AbstractQSimModule() {
			@Override
			protected void configureQSim() {
				addQSimComponentBinding("ParkingOccupancyObserver").to(ParkingOccupancyObserver.class);
				addMobsimScopeEventHandlerBinding().to(ParkingOccupancyObserver.class);
				// One instance, bound both ways: the vehicle handler decides who parks, and it learns which vehicles
				// are transit from TransitDriverStartsEvent. Without the event binding it never learns, and transit
				// vehicles whose type has networkMode car are parked and counted as kerb demand.
				bind(ParkingVehicleHandler.class).in(Singleton.class);
				addVehicleHandlerBinding().to(ParkingVehicleHandler.class);
				addMobsimScopeEventHandlerBinding().to(ParkingVehicleHandler.class);
				addParkingSearchTimeCalculatorBinding().toInstance(new ConstantArrivalTime(parkingSearchSeconds));
			}
		});
		controller.addOverridingModule(new AbstractModule() {
			@Override
			public void install() {
				bind(ParkingOccupancyObserver.class).in(Singleton.class);
				bind(ParkingCapacityInitializer.class).to(DerivedParkingCapacityInitializer.class);
				bind(KerbParkingEligibility.class).toInstance(eligibility);
				bind(KerbParkingSupplyParams.class).toInstance(supply);
				bind(ParkingSpilloverReport.class).in(Singleton.class);
				addControllerListenerBinding().to(ParkingOccupancyObserver.class);
				addMobsimListenerBinding().to(ParkingOccupancyObserver.class);
				addControllerListenerBinding().to(ParkingSpilloverReport.class);
				addMobsimListenerBinding().to(ParkingSpilloverReport.class);
			}
		});
		controller.addOverridingModule(new KerbParkingCapacityEffectModule(effect));
	}

	private static String describeEligibility(Scenario scenario, KerbParkingEligibility eligibility, double minimumLanes) {
		long total = scenario.getNetwork().getLinks().size();
		long carLinks = scenario.getNetwork().getLinks().values().stream()
			.filter(l -> l.getAllowedModes().contains(TransportMode.car)).count();
		long eligible = scenario.getNetwork().getLinks().values().stream()
			.filter(l -> l.getAllowedModes().contains(TransportMode.car)).filter(eligibility::isEligible).count();
		long attributed = scenario.getNetwork().getLinks().values().stream()
			.filter(l -> l.getAttributes().getAttribute(ParkingUtils.LINK_ON_STREET_SPOTS) != null).count();
		return String.format(Locale.ROOT, "Kerb parking eligibility: minimumLanes=%s, excluded classes as above -> %d of %d car links eligible (%.1f%%; network has %d links in total); %d links carry an explicit %s attribute",
			minimumLanes, eligible, carLinks, 100.0 * eligible / Math.max(1, carLinks), total, attributed, ParkingUtils.LINK_ON_STREET_SPOTS);
	}

}
