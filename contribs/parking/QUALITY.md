# Quality gate

```bash
contribs/parking/scripts/quality.sh
```

Runs, and fails on the first that fails: Spotless formatting, Checkstyle, SpotBugs, the unit tests,
the integration tests, and a JaCoCo coverage ratchet.

## Why it is inert

The `parking-quality` Maven profile has no `<activation>` block, so none of it runs in an ordinary
build or in CI. The pattern comes from `contribs/pseudosimulation`, and it is deliberate: a gate that
slows every build down gets switched off.

## Current state

| gate | state |
| --- | --- |
| Spotless | clean |
| Checkstyle | 0 violations |
| SpotBugs | 0 findings after one documented exclusion |
| Tests | 121 unit, 2 integration |
| Coverage | 59.0% line, 48.6% branch |

The ratchet sits at 58% line and 47% branch: below the current figure so an unrelated change does not
break the build, and close enough that a real regression does.

**Raise it as coverage rises. Never lower it to make a build pass.** The same applies to the
Checkstyle rules and the SpotBugs filter: a gate that is weakened whenever it fails measures nothing.

## What adopting the gate changed

Three stray semicolons (two doubled statement terminators and one after an arrow `switch`) and one
`new Random()` immediately reseeded, which is now `new Random(seed)` for the identical sequence. All
four were pre-existing and none changes behaviour.

Spotless then reformatted 63 files. That change is trailing whitespace and end-of-file newlines only;
`git diff -w` leaves nothing but removed blank lines at ends of files. It landed in the same commit
as the gate, which in hindsight should have been split: a 63-file reformat is easier to review and to
rebase past on its own. Split it if this branch is ever rebased.

One SpotBugs finding is excluded, with the reason in `quality/spotbugs-exclude.xml`:
`DMI_RANDOM_USED_ONLY_ONCE` on `RandomErrorTermManager`, where the `Random` is in fact drawn from
inside two loops. Confirmed a false positive at Max effort before excluding it.

## Where the coverage is thin

59% line is well below `contribs/observed-skims` at 80%, and the gap is in the older
`parkingchoice`/`PC2` and `parkingsearch` trees rather than in
`parkingsearchparameterization`, which the kerb-capacity work covers directly. Raising it means
writing tests for code that predates this project, which is worth doing but should not be smuggled
in alongside behavioural changes.
