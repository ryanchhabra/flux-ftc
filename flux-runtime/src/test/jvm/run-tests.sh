#!/bin/bash
# Plain-JVM tests for Flux's classloader routing. No Android, no robot, no emulator.
#
#   ./flux-runtime/src/test/jvm/run-tests.sh
#
# Compiles FluxDelegation on its own (it has no Android dependencies, which is the entire reason
# it was extracted) and runs the assertions against it.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../main/java/dev/flux/runtime/FluxDelegation.java"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -nowarn -d "$OUT" "$SRC"
javac -nowarn -cp "$OUT" -d "$OUT" "$HERE/FluxDelegationTest.java"
java -cp "$OUT" FluxDelegationTest
