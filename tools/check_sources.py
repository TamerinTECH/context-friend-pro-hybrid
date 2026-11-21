"""Fast host checks; deliberately not presented as an iOS compile."""
import hashlib,json,pathlib,plistlib,struct,xml.etree.ElementTree as ET,zipfile
import tree_sitter,tree_sitter_swift
import jsonschema
ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=tree_sitter.Parser(tree_sitter.Language(tree_sitter_swift.language()))
def errors(node):
    return ([node] if node.type=='ERROR' or node.is_missing else [])+sum((errors(c) for c in node.children),[])
files=list((ROOT/'apple').rglob('*.swift'))
for p in files:
    found=errors(parser.parse(p.read_bytes()).root_node)
    assert not found,(p,[(n.start_point,n.type) for n in found])
print('PASS Swift syntax only:',len(files),'files; Apple types/linking unverified')
plistlib.loads((ROOT/'apple/ContextFriendPro/Info.plist').read_bytes())
ET.parse(ROOT/'apple/ContextFriendPro.xcodeproj/xcshareddata/xcschemes/ContextFriendPro.xcscheme')
translations=json.loads((ROOT/'shared/translations.json').read_text(encoding='utf-8'))
assert set(translations)=={'en','de','he'}
for language,strings in translations.items():assert set(strings)==set(translations['en']) and all(strings.values())
print('PASS localization keys and Apple project XML/plist')
manifest=json.loads((ROOT/'shared/MODEL_MANIFEST.json').read_text(encoding='utf-8'))
for m in manifest['models']:
    assert len(m['sha256'])==64 and m['url'].startswith('https://') and '/' not in m['file'] and '\\' not in m['file']
print('PASS manifest structure')
schema=json.loads((ROOT/'shared/bundle.schema.json').read_text(encoding='utf-8'))
vector=json.loads((ROOT/'shared/protocol-vectors.json').read_text(encoding='utf-8'))
jsonschema.Draft202012Validator.check_schema(schema); jsonschema.validate(vector['payload'],schema)
print('PASS portable schema and public payload vector')
apk=ROOT/'android/app/build/outputs/apk/debug/app-debug.apk'
if apk.exists():
    alignments={}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not name.endswith('.so'):continue
            data=archive.read(name); assert data[:4]==b'\x7fELF' and data[4]==2
            phoff=struct.unpack_from('<Q',data,32)[0]; size,count=struct.unpack_from('<HH',data,54)
            aligns=[struct.unpack_from('<Q',data,phoff+i*size+48)[0] for i in range(count) if struct.unpack_from('<I',data,phoff+i*size)[0]==1]
            alignments[name]=min(aligns)
    bad={k:v for k,v in alignments.items() if v<16384}
    print('Android ELF load alignment:',len(alignments),'libraries; below 16 KiB:',bad)
    assert not bad,'Dependencies require 16 KiB alignment rebuild before 16 KiB device support'
print('Host source checks complete')
