# Observed transit skims

## What problem this solves

SwissRailRaptor prices a transfer's wait from its timetable. Arrive at 08:03, the next service is
booked for 08:11, that is eight minutes at the pt waiting utility. If the service in fact runs late,
or the first vehicle is too full to board, the traveller waits longer — and the router never finds
out. It keeps routing people through the stop that fails them, iteration after iteration, because
nothing in the loop carries that information back.

The DRT side of this repository already fixed the equivalent blindness for on-demand feeders; see
`org.matsim.contrib.drt.extension.skims`. This package is the scheduled half, and it is deliberately
built to the same shape so that a MaaS itinerary — walk, hail, ride, transfer, ride — can eventually
be costed on observed times end to end rather than half on measurement and half on wishful thinking.

## What is recorded

A **wait time skim**: mean wait per line, route, stop and time-of-day bin, rebuilt from each
iteration's events and damped against the previous estimate.

```
wait = PersonEntersVehicleEvent.time - PersonDepartureEvent.time
```

Measured from the moment the traveller becomes ready to board, not from any scheduled time, so it
captures both a late service and a passenger left behind by a full one — the two failures a
timetable cannot express.

It is filed under the bin the passenger **arrived** in, not the one they boarded in. That is the bin
a router queries: the question it asks is "someone reaches this stop at 08:03, what should they
expect?", and answering it with waits indexed by boarding time would systematically shift long waits
into the following bin, which is exactly where they did not happen.

The measurement descends from Sergio Ordóñez's `eventsBasedPTRouter` contrib by way of
`PSimWaitTimeCalculator`. What is new here is that it is damped, and that it is offered to routing
rather than to a surrogate mobsim.

## The fallback is the point

A stop and bin nobody boarded at returns the wait the timetable implies. Both interface methods are
contractually forbidden from returning infinity or NaN.

That single guarantee is what makes the rest of the package simple. A router can consult the skim
unconditionally — in iteration 0, in an off-peak bin, at a stop no one used — with no null check, no
`Optional`, and no "not yet observed" branch to get wrong. Every consumer in this package is shorter
for it.

**The fallback is the expected wait over the bin, not the wait at its edge.** Sampling the sawtooth
at one instant makes the answer depend on where departures happen to fall relative to the bin
boundary: a service every ten minutes, read at the end of an hour-long bin, gives a wait of exactly
zero — in every bin, for every such route. `PSimWaitTimeCalculator` has this defect today. Here the
wait is integrated across the bin, giving half the headway for a regular service, which is the
textbook result and the only defensible thing to tell a router that has no observation.

## excessWaitTime, and why the absolute wait is the wrong number to charge

`excessWaitTime` is observed minus scheduled. It, and not `waitTime`, is what a cost consumer wants.

The core already prices the scheduled part of the wait. Handing it the absolute figure would charge
that part twice, making every transfer dearer by roughly a constant. The failure mode is nasty
because the result looks plausible: travellers shift away from transferring, which is the direction
one might expect the feature to push, for a reason that has nothing to do with any service being
unreliable.

Where nothing has been observed the excess is exactly zero, so installing the skim changes no cost
until it has something to say. There is no default to mis-set.

The excess may be negative, when a service runs more reliably than its timetable promises. That is
a real observation and is reported as one. Flooring it at zero would leave a skim that can punish
but never reward, which would bias every scenario in one direction.

## What this cannot do

**It is a cost channel, not a time channel.** The router still believes the traveller catches the
service the timetable promises; it merely prices that itinerary as dearer. A passenger who in
reality would miss the connection still makes it here, and the knock-on — an hour lost to a
half-hourly service — is not modelled at all. That knock-on is usually far larger than the cost
term, so this package understates the true penalty of unreliability, and does so silently.

**The first boarding of a trip is not a transfer**, so it never reaches
`RaptorTransferCostCalculator`. Excess waiting is priced on second and subsequent boardings only:
half the boardings of a one-transfer trip, none at all of a direct one. In a network where most
trips are direct this package will appear to do almost nothing, and that appearance is an artefact
of where the hook sits, not a finding about waiting.

Do not read a small behavioural response as evidence that waiting does not matter. Read it as the
measurement it is: the cost-channel effect, on transfers only.

Fixing both limits means the same thing — rebuilding Raptor's schedule-derived data each iteration
with observed times baked in, so that waiting changes which connections are *reachable* rather than
only what they cost. That is a much larger and much slower change, and it is the honest next step
rather than a further tuning of this one.

## waitingCostFactor

Multiplies the excess before it is priced. **1.0, the default, is the neutral position**: a minute of
unpredicted waiting costs exactly what a minute of timetabled waiting costs, and nothing more is
claimed. Every other value is an assertion about a population, and an assertion of that kind belongs
in a scenario's config, defended by whoever set it, rather than inherited from a library default.

**This is not the knob for "waiting is worse than riding".** That is `scoring.waitingPt`, and it
correctly applies to every wait rather than only the unpredicted part. MATSim's
`marginalUtlOfWaitingPt` defaults to the pt mode's `marginalUtilityOfTraveling`, so out of the box
waiting is priced exactly like sitting on the train, which stated-preference work broadly
contradicts. Using `waitingCostFactor` to compensate would conflate the two and would apply the
correction only where a skim happens to have an observation.

The defensible reason to raise it above 1.0 is narrower: this skim reports a **mean**, and
travellers respond to the distribution. A service that is usually punctual and occasionally
catastrophic is worse than its mean suggests. A factor around 1.2–1.5 is a crude proxy for that
variance penalty — a proxy, not a measurement, which is exactly why it is not the default.

## Damping

The skim feeds the routing that produces the next iteration's traffic, so it sits inside a feedback
loop. Undamped, it oscillates: everyone deserts the stop that was slow last iteration, which makes
it fast, and they crowd back. `updateWeight` folds each iteration's observation part way into the
stored value, the same remedy MATSim's own travel-time feedback uses.

A bin with no observation this iteration **keeps its previous value** rather than decaying towards
zero. No observation is absence of evidence, not evidence of no wait, and a decaying skim would
quietly restore the blindness this package exists to remove.

A value becomes visible only when an iteration is consolidated, never as observations accumulate. A
router that queried a half-built table mid-mobsim would get a mean over whoever happened to have
boarded so far, which is a sample biased towards the early morning.

## Two core changes this package required

Both are the same defect in different places: a public extension point that could not be extended.

- `SwissRailRaptorCore.PathElement` was package-private while appearing in the signature of the
  public `RaptorTransferCostCalculator`, so no implementation outside `ch.sbb.matsim.routing.pt.raptor`
  could compile. It is now public, with its fields left package-private, so an external
  implementation can pass one along without reading or altering search state.
- `Transfer`'s fields are package-private, so no test outside that package can build a populated one.
  This is why the decorator's own wiring is covered by `ObservedSkimsIT` driving a real controler
  rather than by a unit test, and why only the charge arithmetic is pinned directly.

The second is also a warning. An overriding module that fails to replace `RaptorTransferCostCalculator`
leaves every unit test in this package passing while routing ignores waiting altogether. That has
exactly one symptom and it appears only at controler level, which is what `ObservedSkimsIT` asserts
on first.

## Installing

```java
controler.addOverridingModule(new ObservedSkimsModule());
```

**Overriding**, because it replaces a binding `SwissRailRaptorModule` has already made. Added as an
ordinary module it fails loudly, which is the good case.

With `observedSkims.waitTimeEnabled = false` nothing is bound at all and SwissRailRaptor keeps its
own behaviour exactly, so the module is safe to install unconditionally and switch off in config.

## Status

Implemented: the wait skim and its transfer-cost consumer.

Not yet implemented: the inter-stop travel time skim (`TransitStopStopTime` is declared, nothing
implements it). Its natural consumer is `RaptorInVehicleCostCalculator`, whose `RouteSegmentIterator`
exposes in-vehicle time, passenger count and time of day but **no stop identities** — so a
stop-keyed skim cannot reach that hook as it stands. Adding `getFromStop()` / `getToStop()` there is
the third instance of the same core gap the two changes above address.
