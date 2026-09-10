#!/usr/bin/env bash
# One command for the whole gate: formatting, lint, bug patterns, tests, coverage.
#
# Run from anywhere; paths are resolved against this script. Fails on the first gate that fails,
# because a report listing five problems is read less carefully than one.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "==> spotless (formatting)"
mvn --batch-mode -Pskims-quality -pl contribs/observed-skims spotless:check

echo "==> checkstyle"
mvn --batch-mode -Pskims-quality -pl contribs/observed-skims checkstyle:check

echo "==> spotbugs"
mvn --batch-mode -Pskims-quality -pl contribs/observed-skims spotbugs:check

echo "==> tests, integration tests and coverage ratchet"
mvn --batch-mode -Pskims-quality -pl contribs/observed-skims -Dmatsim.preferLocalDtds=true verify

echo
echo "all gates passed"
