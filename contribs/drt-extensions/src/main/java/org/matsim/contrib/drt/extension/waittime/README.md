# Observed DRT wait times in intermodal routing

## What problem this solves

SwissRailRaptor can use DRT as an intermodal access or egress mode, but
`DefaultRaptorIntermodalAccessEgress` costs such a leg from `leg.getTravelTime()` alone. It has no
term for waiting. A feeder leg whose vehicle arrives in twenty minutes therefore scores exactly like
one whose vehicle is already at the kerb, and no amount of replanning will teach an agent to avoid
the first.

DRT does estimate waiting time elsewhere — `DrtEstimator` writes an `EST_WAIT_TIME` leg attribute —
but nothing in routing or scoring reads it; its only consumer is the teleporting mobsim engine, and
it exists only under `estimateAndTeleport`.

This package closes that gap: it measures what waiting actually was, and charges for it.

## What is recorded

A **wait time skim**: mean waiting time per zone and time bin, rebuilt at the end of every
iteration from that iteration's events.

Waiting time is defined exactly as MATSim's own DRT analysis defines it, in
`DrtAnalysisControlerListener`:

```
waitTime = PassengerPickedUpEvent.time - DrtRequestSubmittedEvent.earliestDepartureTime
```

Measured from *readiness*, not from submission, so that a prebooked request is not charged for the
time between booking and its own earliest departure. The event sequences come from
`DrtEventSequenceCollector`, which the DRT analysis module already binds; this package adds no event
handling of its own, so a routed wait and a reported wait cannot drift apart.

## Why zones rather than stops

The obvious key is the DRT stop. It does not survive contact with the three operational schemes:

| Scheme | Stop network | Consequence for a stop-keyed skim |
| --- | --- | --- |
| `stopbased` | curated, from `transitStopFile` | fine |
| `serviceAreaBased` | **every link** in the service area, via `DrtStopFacilityImpl.createFromLink` | table size grows with the network |
| `door2door` | empty — `ImmutableMap::of` | no key exists at all |

A zone system is a tunable-resolution abstraction that already exists in-tree
(`org.matsim.contrib.common.zones`), already has DRT plumbing, and keeps the table bounded in all
three cases. Resolution is a config knob rather than a property of the scenario.

## Where it surfaces

1. **In routing and scoring.** `WaitAwareRaptorIntermodalAccessEgress` decorates the default
   intermodal cost, adding the looked-up wait to both the reported travel time and the disutility.
   Waiting is priced at `RaptorParameters.getMarginalUtilityOfWaitingPt_utl_s()` — MATSim's existing
   marginal utility of waiting for PT — so a minute waiting for a DRT vehicle costs the same as a
   minute waiting for a bus, and the behaviour is tuned with a knob operators already have.
2. **As a file.** `drtWaitTimeSkim_<mode>.csv` in each iteration directory: one row per zone and
   time bin, with the observation count from the most recent iteration alongside the published
   value. A row with a value but no observations was carried forward from earlier iterations.
3. **Programmatically.** Inject `DrtWaitTimeSkim` (mode-keyed map) or the modal
   `ZonalDrtWaitTimeSkim` and call `lookup(linkId, time)`.

## Two deliberate design choices

**Values are damped across iterations, not replaced.** An events-based skim that is overwritten
wholesale each iteration invites the router and the mobsim to chase each other: the router avoids
last iteration's slow zone, the vehicles follow, and the zone is now fast. `smoothingWeight`
controls the blend — 1.0 restores undamped replacement, lower values damp. There is no published
convergence result for this; treat the default of 0.5 as a starting point, not a recommendation.

**A missing observation falls through to a coarser aggregate, not to a constant.** Every lookup
reports its `Source`: `ZONE_TIME_BIN`, `ZONE_MEAN`, `GLOBAL_TIME_BIN`, `GLOBAL_MEAN`, or `DEFAULT`.
Only the last rests on a configured number. Callers that care whether a result is measured or
assumed can check; a result resting on `DEFAULT` throughout is a result about the config file, not
about the scenario.

## Usage

```java
DrtWithExtensionsConfigGroup drtCfg = new DrtWithExtensionsConfigGroup();
drtCfg.addParameterSet(new DrtWaitTimeSkimParams());   // defaults: 15-min bins, square-grid zones

Controler controler = DrtControlerCreator.createControler(config, scenario, false);
controler.addOverridingModule(new MultiModeDrtWaitTimeSkimModule());
```

`MultiModeDrtWaitTimeSkimModule` must be an *overriding* module: it replaces the
`RaptorIntermodalAccessEgress` binding made by `SwissRailRaptorModule`. It is a no-op if no DRT mode
declares the parameter set, so installing it unconditionally is safe.

## Configuration

| Parameter | Default | Meaning |
| --- | --- | --- |
| `timeBinSize` | 900 s | Time bin width. Smaller bins track the peak better but need more observations each. |
| `horizon` | 108000 s | Period covered. Later departures are answered from the last bin. |
| `smoothingWeight` | 0.5 | Weight on the newest iteration, in [0,1]. 1.0 = undamped replacement. |
| `minObservations` | 1 | Observations needed before a value is trusted; thinner bins fall through. |
| `defaultWaitTime` | 300 s | Last resort when nothing has been observed, e.g. iteration 0. |
| `writeSkimCsv` | true | Write the per-iteration CSV. |
| nested zone system | `SquareGridZoneSystem` | Any `ZoneSystemParams` — square grid, H3, or a shapefile. |

## Known limitations

- **Egress waits are looked up at the wrong time.** `DefaultRaptorStopFinder` passes the trip's
  original departure time to the egress routing module and documents it as wrong; the leg carries
  that time, so an egress wait in a long trip may be read from the wrong bin. Access legs, where the
  intermodal wait usually matters most, carry the correct time.
- **Only waiting is instrumented, not stop-to-stop ride time.** Ride time between zone pairs is a
  natural extension using the same machinery, and is well defined here in a way it is not for a
  stop-keyed design.
- **A zone system that does not cover the service area starves the skim.** Requests whose origin
  falls outside it are counted and warned about at the end of each iteration, not silently dropped.
- **Convergence behaviour is unmeasured.** The damping exists because undamped replacement is known
  to be a hazard, not because a particular weight has been shown to converge.

## Provenance

The design — waiting as a first-class, separately-priced element of routing cost rather than
something folded into travel time — comes from Sergio Ordóñez's `eventsBasedPTRouter` contrib
(removed from matsim-libs in October 2023) and its later MaaS-router descendant, where wait was
modelled as an explicit link in the routing graph and scored with
`ScoringConfigGroup.getMarginalUtlOfWaitingPt_utils_hr()`. That lineage is why this package uses the
same scoring knob. The measurement here is new: the DRT side of Sergio's work read transit events
and never observed a DRT wait.
