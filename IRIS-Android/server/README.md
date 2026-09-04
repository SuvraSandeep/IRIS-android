# IRIS Server (Phase D)

Runnable package for **Server Mode**. It gives IRIS a smarter online brain (Ollama LLM) and best-accent transcription (faster-whisper), while the phone stays **offline-first** and auto-falls back when this server is down or slow.

> Nothing leaves your phone unless you turn on **Settings → Server mode** in the app.

## Files
- `app.py` — FastAPI: `/health`, `/chat` (Ollama, with the IRIS action-tag system prompt), `/transcribe` (faster-whisper).
- `requirements.txt` — Python deps.
- `install.sh` — one-command setup on Ubuntu (Ollama + model + venv + **systemd** service). Prints your token.
- `iris-api.service` — systemd unit template (install.sh writes this for you).
- `Caddyfile` — optional public HTTPS front.

## Quick start (Oracle Cloud Always-Free ARM, recommended)
1. Create an **Ubuntu 22.04 Arm (Ampere A1)** VM (up to 4 cores / 24 GB free forever). Open the API port in the VM's security list.
2. Copy this `server/` folder to the VM (or `git clone` the repo), then:
   ```bash
   cd server
   bash install.sh
   ```
   It installs everything, starts the service on boot, and prints your **TOKEN**.
3. **Expose it** (pick one):
   - **Tailscale (simplest, fully private):**
     ```bash
     curl -fsSL https://tailscale.com/install.sh | sh && sudo tailscale up
     ```
     App URL → `http://<tailscale-ip>:8000`
   - **Public HTTPS (Caddy):** point a domain at the VM, edit `Caddyfile`, then
     ```bash
     sudo apt install -y caddy && sudo cp Caddyfile /etc/caddy/Caddyfile && sudo systemctl reload caddy
     ```
     App URL → `https://your.domain.com`
4. In IRIS: **Settings → Server mode → on**, paste the **URL** + **TOKEN**, keep **"Send audio to server (Whisper)"** on, tap **Test connection** → expect **Connected ✅**.

## Home-PC alternative
Same `bash install.sh` on any Linux box (or WSL), then use **Tailscale** so the phone reaches it anywhere. When the PC is off, IRIS just runs offline — no action needed.

## Choosing models
- LLM (`IRIS_MODEL`): `qwen2.5:3b-instruct` (default, ~3–5s on ARM CPU). Alternatives: `llama3.2:3b`, or `qwen2.5:7b-instruct` if you accept slower replies on the 24 GB box.
- Whisper (`IRIS_WHISPER`): `small` (default, best balance) · `base` (faster) · `medium` (more accurate, slower).
  ```bash
  sudo systemctl set-environment IRIS_MODEL=llama3.2:3b IRIS_WHISPER=base   # example
  sudo systemctl restart iris-api
  ```

## Verify manually
```bash
TOKEN=$(cat ~/iris/token)
curl -s localhost:8000/health
curl -s -X POST localhost:8000/chat -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"message":"remind me to call mom at 6 pm","profile":{"preferred_name":"Mr STG"}}'
```
A reply like `[REMINDER: at 6 pm | call mom]` means the phone will execute it — the tags match `IrisListeningService.handleLlmOutput`.

## Security
- Bearer token on every request (over HTTPS or the encrypted Tailscale tunnel).
- `/chat` is stateless (no content stored). Only `message` + compact `profile` + short `context` are sent.
- Prefer Tailscale for zero public exposure.

## Logs / control
```bash
sudo systemctl status iris-api
journalctl -u iris-api -f
sudo systemctl restart iris-api
```
