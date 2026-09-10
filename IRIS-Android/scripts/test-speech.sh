#!/usr/bin/env bash
# Runs every offline regression suite plus a syntax check of all sources.
# Android type checking still requires the APK build.
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
javac -encoding UTF-8 -d "$test_dir" \
  app/src/main/java/com/iris/assistant/SpeechText.java \
  app/src/main/java/com/iris/assistant/IrisIntent.java \
  app/src/main/java/com/iris/assistant/ToolCall.java \
  app/src/main/java/com/iris/assistant/Plan.java \
  app/src/main/java/com/iris/assistant/IntentParser.java \
  app/src/main/java/com/iris/assistant/LocalPlanner.java \
  app/src/main/java/com/iris/assistant/AppRequest.java \
  app/src/main/java/com/iris/assistant/WakePolicy.java \
  app/src/main/java/com/iris/assistant/TelemetrySnapshot.java \
  app/src/main/java/com/iris/assistant/TrafficRateMeter.java \
  app/src/main/java/com/iris/assistant/TelemetryEventLog.java \
  tests/AppRequestTest.java \
  tests/SpeechTextTest.java \
  tests/PersonalVocabularyTest.java \
  tests/PlanTest.java \
  tests/LocalPlannerTest.java \
  tests/WakePolicyTest.java \
  app/src/main/java/com/iris/assistant/PhoneFacts.java \
  tests/PhoneFactsTest.java \
  tests/TelemetryTest.java
java -cp "$test_dir" com.iris.assistant.SpeechTextTest
java -cp "$test_dir" com.iris.assistant.PersonalVocabularyTest
java -cp "$test_dir" com.iris.assistant.PlanTest
java -cp "$test_dir" com.iris.assistant.LocalPlannerTest
java -cp "$test_dir" com.iris.assistant.AppRequestTest
java -cp "$test_dir" com.iris.assistant.WakePolicyTest
java -cp "$test_dir" com.iris.assistant.TelemetryTest
java -cp "$test_dir" com.iris.assistant.PhoneFactsTest
java tests/ParseSources.java app/src/main/java

