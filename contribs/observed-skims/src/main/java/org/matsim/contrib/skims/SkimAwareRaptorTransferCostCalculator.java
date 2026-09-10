package org.matsim.contrib.skims;

import java.util.function.Supplier;

import ch.sbb.matsim.routing.pt.raptor.DefaultRaptorTransferCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.RaptorParameters;
import ch.sbb.matsim.routing.pt.raptor.RaptorStaticConfig;
import ch.sbb.matsim.routing.pt.raptor.RaptorTransferCostCalculator;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorCore;
import ch.sbb.matsim.routing.pt.raptor.Transfer;

/**
 * Charges a transfer for the waiting the timetable does not predict.
 * <p>
 * SwissRailRaptor prices the wait at a transfer from its schedule: arrive at 08:03, the next service
 * leaves at 08:11, that is eight minutes of waiting at the pt waiting utility. If the service in fact
 * runs late, or the first vehicle is too full to board, the traveller waits longer and the router never
 * finds out. This decorator asks {@link TransitWaitTime} how much longer, and adds the difference to the
 * transfer's cost.
 *
 * <h2>Why the excess and not the whole wait</h2>
 * The core already charges the scheduled part. Adding the observed wait in full would charge it twice
 * and make every transfer look worse than it is, uniformly, which would show up as a sensible-looking
 * shift away from transfers for entirely the wrong reason. {@link TransitWaitTime#excessWaitTime} is
 * defined as observed minus scheduled precisely so this class can charge only the part the core has not
 * already accounted for, and so that a skim with nothing observed charges exactly nothing.
 *
 * <h2>What this cannot do</h2>
 * This is a cost channel, not a time channel. The router still believes the traveller catches the
 * service the timetable promises; it merely prices that itinerary as dearer. A passenger who in reality
 * would miss the connection still makes it here, and the knock-on of missing it, an hour lost to a
 * half-hourly service, is not modelled. Making waiting change which connections are reachable means
 * rebuilding Raptor's schedule-derived data each iteration from observed times, which is a much larger
 * and slower change. The cost channel is the honest cheap approximation and is documented as such
 * rather than dressed up.
 * <p>
 * The same limit applies at the first boarding of a trip, which is not a transfer and so never reaches
 * this hook at all. Excess waiting is therefore priced on second and subsequent boardings only. For a
 * trip with one transfer that is half the boardings; for a direct trip it is none. Do not read a small
 * behavioural response as evidence that waiting does not matter.
 *
 * <h2>waitingCostFactor</h2>
 * Multiplies the excess before it is priced, at 1.0 by default: a minute of unpredicted waiting costs
 * what a minute of predicted waiting costs, and nothing more is claimed. Above 1.0 says unreliable
 * waiting is worse than the timetabled kind, which stated-preference work generally supports but which
 * is a claim about a population and belongs in a scenario's config rather than in a library default.
 * This mirrors the DRT skims' factor of the same name and for the same reasons.
 *
 * @author Monash Healthy Active Cities
 */
public final class SkimAwareRaptorTransferCostCalculator implements RaptorTransferCostCalculator {

	private final RaptorTransferCostCalculator delegate;
	private final TransitWaitTime waitTime;
	private final double waitingCostFactor;

	public SkimAwareRaptorTransferCostCalculator(TransitWaitTime waitTime, double waitingCostFactor) {
		this(new DefaultRaptorTransferCostCalculator(), waitTime, waitingCostFactor);
	}

	public SkimAwareRaptorTransferCostCalculator(RaptorTransferCostCalculator delegate, TransitWaitTime waitTime,
			double waitingCostFactor) {
		if (!(waitingCostFactor >= 0)) {
			throw new IllegalArgumentException("waitingCostFactor must be non-negative, got " + waitingCostFactor);
		}
		this.delegate = delegate;
		this.waitTime = waitTime;
		this.waitingCostFactor = waitingCostFactor;
	}

	@Override
	public double calcTransferCost(SwissRailRaptorCore.PathElement currentPE, Supplier<Transfer> transfer,
			RaptorStaticConfig staticConfig, RaptorParameters raptorParams, int totalTravelTime,
			int totalTransferCount, double existingTransferCosts, double currentTime) {
		double base = delegate.calcTransferCost(currentPE, transfer, staticConfig, raptorParams, totalTravelTime,
				totalTransferCount, existingTransferCosts, currentTime);

		Transfer t = transfer.get();
		if (t == null || t.getToTransitLine() == null || t.getToTransitRoute() == null || t.getToStop() == null) {
			// A transfer that names no boarding service, such as a walk to the egress point. Nothing to
			// look up and nothing to charge.
			return base;
		}

		double excess = waitTime.excessWaitTime(t.getToTransitLine().getId(), t.getToTransitRoute().getId(),
				t.getToStop().getId(), currentTime);
		return base + charge(excess, waitingCostFactor, raptorParams.getMarginalUtilityOfWaitingPt_utl_s());
	}

	/**
	 * The cost of {@code excess} seconds of unpredicted waiting.
	 * <p>
	 * Separated out because {@link Transfer}'s fields are package-private to SwissRailRaptor, so no test
	 * outside that package can build a populated one, and the decorator's own wiring is therefore only
	 * reachable from an integration test. The arithmetic at least should be pinned directly.
	 *
	 * @param marginalUtilityOfWaiting a disutility, and so negative; negating it turns the excess into a
	 *                                 positive cost, the convention
	 *                                 {@code DefaultRaptorIntermodalAccessEgress} also uses
	 */
	static double charge(double excess, double waitingCostFactor, double marginalUtilityOfWaiting) {
		if (excess == 0.0) {
			return 0.0;
		}
		return waitingCostFactor * excess * -marginalUtilityOfWaiting;
	}
}
