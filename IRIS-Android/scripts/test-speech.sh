#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d "$test_dir" \
  app/src/main/java/com/iris/assistant/SpeechText.java \
  tests/SpeechTextTest.java \
  tests/PersonalVocabularyTest.java
java -cp "$test_dir" com.iris.assistant.SpeechTextTest
java -cp "$test_dir" com.iris.assistant.PersonalVocabularyTest
java tests/ParseSources.java app/src/main/java
