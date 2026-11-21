"""Reproduce source dependencies and verified runtime archives; never fetch user data."""
import json, pathlib, subprocess, sys
from acquire import ROOT, DEPS, fetch

SOURCES = {
 'whisper': ('https://github.com/ggml-org/whisper.cpp.git','d1be6fde11ac6e0407606b4e42fe72d34add8037'),
 'llama': ('https://github.com/ggml-org/llama.cpp.git','620d2225cc436ab0b001e3200e590e3b286ef9ab'),
 'sherpa': ('https://github.com/k2-fsa/sherpa-onnx.git','af2d8d81dbfa61c4cea4c568da78f3982edca005'),
 'swift-sodium': ('https://github.com/jedisct1/swift-sodium.git','cfd195c76882aa9b997560ca7cb95d72fbf5db00'),
}
def run(*args, cwd):
    subprocess.run(args,cwd=cwd,check=True,stdout=subprocess.DEVNULL)
for name,(url,commit) in SOURCES.items():
    path=DEPS/name
    if not path.exists():
        path.mkdir(); run('git','init',cwd=path); run('git','remote','add','origin',url,cwd=path)
        run('git','fetch','--depth','1','origin',commit,cwd=path); run('git','checkout','--detach','FETCH_HEAD',cwd=path)
    actual=subprocess.check_output(['git','rev-parse','HEAD'],cwd=path,text=True).strip()
    if actual!=commit: raise RuntimeError(f'{name}: wrong checkout; preserve it and resolve manually')
    print(name,actual)
url='https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar'
expected='a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7'
if fetch(url,DEPS/'sherpa-onnx.aar')['sha256']!=expected: raise RuntimeError('sherpa archive checksum mismatch')
print('Pinned sources and Android runtime verified.')
