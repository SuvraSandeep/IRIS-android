#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -d "$test_dir" app/src/main/java/com/iris/assistant/SpeechText.java tests/SpeechTextTest.java
java -cp "$test_dir" com.iris.assistant.SpeechTextTest
java tests/ParseSources.java app/src/main/java
