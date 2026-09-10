# Quality gate

```bash
contribs/observed-skims/scripts/quality.sh
```

Runs, and fails on the first that fails: Spotless formatting, Checkstyle, SpotBugs, the unit tests,
the integration tests, and a JaCoCo coverage ratchet.

## Why it is inert

The `skims-quality` Maven profile has no `<activation>` block, so none of it runs in an ordinary
build or in CI. Adding this module took the `verify-push` matrix from 51 jobs to 52; it did not make
any of them slower. That discipline is inherited from `contribs/pseudosimulation`, which established
the pattern, and it is deliberate: a gate that slows every build down gets switched off.

## Current state

| gate | state |
| --- | --- |
| Spotless | clean |
| Checkstyle | 0 violations |
| SpotBugs | 0 findings, and the exclude file is empty |
| Tests | 36 unit, 2 integration |
| Coverage | 79.8% line, 68.8% branch |

The ratchet sits at 78% line and 67% branch: a little below the current figure, so an unrelated
change does not break the build, and close enough that a real regression does.

**Raise the ratchet as coverage rises. Never lower it to make a build pass.** A gate that is lowered
whenever it fails measures nothing at all, and the same goes for adding a Checkstyle exclusion or a
SpotBugs filter rather than fixing what they found. The SpotBugs exclude file is currently empty and
should be kept that way; every entry it ever gains must name a class, a pattern, and a reason.

## What the coverage does not cover

`RunObservedSkimsScenario` is a `main` method for running case studies and is not exercised by the
test suite. That is a deliberate gap rather than an oversight: testing it means running a scenario,
which is what the integration tests already do through the module it installs. It is the reason the
ratchet is not higher, and it should not be excluded from the measurement to flatter the number.

## What the gate cannot check

The wiring. `Transfer`'s fields are package-private to SwissRailRaptor, so no test outside that
package can build a populated one, and the decorators' interaction with the router is only reachable
by running a controler. Both integration tests exist for that reason and assert on the bindings
first. An overriding module that fails to replace a Raptor binding leaves every unit test here
passing while routing silently ignores everything this package measures.
