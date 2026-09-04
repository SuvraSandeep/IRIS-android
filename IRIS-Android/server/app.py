"""
IRIS Server — chat (Ollama) + transcribe (faster-whisper) with bearer auth.
Offline-first phone; this only runs when you enable Server Mode in the app.

Endpoints:
  GET  /health      -> {"ok": true, "model": ...}
  POST /chat        -> {"reply": "..."}   (reply may contain [ACTION] tags run on the phone)
  POST /transcribe  -> {"text": "..."}    (raw 16k mono WAV body)

Env:
  IRIS_TOKEN   (required)  shared bearer token
  IRIS_MODEL   (default qwen2.5:3b-instruct)
  IRIS_WHISPER (default small)   faster-whisper size: tiny|base|small|medium
"""
import os, tempfile, requests
from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel

TOKEN   = os.environ["IRIS_TOKEN"]
MODEL   = os.environ.get("IRIS_MODEL", "qwen2.5:3b-instruct")
WSIZE   = os.environ.get("IRIS_WHISPER", "small")
OLLAMA  = os.environ.get("IRIS_OLLAMA", "http://127.0.0.1:11434/api/chat")

# Lazy-load Whisper so /chat works even if faster-whisper isn't installed yet.
_asr = None
def asr():
    global _asr
    if _asr is None:
        from faster_whisper import WhisperModel
        _asr = WhisperModel(WSIZE, device="cpu", compute_type="int8")
    return _asr

app = FastAPI(title="IRIS Server")

def auth(h: str):
    if h != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="bad token")

# The action vocabulary must match IrisListeningService.handleLlmOutput on the phone.
SYSTEM = (
    "You are IRIS, a concise, private personal voice assistant. "
    "Address the user by their preferred name if given. Keep replies short and speakable (1-2 sentences). "
    "When the user wants a phone action, reply with ONE tag (and nothing else) so the phone can execute it:\n"
    "[CALL: name] · [SMS: name | message] · [WHATSAPP: name | message] · [EMAIL: recipient | subject] · "
    "[ALARM: time] · [TIMER: duration] · [REMINDER: in|at <time> | task] · [TORCH: on|off] · "
    "[VOLUME: up|down|mute|max] · [WIFI] · [BLUETOOTH] · [SEARCH: query] · [NAVIGATE: place] · "
    "[CALENDAR: title | when] · [TIME] · [BATTERY] · [WEATHER] · [LOCATION] · [NOTIFICATIONS] · "
    "[REDIAL] · [CALL_HISTORY] · [REMEMBER: fact] · [RECALL: topic]. "
    "Otherwise just answer conversationally. Never invent contact numbers; use the name and let the phone resolve it."
)

class ChatIn(BaseModel):
    message: str
    context: list = []
    profile: dict = {}

@app.get("/health")
def health():
    return {"ok": True, "model": MODEL, "whisper": WSIZE}

@app.post("/chat")
def chat(inp: ChatIn, authorization: str = Header("")):
    auth(authorization)
    msgs = [{"role": "system", "content": SYSTEM + f"\nProfile: {inp.profile}"}]
    for t in inp.context[-8:]:
        role = t.get("role", "user")
        msgs.append({"role": "assistant" if role == "assistant" else "user",
                     "content": t.get("text", "")})
    msgs.append({"role": "user", "content": inp.message})
    r = requests.post(OLLAMA, json={"model": MODEL, "messages": msgs, "stream": False}, timeout=90)
    r.raise_for_status()
    return {"reply": r.json()["message"]["content"].strip()}

@app.post("/transcribe")
async def transcribe(request: Request, authorization: str = Header("")):
    auth(authorization)
    data = await request.body()          # raw WAV bytes from the phone
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=True) as f:
        f.write(data); f.flush()
        segments, _ = asr().transcribe(f.name, language="en", beam_size=1)
        return {"text": " ".join(s.text for s in segments).strip()}
