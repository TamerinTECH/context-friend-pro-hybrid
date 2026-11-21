"""Versioned interchange schema. Runtime parsers additionally enforce byte/crypto limits."""
import json,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[1]
uuid={'type':'string','format':'uuid'}; text={'type':'string'}; timestamp={'type':'integer','minimum':0,'maximum':86400000}
def obj(properties,required):return {'type':'object','additionalProperties':False,'properties':properties,'required':required}
def nullable(schema):return {'anyOf':[schema,{'type':'null'}]}
def array(schema,limit):return {'type':'array','items':schema,'maxItems':limit}
sources={'type':'array','items':uuid,'minItems':1,'maxItems':20000}
statement=obj({'text':{'type':'string','maxLength':10000},'sources':sources},['text','sources'])
action=obj({'text':{'type':'string','maxLength':10000},'sources':sources,'owner':nullable(text),'due':nullable(text)},['text','sources'])
summary=obj({'title':{'type':'string','maxLength':500},'paragraphs':array(statement,100),'decisions':array(statement,100),'actions':array(action,100),'uncertainties':array(statement,100)},['title','paragraphs','decisions','actions','uncertainties'])
segment=obj({'id':uuid,'startMs':timestamp,'endMs':timestamp,'original':{'type':'string','maxLength':20000},'correction':nullable({'type':'string','maxLength':20000}),'speaker':nullable(text),'uncertain':{'type':'boolean'},'language':text},['id','startMs','endMs','original'])
chunk=obj({'index':{'type':'integer','minimum':0},'startMs':timestamp,'sampleRate':{'const':16000},'pcm':{'type':'string','contentEncoding':'base64','maxLength':213336},'samples':{'type':'integer','minimum':1,'maximum':80000}},['index','startMs','sampleRate','pcm','samples'])
stage=obj({'state':text,'completedWindows':{'type':'integer','minimum':0},'modelHash':nullable({'type':'string','pattern':'^[0-9a-f]{64}$'}),'error':nullable(text)},['state'])
meeting=obj({'sessionId':uuid,'revision':uuid,'createdAt':{'type':'integer'},'language':{'enum':['en','de','he','auto']},'title':{'type':'string','maxLength':500},'approved':{'type':'boolean'},'summary':{'type':'string','maxLength':300000},'transcript':nullable(array(segment,20000)),'speakers':nullable({'type':'object','maxProperties':1000,'additionalProperties':text}),'audio':nullable(array(chunk,7200)),'stages':{'type':'object','additionalProperties':stage},'structuredSummary':nullable(summary)},['sessionId','revision','createdAt','language','title','approved','summary'])
profile=obj({'id':uuid,'name':{'type':'string','minLength':1,'maxLength':100},'embedding':{'type':'array','items':{'type':'number'},'minItems':192,'maxItems':192},'modelHash':{'type':'string','pattern':'^[0-9a-f]{64}$'},'preprocessing':{'const':'mono16k-f32-sherpa-v1'},'consentVersion':{'const':1},'createdAt':{'type':'integer'},'imported':{'type':'boolean'},'enabled':{'type':'boolean'}},['id','name','embedding','modelHash','preprocessing','consentVersion'])
schema={'$schema':'https://json-schema.org/draft/2020-12/schema','title':'ContextFriend Pro v1 decrypted payload',**obj({'version':{'const':1},'bundleId':uuid,'purpose':{'enum':['meeting','profiles']},'meeting':nullable(meeting),'profiles':nullable({'type':'array','items':profile,'minItems':1,'maxItems':100})},['version','bundleId','purpose']),
 'allOf':[{'if':{'properties':{'purpose':{'const':'meeting'}}},'then':{'required':['meeting'],'properties':{'meeting':meeting,'profiles':{'type':'null'}}},'else':{'required':['profiles'],'properties':{'meeting':{'type':'null'},'profiles':{'type':'array','items':profile,'minItems':1,'maxItems':100}}}}]}
(ROOT/'shared/bundle.schema.json').write_text(json.dumps(schema,indent=2)+'\n',encoding='utf-8')
print('Wrote versioned bundle schema')
