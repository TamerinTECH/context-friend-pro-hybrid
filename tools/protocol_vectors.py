"""Independent libsodium wire vectors. Public test keys only; never use in apps.
Run --generate once when deliberately changing a versioned wire format.
Run without arguments to verify the committed vector and malicious modifications.
"""
import base64,hashlib,json,pathlib,struct,sys,uuid
from nacl.bindings import crypto_aead_xchacha20poly1305_ietf_encrypt,crypto_aead_xchacha20poly1305_ietf_decrypt
from nacl.public import PrivateKey,SealedBox
from nacl.signing import SigningKey,VerifyKey
ROOT=pathlib.Path(__file__).resolve().parents[1]; target=ROOT/'shared/protocol-vectors.json'
def b64(b):return base64.urlsafe_b64encode(b).decode().rstrip('=')
def un64(s):return base64.urlsafe_b64decode(s+'='*((4-len(s)%4)%4))
if '--generate' in sys.argv:
    recipient=PrivateKey(bytes(range(32))); sender=SigningKey(bytes(range(32,64))); signing=SigningKey(bytes(range(64,96)))
    inv='00000000-0000-4000-8000-000000000001'; bundle='00000000-0000-4000-8000-000000000002'
    payload={'version':1,'bundleId':bundle,'purpose':'meeting','meeting':{'sessionId':'00000000-0000-4000-8000-000000000003','revision':'00000000-0000-4000-8000-000000000004','createdAt':0,'language':'he','title':'Public protocol fixture','approved':True,'summary':'שלום · Prüfung · offline','transcript':None,'speakers':None,'audio':None,'stages':{}},'profiles':None}
    plain=json.dumps(payload,ensure_ascii=False,separators=(',',':')).encode(); key=bytes(range(96,128)); nonce=bytes(range(24)); sealed=SealedBox(recipient.public_key).encrypt(key)
    header=b'CFB1'+bytes([1])+uuid.UUID(inv).bytes+uuid.UUID(bundle).bytes+bytes(recipient.public_key)+bytes(signing.verify_key)+bytes(sender.verify_key)+nonce+sealed+struct.pack('>I',len(plain)+16)
    cipher=crypto_aead_xchacha20poly1305_ietf_encrypt(plain,header,nonce,key); signed=header+cipher; envelope=signed+sender.sign(b'CFB1-SIGN:'+signed).signature
    digest=hashlib.sha256(envelope).hexdigest(); receipt=f'CFR1:{inv}:{bundle}:{digest}:imported'; receipt+=':'+b64(signing.sign(receipt.encode()).signature)
    vector={'notice':'PUBLIC TEST KEYS ONLY - DO NOT USE FOR REAL DATA','recipient':{'boxPublic':b64(bytes(recipient.public_key)),'boxSecret':b64(bytes(recipient)),'signPublic':b64(bytes(signing.verify_key)),'signSecret':b64(bytes(signing)+bytes(signing.verify_key))},'invitation':{'version':1,'id':inv,'boxKey':b64(bytes(recipient.public_key)),'signKey':b64(bytes(signing.verify_key)),'ttl':600,'host':None,'port':None},'payload':payload,'envelope':b64(envelope),'sha256':digest,'receipt':receipt}
    target.write_text(json.dumps(vector,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
v=json.loads(target.read_text(encoding='utf-8')); data=un64(v['envelope'])
assert hashlib.sha256(data).hexdigest()==v['sha256']; assert len(data)==305+struct.unpack('>I',data[237:241])[0]
def decrypt(data):
    VerifyKey(data[101:133]).verify(b'CFB1-SIGN:'+data[:-64],data[-64:])
    key=SealedBox(PrivateKey(un64(v['recipient']['boxSecret']))).decrypt(data[157:237])
    return json.loads(crypto_aead_xchacha20poly1305_ietf_decrypt(data[241:-64],data[:241],data[133:157],key))
assert decrypt(data)==v['payload']
for offset in (0,4,30,70,102,150,220,250,len(data)-1):
    altered=bytearray(data);altered[offset]^=1
    try:decrypt(bytes(altered))
    except Exception:pass
    else:raise AssertionError('Accepted tampering')
fields=v['receipt'].split(':'); VerifyKey(un64(v['recipient']['signPublic'])).verify(':'.join(fields[:5]).encode(),un64(fields[5]))
print('PASS: independent envelope, UTF-8 payload, 9 tamper cases and recipient receipt')
