package org.matsim.contrib.parking.parkingsearchparameterization;

import com.google.inject.Singleton;
import org.matsim.contrib.parking.parkingsearchparameterization.KerbParkingCapacityEffectParams.EffectMode;
import org.matsim.core.controler.AbstractModule;

/**
 * Binds {@link KerbParkingCapacityEffect} on top of the kerb-first parking wiring, or binds nothing at all when the
 * mode is {@link EffectMode#off}, so that a run with the effect off is the run without this feature.
 * <p>
 * The effect needs a time-variant network, which must be switched on before the scenario is loaded:
 * {@code config.network().setTimeVariantNetwork(true)}. {@link RunKerbFirstParking} does this when the mode is not off.
 */
public final class KerbParkingCapacityEffectModule extends AbstractModule {
	private final KerbParkingCapacityEffectParams params;

	public KerbParkingCapacityEffectModule(KerbParkingCapacityEffectParams params) {
		this.params = params;
	}

	@Override
	public void install() {
		if (params.mode() == EffectMode.off) {
			return;
		}
		bind(KerbParkingCapacityEffectParams.class).toInstance(params);
		bind(KerbParkingCapacityEffect.class).in(Singleton.class);
		addControllerListenerBinding().to(KerbParkingCapacityEffect.class);
		addMobsimListenerBinding().to(KerbParkingCapacityEffect.class);
	}
}
