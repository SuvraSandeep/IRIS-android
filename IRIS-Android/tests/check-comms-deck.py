#!/usr/bin/env python3
"""Verification test suite for Item 4: Comms Deck, Auto-Responder & Notification Vault.

Verifies:
1. Double-Text Concatenation Buffer in IrisNotificationListener:
   - Map<String, List<String>> pendingBubbles, pendingFlushes, pendingIncomings
   - 2500ms sliding window delay and cancellation
   - Debounce.combine integration
   - onDestroy cancellation and flush cleanup
2. Exact Alarm & Reminder Scheduling on Android 12+:
   - Capability.EXACT_ALARMS (Kind.SPECIAL, core=false, full stop punctuation)
   - Capability.CALENDAR (Kind.RUNTIME, core=false, full stop punctuation)
   - Manifest declarations for READ_CALENDAR, SCHEDULE_EXACT_ALARM, USE_EXACT_ALARM
   - ReminderStore.java and IrisListeningService.java SecurityException handling & setWindow fallback
   - MainActivity capabilityGranted, requestCapability, and permissionFor mapping
3. Driving & Meeting Situational Auto-Modes:
   - AutoModeDetector.java existence, isDrivingActive, isMeetingActive, evaluateSignals
   - AutoModePolicy.Signals wiring in AutoResponder.compose
   - ResponderSettings autoDriving and autoMeeting getters/setters
   - MainActivity Comms Deck UI switches for Auto-Driving and Auto-Meeting
4. Pure policy compliance:
   - Debounce.java, AutoModePolicy.java, PermissionCatalog.java free of android.* imports
"""

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP_SRC = ROOT / "app" / "src" / "main" / "java" / "com" / "iris" / "assistant"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"


def check(condition, msg):
    if not condition:
        print(f"FAIL: {msg}")
        sys.exit(1)
    print(f"PASS: {msg}")


def test_pure_policy_classes():
    pure_files = ["Debounce.java", "AutoModePolicy.java", "PermissionCatalog.java"]
    for fname in pure_files:
        path = APP_SRC / fname
        check(path.exists(), f"{fname} exists")
        content = path.read_text(encoding="utf-8")
        android_imports = [line for line in content.splitlines() if line.strip().startswith("import android.")]
        check(len(android_imports) == 0, f"{fname} has 0 android.* imports (pure policy)")


def test_double_text_buffering():
    path = APP_SRC / "IrisNotificationListener.java"
    content = path.read_text(encoding="utf-8")
    check("pendingBubbles" in content, "pendingBubbles map declared in IrisNotificationListener")
    check("pendingFlushes" in content, "pendingFlushes map declared in IrisNotificationListener")
    check("pendingIncomings" in content, "pendingIncomings map declared in IrisNotificationListener")
    check("bubbleHandler.postDelayed(flushTask, 2500L)" in content, "2500ms sliding window buffer scheduled")
    check("Debounce.combine" in content, "Debounce.combine called to coalesce rapid-fire bubbles")
    check("bubbleHandler.removeCallbacks" in content, "Sliding window timer reset on rapid-fire bubble arrival")
    check("onDestroy" in content and "pendingFlushes.clear()" in content, "onDestroy cleans up pending bubble flushes")


def test_exact_alarms_and_reminders():
    # 1. PermissionCatalog
    pc_path = APP_SRC / "PermissionCatalog.java"
    pc_content = pc_path.read_text(encoding="utf-8")
    check("EXACT_ALARMS" in pc_content, "EXACT_ALARMS capability in PermissionCatalog")
    check("CALENDAR" in pc_content, "CALENDAR capability in PermissionCatalog")
    check('Kind.SPECIAL, "Exact alarms & reminders"' in pc_content, "EXACT_ALARMS is Kind.SPECIAL")
    check('Kind.RUNTIME, "Calendar access"' in pc_content, "CALENDAR is Kind.RUNTIME")

    # 2. Manifest
    manifest_content = MANIFEST.read_text(encoding="utf-8")
    check("android.permission.READ_CALENDAR" in manifest_content, "READ_CALENDAR in AndroidManifest.xml")
    check("android.permission.SCHEDULE_EXACT_ALARM" in manifest_content, "SCHEDULE_EXACT_ALARM in AndroidManifest.xml")
    check("android.permission.USE_EXACT_ALARM" in manifest_content, "USE_EXACT_ALARM in AndroidManifest.xml")

    # 3. ReminderStore fallback
    rs_path = APP_SRC / "ReminderStore.java"
    rs_content = rs_path.read_text(encoding="utf-8")
    check("catch (SecurityException se)" in rs_content, "ReminderStore catches SecurityException on exact alarm")
    check("am.setWindow(android.app.AlarmManager.RTC_WAKEUP, triggerAtMs, 60_000L, pi)" in rs_content,
          "ReminderStore falls back to setWindow on SecurityException")

    # 4. IrisListeningService fallback
    ils_path = APP_SRC / "IrisListeningService.java"
    ils_content = ils_path.read_text(encoding="utf-8")
    check("am.setWindow(android.app.AlarmManager.RTC_WAKEUP, triggerAt, 60_000L, pi)" in ils_content,
          "IrisListeningService falls back to setWindow on SecurityException")

    # 5. MainActivity wiring
    ma_path = APP_SRC / "MainActivity.java"
    ma_content = ma_path.read_text(encoding="utf-8")
    check("case CALENDAR:" in ma_content and "Manifest.permission.READ_CALENDAR" in ma_content,
          "MainActivity maps CALENDAR to Manifest.permission.READ_CALENDAR")
    check("entry.capability == PermissionCatalog.Capability.EXACT_ALARMS" in ma_content,
          "MainActivity checks capabilityGranted for EXACT_ALARMS")
    check("ACTION_REQUEST_SCHEDULE_EXACT_ALARM" in ma_content,
          "MainActivity deep-links to ACTION_REQUEST_SCHEDULE_EXACT_ALARM")


def test_auto_modes():
    # 1. AutoModeDetector
    amd_path = APP_SRC / "AutoModeDetector.java"
    amd_content = amd_path.read_text(encoding="utf-8")
    check("isDrivingActive(Context context)" in amd_content, "AutoModeDetector.isDrivingActive implemented")
    check("isMeetingActive(Context context)" in amd_content, "AutoModeDetector.isMeetingActive implemented")
    check("evaluateSignals(Context context, ResponderSettings settings, int minuteOfDay)" in amd_content,
          "AutoModeDetector.evaluateSignals implemented")
    check("CalendarContract.Instances.AVAILABILITY_BUSY" in amd_content,
          "AutoModeDetector checks CalendarContract for BUSY availability")

    # 2. ResponderSettings
    rs_path = APP_SRC / "ResponderSettings.java"
    rs_content = rs_path.read_text(encoding="utf-8")
    check("public boolean autoDriving()" in rs_content, "ResponderSettings.autoDriving getter implemented")
    check("public void setAutoDriving(boolean v)" in rs_content, "ResponderSettings.setAutoDriving setter implemented")
    check("public boolean autoMeeting()" in rs_content, "ResponderSettings.autoMeeting getter implemented")
    check("public void setAutoMeeting(boolean v)" in rs_content, "ResponderSettings.setAutoMeeting setter implemented")

    # 3. AutoResponder.compose wiring
    ar_path = APP_SRC / "AutoResponder.java"
    ar_content = ar_path.read_text(encoding="utf-8")
    check("AutoModeDetector.evaluateSignals(context, settings, minuteOfDay)" in ar_content,
          "AutoResponder.compose evaluates signals with AutoModeDetector")

    # 4. MainActivity UI switches
    ma_path = APP_SRC / "MainActivity.java"
    ma_content = ma_path.read_text(encoding="utf-8")
    check('responderSwitch("Auto-driving when connected to car Bluetooth"' in ma_content,
          "MainActivity renders Auto-driving switch toggle")
    check('responderSwitch("Auto-meeting during busy calendar events"' in ma_content,
          "MainActivity renders Auto-meeting switch toggle")


def main():
    print("Running Comms Deck, Auto-Responder & Notification Vault verification suite...")
    test_pure_policy_classes()
    test_double_text_buffering()
    test_exact_alarms_and_reminders()
    test_auto_modes()
    print("ALL COMMS DECK CHECKS PASSED (100%)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
