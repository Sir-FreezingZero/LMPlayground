#!/usr/bin/env bash
# Compile and run :agent's unit tests without Gradle repositories, using the
# Kotlin compiler, coroutines and JUnit jars bundled in a Gradle distribution.
# For sandboxes without Maven access; normal builds use ./gradlew :agent:test.
#   GRADLE_LIB=/opt/gradle/lib agent/scripts/offline-test.sh [TestClass ...]
set -euo pipefail
L=${GRADLE_LIB:-/opt/gradle/lib}
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=${OUT:-/tmp/agent-build}
KV=$(ls $L | sed -n 's/^kotlin-stdlib-\([0-9.]*\)\.jar$/\1/p' | head -1)
CO=$(ls $L/kotlinx-coroutines-core-jvm-*.jar | head -1)
RT=$L/kotlin-stdlib-$KV.jar:$CO
COMPILER=$L/kotlin-compiler-embeddable-$KV.jar:$RT:$L/kotlin-script-runtime-$KV.jar:$L/kotlin-reflect-$KV.jar:$L/kotlin-daemon-embeddable-$KV.jar:$(ls $L/annotations-*.jar|head -1):$(ls $L/trove4j*.jar|head -1)
TESTCP=$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar
rm -rf $OUT && mkdir -p $OUT/main $OUT/test
kc() { java -Xmx2g -cp $COMPILER org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -nowarn "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true; }
kc -cp $RT -d $OUT/main $(find $HERE/src/main -name '*.kt')
[ -n "$(find $OUT/main -name '*.class' | head -1)" ] || { echo "main compile failed"; exit 1; }
kc -cp $RT:$TESTCP:$OUT/main -d $OUT/test $(find $HERE/src/test -name '*.kt')
CLASSES=${*:-$(cd $OUT/test && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||;s|\.class$||;s|/|.|g' | sort)}
java -cp $RT:$TESTCP:$OUT/main:$OUT/test org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL_OPTIONS
