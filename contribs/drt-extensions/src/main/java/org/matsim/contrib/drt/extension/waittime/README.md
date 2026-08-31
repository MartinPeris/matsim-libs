# Observed DRT wait times in intermodal routing

## What problem this solves

SwissRailRaptor can use DRT as an intermodal access or egress mode, but
`DefaultRaptorIntermodalAccessEgress` costs such a leg from `leg.getTravelTime()` alone. It has no
term for waiting, and the leg's travel time does not include any. A feeder leg whose vehicle arrives
in twenty minutes therefore reaches the stop at the same modelled instant, and for the same price, as
one whose vehicle is already at the kerb.

DRT does estimate waiting elsewhere — `DrtEstimator` writes an `EST_WAIT_TIME` leg attribute — but
nothing in routing or scoring reads it; its only consumer is the teleporting mobsim engine, and it
exists only under `estimateAndTeleport`.

This package measures what waiting actually was, and makes routing account for it.

## What is recorded

A **wait time skim**: mean waiting time per zone and time bin, rebuilt at the end of every iteration
from that iteration's events, damped against the previous estimate.

Waiting time uses the same formula as MATSim's own DRT analysis (`DrtAnalysisControlerListener`):

```
waitTime = PassengerPickedUpEvent.time - DrtRequestSubmittedEvent.earliestDepartureTime
```

Measured from *readiness*, not from submission, so a prebooked request is not charged for its own
booking lead time. The event sequences come from `DrtEventSequenceCollector`, which the DRT analysis
module already binds, so this package adds no event handling of its own.

The formula matches the reported statistic; the *population* does not exactly. The skim counts
requests that were picked up, whereas `DrtAnalysisControlerListener` counts only requests that also
completed a drop-off, and the skim additionally excludes negative waits and origins outside the zone
system. Expect small differences between the skim and `drt_trips_*.csv`, not identity.

## Why zones rather than stops

The obvious key is the DRT stop. It does not survive contact with the three operational schemes:

| Scheme | Stop network | Consequence for a stop-keyed skim |
| --- | --- | --- |
| `stopbased` | curated, from `transitStopFile` | fine |
| `serviceAreaBased` | **every link** in the service area, via `DrtStopFacilityImpl.createFromLink` | table size grows with the network |
| `door2door` | empty — `ImmutableMap::of` | no key exists at all |

A zone system is a tunable-resolution abstraction that already exists in-tree
(`org.matsim.contrib.common.zones`), already has DRT plumbing, and keeps the table bounded in all
three cases. Resolution becomes a config knob rather than a property of the scenario.

## How the cost is applied

This is the subtle part, and getting it wrong makes the feature do the opposite of what it should.

The travel time this package returns becomes `InitialStop.accessTime`. `SwissRailRaptorCore` handles
that quantity differently at the two ends of a trip, but — and this is the correction — **the charge
this package applies is the same in both directions**: `factor × wait × -mu_wait`.

The tempting mistake is to reason that on access the core "already charges" the DRT wait, so only
the excess `(factor - 1)` is ours to add. An earlier version of this package did exactly that. It is
a sign error. What the core charges is the *platform* wait, `(boardingTime - arrival)`, and pushing
the arrival later by W does not add W to that — while the traveller still catches the same vehicle
it **subtracts** W, *refunding* `W × mu_wait`. The core's net contribution on access is therefore
`-W × mu_wait`, not `+W × mu_wait`, and charging only `(factor - 1)` leaves that refund uncancelled:
at the default factor of 1.0 a longer wait came out **strictly cheaper**, by exactly `W × mu_wait`.

The right way to see it: the traveller's *total* waiting is unchanged by W. Only its composition
moves, out of the platform and into the DRT vehicle. To price DRT waiting at `factor × mu_wait` and
platform waiting at `mu_wait`, the full amount must be charged here, at both ends:

| Direction | Charged here | Contributed by Raptor | Net effect on route cost |
| --- | --- | --- | --- |
| access | `factor × wait × -mu_wait` | `-1 × wait × -mu_wait` (shorter platform wait) | `(factor - 1) × wait × -mu_wait` |
| egress | `factor × wait × -mu_wait` | nothing | `factor × wait × -mu_wait` |

Both rows are behaviourally right, and the asymmetry in the *net* column comes from Raptor's
structure rather than from any asymmetry in what this package charges. On access the traveller swaps
platform waiting for DRT waiting, so at factor 1.0 nothing changes. On egress the wait is purely
additional — there is no platform wait to displace — so it is charged in full.

`waitingCostFactor` is how onerous waiting for an on-demand vehicle is *relative to* waiting at a
transit stop. **1.0, the default, is the neutral position**: a minute is a minute, wherever it is
spent. At 1.0 the *net* access-side effect is zero, not because this package declines to charge, but
because the charge and the core's refund cancel. The behavioural effect on access then comes through
elapsed time: a long wait still makes the traveller miss the connection when it exceeds the slack at
the stop, and still makes the whole trip slower and dearer against any alternative compared outside
Raptor. Above 1.0 prices unscheduled waiting as worse than waiting for a timetabled service, which is
what stated-preference work generally finds; this package does not pick that number for you.

Because this reasoning is about the core's behaviour and not about this package's return value, the
decorator's own unit tests cannot check it — they inspect only the number handed to the core, which
is what let the sign error survive. `WaitAwareIntermodalAccessEgressRaptorIT` drives
`SwissRailRaptorCore` end to end and asserts on the resulting route cost, including the invariant
that a wait must never make a route cheaper at any factor.

## Where it surfaces

1. **In routing and scoring**, via `WaitAwareRaptorIntermodalAccessEgress`, as above.
2. **As a file**: `drtWaitTimeSkim_<mode>.csv` per iteration directory, with columns
   `zone, timeBin, binStart, binEnd, observations, rejections, waitTime, carriedForward`.
3. **Programmatically**: inject the mode-keyed `Map<String, DrtWaitTimeSkim>` or the modal
   `ZonalDrtWaitTimeSkim`, and call `lookup(linkId, time)`.

## Damping, and honesty about provenance

**Values are blended across iterations, not replaced.** An events-based skim overwritten wholesale
each iteration invites the router and the mobsim to chase each other: the router avoids last
iteration's slow zone, vehicles follow, the zone is now fast. `smoothingWeight` controls the blend —
1.0 restores replacement; the permitted range is (0,1] because 0 would freeze the first iteration's
estimate for the whole run. There is no published convergence result for any particular value; the
default of 0.5 is a starting point, not a recommendation.

**Every lookup reports where its number came from**, via `DrtWaitTimeSkim.Source`:

| Source | Meaning |
| --- | --- |
| `ZONE_TIME_BIN` | observed in this zone and bin in the iteration just finished |
| `ZONE_TIME_BIN_CARRIED` | this zone and bin have a value, but from an earlier iteration |
| `ZONE_MEAN` | the zone's all-day mean |
| `GLOBAL_TIME_BIN` | the system-wide mean for this bin |
| `GLOBAL_MEAN` | the system-wide all-day mean |
| `DEFAULT` | nothing observed anywhere; the configured constant |

Only the last rests on a configured number. A result resting on `DEFAULT` throughout is a result
about the config file, not about the scenario.

## Usage

```java
DrtWithExtensionsConfigGroup drtCfg = new DrtWithExtensionsConfigGroup();
drtCfg.addParameterSet(new DrtWaitTimeSkimParams());   // 15-min bins, square-grid zones, factor 1.0

Controler controler = DrtControlerCreator.createControler(config, scenario, false);
controler.addOverridingModule(new MultiModeDrtWaitTimeSkimModule());
```

`MultiModeDrtWaitTimeSkimModule` must be an *overriding* module: it replaces the
`RaptorIntermodalAccessEgress` binding made by `SwissRailRaptorModule`. It is a no-op if no DRT mode
declares the parameter set, so installing it unconditionally is safe.

## Configuration

| Parameter | Default | Meaning |
| --- | --- | --- |
| `timeBinSize` | 900 s | Bin width. Smaller bins track the peak better but need more observations each. |
| `horizon` | 108000 s | Period covered. Later departures are answered from the last bin. |
| `smoothingWeight` | 0.5 | Weight on the newest iteration, in (0,1]. 1.0 = undamped replacement. |
| `minObservations` | 1 | Observations needed before a value is trusted; thinner bins fall through. |
| `defaultWaitTime` | 300 s | Last resort when nothing has been observed, e.g. iteration 0. |
| `waitingCostFactor` | 1.0 | Onerousness of DRT waiting relative to waiting at a stop. Must agree across modes. |
| `writeSkimCsv` | true | Write the per-iteration CSV. |
| nested zone system | `SquareGridZoneSystem` | Any `ZoneSystemParams` — square grid, H3, or a shapefile. |

## Known limitations

- **Rejections are excluded from the mean.** A rejection is an unbounded wait, and averaging it in
  would need a number this package has no basis to invent. The skim is therefore systematically
  optimistic wherever rejection is common — worst exactly where service is worst. Rejection counts
  sit beside the wait times in the CSV and the per-iteration rate is logged as a warning, so the bias
  is visible rather than hidden. Folding a rejection penalty into the cost is a modelling decision
  left open.
- **Carried-forward values never expire.** A zone observed once and never again keeps that value for
  the rest of the run. It is reported as `ZONE_TIME_BIN_CARRIED` rather than as fresh measurement,
  but there is no staleness cutoff.
- **Egress waits are looked up at the wrong time.** `DefaultRaptorStopFinder` passes the trip's
  original departure time to the egress routing module and documents it as wrong; the leg carries
  that time, so an egress wait on a long trip may be read from the wrong bin.
- **Only waiting is instrumented, not stop-to-stop ride time.** Zone-to-zone ride time is a natural
  extension using the same machinery, and is well defined here in a way it is not for a stop-keyed
  design.
- **A zone system that does not cover the service area starves the skim.** Requests whose origin
  falls outside it are counted and warned about at the end of each iteration, not silently dropped.
- **Convergence is unmeasured.** The damping exists because undamped replacement is a known hazard,
  not because any weight has been shown to converge.
- **A user-supplied `RaptorIntermodalAccessEgress` is discarded.** The decorator wraps
  `DefaultRaptorIntermodalAccessEgress` directly rather than the previously bound implementation.

## Provenance

The design — waiting as a first-class, separately-priced element of routing cost rather than
something folded into travel time — comes from Sergio Ordóñez's `eventsBasedPTRouter` contrib
(removed from matsim-libs in October 2023) and its later MaaS-router descendant, where waiting was an
explicit link in the routing graph, priced with
`ScoringConfigGroup.getMarginalUtlOfWaitingPt_utils_hr()`. That lineage is why this package uses the
same scoring knob. The measurement is new: the DRT side of that earlier work filtered on transit leg
modes and transit vehicle events, and never observed a DRT wait at all.
