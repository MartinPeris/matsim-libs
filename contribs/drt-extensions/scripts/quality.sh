#!/usr/bin/env bash
# One command for the whole gate: formatting, lint, bug patterns, tests, coverage.
# Fails on the first gate that fails; a report listing five problems is read less carefully than one.
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_DIR/../.." && pwd)"
cd "$REPO_ROOT"

echo "==> spotless (formatting)"
mvn --batch-mode -Pdrtext-quality -pl contribs/drt-extensions spotless:check

echo "==> checkstyle"
mvn --batch-mode -Pdrtext-quality -pl contribs/drt-extensions checkstyle:check

echo "==> spotbugs"
mvn --batch-mode -Pdrtext-quality -pl contribs/drt-extensions spotbugs:check

echo "==> tests, integration tests and coverage ratchet"
mvn --batch-mode -Pdrtext-quality -pl contribs/drt-extensions -Dmatsim.preferLocalDtds=true verify

echo
echo "all gates passed"
