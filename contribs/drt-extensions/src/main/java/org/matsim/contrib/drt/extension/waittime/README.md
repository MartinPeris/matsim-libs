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

## How the cost is applied, and why it is direction-dependent

This is the subtle part, and getting it wrong makes the feature silently do nothing.

The travel time this package returns becomes `InitialStop.accessTime`, and `SwissRailRaptorCore`
does not treat that quantity symmetrically:

- On **access**, the core computes arrival at the stop as `departureTime + accessTime` and then
  charges `(nextDeparture - arrival)` at `marginalUtilityOfWaitingPt`. Pushing the arrival later by
  the DRT wait *already* costs the traveller that wait — it consumes slack they would otherwise have
  spent waiting on the platform, at exactly the marginal utility of waiting. Adding a second, equal
  charge on top does not double the penalty; it **cancels out exactly**, leaving the route cost
  unchanged.
- On **egress**, the core adds `accessTime` to the arrival time and `accessCost` to the total with
  no waiting term at all. Nothing is charged implicitly.

So the wait is always added to elapsed time, and the *cost* is:

| Direction | Charged here | Charged by Raptor | Total |
| --- | --- | --- | --- |
| access | `(factor - 1) × wait × -mu_wait` | `1 × wait × -mu_wait` | `factor × wait × -mu_wait` |
| egress | `factor × wait × -mu_wait` | nothing | `factor × wait × -mu_wait` |

`waitingCostFactor` is how onerous waiting for an on-demand vehicle is *relative to* waiting at a
transit stop. **1.0, the default, is the neutral position**: a minute is a minute, wherever it is
spent. At 1.0 an access-side wait adds no extra cost — correctly, because Raptor already charges it —
and the behavioural effect comes through elapsed time: a long wait still makes the traveller miss the
connection when it exceeds the slack at the stop, and still makes the whole trip slower and dearer
against any alternative compared outside Raptor. Above 1.0 prices unscheduled waiting as worse than
waiting for a timetabled service, which is what stated-preference work generally finds; this package
does not pick that number for you.

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
