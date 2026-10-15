"""Local Vosk worker. Audio arrives as streamed 16 kHz mono signed PCM."""
import json, os, sys, pathlib, urllib.request, zipfile, shutil, tempfile

MODELS={'en':'vosk-model-small-en-us-0.15','hi':'vosk-model-small-hi-0.22'}
def emit(value):
    print(json.dumps(value,ensure_ascii=True),flush=True)

def install(root,language):
    name=MODELS[language]
    root.mkdir(parents=True,exist_ok=True)
    target=root/name
    if (target/'am'/'final.mdl').is_file():
        emit({'done':True});return
    # Download only a fixed official model, with TLS verification and hard limits.
    with tempfile.TemporaryDirectory(prefix='install-',dir=root) as staging:
        staging=pathlib.Path(staging);archive=staging/'model.zip'
        total=0
        with urllib.request.urlopen(f'https://alphacephei.com/vosk/models/{name}.zip',timeout=60) as response,archive.open('wb') as output:
            if not response.url.startswith('https://alphacephei.com/vosk/models/'):
                raise ValueError('Unexpected model download location')
            while chunk:=response.read(1024*1024):
                total+=len(chunk)
                if total>100*1024*1024:raise ValueError('Model archive exceeds limit')
                output.write(chunk);emit({'downloaded':total})
        with zipfile.ZipFile(archive) as z:
            entries=z.infolist()
            if len(entries)>2000 or sum(e.file_size for e in entries)>300*1024*1024:raise ValueError('Model extraction exceeds limit')
            for entry in entries:
                destination=(staging/entry.filename).resolve()
                if not destination.is_relative_to(staging.resolve()) or '\\' in entry.filename or ':' in entry.filename:raise ValueError('Invalid model archive path')
                if (entry.external_attr>>16)&0o170000==0o120000:raise ValueError('Model archive symlinks are forbidden')
            z.extractall(staging)
        if not (staging/name/'am'/'final.mdl').is_file():raise ValueError('Model is incomplete')
        (staging/name).rename(target)
    emit({'done':True})

def transcribe(root,language):
    from vosk import Model,KaldiRecognizer,SetLogLevel
    SetLogLevel(-1)
    model=Model(str(root/MODELS[language]));recognizer=KaldiRecognizer(model,16000);recognizer.SetWords(True)
    words=[];processed=0
    while chunk:=sys.stdin.buffer.read(32000):
        processed+=len(chunk)
        if recognizer.AcceptWaveform(chunk):words.extend(json.loads(recognizer.Result()).get('result',[]))
        if processed%320000==0:emit({'seconds':processed/32000})
    words.extend(json.loads(recognizer.FinalResult()).get('result',[]))
    cues=[];group=[]
    for word in words:
        if group and (len(group)>=8 or word['end']-group[0]['start']>4 or word['start']-group[-1]['end']>1):
            cues.append({'start':group[0]['start'],'end':group[-1]['end'],'text':' '.join(w['word'] for w in group)});group=[]
        group.append(word)
    if group:cues.append({'start':group[0]['start'],'end':group[-1]['end'],'text':' '.join(w['word'] for w in group)})
    emit({'done':True,'cues':cues})

if __name__=='__main__':
    try:
        action,folder,language=sys.argv[1:]
        if language not in MODELS:raise ValueError('Unsupported speech language')
        if action=='install':install(pathlib.Path(folder),language)
        elif action=='transcribe':transcribe(pathlib.Path(folder),language)
        else:raise ValueError('Unknown speech action')
    except Exception as error:
        print(str(error),file=sys.stderr);sys.exit(1)
