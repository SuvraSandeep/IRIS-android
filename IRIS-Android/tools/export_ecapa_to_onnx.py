#!/usr/bin/env python3
"""
One-time export script: SpeechBrain's pretrained ECAPA-TDNN speaker-embedding model
(speechbrain/spkrec-ecapa-voxceleb) -> ONNX, for on-device inference via ONNX Runtime Mobile.

WHY THIS SCRIPT EXISTS (see EcapaEmbedding.java's class doc and
WAKE-TRAINING-REDESIGN.md's "Model integration plan"):
SpeechBrain's official HuggingFace repo ships only PyTorch .ckpt checkpoints, not ONNX. This
environment (the AI agent's sandbox) has no Python/PyTorch available, so this export could not
be run automatically as part of the redesign implementation -- it must be run ONCE, manually,
on any machine with Python + PyTorch + SpeechBrain installed (a laptop, a CI runner, a Colab
notebook -- this project already has a server/colab/ directory for exactly this kind of
offline-preparation step).

USAGE:
    pip install speechbrain torch onnx
    python tools/export_ecapa_to_onnx.py --output ecapa_tdnn_voxceleb.onnx

After running this once, host the resulting .onnx file somewhere this app can download it from
(e.g. a GitHub release attached to this repo), and update EcapaEmbedding.MODEL_URL in
app/src/main/java/com/iris/assistant/EcapaEmbedding.java to point at that real URL, replacing
the current "REPLACE-ME" placeholder. Optionally also drop the file into
app/src/main/assets/ecapa_tdnn_voxceleb.onnx to bundle it directly in the APK (matching how the
Vosk models are bundled) -- see EcapaEmbedding.java's load() for the bundled-asset-first logic.

WHAT THIS PRODUCES:
A 192-dim speaker embedding model. Input: a 16kHz mono float32 waveform, shape (1, num_samples).
Output: a single (1, 192) embedding tensor. This matches EcapaEmbedding.java's exact
expectations (OUTPUT_DIM=192, input name "wav") -- do not change the input/output names or
shapes here without also updating EcapaEmbedding.java to match.
"""
import argparse
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="ecapa_tdnn_voxceleb.onnx",
                         help="Output .onnx file path")
    parser.add_argument("--sample-seconds", type=float, default=3.0,
                         help="Dummy input duration in seconds, used only to trace the model "
                              "for export -- the exported graph accepts variable-length input "
                              "at inference time regardless of this value")
    args = parser.parse_args()

    try:
        import torch
        from speechbrain.inference.speaker import EncoderClassifier
    except ImportError as e:
        print(f"Missing dependency: {e}", file=sys.stderr)
        print("Run: pip install speechbrain torch onnx", file=sys.stderr)
        sys.exit(1)

    print("Downloading/loading speechbrain/spkrec-ecapa-voxceleb (one-time, ~90MB)...")
    classifier = EncoderClassifier.from_hparams(
        source="speechbrain/spkrec-ecapa-voxceleb",
        savedir="pretrained_models/spkrec-ecapa-voxceleb",
    )
    model = classifier.mods.embedding_model
    model.eval()

    sample_rate = 16000
    num_samples = int(args.sample_seconds * sample_rate)
    dummy_wav = torch.randn(1, num_samples)

    # SpeechBrain's embedding_model expects (batch, time, features) after its own feature
    # extraction (mel-fbank) stage, not raw waveform directly -- wrap it so the EXPORTED graph's
    # public input is still raw 16kHz waveform (matching EcapaEmbedding.java's "wav" input),
    # with feature extraction fused into the exported graph itself.
    class RawWaveformEcapa(torch.nn.Module):
        def __init__(self, classifier):
            super().__init__()
            self.compute_features = classifier.mods.compute_features
            self.mean_var_norm = classifier.mods.mean_var_norm
            self.embedding_model = classifier.mods.embedding_model

        def forward(self, wav):
            feats = self.compute_features(wav)
            feats = self.mean_var_norm(feats, torch.ones(wav.shape[0]))
            embeddings = self.embedding_model(feats)
            # embedding_model outputs (batch, 1, 192) -- squeeze to (batch, 192) to match
            # EcapaEmbedding.java's expected float[][] shape.
            return embeddings.squeeze(1)

    export_model = RawWaveformEcapa(classifier)
    export_model.eval()

    print(f"Tracing and exporting to {args.output}...")
    torch.onnx.export(
        export_model,
        dummy_wav,
        args.output,
        input_names=["wav"],
        output_names=["embedding"],
        dynamic_axes={"wav": {0: "batch", 1: "time"}, "embedding": {0: "batch"}},
        opset_version=14,
    )
    print(f"Done. Wrote {args.output}")
    print()
    print("Next steps:")
    print("1. Verify with: python -c \"import onnx; onnx.checker.check_model(onnx.load('%s'))\"" % args.output)
    print("2. Host this file (e.g. a GitHub release) and update EcapaEmbedding.MODEL_URL,")
    print("   OR place it at app/src/main/assets/ecapa_tdnn_voxceleb.onnx to bundle in the APK.")


if __name__ == "__main__":
    main()
