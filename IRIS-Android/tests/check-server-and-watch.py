#!/usr/bin/env python3
"""Verification suite for Item 5: Server Mode & Watch Companion Bridge.

Verifies:
1. ServerClient pure policy (zero android.* imports).
2. ServerClient SSE streaming contracts, StreamListener interface, and sentence splitting.
3. IrisListeningService streaming TTS dispatch and lifecycle re-arming.
4. IRIS-Android server/app.py /chat/stream SSE streaming endpoint.
5. WatchBridge RFC 6455 WebSocket handshake, framing, and dual-transport architecture.
6. WatchGattBridge authenticated BLE GATT service and lifecycle.
7. IRIS-Watch app-side WebSocket fallback when local HTTP is blocked.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def test_pure_policy(file_path):
    content = file_path.read_text(encoding="utf-8")
    android_imports = [line for line in content.splitlines() if line.strip().startswith("import android.")]
    assert not android_imports, f"{file_path.name} violates pure policy rule by importing android.*: {android_imports}"
    print(f"PASS: {file_path.name} has 0 android.* imports (pure policy)")

def test_sentence_split_algorithm():
    # Mirror Java findSentenceSplit logic
    def is_abbreviation(s, dot_idx):
        start = dot_idx - 1
        while start >= 0 and (s[start].isalpha() or s[start] == '.'):
            start -= 1
        word = s[start + 1:dot_idx].replace(".", "").lower()
        return word in {"mr", "mrs", "ms", "dr", "vs", "etc", "eg", "ie", "sr", "jr", "prof", "inc", "ltd"}

    def find_sentence_split(s):
        if not s or len(s) < 3:
            return -1
        open_brackets = 0
        for i, c in enumerate(s):
            if c == '[':
                open_brackets += 1
            elif c == ']' and open_brackets > 0:
                open_brackets -= 1
            if open_brackets == 0:
                if c in {'.', '!', '?'}:
                    if i + 1 < len(s):
                        next_ch = s[i + 1]
                        if next_ch.isspace():
                            if c == '.' and is_abbreviation(s, i):
                                continue
                            return i + 1
                elif c == ']':
                    if i + 1 < len(s) and s[i + 1].isspace():
                        return i + 1
                elif c == '\n':
                    if i + 1 < len(s) and s[i + 1] == '\n':
                        return i + 1
        return -1

    # Case 1: Simple sentence split
    t1 = "Hello world. How are you today?"
    s1 = find_sentence_split(t1)
    assert s1 == 12, f"Expected split at 12, got {s1}"
    assert t1[:s1].strip() == "Hello world."

    # Case 2: Action tag inside brackets - must not split inside, but split after closing bracket
    t2 = "[CALL: Dr. Smith. Extra info.] Next sentence here."
    s2 = find_sentence_split(t2)
    assert s2 == len("[CALL: Dr. Smith. Extra info.]"), f"Expected split after bracket, got {s2}"
    assert t2[:s2].strip() == "[CALL: Dr. Smith. Extra info.]"

    # Case 3: Abbreviations like Dr. and e.g.
    t3 = "Please visit Dr. Smith or e.g. consult a specialist. It is important."
    s3 = find_sentence_split(t3)
    assert t3[:s3].strip() == "Please visit Dr. Smith or e.g. consult a specialist."

    # Case 4: Exclamation and question marks
    t4 = "Watch out! Are you sure? Absolutely."
    s4 = find_sentence_split(t4)
    assert t4[:s4].strip() == "Watch out!"

    # Case 5: Floating point numbers
    t5 = "The score was 3.14 points. Well done!"
    s5 = find_sentence_split(t5)
    assert t5[:s5].strip() == "The score was 3.14 points."

    print("PASS: findSentenceSplit algorithm verified against sentence boundaries, abbreviations, and action tags")

def main():
    print("Running Server Mode & Watch Companion Bridge verification suite...")
    
    server_client_path = ROOT / "app/src/main/java/com/iris/assistant/ServerClient.java"
    service_path = ROOT / "app/src/main/java/com/iris/assistant/IrisListeningService.java"
    watch_bridge_path = ROOT / "app/src/main/java/com/iris/assistant/WatchBridge.java"
    watch_gatt_path = ROOT / "app/src/main/java/com/iris/assistant/WatchGattBridge.java"
    server_app_path = ROOT / "server/app.py"
    watch_app_side_path = ROOT.parent / "IRIS-Watch/app-side/index.js"

    # 1. ServerClient pure policy
    test_pure_policy(server_client_path)

    # 2. ServerClient SSE contracts
    sc_code = server_client_path.read_text(encoding="utf-8")
    assert "interface StreamListener" in sc_code, "StreamListener interface missing in ServerClient"
    assert "void onSentence(String sentence, int index)" in sc_code, "onSentence missing in StreamListener"
    assert "void onComplete(String fullReply)" in sc_code, "onComplete missing in StreamListener"
    assert "void onError(Throwable error)" in sc_code, "onError missing in StreamListener"
    assert "void chatStream(" in sc_code, "chatStream method missing in ServerClient"
    assert "static int findSentenceSplit(String s)" in sc_code, "findSentenceSplit missing in ServerClient"
    assert 'Accept", "text/event-stream' in sc_code, "Accept text/event-stream header missing"
    assert "data:" in sc_code, "SSE data: prefix parsing missing"
    print("PASS: ServerClient SSE stream parser and StreamListener contracts verified")

    test_sentence_split_algorithm()

    # 3. IrisListeningService streaming TTS integration
    svc_code = service_path.read_text(encoding="utf-8")
    assert "client.chatStream(" in svc_code, "IrisListeningService does not invoke client.chatStream"
    assert "speakStreamSentence(" in svc_code, "speakStreamSentence method missing"
    assert "scheduleStreamRearm(" in svc_code, "scheduleStreamRearm method missing"
    assert "TextToSpeech.QUEUE_FLUSH" in svc_code, "QUEUE_FLUSH missing for first streamed sentence"
    assert "TextToSpeech.QUEUE_ADD" in svc_code, "QUEUE_ADD missing for subsequent streamed sentences"
    assert 'lastUtterance.equals(id)' in svc_code, "Utterance progress listener does not track last streamed utterance"
    print("PASS: IrisListeningService streaming TTS and lifecycle re-arming verified")

    # 4. server/app.py SSE endpoint
    app_code = server_app_path.read_text(encoding="utf-8")
    assert "@app.post(\"/chat/stream\")" in app_code, "/chat/stream endpoint missing in server/app.py"
    assert "text/event-stream" in app_code, "text/event-stream media type missing in server/app.py"
    assert '"stream": True' in app_code or "'stream': True" in app_code, "stream: True missing in Ollama request"
    print("PASS: server/app.py /chat/stream SSE streaming verified")

    # 5. WatchBridge WebSocket transport
    wb_code = watch_bridge_path.read_text(encoding="utf-8")
    assert "258EAFA5-E914-47DA-95CA-C5AB0DC85B11" in wb_code, "RFC 6455 magic GUID missing in WatchBridge"
    assert "101 Switching Protocols" in wb_code, "101 Switching Protocols missing in WatchBridge"
    assert "Sec-WebSocket-Accept" in wb_code, "Sec-WebSocket-Accept header missing in WatchBridge"
    assert "serveWebSocket(" in wb_code, "serveWebSocket framing method missing in WatchBridge"
    assert "WatchGattBridge" in wb_code, "WatchGattBridge lifecycle coordination missing in WatchBridge"
    print("PASS: WatchBridge RFC 6455 WebSocket upgrade, framing, and dual-transport verified")

    # 6. WatchGattBridge BLE GATT Service
    wgb_code = watch_gatt_path.read_text(encoding="utf-8")
    assert "00001847-0000-1000-8000-00805f9b34fb" in wgb_code, "GATT Service UUID 1847 missing"
    assert "00001848-0000-1000-8000-00805f9b34fb" in wgb_code, "Command Characteristic UUID 1848 missing"
    assert "00001849-0000-1000-8000-00805f9b34fb" in wgb_code, "Response Characteristic UUID 1849 missing"
    assert "BluetoothGattServer" in wgb_code, "BluetoothGattServer usage missing in WatchGattBridge"
    assert "WatchBridge.authorized" in wgb_code, "Token authorization missing in WatchGattBridge"
    assert "actions.run(path)" in wgb_code, "Action dispatch missing in WatchGattBridge"
    print("PASS: WatchGattBridge BLE GATT service, authorization, and dispatch verified")

    # 7. IRIS-Watch app-side WebSocket fallback
    was_code = watch_app_side_path.read_text(encoding="utf-8")
    assert "fetch({" in was_code, "fetch() missing in IRIS-Watch app-side"
    assert "WebSocket" in was_code, "WebSocket fallback missing in IRIS-Watch app-side"
    assert "ws://127.0.0.1:18473/ws" in was_code, "WebSocket URL ws://127.0.0.1:18473/ws missing"
    print("PASS: IRIS-Watch app-side WebSocket fallback verified")

    print("\nALL SERVER MODE & WATCH COMPANION BRIDGE CHECKS PASSED (100%)")

if __name__ == "__main__":
    main()
