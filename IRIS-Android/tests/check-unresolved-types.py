#!/usr/bin/env python3
"""Flag type names a Java file uses but has no way to resolve.

WHY THIS EXISTS: there is no Android SDK on the development machine, so the only real type check is
CI. ParseSources validates syntax only, which means an unimported Android class -- `new TextWatcher()`
where the file never imports android.text.TextWatcher -- parses perfectly and then fails the CI
compile. That has now cost two CI round-trips, so this catches the specific class of error cheaply.

It is a heuristic, not a compiler. A name is considered resolvable if it is:
  - imported explicitly, or covered by a wildcard import;
  - declared in this file (including nested classes);
  - another class in the same package (any .java beside it);
  - a well-known java.lang type;
  - used fully qualified (a.b.Name), which is how this project references several Android types.

False positives are possible and are reported as "review", not failures, so the output is a short
list to eyeball rather than a gate that blocks on noise.

Run from the IRIS-Android directory:
  python tests/check-unresolved-types.py [file.java ...]
With no arguments it checks the notification-archive files, which are the ones never compiled
locally.
"""
import re
import sys
from pathlib import Path

SOURCE_ROOT = Path("app/src/main/java/com/iris/assistant")

DEFAULT_TARGETS = [
    "MainActivity.java",
    "IrisListeningService.java",
    "IrisNotificationListener.java",
    "NotificationArchive.java",
    "NotificationInspector.java",
    "ContactResolver.java",
    "ListenerWatchdog.java",
    "NotificationImageSupport.java",
    "ContactNumber.java",
    "MessageRollup.java",
    "ArchiveImages.java",
    "ArchiveImagePolicy.java",
    "SwipeDecision.java",
    "MessageCategory.java",
    "ReplyTone.java",
    "ResponderWindow.java",
    "ResponderGuards.java",
    "ReplyTemplates.java",
    "ResponderSettings.java",
    "NotificationReply.java",
    "AutoResponder.java",
    "ResponderAppPicker.java",
    "ResponderLog.java",
    "NotificationReplyActivity.java",
    "ArchiveAlerts.java",
    "ArchiveAppPicker.java",
    "ArchiveAppTargets.java",
    "ArchiveExport.java",
    "ArchiveRow.java",
    "ArchiveSchema.java",
    "DeletedMessageRules.java",
    "DeletedQuery.java",
    "IrisFileProvider.java",
    "ArchiveViewController.java",
    "VoicePipelineCoordinator.java",
    "PlanRunner.java",
    "AudioFocusSession.java",
    "OnnxSpeechRecognizer.java",
    "OnnxSlmEngine.java",
    "AutoModeDetector.java",
    "ServerClient.java",
    "WatchBridge.java",
    "WatchGattBridge.java",
    "HeadsetSensorCoordinator.java",
    "CallManager.java",
    "ResponderViewController.java",
    "ContactsViewController.java",
    "FlaggedQueueController.java",
]

JAVA_LANG = {
    "Object", "String", "CharSequence", "Integer", "Long", "Double", "Float", "Boolean", "Byte",
    "Short", "Character", "Number", "Math", "System", "Thread", "Runnable", "Exception",
    "RuntimeException", "IllegalStateException", "IllegalArgumentException", "NullPointerException",
    "Throwable", "Error", "AssertionError", "StringBuilder", "StringBuffer", "Class", "Enum",
    "Iterable", "Comparable", "Override", "Deprecated", "SuppressWarnings", "SafeVarargs",
    "FunctionalInterface", "AutoCloseable", "Cloneable", "Void", "Process", "ClassLoader",
    "InterruptedException", "NumberFormatException", "UnsupportedOperationException",
    "IndexOutOfBoundsException", "ArrayIndexOutOfBoundsException", "SecurityException",
    "ArithmeticException", "ClassCastException", "StackOverflowError", "OutOfMemoryError",
}

# Type-position patterns. Deliberately narrow: these are the places an unresolvable name actually
# breaks the build, and matching more broadly produced too much noise to be useful.
TYPE_USE = [
    re.compile(r"\bnew\s+([A-Z][A-Za-z0-9_]*)\s*[(<]"),
    re.compile(r"\bextends\s+([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"\bimplements\s+([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"\bcatch\s*\(\s*([A-Z][A-Za-z0-9_]*)"),
    re.compile(r"\binstanceof\s+([A-Z][A-Za-z0-9_]*)"),
]


def strip_noise(text):
    """Remove comments and string literals so their contents are never treated as code."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', text)
    text = re.sub(r"'(?:[^'\\]|\\.)'", "' '", text)
    return text


def resolvable_names(path, package_classes):
    text = path.read_text(encoding="utf-8")
    names = set(JAVA_LANG) | package_classes
    wildcard = False
    for line in text.splitlines():
        match = re.match(r"\s*import\s+(?:static\s+)?([\w.]+)\s*;", line)
        if not match:
            continue
        target = match.group(1)
        if target.endswith(".*"):
            wildcard = True
            continue
        names.add(target.rsplit(".", 1)[-1])
    # Types declared in this file, nested ones included.
    for match in re.finditer(r"\b(?:class|interface|enum|record)\s+([A-Z][A-Za-z0-9_]*)", text):
        names.add(match.group(1))
    return names, wildcard, strip_noise(text)


def check_layout_ids():
    """Every R.id.X referenced from Java must exist in a layout, or the compile fails.

    Another error class only the real Android build catches: R is generated from the resources, so a
    typo'd or deleted id parses fine and breaks compilation. android.R.id.* is excluded, being
    platform-provided rather than generated from this project's layouts.
    """
    layout_dir = Path("app/src/main/res/layout")
    if not layout_dir.exists():
        return 0, []

    defined = set()
    for layout in layout_dir.glob("*.xml"):
        defined.update(re.findall(r"@\+id/([A-Za-z0-9_]+)",
                                  layout.read_text(encoding="utf-8")))

    missing = []
    for source in SOURCE_ROOT.glob("*.java"):
        body = strip_noise(source.read_text(encoding="utf-8"))
        for match in re.finditer(r"(?<!android\.)\bR\.id\.([A-Za-z0-9_]+)", body):
            name = match.group(1)
            if name in defined:
                continue
            line_no = body[:match.start()].count("\n") + 1
            missing.append((source.name, line_no, name))
    return len(defined), missing


def check_forbidden_imports():
    """Verify no sources import unbundled androidx libraries (not in build.gradle)."""
    forbidden = []
    for source in SOURCE_ROOT.glob("*.java"):
        text = source.read_text(encoding="utf-8")
        for match in re.finditer(r"^\s*import\s+(androidx\.[^;]+);", text, re.M):
            line_no = text[:match.start()].count("\n") + 1
            forbidden.append((source.name, line_no, match.group(1)))
    return forbidden


def check_bare_services():
    """Verify non-Context classes do not use bare Context.*_SERVICE constants."""
    services = [
        'ALARM_SERVICE', 'NOTIFICATION_SERVICE', 'CLIPBOARD_SERVICE', 'AUDIO_SERVICE',
        'POWER_SERVICE', 'CONNECTIVITY_SERVICE', 'BLUETOOTH_SERVICE', 'SENSOR_SERVICE',
        'TELEPHONY_SERVICE', 'VIBRATOR_SERVICE', 'KEYGUARD_SERVICE'
    ]
    non_context_files = [
        'ResponderViewController.java', 'ContactsViewController.java',
        'FlaggedQueueController.java', 'ArchiveViewController.java',
        'CallManager.java', 'HeadsetSensorCoordinator.java',
        'VoicePipelineCoordinator.java'
    ]
    bare = []
    for fname in non_context_files:
        path = SOURCE_ROOT / fname
        if not path.exists():
            continue
        text = strip_noise(path.read_text(encoding="utf-8"))
        for s in services:
            for match in re.finditer(r'(?<!Context\.)(?<!android\.content\.Context\.)\b' + s + r'\b', text):
                line_no = text[:match.start()].count("\n") + 1
                bare.append((fname, line_no, s))
    return bare


def main():
    if not SOURCE_ROOT.exists():
        print(f"FAILED: {SOURCE_ROOT} not found (run from the IRIS-Android directory)")
        return 1

    package_classes = {p.stem for p in SOURCE_ROOT.glob("*.java")}
    targets = sys.argv[1:] or DEFAULT_TARGETS

    findings = []
    checked = 0
    for name in targets:
        path = Path(name) if Path(name).exists() else SOURCE_ROOT / name
        if not path.exists():
            print(f"  ! skipped, not found: {name}")
            continue
        checked += 1
        known, wildcard, body = resolvable_names(path, package_classes)
        if wildcard:
            # A wildcard import could resolve anything, so conclusions would be unsound.
            print(f"  ! {path.name} uses a wildcard import; skipped")
            continue

        seen = {}
        for pattern in TYPE_USE:
            for match in pattern.finditer(body):
                simple = match.group(1)
                if simple in known:
                    continue
                # Fully qualified uses carry a dot immediately before the name.
                start = match.start(1)
                if start > 0 and body[start - 1] == ".":
                    continue
                line_no = body[:start].count("\n") + 1
                seen.setdefault(simple, line_no)
        for simple, line_no in sorted(seen.items(), key=lambda item: item[1]):
            findings.append((path.name, line_no, simple))

    print(f"Checked {checked} file(s) for unresolvable type names")
    failed = False
    if findings:
        print(f"\n{len(findings)} name(s) to review (import them or qualify them fully):")
        for file_name, line_no, simple in findings:
            print(f"  {file_name}:{line_no}  {simple}")
        failed = True
    else:
        print("No unresolvable type names found.")

    forbidden = check_forbidden_imports()
    if forbidden:
        print(f"\n{len(forbidden)} forbidden unbundled import(s) found:")
        for file_name, line_no, imp in forbidden:
            print(f"  {file_name}:{line_no}  import {imp}")
        failed = True
    else:
        print("No forbidden unbundled imports found.")

    bare = check_bare_services()
    if bare:
        print(f"\n{len(bare)} bare Context constant(s) in non-Context classes:")
        for file_name, line_no, s in bare:
            print(f"  {file_name}:{line_no}  bare {s} (prefix with Context.)")
        failed = True
    else:
        print("No bare Context constants found in controller classes.")

    defined, missing = check_layout_ids()
    if missing:
        print(f"\n{len(missing)} R.id reference(s) with no matching layout id:")
        for file_name, line_no, name in missing:
            print(f"  {file_name}:{line_no}  R.id.{name}")
        failed = True
    else:
        print(f"All R.id references resolve against {defined} layout ids.")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
