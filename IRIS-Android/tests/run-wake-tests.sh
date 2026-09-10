#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
wake_classes=$(mktemp -d)
trap 'rm -rf "$wake_classes"' EXIT
javac -d "$wake_classes" app/src/main/java/com/iris/assistant/WakePolicy.java tests/WakePolicyTest.java
java -cp "$wake_classes" com.iris.assistant.WakePolicyTest
