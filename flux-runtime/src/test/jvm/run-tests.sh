#!/bin/bash
# Plain-JVM tests for Flux's classloader routing. No Android, no robot, no emulator.
#
#   ./flux-runtime/src/test/jvm/run-tests.sh
#
# Compiles the classes that were deliberately kept free of Android dependencies -- the
# classloader routing decision and the safetyPolicy decision -- and runs the assertions against
# them. Both are decisions whose surrounding code only runs on a robot.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../main/java/dev/ryanchhab/flux/runtime/FluxDelegation.java"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

SAFETY="$HERE/../../main/java/dev/ryanchhab/flux/runtime/FluxSafetyDecision.java"

javac -nowarn -d "$OUT" "$SRC" "$SAFETY"
javac -nowarn -cp "$OUT" -d "$OUT" "$HERE/FluxDelegationTest.java" "$HERE/FluxSafetyDecisionTest.java"

java -cp "$OUT" FluxDelegationTest
echo
java -cp "$OUT" FluxSafetyDecisionTest
