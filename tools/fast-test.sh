#!/bin/bash
# Compile core/ + tests with the Kotlin compiler bundled in Gradle, then run JUnit.
# No network, no Android SDK: core/ is pure Kotlin on purpose.
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/jarvis-fast-test"
L=/opt/gradle/lib
CP="$L/kotlin-stdlib-2.0.21.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar"
rm -rf "$OUT"; mkdir -p "$OUT"
java -cp "$L/*" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -cp "$CP" -d "$OUT" \
  $(find "$REPO/app/src/main/java/com/jarvis/agent/core" -name '*.kt') \
  $(find "$REPO/app/src/test/java" -name '*.kt') 2>&1 | grep -v "^warning:" || true
[ -d "$OUT" ] || { echo "COMPILE FAILED"; exit 1; }
CLASSES=$(cd "$OUT" && find . -name '*Test.class' | sed 's|^\./||; s|\.class$||; s|/|.|g' | grep -v '\$')
java -cp "$OUT:$CP" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL | tail -40
