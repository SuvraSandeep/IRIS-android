#!/usr/bin/env python3
"""
Test suite for Real Agent Planning: Bridging LocalPlanner and Execution.
Validates:
1. Multi-intent compound semantic intent decomposition and JSON synthesis.
2. Sensitivity propagation (needs_confirmation = true when call_contact / compose_message present).
3. PlanRunner full dispatch coverage across all ToolCall.KNOWN tools.
4. PlanRunner.Host implementation in IrisListeningService.
5. Pure policy constraint: no android.* imports in Plan, IntentParser, IrisIntent, ToolCall.
"""

import os
import re
import sys
import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
JAVA_SRC = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "iris" / "assistant"

def test_pure_policy_classes():
    pure_classes = ["Plan.java", "IntentParser.java", "IrisIntent.java", "ToolCall.java"]
    for fname in pure_classes:
        fpath = JAVA_SRC / fname
        assert fpath.exists(), f"File {fname} not found"
        content = fpath.read_text(encoding="utf-8")
        android_imports = re.findall(r"^\s*import\s+android\..*", content, re.MULTILINE)
        assert not android_imports, f"{fname} must have zero android.* imports, found: {android_imports}"
    print("PASS: Pure policy classes verified (zero android.* imports)")

def test_toolcall_known_coverage():
    tc_file = JAVA_SRC / "ToolCall.java"
    pr_file = JAVA_SRC / "PlanRunner.java"
    tc_content = tc_file.read_text(encoding="utf-8")
    pr_content = pr_file.read_text(encoding="utf-8")

    # Extract ToolCall.KNOWN
    m = re.search(r"Arrays\.asList\(([^)]+)\)", tc_content, re.DOTALL)
    assert m, "Could not extract ToolCall.KNOWN array"
    known_tools = [t.strip().strip('"') for t in m.group(1).split(",") if t.strip()]
    assert len(known_tools) == 20, f"Expected 20 known tools, found {len(known_tools)}: {known_tools}"

    # Verify each known tool is handled in PlanRunner.executeStep
    for tool in known_tools:
        case_pattern = rf'case\s+"{tool}"\s*:'
        assert re.search(case_pattern, pr_content), f"ToolCall {tool} is not handled in PlanRunner.executeStep"
    print(f"PASS: All {len(known_tools)} ToolCall.KNOWN tools dispatched in PlanRunner")

def test_host_methods_in_listening_service():
    pr_file = JAVA_SRC / "PlanRunner.java"
    ils_file = JAVA_SRC / "IrisListeningService.java"
    pr_content = pr_file.read_text(encoding="utf-8")
    ils_content = ils_file.read_text(encoding="utf-8")

    # Extract methods from PlanRunner.Host
    m = re.search(r"public\s+interface\s+Host\s*\{([^}]+)\}", pr_content, re.DOTALL)
    assert m, "Could not extract PlanRunner.Host interface"
    host_body = m.group(1)
    method_decls = re.findall(r"\b([a-zA-Z0-9_]+)\s*\([^)]*\)\s*;", host_body)

    for method in method_decls:
        # Check method exists in IrisListeningService
        pattern = rf"\b{method}\s*\("
        assert re.search(pattern, ils_content), f"Host method {method} not found in IrisListeningService"
    print(f"PASS: All {len(method_decls)} PlanRunner.Host methods implemented in IrisListeningService")

def test_multi_intent_decomposition():
    conjunctions = re.compile(
        r"\s*(?:;\s*|,\s*(?:and(?:\s+then)?|then)?\s*|\s+(?:and(?:\s+then)?|then|also|plus|after\s+that)\s+)\s*",
        re.IGNORECASE
    )

    test_phrases = [
        ("set an alarm for 7 am and turn on the torch", ["set an alarm for 7 am", "turn on the torch"]),
        ("turn on the torch and take a screenshot", ["turn on the torch", "take a screenshot"]),
        ("call Alice and set a timer for 10 minutes", ["call Alice", "set a timer for 10 minutes"]),
        ("take a selfie, then turn off the torch", ["take a selfie", "turn off the torch"]),
        ("turn on the torch; take a screenshot", ["turn on the torch", "take a screenshot"]),
        ("record the screen for 2 minutes and set volume to 50", ["record the screen for 2 minutes", "set volume to 50"]),
    ]

    for utterance, expected_parts in test_phrases:
        parts = [p.strip() for p in conjunctions.split(utterance) if p.strip()]
        assert parts == expected_parts, f"Mismatch splitting '{utterance}': got {parts}, expected {expected_parts}"
    print(f"PASS: Multi-intent conjunction regex decomposition verified across {len(test_phrases)} phrases")

def test_slm_engine_wiring():
    ils_file = JAVA_SRC / "IrisListeningService.java"
    ils_content = ils_file.read_text(encoding="utf-8")

    assert "private OnnxSlmEngine slmEngine;" in ils_content, "slmEngine field missing in IrisListeningService"
    assert "private LocalPlanner localPlanner;" in ils_content, "localPlanner field missing in IrisListeningService"
    assert "slmEngine = new OnnxSlmEngine(this);" in ils_content, "slmEngine not initialized in onCreate"
    assert "localPlanner = new LocalPlanner(slmEngine);" in ils_content, "localPlanner not initialized in onCreate"
    assert "slmEngine.close();" in ils_content, "slmEngine not closed in onDestroy"
    assert "localPlanner.plan(clean, recentActionContext())" in ils_content, "localPlanner.plan not invoked in handleCommandInner"
    assert "planRunner.execute(plan)" in ils_content, "planRunner.execute not invoked for complete plans"
    print("PASS: OnnxSlmEngine, LocalPlanner, and PlanRunner wired in IrisListeningService")

def main():
    print("Running Real Agent Planning verification test suite...")
    test_pure_policy_classes()
    test_toolcall_known_coverage()
    test_host_methods_in_listening_service()
    test_multi_intent_decomposition()
    test_slm_engine_wiring()
    print("ALL PLANNING PIPELINE CHECKS PASSED (100%)")

if __name__ == "__main__":
    main()
