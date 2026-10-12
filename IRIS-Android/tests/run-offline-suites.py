#!/usr/bin/env python3
"""Run the javac/java commands from scripts/test-speech.sh on a machine without bash.

WHY THIS EXISTS: the offline suites are defined in scripts/test-speech.sh, which CI runs under bash.
On a Windows development machine bash cannot see the JDK, so the suites were being verified by
re-typing the javac file lists by hand. That is a second, silently divergent source of truth: a
block in the script missing a dependency still passed locally because the hand-typed command
happened to include it, and the mistake only surfaced in CI.

This executes the ACTUAL commands from the script, so the thing being verified is the thing CI runs.

It is a pragmatic reader, not a bash implementation. It handles backslash line continuations,
`mkdir -p`, `javac`, `java`, `python3`, and the one `$(find ...)` substitution the script uses. Any
line it cannot interpret is reported as skipped rather than silently ignored, so the output never
overstates what was checked.

Run from the IRIS-Android directory:  python tests/run-offline-suites.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path("scripts/test-speech.sh")


def logical_lines(text):
    """Join backslash-continued lines, dropping comments and blanks."""
    out = []
    buffer = ""
    for raw in text.splitlines():
        line = raw.rstrip()
        stripped = line.strip()
        if not buffer and (not stripped or stripped.startswith("#")):
            continue
        if line.endswith("\\"):
            buffer += line[:-1].rstrip() + " "
            continue
        out.append((buffer + stripped).strip())
        buffer = ""
    if buffer.strip():
        out.append(buffer.strip())
    return out


def expand(token, test_dir):
    token = token.replace('"', "").replace("'", "")
    return token.replace("$test_dir", test_dir).replace("${test_dir}", test_dir)


def substitute_find(line):
    """Replace $(find <dir> -name '<glob>') with the matching paths, as bash would."""
    pattern = re.compile(r"\$\(find\s+(\S+)\s+-name\s+'([^']+)'\s*\)")

    def replace(match):
        root = Path(match.group(1))
        if not root.exists():
            return ""
        return " ".join(str(p).replace("\\", "/") for p in sorted(root.rglob(match.group(2))))

    return pattern.sub(replace, line)


def main():
    if not SCRIPT.exists():
        print(f"FAILED: {SCRIPT} not found (run from the IRIS-Android directory)")
        return 1

    java_home = os.environ.get("JAVA_HOME", "")
    javac = str(Path(java_home, "bin", "javac")) if java_home else "javac"
    java = str(Path(java_home, "bin", "java")) if java_home else "java"
    if not shutil.which(javac) and not Path(javac + ".exe").exists():
        javac, java = "javac", "java"

    test_dir = tempfile.mkdtemp(prefix="iris-suites-")
    ran = 0
    skipped = []
    failures = []

    try:
        for line in logical_lines(SCRIPT.read_text(encoding="utf-8")):
            head = line.split()[0]

            if head in ("set", "trap", "cd", "echo") or line.startswith("test_dir="):
                continue

            if head == "mkdir":
                for token in line.split()[1:]:
                    if token.startswith("-"):
                        continue
                    Path(expand(token, test_dir)).mkdir(parents=True, exist_ok=True)
                continue

            if head in ("javac", "java", "python3", "python"):
                # The script uses one command substitution: $(find <dir> -name '<glob>').
                # Implemented rather than skipped, because skipping it would leave the classes it
                # compiles missing and make the dependent java command fail for the wrong reason.
                line = substitute_find(line)

                tokens = []
                for token in line.split():
                    if "$(" in token:
                        skipped.append(line)
                        tokens = None
                        break
                    tokens.append(expand(token, test_dir))
                if tokens is None:
                    continue

                if head == "javac":
                    tokens[0] = javac
                elif head == "java":
                    tokens[0] = java
                    # The script relies on the Linux default charset being UTF-8. On Windows the
                    # default is windows-1252, which makes ParseSources fail on the non-ASCII
                    # characters in the sources. This is an accommodation for running the script
                    # off-platform, not a defect in the script itself.
                    tokens.insert(1, "-Dfile.encoding=UTF-8")
                else:
                    tokens[0] = sys.executable

                result = subprocess.run(tokens, capture_output=True, text=True)
                ran += 1
                if result.returncode != 0:
                    failures.append((" ".join(tokens[:6]) + " ...",
                                     (result.stderr or result.stdout).strip()[:1500]))
                elif head in ("java", "python3", "python") and result.stdout.strip():
                    for out_line in result.stdout.strip().splitlines():
                        print("  " + out_line)
                continue

            skipped.append(line)
    finally:
        shutil.rmtree(test_dir, ignore_errors=True)

    print()
    print(f"Executed {ran} commands from {SCRIPT}")
    if skipped:
        print(f"Skipped {len(skipped)} line(s) this reader cannot interpret:")
        for line in skipped:
            print("  ! " + (line[:110] + ("..." if len(line) > 110 else "")))
        print("  (those blocks are only covered by CI)")

    if failures:
        print()
        print(f"FAILED: {len(failures)} command(s) from the suite script")
        for command, error in failures:
            print(f"\n  $ {command}\n{error}")
        return 1
    print("All interpretable suite commands passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
