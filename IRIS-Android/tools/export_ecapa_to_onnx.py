#!/usr/bin/env python3
"""Reproducible SpeechBrain ECAPA export with raw-waveform frontend and parity gates.
CPU build only. Output and SHA-256 manifest are bundled in the APK, never fetched from
an unconfigured runtime URL. Original SpeechBrain model is Apache-2.0.
"""
import argparse, hashlib, json
from pathlib import Path

def main():
    import numpy as np
    import torch
    import torch.nn.functional as F
    import onnx
    import onnxruntime as ort
    from huggingface_hub import snapshot_download
    from speechbrain.inference.speaker import EncoderClassifier
    torch.set_num_threads(2)
    args=argparse.ArgumentParser()
    args.add_argument('--output',required=True)
    dest=Path(args.parse_args().output);dest.parent.mkdir(parents=True,exist_ok=True)
    revision='a025b9e8262be969a7b3f8f53c01d346eadf361b'
    source=Path(snapshot_download('speechbrain/spkrec-ecapa-voxceleb',revision=revision))
    # Keep checkpoint paths pinned to the downloaded snapshot, including HyperPyYAML refs.
    local=Path('pretrained_models/ecapa-pinned');local.mkdir(parents=True,exist_ok=True)
    yaml=(source/'hyperparams.yaml').read_text().replace('pretrained_path: speechbrain/spkrec-ecapa-voxceleb','pretrained_path: '+str(source))
    (local/'hyperparams.yaml').write_text(yaml)
    classifier=EncoderClassifier.from_hparams(source=str(local),savedir=str(local/'loaded'),run_opts={'device':'cpu'})
    classifier.eval()
    stft=classifier.mods.compute_features.compute_STFT
    assert stft.n_fft==400 and stft.hop_length==160 and stft.win_length==400 and stft.pad_mode=="constant"
    assert classifier.mods.mean_var_norm.norm_type=='sentence' and not classifier.mods.mean_var_norm.std_norm
    class Export(torch.nn.Module):
        def __init__(self):
            super().__init__()
            # Equivalent real-valued DFT avoids torch.stft's unsupported complex ONNX export.
            n=torch.arange(400,dtype=torch.float64);k=torch.arange(201,dtype=torch.float64)[:,None]
            angle=2*torch.pi*k*n/400
            kernel=torch.cat([torch.cos(angle),-torch.sin(angle)],0)*stft.window.double()
            self.register_buffer('dft',kernel.float().unsqueeze(1))
            self.fb=classifier.mods.compute_features.compute_fbanks
            self.embedding=classifier.mods.embedding_model
        def forward(self,wav):
            spectrum=F.conv1d(F.pad(wav.unsqueeze(1),(200,200),mode='constant'),self.dft,stride=160)
            power=(spectrum[:,:201]**2+spectrum[:,201:]**2).transpose(1,2)
            features=self.fb(power)
            features=features-features.mean(dim=1,keepdim=True)
            return self.embedding(features).squeeze(1)
    exported=Export().eval()
    torch.manual_seed(17)
    dummy=torch.randn(1,48000)*.05
    with torch.no_grad():
        torch.onnx.export(exported,dummy,str(dest),input_names=['wav'],output_names=['embedding'],
                          dynamic_axes={'wav':{1:'time'}},opset_version=17)
    onnx.checker.check_model(onnx.load(str(dest)))
    options=ort.SessionOptions();options.intra_op_num_threads=2
    session=ort.InferenceSession(str(dest),sess_options=options,providers=['CPUExecutionProvider'])
    checks=[]
    for seconds in [.5,1,2,4]:
        wave=torch.randn(1,int(seconds*16000))*.05
        with torch.no_grad():reference=classifier.encode_batch(wave,normalize=False).squeeze(1).numpy()
        actual=session.run(None,{'wav':wave.numpy()})[0]
        assert actual.shape==(1,192) and np.isfinite(actual).all()
        cosine=float((actual*reference).sum()/(np.linalg.norm(actual)*np.linalg.norm(reference)))
        assert cosine>.9999,(seconds,cosine)
        checks.append({'seconds':seconds,'cosine_with_pytorch':cosine})
    manifest={'source':'speechbrain/spkrec-ecapa-voxceleb','revision':revision,'license':'Apache-2.0',
              'sha256':hashlib.sha256(dest.read_bytes()).hexdigest(),'input':'wav','sample_rate':16000,'dimension':192,
              'preprocessing':'speechbrain-fbank80-sentence-mean-raw-v1','parity':checks}
    dest.with_suffix('.json').write_text(json.dumps(manifest,indent=2))
    print(json.dumps(manifest,indent=2))
if __name__=='__main__':main()
