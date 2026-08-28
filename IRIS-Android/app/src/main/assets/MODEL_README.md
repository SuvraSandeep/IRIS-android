# Speaker Verification Model

IRIS uses a TFLite speaker embedding model for voice identity verification.
The model file is NOT included in the source archive due to its size (~3-5 MB).

## How to add the model

Place a TFLite speaker embedding model at:
```
app/src/main/assets/speaker_model.tflite
```

## Compatible models

Any TFLite model that:
- Takes input: `[1, num_frames, 80]` (batch, time frames, 80 mel bands)
- Outputs: `[1, 192]` (batch, 192-dimensional embedding)

### Option 1: SpeechBrain ECAPA-TDNN (recommended)

```bash
pip install speechbrain torch torchaudio

python3 -c "
import torch
from speechbrain.pretrained import EncoderClassifier
import tensorflow as tf

# Load pretrained ECAPA-TDNN
model = EncoderClassifier.from_hparams(source='speechbrain/spkrec-ecapa-voxceleb')

# Export to ONNX then convert to TFLite
# (See SpeechBrain docs for full conversion pipeline)
"
```

### Option 2: Google's speech_embedding model

Download from TensorFlow Hub:
https://tfhub.dev/google/speech_embedding/1

### Option 3: Use without model

If no model file is present, IRIS works normally but without speaker
verification. The "Only respond to my voice" setting has no effect.
The SpeakerVerifier.loadModel() returns false and all verify() calls
return true (fail-open), so the app degrades gracefully.

## What happens without the model

- Wake phrase detection: works normally (DTW-based)
- Speaker verification: disabled (anyone's voice triggers wake)
- Training: records 5 samples but skips voiceprint enrollment
- Settings toggle: visible but has no effect
- No crashes, no errors — just reduced security
