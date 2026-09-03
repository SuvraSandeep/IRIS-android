# IRIS Server Mode — Free Hosting + Hybrid Online/Offline Design

**Status:** design + roadmap (offline-first stays the default; server mode is opt-in).
**Guiding rules:** free only, privacy-first, and IRIS must keep working with **no internet**.

---

## 1. Why a server at all?

Everything IRIS does today runs **on-device** (Vosk speech-to-text, rule-based NLP, Android TTS, optional small LLM). That is private and free but limited by the phone's CPU/RAM — which is exactly why the on-device AI brain crashes and why accent handling is capped.

A small personal server (that only *you* use) can run models the phone can't:

| Capability | On-device today | With a free server |
|---|---|---|
| Conversational NLP | tiny 0.5B LLM (unstable) | 3B–8B LLM (stable, much smarter) via Ollama |
| Speech-to-text | Vosk small/large en-IN | Whisper (large) — best accent handling |
| Text-to-speech | Android TTS | Piper / Coqui (natural voices) |
| Web / tools | limited | live web search, summarize, etc. |

The phone stays the **client**; the server is a **capability booster** used *only when online and healthy*.

---

## 2. Design principles

1. **Offline-first.** On-device Vosk + rule-based engine is always the baseline and never removed.
2. **Opt-in.** Server mode is a Settings toggle; off by default. Nothing leaves the phone unless enabled.
3. **Automatic fallback.** No internet, slow link, timeout, or server error → instantly use on-device. No dead-ends.
4. **Manual override.** User can force offline any time ("go offline"), and force online when they want power.
5. **Private.** Single-user server, HTTPS + bearer token, no logging of message content server-side.

---

## 3. Free hosting options (ranked for this use case)

### Recommended: Oracle Cloud — Always Free (not a trial)
- **What you get free, forever:** up to **4 Arm Ampere (A1) cores + 24 GB RAM** (or 2 small AMD VMs). By far the most generous always-free tier.
- **Runs comfortably:** Ollama with a 3B–8B quantized model (e.g. `llama3.2:3b`, `qwen2.5:7b-instruct-q4`), plus Whisper-small for STT.
- **Setup:** create an Ubuntu ARM VM → install Ollama (`curl -fsSL https://ollama.com/install.sh | sh`) → `ollama pull llama3.2:3b` → put Caddy in front (auto-HTTPS) with a bearer token.
- **Caveat:** ARM capacity in the free tier is sometimes "out of capacity" in popular regions — retry or pick another region.

### Home PC / old laptop + free tunnel (most powerful, zero cloud limits)
- Run **Ollama** (and optionally `whisper.cpp`, Piper) on a PC you already own.
- Expose it privately with a **free tunnel** so the phone can reach it anywhere:
  - **Tailscale** (free private mesh VPN — best for a personal device; no public exposure at all).
  - **Cloudflare Tunnel** (free, HTTPS hostname, no port-forwarding).
  - **ngrok** (free tier, random URL, fine for testing).
- **Pros:** use your full GPU/CPU, unlimited, totally free. **Cons:** PC must be on for online mode (IRIS simply falls back to offline when it's off — which is the whole point of the hybrid design).

### Hugging Face Spaces (free CPU tier)
- Host a small **FastAPI/Gradio** app (quantized LLM or a Whisper endpoint).
- **Pros:** truly free, public HTTPS URL, easy. **Cons:** CPU-only free tier is slow for big models; Spaces sleep when idle (cold starts). Good for light chat/summarize, not heavy work.

### Other viable free tiers
- **Google Cloud** `e2-micro` (Always Free, 1 vCPU/1 GB — too small for LLMs, ok for a proxy/tools API).
- **AWS** `t2.micro`/`t3.micro` (free for 12 months only).
- **Fly.io** (small free allowance; can sleep).
- **Render** free web services (sleep after inactivity → cold starts).
- **Google Colab** — *not* for serving (sessions are temporary); experiments only.

**Bottom line:** For real power use **Oracle Always Free (ARM, 24 GB) + Ollama**, or **home PC + Tailscale**. Use **HF Spaces** if you want the simplest always-free public endpoint and can accept slower responses.

---

## 4. Architecture

```
Phone (IRIS)
  Mic -> Vosk (on-device STT)
                |
                v
  Router: online & enabled & healthy?
     |  yes -> ServerClient (HTTPS) --------> Server
     |                                          - Ollama /chat (LLM)
     |                                          - /transcribe (Whisper, optional)
     |                                          - /health
     |                                          - /tts (Piper, optional)
     |  no / slow / error
     v
  On-device engine (rule-based NLP + memory + actions)
                |
                v
  Android TTS  <- reply text
```

- Speech-to-text can stay **on-device** (fast, private) even in server mode; only the **understanding/reply** goes to the server. (Optionally also send audio to Whisper for the best accent accuracy.)
- Device **actions** (call, SMS, alarm…) always execute **on the phone** — the server only returns intent/text.

---

## 5. Hybrid online/offline: the fallback logic

**Inputs:** `serverModeEnabled`, `serverUrl`, `serverToken`, live connectivity, and a rolling latency estimate.

**Decision per request:**
```
if (!serverModeEnabled)      -> OFFLINE
else if (!hasInternet())     -> OFFLINE   // ConnectivityManager: no validated network
else if (circuitOpen())      -> OFFLINE   // too many recent failures
else:
    try server call with tight timeouts (connect ~2.5s, read ~6s)
    on success            -> use server reply; record latency
    on timeout/error/5xx  -> OFFLINE for this turn; trip circuit breaker
```

**"Go offline when the internet is slow":**
- Track a rolling average round-trip time. If it exceeds a threshold (e.g. **> 4 s** for N consecutive calls), auto-switch to offline and announce "Switched to offline — connection is slow." User can force online again.

**Circuit breaker:** after 3 consecutive failures, stay offline for a cooldown (~60 s), then probe `/health` once before resuming. Prevents hammering a down/slow server.

**Connectivity monitoring:** register a `ConnectivityManager.NetworkCallback` to flip state instantly when Wi-Fi/data drops → seamless fallback mid-session.

**Voice cues (tone-aware):** "Online brain connected." / "No connection — I'll handle it offline." (respect the Silent personality).

---

## 6. API contract (server side)

Minimal, so any host above can implement it:

- `GET /health` -> `{ "ok": true, "model": "llama3.2:3b" }` (used for probes).
- `POST /chat` -> `{ "message": "...", "context": [...], "profile": {...} }` -> `{ "reply": "...", "action": {optional intent} }`.
- `POST /transcribe` (optional) -> audio (16k wav) -> `{ "text": "..." }` (Whisper).
- `POST /tts` (optional) -> `{ "text": "..." }` -> audio (Piper).

Auth: `Authorization: Bearer <token>` on every call. HTTPS only.

**Ollama shortcut:** Ollama already exposes `POST /api/chat`. A ~30-line Caddy/FastAPI wrapper can add the bearer token, HTTPS, and IRIS profile/system-prompt injection.

---

## 7. Security & privacy

- **Nothing leaves the phone unless server mode is ON** — state this in the toggle's description.
- HTTPS everywhere (Caddy/Cloudflare give free certs; Tailscale is encrypted by default).
- Single **bearer token** stored in encrypted settings; never logged.
- Server should **not persist** message content (stateless `/chat`).
- Prefer **Tailscale** (no public exposure at all) for the home-PC option.
- Treat the server as untrusted transport for PII: send only what's needed for the reply.

---

## 8. Bigger on-device model (the offline half of "more capability")

Already shipped as an **opt-in** in Settings -> **"High-accuracy voice model (large, ~1GB)"**:
- Downloads `vosk-model-en-in-0.5` (full en-IN model) on first use for markedly better accent recognition.
- **Automatic fallback** to the bundled small model if the download/load fails or RAM is short.
- Improves offline accuracy without any server.

Future offline upgrades to consider: `whisper.cpp` tiny/base int8 on-device (better STT than Vosk but heavier), and Piper TTS for a nicer offline voice.

---

## 9. In-app implementation plan (future work)

1. **Settings:** `serverModeEnabled`, `serverUrl`, `serverToken`, `autoOfflineWhenSlow` (+ a UI card).
2. **`ServerClient`**: `health()`, `chat(message, context, profile)`, tight timeouts, bearer auth (use `HttpURLConnection` — no new dependencies).
3. **`ConnectivityMonitor`**: wraps `ConnectivityManager` + rolling latency + circuit breaker; exposes `shouldUseServer()`.
4. **Router in `handleChat`/`handleCommandInner`:** if `shouldUseServer()` -> `ServerClient.chat(...)`, else existing on-device path; wrap in try/finally so any failure falls back for that turn.
5. **Voice commands:** "go online" / "use the server", "go offline" / "offline mode".
6. **Actions stay local:** server returns text or an `action` intent; the phone executes calls/SMS/etc.

This keeps the offline experience identical and layers server power on top only when it's actually available and fast.

---

### TL;DR
- **Best free power:** Oracle Cloud Always-Free ARM (24 GB) + **Ollama**, or **home PC + Tailscale + Ollama**.
- **Simplest free:** Hugging Face Spaces (slower, sleeps).
- **App design:** offline-first, opt-in server mode, automatic fallback on no-internet/slow/error, manual override, HTTPS + token, actions always run on the phone.
- **Offline boost already available:** the large Vosk model toggle with auto-fallback.
