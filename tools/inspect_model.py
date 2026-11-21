import pathlib,struct
p=pathlib.Path(__file__).resolve().parents[1]/'models/gemma-4-E2B_q4_0-it.gguf'
with p.open('rb') as f:
    def readfmt(fmt):return struct.unpack('<'+fmt,f.read(struct.calcsize('<'+fmt)))[0]
    def string():return f.read(readfmt('Q')).decode('utf-8')
    def value(kind):
        if kind==8:return string()
        if kind==9:
            sub=readfmt('I');n=readfmt('Q');return [value(sub) for _ in range(n)]
        return readfmt({0:'B',1:'b',2:'H',3:'h',4:'I',5:'i',6:'f',7:'?',10:'Q',11:'q',12:'d'}[kind])
    assert f.read(4)==b'GGUF';version=readfmt('I');tensors=readfmt('Q');count=readfmt('Q')
    for _ in range(count):
        key=string();v=value(readfmt('I'))
        if key in ('general.architecture','tokenizer.chat_template'):print(key,':',v)
