"""Explicit developer provisioning. Credentials never enter manifests or output."""
import hashlib, json, os, pathlib, tarfile, urllib.request, urllib.parse, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEPS = ROOT / '.deps'
DEPS.mkdir(exist_ok=True)

def token():
    for key in ('HF_TOKEN', 'HUGGINGFACE_TOKEN', 'HUGGING_FACE_HUB_TOKEN', 'HUGGINGFACE_HUB_TOKEN'):
        if os.environ.get(key):
            return os.environ[key]
    for p in (ROOT.parent / 'context-friend/apps/central/.env', ROOT.parent / 'context-friend/.env'):
        if p.exists():
            for line in p.read_text(encoding='utf-8-sig').splitlines():
                k, sep, v = line.partition('=')
                if sep and ('HUGGING' in k.upper() or k.strip() == 'HF_TOKEN'):
                    return v.strip().strip('\"').strip("'")
    return None

TOKEN = token()

class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected and urllib.parse.urlparse(newurl).hostname != urllib.parse.urlparse(req.full_url).hostname:
            redirected.remove_header('Authorization')
        if urllib.parse.urlparse(newurl).scheme != 'https':
            raise ValueError('Non-HTTPS redirect rejected')
        return redirected

opener = urllib.request.build_opener(SafeRedirect())

def request(url):
    headers = {'User-Agent': 'ContextFriend-Pro-provisioning/1'}
    if TOKEN and urllib.parse.urlparse(url).hostname == 'huggingface.co':
        headers['Authorization'] = 'Bearer ' + TOKEN
    return opener.open(urllib.request.Request(url, headers=headers), timeout=90)

def api(url):
    with request(url) as response:
        return json.load(response)

def fetch(url, path):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        partial = path.with_suffix(path.suffix + '.partial')
        with request(url) as response, partial.open('wb') as target:
            while chunk := response.read(1024 * 1024):
                target.write(chunk)
        partial.replace(path)
    h = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024):
            h.update(chunk)
    return {'bytes': path.stat().st_size, 'sha256': h.hexdigest()}

if __name__ == '__main__':
    print('Hugging Face credential available:', bool(TOKEN))
    for repo in ('google/gemma-4-E2B-it-qat-q4_0-gguf', 'ggerganov/whisper.cpp'):
        info = api('https://huggingface.co/api/models/' + repo)
        (DEPS / (repo.split('/')[-1] + '.json')).write_text(json.dumps(info, indent=2), encoding='utf-8')
        print(json.dumps({'repo': repo, 'revision': info.get('sha'), 'license': info.get('cardData', {}).get('license'),
            'files': [x['rfilename'] for x in info['siblings'] if x['rfilename'].endswith('.gguf') or x['rfilename'] in ('ggml-tiny.bin', 'ggml-small-q5_1.bin')]}))
    for name in ('Speaker.kt', 'SpeakerEmbeddingExtractorConfig.kt', 'OfflineSpeakerDiarization.kt'):
        fetch('https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v2.0.1/sherpa-onnx/kotlin-api/' + name, DEPS / name)
    for name in ('sherpa-onnx.aar', 'lazysodium.aar'):
        with zipfile.ZipFile(DEPS / name) as archive:
            print(name, archive.namelist()[:12])
            archive.extract('classes.jar', DEPS / name.removesuffix('.aar'))
