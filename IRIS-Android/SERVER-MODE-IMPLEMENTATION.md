# IRIS Server Mode — Implementation Design Plan

Companion to `SERVER-MODE.md` (which covers *hosting options + architecture*). This document is the **how-to-build-it** plan: concrete classes, settings, integration points in the current codebase, the server reference implementation, threading, fallback logic, test plan, and a phased rollout.

**Non-negotiables:** offline-first, opt-in, automatic fallback, no new Gradle dependencies (use `HttpURLConnection` + `org.json`), actions always execute on the phone.

---

## 0. Where this plugs into the existing code

Real integration points in `IrisListeningService.java`:
- `handleChat(String clean, String normalized, ProfileStore store)` — the conversational entry (currently: LLM if ready, else `ruleBasedChat`). **This is where the server call goes first.**
- `handleCommandInner(...)` — command routing; unchanged (actions stay local).
- `ruleBasedChat(...)` — remains the ultimate offline fallback.
- `ConversationManager.transcript()` — conversation context to send.
- `PersonalProfile.contextForAI(context)` — persona/profile block for the system prompt.
- `speakThenRun(text, this::rearmAfterAction)` — speak reply + return to listening.
- `AppSettings` — add the new settings keys.

Pattern to copy: `LlmAgent.generateReply(...)` is already invoked on a **background thread** inside `handleChat` with a UI-thread `handler.post(...)` for the reply. Server calls follow the exact same shape.

---

## 1. New components (app side)

```
com.iris.assistant
├── ServerConfig.java         // thin typed wrapper over AppSettings server keys
├── ServerClient.java         // HTTP client: health(), chat(), transcribe() (opt)
├── ConnectivityMonitor.java  // network state + latency + circuit breaker → shouldUseServer()
└── (edits) AppSettings, IrisListeningService, view_settings.xml, MainActivity, ThemeManager(n/a)
```

### 1a. AppSettings — new keys
```java
public boolean serverModeEnabled()      { return prefs.getBoolean("server_mode", false); }
public void setServerModeEnabled(boolean v){ prefs.edit().putBoolean("server_mode", v).apply(); }
public String serverUrl()                { return prefs.getString("server_url", ""); }        // https://host[/base]
public void setServerUrl(String v)       { prefs.edit().putString("server_url", v==null?"":v.trim()).apply(); }
public String serverToken()              { return prefs.getString("server_token", ""); }
public void setServerToken(String v)     { prefs.edit().putString("server_token", v==null?"":v.trim()).apply(); }
public boolean autoOfflineWhenSlow()     { return prefs.getBoolean("server_auto_offline_slow", true); }
public void setAutoOfflineWhenSlow(boolean v){ prefs.edit().putBoolean("server_auto_offline_slow", v).apply(); }
public int serverSlowMs()                { return prefs.getInt("server_slow_ms", 4000); }
```

### 1b. ServerClient (no new deps — HttpURLConnection + org.json)
```java
final class ServerClient {
    private final String baseUrl, token;
    ServerClient(String baseUrl, String token) { this.baseUrl = trimSlash(baseUrl); this.token = token; }

    boolean health(int timeoutMs) { /* GET /health, expect {"ok":true}; return false on any error */ }

    /** Blocking; call on a background thread. Returns reply text, or null on failure (→ caller falls back). */
    String chat(String message, org.json.JSONArray context, org.json.JSONObject profile,
                int connectMs, int readMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/chat").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(connectMs);         // ~2500
            c.setReadTimeout(readMs);               // ~6000
            c.setRequestProperty("Content-Type", "application/json");
            if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
            c.setDoOutput(true);
            org.json.JSONObject body = new org.json.JSONObject()
                .put("message", message).put("context", context).put("profile", profile);
            try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(UTF_8)); }
            if (c.getResponseCode() / 100 != 2) return null;
            String json = readAll(c.getInputStream());
            org.json.JSONObject o = new org.json.JSONObject(json);
            return o.optString("reply", null);      // optional: o.optJSONObject("action")
        } catch (Throwable t) { return null; }       // timeout/DNS/5xx → null → offline fallback
        finally { if (c != null) c.disconnect(); }
    }
}
```
Notes: never throw to the caller — return `null` so the router falls back. All PII limited to `message` + `profile` (only sent when server mode is ON).

### 1c. ConnectivityMonitor — the decision brain
State (all in the service or a singleton):
```java
boolean online;                 // updated by NetworkCallback
long lastFailureAt; int consecutiveFailures;      // circuit breaker
long circuitOpenUntil;          // cooldown timestamp
long[] latencySamples; ...      // rolling avg for "slow" detection
```
```java
boolean shouldUseServer(AppSettings s) {
    if (!s.serverModeEnabled() || s.serverUrl().isEmpty()) return false;
    if (!online) return false;                          // ConnectivityManager: no validated internet
    if (System.currentTimeMillis() < circuitOpenUntil) return false;   // breaker open
    if (s.autoOfflineWhenSlow() && rollingLatencyMs() > s.serverSlowMs()) return false;
    return true;
}
void recordSuccess(long ms) { consecutiveFailures = 0; pushLatency(ms); }
void recordFailure() {
    if (++consecutiveFailures >= 3) circuitOpenUntil = System.currentTimeMillis() + 60_000; // 60s cooldown
}
```
Register once in `onCreate`:
```java
ConnectivityManager cm = getSystemService(ConnectivityManager.class);
cm.registerDefaultNetworkCallback(new NetworkCallback() {
    public void onAvailable(Network n){ monitor.online = true; }
    public void onLost(Network n){ monitor.online = false; }
    public void onCapabilitiesChanged(Network n, NetworkCapabilities c){
        monitor.online = c.hasCapability(NET_CAPABILITY_VALIDATED);
    }
});
```

---

## 2. Routing change in `handleChat`

Insert the server attempt **before** the LLM/rule-based path; on any miss, fall through unchanged.

```java
private void handleChat(String clean, String normalized, ProfileStore store) {
    // identity short-circuits stay as-is (who am i / who are you) ...

    if (monitor.shouldUseServer(settings)) {
        broadcastMessage("Thinking…");
        final ServerClient client = new ServerClient(settings.serverUrl(), settings.serverToken());
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            String reply = client.chat(clean, conversation.transcriptJson(),
                                       PersonalProfile.profileJson(this), 2500, 6000);
            long dt = System.currentTimeMillis() - t0;
            handler.post(() -> {
                if (reply != null && !reply.isEmpty()) {
                    monitor.recordSuccess(dt);
                    conversation.add(clean, reply);
                    handleLlmOutput(reply, store);   // reuse: parses optional [CALL:]/[SMS:] tags, else speaks
                } else {
                    monitor.recordFailure();
                    LogStore.append(this, "SERVER", "miss → offline fallback");
                    fallbackChat(clean, normalized, store);   // = existing LLM-or-ruleBased path
                }
            });
        }, "IRIS-Server").start();
        return;
    }
    fallbackChat(clean, normalized, store);
}
```
`fallbackChat` = the current body of `handleChat` (LLM if ready else `ruleBasedChat`). Extract it once so both paths share it. Reusing `handleLlmOutput` means the server can also return `[CALL: mom]` / `[SMS: ...]` tags that execute **on the phone**.

Add helpers: `ConversationManager.transcriptJson()` (array of `{role,text}`) and `PersonalProfile.profileJson(ctx)` (compact profile object; reuse `contextForAI` content).

---

## 3. Voice commands (manual override)

Add patterns handled in `handleCommandInner` (early, before chat):
- `"go online" | "use the server" | "server mode on"` → `settings.setServerModeEnabled(true)` + confirm ("Online brain on.").
- `"go offline" | "offline mode" | "server mode off"` → `settings.setServerModeEnabled(false)` + confirm.
Tone-aware confirmation via existing `personalityLine`/`pick`.

---

## 4. Settings UI (view_settings.xml + MainActivity.showSettings)

New "Server mode" card:
- `Switch serverModeSwitch` (+ description: "Off by default. When on and connected, IRIS uses your private server for smarter replies and automatically falls back offline when there's no internet or it's slow.").
- `EditText serverUrlInput` (hint `https://your-server`), `EditText serverTokenInput` (bearer), `Button saveServerButton`.
- `Button testServerButton` → runs `ServerClient.health()` on a thread → toast "Connected ✅ (model …)" or "Couldn't reach server".
- Wire like the existing `aiEnabledSwitch` / `hfTokenInput` block.

---

## 5. Server reference implementation (free host)

Minimal FastAPI wrapper around **Ollama** (adds auth + IRIS system prompt). Runs on Oracle Always-Free ARM or a home PC.

```python
# app.py  (pip install fastapi uvicorn requests)
import os, requests
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

TOKEN = os.environ["IRIS_TOKEN"]
OLLAMA = "http://127.0.0.1:11434/api/chat"
MODEL  = os.environ.get("IRIS_MODEL", "llama3.2:3b")
app = FastAPI()

class ChatIn(BaseModel):
    message: str
    context: list = []
    profile: dict = {}

@app.get("/health")
def health(): return {"ok": True, "model": MODEL}

@app.post("/chat")
def chat(inp: ChatIn, authorization: str = Header("")):
    if authorization != f"Bearer {TOKEN}": raise HTTPException(401)
    sys = "You are IRIS, a concise personal voice assistant. " \
          "Address the user by their preferred name. Keep replies short and speakable. " \
          "To trigger a phone action, emit a tag like [CALL: name] or [SMS: name | text]. " \
          f"Profile: {inp.profile}"
    msgs = [{"role":"system","content":sys}]
    for t in inp.context[-8:]:
        msgs.append({"role": t.get("role","user"), "content": t.get("text","")})
    msgs.append({"role":"user","content": inp.message})
    r = requests.post(OLLAMA, json={"model": MODEL, "messages": msgs, "stream": False}, timeout=30)
    return {"reply": r.json()["message"]["content"]}
```
Serve + HTTPS:
- `ollama serve` + `ollama pull llama3.2:3b`
- `IRIS_TOKEN=... uvicorn app:app --host 127.0.0.1 --port 8000`
- **Caddy** in front for auto-HTTPS: `yourhost { reverse_proxy 127.0.0.1:8000 }` — or **Tailscale** (then the app uses the tailnet IP, no public exposure), or **Cloudflare Tunnel**.
- `systemd` units so it restarts on boot.

---

## 6. Threading & lifecycle rules

- All network on a **background thread**; UI/speech via `handler.post`.
- Respect the command flow: a server turn still ends with `handleLlmOutput`/`ruleBasedChat` → `speakThenRun(reply, this::rearmAfterAction)` so IRIS returns to listening.
- If a server turn is in flight and the user says "stop", cancel by ignoring the late result (guard with a per-turn token, like the `handled[]` flag in `startCommandRecognition`).
- Unregister the `NetworkCallback` in `onDestroy`.

---

## 7. Security & privacy checklist

- [ ] Nothing sent unless `serverModeEnabled` is true (enforced in `shouldUseServer`).
- [ ] HTTPS required; reject `http://` unless it's a tailnet/localhost address.
- [ ] Bearer token stored via existing encrypted settings; never written to `LogStore`.
- [ ] Server is stateless (`/chat` doesn't persist content).
- [ ] Only `message` + compact `profile` + short `context` leave the device — never contacts DB, memory store, or audio (unless `/transcribe` is explicitly enabled).
- [ ] Redact tokens/PII from any debug logging.

---

## 8. Test plan (must pass before shipping the toggle ON by default = never; stays opt-in)

| Scenario | Expected |
|---|---|
| Server off | Identical to today (offline). |
| Server on, healthy | Reply from server; latency logged. |
| Server on, airplane mode | Instant offline fallback; no hang. |
| Server on, wrong URL/token | `null` → offline fallback; breaker trips after 3. |
| Slow link (>4s) + autoOfflineWhenSlow | Switches to offline, announces it. |
| Mid-turn network drop | Falls back for that turn; next turn re-probes. |
| "go offline" / "go online" | Toggles + confirms by voice. |
| Server returns `[CALL: mom]` | Phone confirms + calls locally. |

Manual harness: point `serverUrl` at a laptop running the FastAPI+Ollama sample over Tailscale.

---

## 9. Phased rollout

- **Phase A (core, ~1 build):** AppSettings keys + `ServerClient.chat/health` + `ConnectivityMonitor` + routing in `handleChat` + Settings card + Test button. Ship behind the off-by-default toggle.
- **Phase B:** voice "go online/offline", circuit breaker + slow-latency auto-offline, `[CALL:]/[SMS:]` action tags from server via `handleLlmOutput`.
- **Phase C (optional):** `/transcribe` (Whisper) for hardest accents, `/tts` (Piper) for a nicer online voice, streaming replies.
- **Phase D (docs/ops):** one-command server setup script (Ollama + FastAPI + Caddy/Tailscale) checked into `server/`.

**Effort:** Phase A is the bulk (~one focused build); B/C are incremental. No new Gradle dependencies; APK size unchanged.

---

## 10. Open decisions (need your call)

1. **Which host** to target first — Oracle Always-Free ARM, or your home PC via Tailscale? (Affects the setup script in Phase D.)
2. **Which model** — `llama3.2:3b` (fast) vs `qwen2.5:7b-instruct` (smarter, needs the 24 GB ARM box)?
3. **Send audio to the server** for STT (best accent accuracy) or keep STT fully on-device (more private, lower bandwidth)? Default recommendation: **on-device STT**, server for understanding only.
4. Auto-offline **slow threshold** (default 4 s) and breaker cooldown (default 60 s) — tune to taste.

Once you pick 1–3, I can implement **Phase A** end-to-end.

---

## 11. FINALIZED DECISIONS (locked)

1. **Host:** Oracle Cloud **Always Free** (Arm Ampere A1, up to 4 cores / 24 GB RAM).
2. **LLM:** `qwen2.5:3b-instruct` (q4) — best speed/quality balance for CPU-only ARM (~3–5s replies). `llama3.2:3b` is an alternative; avoid 7B (too slow for voice on CPU).
3. **STT:** **send audio to the server's Whisper** — `faster-whisper small` (int8) for the best accent accuracy at ~1–3s.
4. **Thresholds:** slow cutoff **8000 ms** (STT+LLM per turn), circuit breaker **3 failures → 60 s** cooldown. (Baked into `AppSettings.serverSlowMs()=8000` and `ConnectivityMonitor`.)

## 12. STATUS — Phase A shipped (app side, v7.23.0)

Implemented in the app, gated behind the off-by-default **Server mode** toggle:
- `ServerClient` (`health`, `chat`, `transcribe` with on-device WAV encoding) — `HttpURLConnection` + `org.json`, no new deps.
- `ConnectivityMonitor` (validated-internet callback + circuit breaker + slow-latency guard) → `shouldUseServer()`.
- `AppSettings`: `serverModeEnabled`, `serverUrl`, `serverToken`, `serverStt`, `autoOfflineWhenSlow`, `serverSlowMs(=8000)`.
- Routing: `handleChat` tries the server first, falls back to `offlineChat` (LLM→rule-based) on any miss; `startCommandRecognition` dispatches to `startServerSttCommand` (record 6 s → `/transcribe`) when server STT is on, else on-device Vosk.
- Voice: "go online" / "go offline". Settings card: URL, token, Whisper toggle, Save, Test connection.
- Server replies may include `[CALL: …]` / `[SMS: …]` tags → executed on the phone via `handleLlmOutput`.

**Remaining (Phase B/C/D):** streaming replies, `/tts` (Piper), one-command server installer in `server/`.

## 13. Oracle server setup (copy-paste)

On an Ubuntu 22.04 **Arm** VM (Always Free A1). Open port 443 in the VM's security list + `ufw`.

```bash
# 1) Ollama + the model
curl -fsSL https://ollama.com/install.sh | sh
ollama pull qwen2.5:3b-instruct

# 2) Whisper (faster-whisper) + API deps
sudo apt update && sudo apt install -y python3-pip ffmpeg
pip3 install fastapi uvicorn requests faster-whisper

# 3) The IRIS API (chat + transcribe)  — save as ~/iris/app.py (see below)
export IRIS_TOKEN="$(openssl rand -hex 24)"; echo "TOKEN=$IRIS_TOKEN"
export IRIS_MODEL="qwen2.5:3b-instruct"
uvicorn app:app --host 127.0.0.1 --port 8000
```

`~/iris/app.py`:
```python
import os, tempfile, requests
from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel
from faster_whisper import WhisperModel

TOKEN = os.environ["IRIS_TOKEN"]
MODEL = os.environ.get("IRIS_MODEL", "qwen2.5:3b-instruct")
OLLAMA = "http://127.0.0.1:11434/api/chat"
asr = WhisperModel("small", device="cpu", compute_type="int8")   # accent-friendly, fast enough
app = FastAPI()

def auth(h): 
    if h != f"Bearer {TOKEN}": raise HTTPException(401)

class ChatIn(BaseModel):
    message: str; context: list = []; profile: dict = {}

@app.get("/health")
def health(): return {"ok": True, "model": MODEL}

@app.post("/chat")
def chat(inp: ChatIn, authorization: str = Header("")):
    auth(authorization)
    sys = ("You are IRIS, a concise personal voice assistant. Address the user by their preferred name. "
           "Keep replies short and speakable. To trigger a phone action emit a tag like [CALL: name] "
           f"or [SMS: name | text]. Profile: {inp.profile}")
    msgs = [{"role":"system","content":sys}]
    for t in inp.context[-8:]:
        msgs.append({"role": t.get("role","user"), "content": t.get("text","")})
    msgs.append({"role":"user","content": inp.message})
    r = requests.post(OLLAMA, json={"model": MODEL, "messages": msgs, "stream": False}, timeout=60)
    return {"reply": r.json()["message"]["content"]}

@app.post("/transcribe")
async def transcribe(request: Request, authorization: str = Header("")):
    auth(authorization)
    data = await request.body()                       # raw WAV bytes from the phone
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=True) as f:
        f.write(data); f.flush()
        segs, _ = asr.transcribe(f.name, language="en", beam_size=1)
        return {"text": " ".join(s.text for s in segs).strip()}
```

HTTPS + always-on:
- **Tailscale (simplest, private):** `curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up` → in IRIS set Server URL to `http://<tailscale-ip>:8000` (tailnet traffic is already encrypted).
- **Public HTTPS:** install **Caddy** → `Caddyfile`:
  ```
  your.domain.com { reverse_proxy 127.0.0.1:8000 }
  ```
  then set Server URL to `https://your.domain.com`.
- **systemd** so it survives reboots: create units for `ollama` (already installed as a service) and a `iris-api.service` running the uvicorn command with the env vars.

In the IRIS app: **Settings → Server mode → on**, paste the URL + the printed token, keep **"Send audio to server (Whisper)"** on, tap **Test connection** (expect "Connected ✅"). Say "go offline" / "go online" any time; it auto-falls back when the VM is unreachable or slow.
