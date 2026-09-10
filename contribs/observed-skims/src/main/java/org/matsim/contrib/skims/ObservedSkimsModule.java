package org.matsim.contrib.skims;

import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import ch.sbb.matsim.routing.pt.raptor.RaptorTransferCostCalculator;

/**
 * Wires the observed transit skims: measures wait times from the mobsim's events and makes
 * SwissRailRaptor charge a transfer for the part of the wait its timetable does not predict.
 * <p>
 * <b>Install this as an overriding module.</b> It replaces {@code RaptorTransferCostCalculator}, which
 * {@code SwissRailRaptorModule} has already bound, and Guice refuses a duplicate binding rather than
 * silently preferring one:
 *
 * <pre>
 * controler.addOverridingModule(new ObservedSkimsModule());
 * </pre>
 *
 * Added as an ordinary module it fails loudly, which is the good case. The bad case, and the reason the
 * integration test exists, is a setup where the override silently does not take: every unit test still
 * passes while routing quietly ignores waiting altogether.
 * <p>
 * With {@code waitTimeEnabled} false nothing at all is bound and SwissRailRaptor keeps its own
 * behaviour exactly, so the module is safe to install unconditionally and switch off in config.
 * <p>
 * The bindings are made with explicit {@link Provider} classes rather than {@code @Provides} methods
 * deliberately. A {@code @Provides} method is registered whatever {@link #install()} decides, so the
 * disabled case would still declare a calculator needing a {@code TransitWaitTime} that nothing binds,
 * and the injector would fail to build at all. Guice validates the graph, not the code path.
 *
 * @author Monash Healthy Active Cities
 */
public final class ObservedSkimsModule extends AbstractModule {

	/** Used when the qsim declares no end time; late enough to cover a service day and its tail. */
	private static final double DEFAULT_END_TIME = 30 * 3600.0;

	@Override
	public void install() {
		if (!ConfigUtils.addOrGetModule(getConfig(), ObservedSkimsConfigGroup.class).isWaitTimeEnabled()) {
			return;
		}
		bind(ObservedTransitWaitTime.class).toProvider(WaitTimeSkimProvider.class).asEagerSingleton();
		bind(TransitWaitTime.class).to(ObservedTransitWaitTime.class);
		addEventHandlerBinding().to(ObservedTransitWaitTime.class);
		addControllerListenerBinding().to(ObservedTransitWaitTime.class);
		bind(RaptorTransferCostCalculator.class).toProvider(TransferCostProvider.class).in(Singleton.class);
	}

	private static final class WaitTimeSkimProvider implements Provider<ObservedTransitWaitTime> {
		private final TransitSchedule schedule;
		private final Config config;

		@Inject
		WaitTimeSkimProvider(TransitSchedule schedule, Config config) {
			this.schedule = schedule;
			this.config = config;
		}

		@Override
		public ObservedTransitWaitTime get() {
			ObservedSkimsConfigGroup params = ConfigUtils.addOrGetModule(config, ObservedSkimsConfigGroup.class);
			return new ObservedTransitWaitTime(schedule, params.getBinSize(),
				config.qsim().getEndTime().orElse(DEFAULT_END_TIME), params.getUpdateWeight());
		}
	}

	private static final class TransferCostProvider implements Provider<RaptorTransferCostCalculator> {
		private final TransitWaitTime waitTime;
		private final Config config;

		@Inject
		TransferCostProvider(TransitWaitTime waitTime, Config config) {
			this.waitTime = waitTime;
			this.config = config;
		}

		@Override
		public RaptorTransferCostCalculator get() {
			ObservedSkimsConfigGroup params = ConfigUtils.addOrGetModule(config, ObservedSkimsConfigGroup.class);
			return new SkimAwareRaptorTransferCostCalculator(waitTime, params.getWaitingCostFactor());
		}
	}
}
