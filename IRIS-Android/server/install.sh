#!/usr/bin/env bash
# IRIS Server one-command setup for Ubuntu (Oracle Always-Free ARM, or any Linux box).
# Installs Ollama + model, Python deps, and a systemd service. Prints your bearer token.
#
#   curl -fsSL <raw-url>/install.sh | bash          # or: bash install.sh
#
# Env overrides:  IRIS_MODEL (default qwen2.5:3b-instruct)  IRIS_WHISPER (default small)
set -euo pipefail

IRIS_DIR="${IRIS_DIR:-$HOME/iris}"
IRIS_MODEL="${IRIS_MODEL:-qwen2.5:3b-instruct}"
IRIS_WHISPER="${IRIS_WHISPER:-small}"
PORT="${PORT:-8000}"

echo "==> Installing system packages (python, ffmpeg)…"
sudo apt-get update -y
sudo apt-get install -y python3-pip python3-venv ffmpeg curl

echo "==> Installing Ollama…"
if ! command -v ollama >/dev/null 2>&1; then
  curl -fsSL https://ollama.com/install.sh | sh
fi
echo "==> Pulling model: $IRIS_MODEL (this can take a while)…"
ollama pull "$IRIS_MODEL"

echo "==> Setting up the API in $IRIS_DIR…"
mkdir -p "$IRIS_DIR"
# app.py and requirements.txt should sit next to this script; copy them in.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
cp -f "$SCRIPT_DIR/app.py" "$IRIS_DIR/app.py" 2>/dev/null || true
cp -f "$SCRIPT_DIR/requirements.txt" "$IRIS_DIR/requirements.txt" 2>/dev/null || true

python3 -m venv "$IRIS_DIR/venv"
"$IRIS_DIR/venv/bin/pip" install --upgrade pip
"$IRIS_DIR/venv/bin/pip" install -r "$IRIS_DIR/requirements.txt"

# Generate a token once and persist it.
TOKEN_FILE="$IRIS_DIR/token"
if [ ! -f "$TOKEN_FILE" ]; then openssl rand -hex 24 > "$TOKEN_FILE"; fi
IRIS_TOKEN="$(cat "$TOKEN_FILE")"

echo "==> Installing systemd service…"
sudo tee /etc/systemd/system/iris-api.service >/dev/null <<UNIT
[Unit]
Description=IRIS Server (chat + transcribe)
After=network-online.target ollama.service
Wants=network-online.target

[Service]
User=$USER
WorkingDirectory=$IRIS_DIR
Environment=IRIS_TOKEN=$IRIS_TOKEN
Environment=IRIS_MODEL=$IRIS_MODEL
Environment=IRIS_WHISPER=$IRIS_WHISPER
ExecStart=$IRIS_DIR/venv/bin/uvicorn app:app --host 0.0.0.0 --port $PORT
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now iris-api
sudo systemctl --no-pager status iris-api | head -n 5 || true

echo
echo "======================================================================"
echo " IRIS server is up on port $PORT."
echo " TOKEN:  $IRIS_TOKEN"
echo
echo " Next: expose it securely, then set it in the app (Settings → Server mode):"
echo "   • Tailscale (private): sudo tailscale up ; URL = http://<tailscale-ip>:$PORT"
echo "   • Public HTTPS: put Caddy in front (see Caddyfile) ; URL = https://your.domain"
echo "======================================================================"
