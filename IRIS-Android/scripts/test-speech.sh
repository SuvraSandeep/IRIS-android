#!/usr/bin/env bash
# Runs every offline regression suite plus a syntax check of all sources.
# Android type checking still requires the APK build.
set -euo pipefail
cd "$(dirname "$0")/.."
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
javac -encoding UTF-8 -d "$test_dir" \
  app/src/main/java/com/iris/assistant/SpeechText.java \
  app/src/main/java/com/iris/assistant/SmsIntentPolicy.java \
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
  app/src/main/java/com/iris/assistant/ChargingState.java \
  tests/PhoneCommandsTest.java \
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
java -cp "$test_dir" com.iris.assistant.PhoneCommandsTest
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
# Parse every resource XML the way AAPT will. ParseSources.java only covers Java, so a
# malformed resource (e.g. "--" used as a dash inside an XML comment, which is illegal XML)
# previously got all the way to CI and failed :app:mergeDebugResources.
python3 tests/check-xml.py
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
javac -encoding UTF-8 -cp "$test_dir/recorded" -d "$test_dir/recorded" app/src/main/java/com/iris/assistant/AudioRing.java app/src/main/java/com/iris/assistant/StreamingWakeDetector.java app/src/main/java/com/iris/assistant/LiveWakeProbe.java tests/StreamingWakeTest.java
java -cp "$test_dir/recorded" com.iris.assistant.StreamingWakeTest
javac -encoding UTF-8 -d "$test_dir/recorded" app/src/main/java/com/iris/assistant/ReplyCompletionGate.java app/src/main/java/com/iris/assistant/CommandLoadGate.java tests/ReplyCompletionTest.java
java -cp "$test_dir/recorded" com.iris.assistant.ReplyCompletionTest

# Phone-history question classifier + pronoun follow-ups. Both are pure (no Android), so the
# regression guards that stop them stealing commands from the existing outgoing-call history
# and redial handlers run offline here.
mkdir -p "$test_dir/history"
javac -encoding UTF-8 -d "$test_dir/history" \
  app/src/main/java/com/iris/assistant/PhoneHistoryQuery.java \
  app/src/main/java/com/iris/assistant/Referent.java \
  tests/PhoneHistoryQueryTest.java \
  tests/ReferentTest.java
java -cp "$test_dir/history" com.iris.assistant.PhoneHistoryQueryTest
java -cp "$test_dir/history" com.iris.assistant.ReferentTest

# Assistant policy classes: quiet hours, notification summary grouping, proactive suggestion
# restraint (cooldown / quiet-hours silence / priority order). All pure, no Android.
mkdir -p "$test_dir/policy"
javac -encoding UTF-8 -d "$test_dir/policy" \
  app/src/main/java/com/iris/assistant/CallTiming.java \
  app/src/main/java/com/iris/assistant/NotificationSummary.java \
  app/src/main/java/com/iris/assistant/ProactiveSuggestion.java \
  tests/AssistantPolicyTest.java
java -cp "$test_dir/policy" com.iris.assistant.AssistantPolicyTest

# Permission dashboard catalogue. Pure (no Android) by design: the real Manifest.permission
# constants are mapped in MainActivity so the compiler checks them, leaving the catalogue to hold
# only the owner-facing copy. These checks pin that every capability has a row, that "core" keeps
# meaning "IRIS cannot work without it", and that notification access stays SPECIAL rather than
# RUNTIME (it cannot be granted by a dialog, so a RUNTIME label would render a dead Allow action).
mkdir -p "$test_dir/permissions"
javac -encoding UTF-8 -d "$test_dir/permissions" \
  app/src/main/java/com/iris/assistant/PermissionCatalog.java \
  tests/PermissionCatalogTest.java
java -cp "$test_dir/permissions" com.iris.assistant.PermissionCatalogTest

# Notification-archive decision rules (deleted / unsent message detection). Pure by design: the
# Android REASON_* constants are mapped to an enum in IrisNotificationListener so the compiler
# checks them, leaving the judgement calls here where they are cheap to test. The false-positive
# guards matter most -- owner dismissal, owner taps and the owner's own deletions must never be
# reported as somebody taking a message back.
mkdir -p "$test_dir/archive"
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/DeletedMessageRules.java \
  tests/DeletedMessageRulesTest.java
java -cp "$test_dir/archive" com.iris.assistant.DeletedMessageRulesTest

# Archive size policy (pure arithmetic and capture decisions).
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/ArchiveSchema.java \
  tests/ArchiveSchemaTest.java
java -cp "$test_dir/archive" com.iris.assistant.ArchiveSchemaTest

# Archive app targeting: which apps are pre-ticked, clone vs work-profile labelling.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/ArchiveAppTargets.java \
  tests/ArchiveAppTargetsTest.java
java -cp "$test_dir/archive" com.iris.assistant.ArchiveAppTargetsTest

# Export formatting. The CSV escaping cases matter most: the export is the only copy of the archive
# that survives a factory reset or a new phone, so a message containing a comma must not shift
# every later column and corrupt the owner's only backup.
#
# Every source this block needs is listed explicitly rather than relying on classes left behind by
# the blocks above. ArchiveRow and ArchiveExport both reference DeletedMessageRules and
# ArchiveSchema, and omitting them here broke CI once even though the suite passed locally, because
# the local run happened to pass the full file list by hand. A self-contained block cannot be broken
# by reordering or removing an earlier one.
javac -encoding UTF-8 -cp "$test_dir/archive" -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/DeletedMessageRules.java \
  app/src/main/java/com/iris/assistant/ArchiveSchema.java \
  app/src/main/java/com/iris/assistant/ArchiveRow.java \
  app/src/main/java/com/iris/assistant/ArchiveExport.java \
  tests/ArchiveExportTest.java
java -cp "$test_dir/archive" com.iris.assistant.ArchiveExportTest

# Deleted-message voice query. The regression guards are the point: this classifier is dispatched
# BEFORE the existing notification reader, so any phrasing it wrongly claims is one the notification
# reader silently stops serving. That mistake has already shipped once in this project.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/DeletedQuery.java \
  tests/DeletedQueryTest.java
java -cp "$test_dir/archive" com.iris.assistant.DeletedQueryTest

# The listener watchdog. Android reports a clean disconnect, but a process killed outright by an OEM
# battery manager reports nothing, so the watchdog is the only thing that notices. Its decisions are
# pure timing and state, including a clock that moves backwards, so they are all checkable here.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/ListenerWatchdog.java \
  tests/ListenerWatchdogTest.java
java -cp "$test_dir/archive" com.iris.assistant.ListenerWatchdogTest

# Phone-number recognition for contact name resolution. The false-positive direction is the risk:
# a name misread as a number would be looked up in contacts and, on a match, replace a name the
# messaging app already supplied correctly. A false negative only leaves a number unresolved.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/ContactNumber.java \
  tests/ContactNumberTest.java
java -cp "$test_dir/archive" com.iris.assistant.ContactNumberTest

# Image-capture verdict. Pins the mistake already made once: concluding "no images" from
# EXTRA_PICTURE alone while never examining the MessagingStyle data URI a smartwatch uses. Also
# pins the worse opposite error, reporting images available because a sender avatar was found.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/NotificationImageSupport.java \
  tests/NotificationImageSupportTest.java
java -cp "$test_dir/archive" com.iris.assistant.NotificationImageSupportTest

# Roll-up detection. An on-device probe showed WhatsApp posts "14 messages from 3 chats" as a
# summary, which would otherwise be archived as a message whose content is a number. The
# false-positive direction is weighted heavily: discarding a real message is permanent.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/MessageRollup.java \
  tests/MessageRollupTest.java
java -cp "$test_dir/archive" com.iris.assistant.MessageRollupTest

# Messaging-channel classifier. SMS behaves unlike the chat apps (lettered shortcodes, one-time
# codes, no group semantics), so naming the channel is the first step to treating it differently.
mkdir -p "$test_dir/responder"
javac -encoding UTF-8 -d "$test_dir/responder" \
  app/src/main/java/com/iris/assistant/MessagingApp.java \
  tests/MessagingAppTest.java
java -cp "$test_dir/responder" com.iris.assistant.MessagingAppTest

# Image sizing and budget. Images are orders of magnitude larger than text rows, so the budget is a
# fraction of the ceiling: a vault full of photos must never be the reason a deleted message goes
# uncaptured. Sample size is computed from dimensions alone, before decoding, so a camera photo is
# never fully loaded in the notification listener process.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/ArchiveImagePolicy.java \
  tests/ArchiveImagePolicyTest.java
java -cp "$test_dir/archive" com.iris.assistant.ArchiveImagePolicyTest

# Swipe-to-delete thresholds. Two opposing failures: claiming gestures too eagerly makes the list
# impossible to scroll, dismissing too eagerly deletes evidence that cannot be recovered. The bias
# is toward scrolling throughout, and these checks stop a later tweak reversing that.
javac -encoding UTF-8 -d "$test_dir/archive" \
  app/src/main/java/com/iris/assistant/SwipeDecision.java \
  tests/SwipeDecisionTest.java
java -cp "$test_dir/archive" com.iris.assistant.SwipeDecisionTest

# Auto-responder message classification. Weighted toward what must NOT match: a missed category
# means silence, which is safe, while a false match sends a colleague a reply from Suvra's account
# to a message IRIS did not understand. Includes the owner's real Odia messages captured by the
# PROBE as must-not-match cases, plus the substring traps for the ty/gm/gn/k shortforms.
mkdir -p "$test_dir/responder"
javac -encoding UTF-8 -d "$test_dir/responder" \
  app/src/main/java/com/iris/assistant/MessageCategory.java \
  tests/MessageCategoryTest.java
java -cp "$test_dir/responder" com.iris.assistant.MessageCategoryTest

# Tone resolution and the silence decision. Sarcasm must be unreachable without naming a contact,
# a relationship must beat a name pattern (the owner's "eric" rule would otherwise claim a relative
# called Erica), and "ok" must be silent when professional but answered when sarcastic.
javac -encoding UTF-8 -d "$test_dir/responder" \
  app/src/main/java/com/iris/assistant/MessageCategory.java \
  app/src/main/java/com/iris/assistant/ReplyTone.java \
  app/src/main/java/com/iris/assistant/ReplyTemplates.java \
  app/src/main/java/com/iris/assistant/ResponderMode.java \
  tests/ReplyToneTest.java
java -cp "$test_dir/responder" com.iris.assistant.ReplyToneTest

# Responder time windows. The owner's default quiet hours are 23:00-07:00, which wrap past
# midnight: a naive start <= now && now <= end test is false for every minute of that window, so
# quiet hours would silently never fire. The other guarded failure is a negative duration, which
# would reach a recipient as "he'll be up in -16 hours". Properties are swept across all 1440
# minutes of the day rather than spot-checked.
javac -encoding UTF-8 -d "$test_dir/responder" \
  app/src/main/java/com/iris/assistant/MessageCategory.java \
  app/src/main/java/com/iris/assistant/ReplyTone.java \
  app/src/main/java/com/iris/assistant/ResponderWindow.java \
  tests/ResponderWindowTest.java
java -cp "$test_dir/responder" com.iris.assistant.ResponderWindowTest

# Responder guards. These are what stop the feature embarrassing its owner, so they are checked
# adversarially. The most important is the burst guard: one notification on the owner's own phone
# held seven messages from one chat, which without it is seven replies to one person in one second.
# Where a judgement could go either way these guards choose silence, the opposite of the listener
# watchdog, because acting wrongly here puts a message in somebody's chat.
javac -encoding UTF-8 -d "$test_dir/responder" \
  app/src/main/java/com/iris/assistant/MessageCategory.java \
  app/src/main/java/com/iris/assistant/ReplyTone.java \
  app/src/main/java/com/iris/assistant/ResponderGuards.java \
  tests/ResponderGuardsTest.java
java -cp "$test_dir/responder" com.iris.assistant.ResponderGuardsTest

# Shipped reply texts. The headline check sweeps EVERY variant of EVERY category in EVERY tone and
# asserts each one names both IRIS and Suvra, which is the transparency decision made enforceable:
# a later edit cannot quietly ship a reply that reads as though Suvra wrote it himself. Also pins
# that urgency does not pierce quiet hours but does redirect the sender to a phone call.
javac -encoding UTF-8 -d "$test_dir/responder" \
  app/src/main/java/com/iris/assistant/MessageCategory.java \
  app/src/main/java/com/iris/assistant/ReplyTone.java \
  app/src/main/java/com/iris/assistant/ReplyTemplates.java \
  app/src/main/java/com/iris/assistant/ResponderMode.java \
  tests/ReplyTemplatesTest.java
java -cp "$test_dir/responder" com.iris.assistant.ReplyTemplatesTest

# The append-only guarantee is enforced by SQLite triggers, not by Java, so it cannot be covered by
# the Java suites above (SQLiteOpenHelper is Android-only and Robolectric is unavailable offline).
# Python ships a real SQLite engine, so the DDL is extracted from ArchiveSchema.java and actually
# executed: edits to captured content and unauthorised deletes must both be refused by the engine.
python3 tests/check-archive-schema.py

# Catch the errors that only the real Android compile would otherwise find: a type used without an
# import (which parses fine and then fails compilation), and an R.id reference with no matching
# layout id. Both have cost CI round-trips, and neither is visible to ParseSources.
python3 tests/check-unresolved-types.py
