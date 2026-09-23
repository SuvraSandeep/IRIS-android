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
  tests/TelemetryTest.java \
  app/src/main/java/com/iris/assistant/BatteryRateTracker.java \
  tests/BatteryRateTrackerTest.java \
  app/src/main/java/com/iris/assistant/PhoneFacts.java \
  tests/PhoneFactsTest.java \
  app/src/main/java/com/iris/assistant/QuietAudioProcessor.java \
  app/src/main/java/com/iris/assistant/WakeChangeApproval.java \
  tests/OwnerContractTest.java \
  app/src/main/java/com/iris/assistant/RecordingDeadline.java \
  tests/RecordingDeadlineTest.java \
  app/src/main/java/com/iris/assistant/OwnerTrainingPlan.java \
  app/src/main/java/com/iris/assistant/OwnerTrainingStage.java \
  tests/OwnerTrainingStageTest.java
java -cp "$test_dir" com.iris.assistant.SpeechTextTest
java -cp "$test_dir" com.iris.assistant.PersonalVocabularyTest
java -cp "$test_dir" com.iris.assistant.PlanTest
java -cp "$test_dir" com.iris.assistant.LocalPlannerTest
java -cp "$test_dir" com.iris.assistant.AppRequestTest
java -cp "$test_dir" com.iris.assistant.WakePolicyTest
java -cp "$test_dir" com.iris.assistant.TelemetryTest
java -cp "$test_dir" com.iris.assistant.BatteryRateTrackerTest
java -cp "$test_dir" com.iris.assistant.PhoneFactsTest
java -cp "$test_dir" com.iris.assistant.OwnerContractTest
java -cp "$test_dir" com.iris.assistant.RecordingDeadlineTest
java tests/ParseSources.java app/src/main/java


java -cp "$test_dir" com.iris.assistant.OwnerTrainingStageTest
python3 tests/check-training-layout.py
mkdir -p "$test_dir/recorder"
javac -encoding UTF-8 -d "$test_dir/recorder" \
  $(find tests/recorder-stubs -name '*.java') \
  app/src/main/java/com/iris/assistant/RecordingDeadline.java \
  app/src/main/java/com/iris/assistant/AudioCaptureCoordinator.java \
  app/src/main/java/com/iris/assistant/SpeechEndpoint.java \
  app/src/main/java/com/iris/assistant/PhraseCapture.java \
  app/src/main/java/com/iris/assistant/TimedRecorder.java \
  tests/TimedRecorderFlowTest.java
java -cp "$test_dir/recorder" com.iris.assistant.TimedRecorderFlowTest
mkdir -p "$test_dir/voskzip"
javac -encoding UTF-8 -d "$test_dir/voskzip" tests/VoskZipUnpackTest.java
java -cp "$test_dir/voskzip" com.iris.assistant.VoskZipUnpackTest
mkdir -p "$test_dir/voice"
javac -encoding UTF-8 -d "$test_dir/voice" \
  app/src/main/java/com/iris/assistant/WakePolicy.java \
  app/src/main/java/com/iris/assistant/PhraseEvidence.java \
  app/src/main/java/com/iris/assistant/SpeechEndpoint.java \
  app/src/main/java/com/iris/assistant/AudioCaptureCoordinator.java \
  app/src/main/java/com/iris/assistant/VoiceProfileCrypto.java \
  tests/VoiceCoreTest.java
java -cp "$test_dir/voice" com.iris.assistant.VoiceCoreTest
mkdir -p "$test_dir/recorded"
javac -encoding UTF-8 -d "$test_dir/recorded" app/src/main/java/com/iris/assistant/WakePolicy.java app/src/main/java/com/iris/assistant/SoundPattern.java app/src/main/java/com/iris/assistant/RecordedWakeCheck.java tests/RecordedWakeCheckTest.java
java -cp "$test_dir/recorded" com.iris.assistant.RecordedWakeCheckTest

javac -encoding UTF-8 -d "$test_dir/recorded" app/src/main/java/com/iris/assistant/WakePolicy.java app/src/main/java/com/iris/assistant/SoundPattern.java app/src/main/java/com/iris/assistant/PhraseCapture.java tests/PhraseCaptureTest.java
java -cp "$test_dir/recorded" com.iris.assistant.PhraseCaptureTest
javac -encoding UTF-8 -d "$test_dir/recorded" app/src/main/java/com/iris/assistant/WakeAnalysisQueue.java tests/WakeAnalysisQueueTest.java
java -cp "$test_dir/recorded" com.iris.assistant.WakeAnalysisQueueTest
