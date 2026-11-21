"""Download the committed model set, enforcing existing hashes, never rewriting trust."""
import hashlib,json,pathlib,tarfile
from concurrent.futures import ThreadPoolExecutor
from acquire import ROOT,DEPS,fetch
MODEL=ROOT/'models'; MODEL.mkdir(exist_ok=True)
manifest=json.loads((ROOT/'shared/MODEL_MANIFEST.json').read_text(encoding='utf-8'))
def install(item):
    destination=MODEL/item['file']
    if item.get('archive'):
        archive=DEPS/'segmentation.tar.bz2'; info=fetch(item['url'],archive)
        if info['sha256']!=item['revision']: raise RuntimeError('Segmentation archive hash mismatch')
        with tarfile.open(archive) as tf:
            approved=[m for m in tf.getmembers() if m.isfile() and pathlib.PurePosixPath(m.name).name=='model.int8.onnx']
            if len(approved)!=1 or approved[0].size!=item['bytes']: raise RuntimeError('Unexpected segmentation archive')
            data=tf.extractfile(approved[0]).read(item['bytes']+1)
            if hashlib.sha256(data).hexdigest()!=item['sha256']: raise RuntimeError('Segmentation model hash mismatch')
            destination.write_bytes(data)
    info=fetch(item['url'],destination)
    if info['bytes']!=item['bytes'] or info['sha256']!=item['sha256']: raise RuntimeError(item['id']+': integrity failed; existing file preserved for inspection')
    print(item['id'],info['bytes'],'verified',flush=True)
with ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(install,manifest['models']))
print('Pinned model pack ready. Import files separately through each app Models screen.')
