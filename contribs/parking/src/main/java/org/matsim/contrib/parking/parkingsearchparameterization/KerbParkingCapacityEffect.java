package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.parking.parkingsearchparameterization.KerbParkingCapacityEffectParams.EffectMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.EventsManagerConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.events.StartupEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.controler.listener.StartupListener;
import org.matsim.core.mobsim.framework.events.MobsimBeforeCleanupEvent;
import org.matsim.core.mobsim.framework.events.MobsimBeforeSimStepEvent;
import org.matsim.core.mobsim.framework.events.MobsimInitializedEvent;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeCleanupListener;
import org.matsim.core.mobsim.framework.listeners.MobsimBeforeSimStepListener;
import org.matsim.core.mobsim.framework.listeners.MobsimInitializedListener;
import org.matsim.core.mobsim.qsim.interfaces.Netsim;
import org.matsim.core.mobsim.qsim.interfaces.NetsimLink;
import org.matsim.core.mobsim.qsim.interfaces.NetsimNetwork;
import org.matsim.core.mobsim.qsim.interfaces.TimeVariantLink;
import org.matsim.core.network.NetworkChangeEvent;
import org.matsim.core.network.NetworkChangeEvent.ChangeType;
import org.matsim.core.network.NetworkChangeEvent.ChangeValue;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.TimeDependentNetwork;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.core.utils.misc.Time;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lets parked cars take capacity and speed from the street they are parked on, as specified in
 * {@link KerbParkingCapacityEffectParams}, by issuing network change events during the mobsim.
 * <h2>Mechanism</h2>
 * The kerb occupancy lives in {@link ParkingOccupancyObserver}, which is an event handler. This class is a
 * {@link MobsimBeforeSimStepListener}: at the start of each time step it collects the links whose on-street occupancy
 * changed (the observer keeps a dirty set, so nothing scans the whole network), updates the occupied share of the
 * affected link or cell, and, every {@code updateIntervalSeconds}, issues one absolute {@link NetworkChangeEvent} per
 * link whose factors changed. Each event is added to the network and the running QSim link is told to recalculate its
 * flow capacity and storage, which is what {@code NetworkChangeEventsEngine.addNetworkChangeEvent} does internally.
 * That method is not used because it logs three warnings per event and checks for duplicates with a linear scan of
 * the network's whole change-event queue, which is quadratic over the hundreds of thousands of events a city run
 * produces.
 * <h2>Lag</h2>
 * Reading occupancy from an event handler inside the mobsim is sound only if every event of step {@code t-1} has been
 * handled when step {@code t} begins. MATSim's default events manager ({@code SimStepParallelEventsManagerImpl})
 * guarantees this: {@code QSim.doSimStep} calls {@code events.afterSimStep(t-1)}, which waits on a barrier until all
 * handlers have caught up, before it fires the before-sim-step listeners of {@code t}. The observer itself already
 * depends on it (its {@code checkTime} throws otherwise); {@link #notifyStartup} refuses an unsynchronised events
 * manager. A car that parks or leaves at time {@code t} therefore changes the factors at the first update at or after
 * {@code t + 1 step}, i.e. after a lag of at least one time step and at most {@code updateIntervalSeconds + 1 step}.
 * <h2>Units</h2>
 * Values are absolute and computed from the link's base values, never from the current ones, so changes cannot
 * compound and {@code s = 0} restores the base exactly ({@code base * 1.0 == base}):
 * <ul>
 * <li>flow capacity in vehicles per second, {@code link.getCapacity() / link.getCapacityPeriod() * f}, which is the
 * full-population value the link carries. {@code QueueWithBuffer.calculateFlowCapacity} multiplies it by the
 * {@code flowCapacityFactor} itself, so the factor must not be applied here;</li>
 * <li>number of lanes {@code baseLanes * f}. {@code QueueWithBuffer.calculateStorageCapacity} turns it into storage as
 * {@code length * lanes / cellSize * storageCapacityFactor}, again applying the sample factor itself. Under
 * {@code kinematicWaves} the lanes also set the inflow capacity from the fundamental diagram;</li>
 * <li>free speed {@code baseFreespeed * g} in m/s. The QSim pulls free speed per vehicle when it enters the link, so
 * a vehicle already on the link keeps the speed it entered with.</li>
 * </ul>
 * <h2>Why updates are not every second</h2>
 * {@code QueueWithBuffer.recalcTimeVariantAttributes} sets the link's accumulated outflow capacity to one full time
 * step's worth whenever a capacity or lanes change arrives. At a 1% or 10% sample a link earns well under one vehicle of
 * outflow per second, so after each departing vehicle the accumulator is negative for tens or hundreds of seconds;
 * the recalculation forgives that debt and lets the next queued vehicle out at once. Each change event can therefore
 * release at most one extra vehicle from a link that has a queue. Re-evaluating every second would, on a congested
 * link in a busy cell, add more outflow than the reduction removes. Three things bound it: the update interval (at most
 * one change per link per interval); events that change only the free speed, which need no recalculation and are
 * issued without one; and the optional {@code flowTolerance}, which skips capacity changes smaller than itself. The
 * {@code capacityChangeEvents} columns of the outputs count the changes that can have released an extra vehicle.
 * <h2>Outputs</h2>
 * Per iteration, copied to the output directory at shutdown:
 * <ul>
 * <li>{@value #NETWORK_FILE}: per hour, the lane-km-weighted time-mean occupied share, flow factor and speed factor
 * over the links in scope, the time-mean and maximum number of links with {@code f < 1}, the number of change
 * events issued, and how many of them changed flow capacity and lanes;</li>
 * <li>{@value #PER_LINK_FILE}: per link that received a change, its base values, the time it spent with
 * {@code f < 1}, its lowest factors and its number of change events, all and capacity-changing.</li>
 * </ul>
 * Each change event is added to the network for the duration of the mobsim only; the input change events are
 * restored before cleanup, so a later iteration starts from the input network and the output network carries none of
 * them.
 */
public class KerbParkingCapacityEffect implements StartupListener, IterationEndsListener, ShutdownListener,
	MobsimInitializedListener, MobsimBeforeSimStepListener, MobsimBeforeCleanupListener {
	private static final Logger log = LogManager.getLogger(KerbParkingCapacityEffect.class);

	public static final String NETWORK_FILE = "parking_capacity_effect_network.csv";
	public static final String PER_LINK_FILE = "parking_capacity_effect_per_link.csv";
	public static final double BIN_SIZE_SECONDS = 3600.0;

	/** Applies one change event to the network and to the running mobsim. Replaceable for tests. */
	interface ChangeApplier {
		void apply(NetworkChangeEvent event);
	}

	private final ParkingOccupancyObserver observer;
	private final Network network;
	private final KerbParkingCapacityEffectParams params;
	private final OutputDirectoryHierarchy outputDirectoryHierarchy;
	private final Config config;

	private ChangeApplier applier;
	private List<NetworkChangeEvent> inputChangeEvents = List.of();
	private boolean started;
	private boolean finished;

	// Links in scope, indexed 0..n-1 in id order.
	private Link[] links;
	private int[] groupOfLink;
	private double[] baseCapacityPerSecond;
	private double[] baseLanes;
	private double[] baseFreespeed;
	private double[] laneKm;
	private double[] appliedShare;
	private double[] appliedFlowFactor;
	private double[] appliedSpeedFactor;
	private double[] reducedSince;
	private double[] secondsReduced;
	private double[] minFlowFactor;
	private double[] minSpeedFactor;
	private int[] changeEvents;
	private int[] capacityChanges;

	// Groups: one per link in link mode, one per cell in cell mode.
	private String[] groupLabel;
	private int[][] groupMembers;
	private long[] groupCapacity;
	private long[] groupOccupancy;
	private BitSet dirtyGroups;
	/** Observer index -> group whose share the link's kerb contributes to, or -1. */
	private int[] groupOfObserverIndex;
	/** Observer index -> on-street occupancy last added into its group. */
	private int[] lastSeenOccupancy;

	private double nextUpdateTime;
	private double lastIntegrationTime;
	private double totalLaneKm;
	private double sumShare;
	private double sumFlowFactor;
	private double sumSpeedFactor;
	private int linksReduced;
	private long totalChangeEvents;
	private long capacityChangeEvents;
	private final List<Bin> bins = new ArrayList<>();

	private static final class Bin {
		double covered;
		double share;
		double flowFactor;
		double speedFactor;
		double linksReduced;
		int maxLinksReduced;
		long changeEvents;
		long capacityChangeEvents;
	}

	@Inject
	public KerbParkingCapacityEffect(ParkingOccupancyObserver observer, Network network, KerbParkingCapacityEffectParams params,
									 OutputDirectoryHierarchy outputDirectoryHierarchy, Config config) {
		this.observer = observer;
		this.network = network;
		this.params = params;
		this.outputDirectoryHierarchy = outputDirectoryHierarchy;
		this.config = config;
	}

	@Override
	public void notifyStartup(StartupEvent event) {
		checkPreconditions(config, params);
		log.info("Kerb parking capacity effect: {}", params.describe());
	}

	static void checkPreconditions(Config config, KerbParkingCapacityEffectParams params) {
		if (params.mode() == EffectMode.off) {
			throw new IllegalStateException("the capacity effect is bound with effectMode=off; bind nothing instead, so the run is identical to one without it");
		}
		if (!config.network().isTimeVariantNetwork()) {
			throw new IllegalStateException("the kerb parking capacity effect needs a time-variant network: call config.network().setTimeVariantNetwork(true) before the scenario is loaded (RunKerbFirstParking does this)");
		}
		EventsManagerConfigGroup em = config.eventsManager();
		boolean unsynchronised = Boolean.TRUE.equals(em.getOneThreadPerHandler())
			|| (em.getNumberOfThreads() != null && Boolean.FALSE.equals(em.getSynchronizeOnSimSteps()));
		if (unsynchronised) {
			throw new IllegalStateException("the kerb parking capacity effect reads parking occupancy at the start of each time step and needs every event of the previous step handled by then; use an events manager that synchronises on sim steps (eventsManager.synchronizeOnSimSteps=true, oneThreadPerHandler=false)");
		}
	}

	@Override
	@SuppressWarnings("rawtypes")
	public void notifyMobsimInitialized(MobsimInitializedEvent e) {
		if (!(network instanceof TimeDependentNetwork)) {
			throw new IllegalStateException("network is not time-variant; set config.network().setTimeVariantNetwork(true) before loading the scenario");
		}
		NetsimNetwork netsimNetwork = ((Netsim) e.getQueueSimulation()).getNetsimNetwork();
		reset(event -> {
			NetworkUtils.addNetworkChangeEvent(network, event);
			for (Link link : event.getLinks()) {
				if (event.getFlowCapacityChange() == null && event.getLanesChange() == null) {
					continue;
				}
				NetsimLink netsimLink = netsimNetwork.getNetsimLink(link.getId());
				if (netsimLink instanceof TimeVariantLink timeVariant) {
					timeVariant.recalcTimeVariantAttributes();
				} else if (netsimLink != null) {
					throw new IllegalStateException("mobsim link " + link.getId() + " is not time-variant");
				}
			}
		});
	}

	@Override
	public void notifyMobsimBeforeSimStep(MobsimBeforeSimStepEvent e) {
		step(e.getSimulationTime());
	}

	@Override
	public void notifyMobsimBeforeCleanup(MobsimBeforeCleanupEvent e) {
		finish(((Netsim) e.getQueueSimulation()).getSimTimer().getTimeOfDay());
	}

	/** Starts a mobsim: forgets the previous one and remembers the input change events, to restore them at the end. */
	void reset(ChangeApplier changeApplier) {
		this.applier = changeApplier;
		this.inputChangeEvents = network instanceof TimeDependentNetwork
			? new ArrayList<>(NetworkUtils.getNetworkChangeEvents(network)) : List.of();
		if (!inputChangeEvents.isEmpty()) {
			log.warn("the input network has {} change events; the kerb parking capacity effect sets absolute values from the base link attributes and overrides them on the links it changes", inputChangeEvents.size());
		}
		this.started = false;
		this.finished = false;
		this.bins.clear();
		this.totalChangeEvents = 0;
		this.capacityChangeEvents = 0;
	}

	/** One time step: collect occupancy changes, and re-evaluate the factors if an update is due. */
	void step(double now) {
		if (!started) {
			start(now);
		}
		integrate(now);
		observer.drainOnStreetChanges(index -> {
			int group = groupOfObserverIndex[index];
			if (group < 0) {
				return;
			}
			int occupancy = observer.onStreetOccupancyAt(index);
			groupOccupancy[group] += occupancy - lastSeenOccupancy[index];
			lastSeenOccupancy[index] = occupancy;
			dirtyGroups.set(group);
		});
		if (now >= nextUpdateTime) {
			update(now);
			nextUpdateTime = now + params.updateIntervalSeconds();
		}
		Bin bin = bin(now);
		bin.maxLinksReduced = Math.max(bin.maxLinksReduced, linksReduced);
	}

	/** Ends a mobsim: closes the statistics and takes this mobsim's change events off the network again. */
	void finish(double now) {
		if (!started || finished) {
			return;
		}
		integrate(now);
		for (int i = 0; i < links.length; i++) {
			if (!Double.isNaN(reducedSince[i])) {
				secondsReduced[i] += now - reducedSince[i];
				reducedSince[i] = Double.NaN;
			}
		}
		if (network instanceof TimeDependentNetwork) {
			NetworkUtils.setNetworkChangeEvents(network, inputChangeEvents);
		}
		finished = true;
		log.info("Kerb parking capacity effect: {} change events issued ({} of them changing flow capacity and lanes) on {} links in scope ({} lane-km) up to {}",
			totalChangeEvents, capacityChangeEvents, links.length, String.format(Locale.ROOT, "%.1f", totalLaneKm), Time.writeTime(now));
	}

	private void start(double now) {
		started = true;
		List<Link> sorted = network.getLinks().values().stream()
			.sorted(Comparator.comparing(l -> l.getId().toString()))
			.map(l -> (Link) l)
			.toList();

		// Build groups: contributors are the links whose kerb counts towards the share; members receive it.
		Map<Object, List<Link>> groups = new LinkedHashMap<>();
		for (Link link : sorted) {
			int index = observer.indexOf(link.getId());
			if (index < 0 || !observer.kerbParkingPermittedAt(index)) {
				continue;
			}
			if (params.mode() == EffectMode.link) {
				if (observer.onStreetCapacityAt(index) > 0) {
					groups.put(link.getId(), List.of(link));
				}
			} else {
				groups.computeIfAbsent(cellOf(link), k -> new ArrayList<>()).add(link);
			}
		}

		int linkCount = network.getLinks().size();
		groupOfObserverIndex = new int[linkCount];
		Arrays.fill(groupOfObserverIndex, -1);
		lastSeenOccupancy = new int[linkCount];

		List<String> labels = new ArrayList<>();
		List<int[]> members = new ArrayList<>();
		List<Long> capacities = new ArrayList<>();
		List<Long> occupancies = new ArrayList<>();
		List<Link> scope = new ArrayList<>();
		List<Integer> groupOfScope = new ArrayList<>();
		for (Map.Entry<Object, List<Link>> entry : groups.entrySet()) {
			long capacity = 0;
			long occupancy = 0;
			for (Link link : entry.getValue()) {
				int index = observer.indexOf(link.getId());
				capacity += observer.onStreetCapacityAt(index);
				occupancy += observer.onStreetOccupancyAt(index);
			}
			if (capacity <= 0) {
				continue;
			}
			int group = labels.size();
			labels.add(entry.getKey().toString());
			capacities.add(capacity);
			occupancies.add(occupancy);
			int[] m = new int[entry.getValue().size()];
			for (int k = 0; k < m.length; k++) {
				Link link = entry.getValue().get(k);
				int index = observer.indexOf(link.getId());
				groupOfObserverIndex[index] = group;
				lastSeenOccupancy[index] = observer.onStreetOccupancyAt(index);
				m[k] = scope.size();
				scope.add(link);
				groupOfScope.add(group);
			}
			members.add(m);
		}
		// Changes recorded before the first step are part of the state just read.
		observer.drainOnStreetChanges(index -> lastSeenOccupancy[index] = observer.onStreetOccupancyAt(index));

		int n = scope.size();
		links = scope.toArray(new Link[0]);
		groupOfLink = groupOfScope.stream().mapToInt(Integer::intValue).toArray();
		baseCapacityPerSecond = new double[n];
		baseLanes = new double[n];
		baseFreespeed = new double[n];
		laneKm = new double[n];
		appliedShare = new double[n];
		appliedFlowFactor = new double[n];
		appliedSpeedFactor = new double[n];
		reducedSince = new double[n];
		secondsReduced = new double[n];
		minFlowFactor = new double[n];
		minSpeedFactor = new double[n];
		changeEvents = new int[n];
		capacityChanges = new int[n];
		totalLaneKm = 0;
		for (int i = 0; i < n; i++) {
			Link link = links[i];
			// The no-argument getters are the base values, untouched by change events.
			baseCapacityPerSecond[i] = link.getCapacity() / link.getCapacityPeriod();
			baseLanes[i] = link.getNumberOfLanes();
			baseFreespeed[i] = link.getFreespeed();
			laneKm[i] = link.getLength() / 1000.0 * baseLanes[i];
			totalLaneKm += laneKm[i];
		}
		Arrays.fill(appliedFlowFactor, 1.0);
		Arrays.fill(appliedSpeedFactor, 1.0);
		Arrays.fill(reducedSince, Double.NaN);
		Arrays.fill(minFlowFactor, 1.0);
		Arrays.fill(minSpeedFactor, 1.0);
		sumShare = 0;
		sumFlowFactor = totalLaneKm;
		sumSpeedFactor = totalLaneKm;
		linksReduced = 0;

		groupLabel = labels.toArray(new String[0]);
		groupMembers = members.toArray(new int[0][]);
		groupCapacity = capacities.stream().mapToLong(Long::longValue).toArray();
		groupOccupancy = occupancies.stream().mapToLong(Long::longValue).toArray();
		dirtyGroups = new BitSet(groupLabel.length);
		// Seeded occupancy takes effect at the first update.
		dirtyGroups.set(0, groupLabel.length);

		nextUpdateTime = now;
		lastIntegrationTime = now;
		log.info("Kerb parking capacity effect ({}): {} groups, {} links in scope, {} lane-km",
			params.mode(), groupLabel.length, n, String.format(Locale.ROOT, "%.1f", totalLaneKm));
	}

	/** The cell of a link's midpoint, as a label {@code "x_y"} of integer cell coordinates. */
	String cellOf(Link link) {
		Coord from = link.getFromNode().getCoord();
		Coord to = link.getToNode().getCoord();
		long cx = (long) Math.floor((from.getX() + to.getX()) / 2.0 / params.cellSizeMetres());
		long cy = (long) Math.floor((from.getY() + to.getY()) / 2.0 / params.cellSizeMetres());
		return cx + "_" + cy;
	}

	private void update(double now) {
		for (int group = dirtyGroups.nextSetBit(0); group >= 0; group = dirtyGroups.nextSetBit(group + 1)) {
			double share = Math.min(1.0, Math.max(0.0, (double) groupOccupancy[group] / groupCapacity[group]));
			double targetF = params.flowFactor(share);
			double g = params.speedFactor(share);
			for (int i : groupMembers[group]) {
				sumShare += laneKm[i] * (share - appliedShare[i]);
				appliedShare[i] = share;
				double f = params.flowFactorToApply(appliedFlowFactor[i], targetF);
				if (f != appliedFlowFactor[i] || g != appliedSpeedFactor[i]) {
					emit(i, f, g, now);
				}
			}
		}
		dirtyGroups.clear();
	}

	private void emit(int i, double f, double g, double now) {
		NetworkChangeEvent event = new NetworkChangeEvent(now);
		event.addLink(links[i]);
		// Only what changed. A speed-only event needs no recalculation of the mobsim link, which pulls free speed
		// itself, and so does not touch its outflow accumulator.
		if (f != appliedFlowFactor[i]) {
			event.setFlowCapacityChange(new ChangeValue(ChangeType.ABSOLUTE_IN_SI_UNITS, baseCapacityPerSecond[i] * f));
			event.setLanesChange(new ChangeValue(ChangeType.ABSOLUTE_IN_SI_UNITS, baseLanes[i] * f));
			capacityChangeEvents++;
			capacityChanges[i]++;
			bin(now).capacityChangeEvents++;
		}
		if (g != appliedSpeedFactor[i]) {
			event.setFreespeedChange(new ChangeValue(ChangeType.ABSOLUTE_IN_SI_UNITS, baseFreespeed[i] * g));
		}
		applier.apply(event);

		sumFlowFactor += laneKm[i] * (f - appliedFlowFactor[i]);
		sumSpeedFactor += laneKm[i] * (g - appliedSpeedFactor[i]);
		boolean wasReduced = appliedFlowFactor[i] < 1.0;
		boolean isReduced = f < 1.0;
		if (!wasReduced && isReduced) {
			linksReduced++;
			reducedSince[i] = now;
		} else if (wasReduced && !isReduced) {
			linksReduced--;
			secondsReduced[i] += now - reducedSince[i];
			reducedSince[i] = Double.NaN;
		}
		appliedFlowFactor[i] = f;
		appliedSpeedFactor[i] = g;
		minFlowFactor[i] = Math.min(minFlowFactor[i], f);
		minSpeedFactor[i] = Math.min(minSpeedFactor[i], g);
		changeEvents[i]++;
		totalChangeEvents++;
		bin(now).changeEvents++;
	}

	/** Adds the state held since the last call to the hourly time integrals. */
	private void integrate(double now) {
		double t = lastIntegrationTime;
		while (t < now) {
			int k = (int) Math.floor(t / BIN_SIZE_SECONDS);
			double end = Math.min(now, (k + 1) * BIN_SIZE_SECONDS);
			double dt = end - t;
			Bin bin = bin(t);
			bin.covered += dt;
			bin.share += sumShare * dt;
			bin.flowFactor += sumFlowFactor * dt;
			bin.speedFactor += sumSpeedFactor * dt;
			bin.linksReduced += linksReduced * (double) dt;
			bin.maxLinksReduced = Math.max(bin.maxLinksReduced, linksReduced);
			t = end;
		}
		lastIntegrationTime = Math.max(lastIntegrationTime, now);
	}

	private Bin bin(double time) {
		int k = Math.max(0, (int) Math.floor(time / BIN_SIZE_SECONDS));
		while (bins.size() <= k) {
			bins.add(new Bin());
		}
		return bins.get(k);
	}

	// ---- accessors for tests ----

	long getTotalChangeEvents() {
		return totalChangeEvents;
	}

	/** Change events that changed flow capacity and lanes, each of which resets the link's outflow accumulator. */
	long getCapacityChangeEvents() {
		return capacityChangeEvents;
	}

	/** Flow factor currently applied to the link, 1 if it is not in scope. */
	double appliedFlowFactor(Id<Link> linkId) {
		int i = scopeIndex(linkId);
		return i < 0 ? 1.0 : appliedFlowFactor[i];
	}

	double appliedSpeedFactor(Id<Link> linkId) {
		int i = scopeIndex(linkId);
		return i < 0 ? 1.0 : appliedSpeedFactor[i];
	}

	boolean isInScope(Id<Link> linkId) {
		return scopeIndex(linkId) >= 0;
	}

	private int scopeIndex(Id<Link> linkId) {
		for (int i = 0; i < links.length; i++) {
			if (links[i].getId().equals(linkId)) {
				return i;
			}
		}
		return -1;
	}

	// ---- output ----

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		if (!started) {
			return;
		}
		writeNetwork(outputDirectoryHierarchy.getIterationFilename(event.getIteration(), NETWORK_FILE));
		writePerLink(outputDirectoryHierarchy.getIterationFilename(event.getIteration(), PER_LINK_FILE));
	}

	@Override
	public void notifyShutdown(ShutdownEvent event) {
		for (String file : new String[]{NETWORK_FILE, PER_LINK_FILE}) {
			String from = outputDirectoryHierarchy.getIterationFilename(event.getIteration(), file);
			if (!new java.io.File(from).exists()) {
				continue;
			}
			try {
				IOUtils.copyFile(from, outputDirectoryHierarchy.getOutputFilename(file));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	void writeNetwork(String file) {
		log.info("Writing kerb parking capacity effect per hour to {}", file);
		try (BufferedWriter writer = IOUtils.getBufferedWriter(file);
			 CSVPrinter csv = new CSVPrinter(writer, format("binStart", "coveredSeconds", "effectMode", "linksInScope",
				 "laneKmInScope", "meanKerbShare", "meanFlowFactor", "meanSpeedFactor", "meanLinksWithReducedFlow",
				 "maxLinksWithReducedFlow", "changeEvents", "capacityChangeEvents"))) {
			for (int k = 0; k < bins.size(); k++) {
				Bin bin = bins.get(k);
				if (bin.covered <= 0 && bin.changeEvents == 0) {
					continue;
				}
				double norm = bin.covered * totalLaneKm;
				csv.printRecord(Time.writeTime(k * BIN_SIZE_SECONDS), num(bin.covered), params.mode(), links.length,
					num(totalLaneKm),
					num(norm > 0 ? bin.share / norm : Double.NaN),
					num(norm > 0 ? bin.flowFactor / norm : Double.NaN),
					num(norm > 0 ? bin.speedFactor / norm : Double.NaN),
					num(bin.covered > 0 ? bin.linksReduced / bin.covered : Double.NaN),
					bin.maxLinksReduced, bin.changeEvents, bin.capacityChangeEvents);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	void writePerLink(String file) {
		log.info("Writing kerb parking capacity effect per link to {}", file);
		try (BufferedWriter writer = IOUtils.getBufferedWriter(file);
			 CSVPrinter csv = new CSVPrinter(writer, format("linkId", "group", "baseCapacity", "baseLanes",
				 "baseFreespeed", "laneKm", "secondsWithReducedFlow", "minFlowFactor", "minSpeedFactor", "changeEvents", "capacityChangeEvents"))) {
			for (int i = 0; i < links.length; i++) {
				if (changeEvents[i] == 0) {
					continue;
				}
				csv.printRecord(links[i].getId(), groupLabel[groupOfLink[i]], num(links[i].getCapacity()), num(baseLanes[i]),
					num(baseFreespeed[i]), num(laneKm[i]), num(secondsReduced[i]), num(minFlowFactor[i]),
					num(minSpeedFactor[i]), changeEvents[i], capacityChanges[i]);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String num(double x) {
		if (Double.isNaN(x)) {
			return "";
		}
		return String.format(Locale.ROOT, "%.6f", x);
	}

	private CSVFormat format(String... header) {
		return CSVFormat.Builder.create()
			.setDelimiter(config.global().getDefaultDelimiter().charAt(0))
			.setHeader(header)
			.build();
	}
}
