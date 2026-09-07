# IRIS Server on Google Colab (free, no card)

Run the IRIS online brain for free to test Server Mode — no Oracle, no billing.

## Steps
1. Go to **https://colab.research.google.com** → sign in with a Google account → **New notebook**.
2. *(Optional, faster)* **Runtime → Change runtime type → GPU (T4)**. Then you can set `MODEL = "qwen2.5:3b-instruct"` in the cell.
3. Open **`iris_colab.py`**, copy **all** of it, paste into the single Colab cell.
4. **Runtime → Run all** (or press ▶). First run takes a few minutes (downloads Ollama + model + Whisper).
5. When it finishes it prints:
   ```
   URL  : https://xxxx-xxxx.trycloudflare.com
   TOKEN: <random hex>
   ```
6. In IRIS: **Settings → Server mode** → paste the **URL** and **TOKEN** → keep **"Send audio to server (Whisper)"** on → **Test connection** → expect **Connected ✅**.
7. Talk to IRIS. Say **"go offline"** anytime to force local; it also auto‑falls back if the tunnel drops.

## Notes / limits
- **Free & no card** — Colab needs no payment method.
- **Temporary:** the server lives only while the Colab tab/cell is running. When Colab disconnects (idle ~90 min, or you close it), the URL dies and IRIS automatically falls back to offline. Re‑run the cell to get a new URL/token.
- The `trycloudflare.com` URL **changes every run** — repaste it in the app each session.
- CPU runtime + `qwen2.5:1.5b` replies in a few seconds; a **GPU runtime** is much faster and handles `3b`.
- This is for **testing**. For an always‑on server, use the Oracle box (see `../README.md`) once free capacity lands.
