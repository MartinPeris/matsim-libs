# Quality gate

```bash
contribs/drt-extensions/scripts/quality.sh
```

Runs, and fails on the first that fails: Spotless formatting, Checkstyle, SpotBugs, the unit tests,
the integration tests, and a JaCoCo coverage ratchet.

## Why it is inert

The `drtext-quality` Maven profile has no `<activation>` block, so none of it runs in an ordinary
build or in CI. The pattern comes from `contribs/pseudosimulation`: a gate that slows every build
down gets switched off.

## Current state

| gate | state |
| --- | --- |
| Spotless | clean |
| Checkstyle | 0 violations |
| SpotBugs | 0 findings after one documented exclusion |
| Tests | 91 unit, 48 integration |
| Coverage | 75.9% line, 63.6% branch |

The ratchet sits at 75% line and 62% branch. **Raise it as coverage rises; never lower it to make a
build pass.**

## What adopting the gate changed

A doubled statement terminator in `OperationFacilitiesReader`, and `BenchmarkResult.DrtQualityStats.EMPTY`
made `final`, which it always should have been. Spotless then reformatted 32 files: trailing
whitespace and end-of-file newlines only, nothing that `git diff -w` shows as substantive.

One SpotBugs finding is excluded, with the reason in `quality/spotbugs-exclude.xml`.
`MS_SHOULD_BE_FINAL` on `DefaultShiftScheduler.createShiftFromSpec` is right about the mutability and
wrong about the intent: that public static `Function` exists so a scenario can replace how a shift is
built from its specification, and making it final would remove the extension point rather than
tighten it. Whether that is a good design is a question for that package's owners; it is not
something a lint gate should decide.

## Noted, not changed

`OperationFacilitiesReader` has a `case CHARGER:` that falls through to `case ROOT:`. It is harmless,
because `ROOT` only breaks, so the effect is the same as ending `CHARGER` with a `break`. Left alone:
there is no test covering that reader, and a silent behavioural change to an XML parser is a poor
trade for a readability gain.

## What the gate cannot check

The wiring. `SkimAwareRaptorIntermodalAccessEgress` and the transit decorators in
`contribs/observed-skims` are only exercised against a real router by running a controler, which is
why `RunDrtSkimsIT`, `SkimAwareIntermodalAccessEgressRaptorIT` and `MaasSkimsIT` exist and why each
asserts on the Guice bindings before anything else. An overriding module that fails to replace a
Raptor binding leaves every unit test passing while routing silently ignores the skims.
