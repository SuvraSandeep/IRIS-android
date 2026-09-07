# IRIS Server on Google Colab (100% free, no card).
# Paste this whole file into ONE cell of a new Colab notebook and run it.
# It installs Ollama + a small model + Whisper, starts the IRIS API, and prints a
# public URL + TOKEN to paste into the app (Settings -> Server mode).
# Optional: Runtime -> Change runtime type -> GPU (T4) makes it much faster and lets you
# bump MODEL to "qwen2.5:3b-instruct".

import os, subprocess, time, secrets, re, urllib.request

TOKEN = secrets.token_hex(16)
MODEL = "qwen2.5:1.5b-instruct"   # CPU-friendly; use 3b if you selected a GPU runtime
WSIZE = "small"                    # faster-whisper size: tiny|base|small|medium

def sh(c):  return subprocess.run(c, shell=True)
def bg(c, env=None): return subprocess.Popen(c, shell=True, env=env,
                                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)

print("Installing Ollama…")
sh("curl -fsSL https://ollama.com/install.sh | sh >/dev/null 2>&1")
bg("ollama serve")
for _ in range(40):
    try: urllib.request.urlopen("http://127.0.0.1:11434/api/tags", timeout=2); break
    except Exception: time.sleep(2)
print(f"Pulling {MODEL} (a few minutes the first time)…")
sh(f"ollama pull {MODEL}")

print("Installing Python deps…")
sh("pip -q install fastapi uvicorn requests faster-whisper >/dev/null 2>&1")

APP = r'''
import os, tempfile, requests
from fastapi import FastAPI, Header, HTTPException, Request
from pydantic import BaseModel
TOKEN=os.environ["IRIS_TOKEN"]; MODEL=os.environ.get("IRIS_MODEL"); WSIZE=os.environ.get("IRIS_WHISPER","small")
OLLAMA="http://127.0.0.1:11434/api/chat"
_asr=None
def asr():
    global _asr
    if _asr is None:
        from faster_whisper import WhisperModel; _asr=WhisperModel(WSIZE, device="cpu", compute_type="int8")
    return _asr
app=FastAPI()
def auth(h):
    if h!=f"Bearer {TOKEN}": raise HTTPException(401)
SYS=("You are IRIS, a concise personal voice assistant. Address the user by their preferred name. "
     "Keep replies short and speakable. For a phone action reply with ONE tag: [CALL: name] | "
     "[SMS: name | message] | [WHATSAPP: name | message] | [ALARM: time] | [TIMER: dur] | "
     "[REMINDER: in|at t | task] | [TORCH: on|off] | [VOLUME: up|down|mute|max] | [SEARCH: q] | "
     "[NAVIGATE: place] | [WEATHER] | [TIME] | [BATTERY] | [LOCATION] | [NOTIFICATIONS] | [REMEMBER: fact]. "
     "Otherwise answer conversationally.")
class C(BaseModel):
    message:str; context:list=[]; profile:dict={}
@app.get("/health")
def h(): return {"ok":True,"model":MODEL}
@app.post("/chat")
def chat(inp:C, authorization:str=Header("")):
    auth(authorization)
    m=[{"role":"system","content":SYS+f" Profile: {inp.profile}"}]
    for t in inp.context[-8:]:
        m.append({"role":"assistant" if t.get("role")=="assistant" else "user","content":t.get("text","")})
    m.append({"role":"user","content":inp.message})
    r=requests.post(OLLAMA,json={"model":MODEL,"messages":m,"stream":False},timeout=120); r.raise_for_status()
    return {"reply":r.json()["message"]["content"].strip()}
@app.post("/transcribe")
async def tr(request:Request, authorization:str=Header("")):
    auth(authorization)
    data=await request.body()
    with tempfile.NamedTemporaryFile(suffix=".wav",delete=True) as f:
        f.write(data); f.flush()
        segs,_=asr().transcribe(f.name,language="en",beam_size=1)
        return {"text":" ".join(s.text for s in segs).strip()}
'''
open("app.py","w").write(APP)

print("Starting IRIS API…")
env=dict(os.environ, IRIS_TOKEN=TOKEN, IRIS_MODEL=MODEL, IRIS_WHISPER=WSIZE)
bg("uvicorn app:app --host 0.0.0.0 --port 8000", env=env)
for _ in range(40):
    try: urllib.request.urlopen("http://127.0.0.1:8000/health", timeout=2); break
    except Exception: time.sleep(2)

print("Starting public tunnel (cloudflared)…")
sh("wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O cloudflared && chmod +x cloudflared")
proc=bg("./cloudflared tunnel --url http://localhost:8000 --no-autoupdate")
url=None
for line in proc.stdout:
    m=re.search(r"https://[-a-z0-9]+\.trycloudflare\.com", line)
    if m: url=m.group(0); break

print("\n\n================ PASTE INTO IRIS (Settings -> Server mode) ================")
print("  URL  :", url)
print("  TOKEN:", TOKEN)
print("===========================================================================")
print("Keep this cell running. When you close Colab, the server stops (that's fine — the app falls back to offline).")
for line in proc.stdout:
    pass
