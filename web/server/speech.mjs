import {spawn} from 'node:child_process';
import {access,readFile,writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {ffmpeg,stamp} from './media.mjs';
const models={en:'vosk-model-small-en-us-0.15',hi:'vosk-model-small-hi-0.22'};
export function speechRoutes(app,{root,data,read}){
 const python=process.env.CLIPPER_PYTHON||path.join(root,'.venv',process.platform==='win32'?'Scripts/python.exe':'bin/python');
 const folder=path.join(data,'models'),script=path.join(root,'server','speech.py');let current={status:'idle'},workers=[],generation=0;
 const available=async file=>access(file).then(()=>true,()=>false);
 app.get('/api/speech',async(req,res)=>res.json({runtime:await available(python),models:Object.fromEntries(await Promise.all(Object.entries(models).map(async([key,name])=>[key,await available(path.join(folder,name,'am','final.mdl'))]))),...current}));
 const start=(action,language,media)=>{
  const runId=++generation;
  const proc=spawn(python,['-u',script,action,folder,language],{windowsHide:true,shell:false,stdio:['pipe','pipe','pipe']});workers=[proc];let buffer='',errors='',result=null,decodeCode=null,decodeDone=Promise.resolve();
  current={status:action==='install'?'installing':'transcribing',language,mediaId:media?.id,seconds:0,downloaded:0};
  if(media){const decoder=spawn(ffmpeg,['-v','error','-i',path.join(data,'media',media.id+media.ext),'-vn','-ac','1','-ar','16000','-f','s16le','pipe:1'],{windowsHide:true,shell:false});workers.push(decoder);decoder.stdout.pipe(proc.stdin);decodeDone=new Promise(resolve=>decoder.on('close',code=>{decodeCode=code;resolve();}));decoder.on('error',e=>{errors=e.message;proc.kill();});decoder.stderr.on('data',c=>{errors=(errors+c).slice(-4000);});proc.stdin.on('error',()=>{});}
  else proc.stdin.end();
  proc.stdout.on('data',chunk=>{buffer+=chunk;if(buffer.length>5_000_000){errors='Transcript exceeds output limit';workers.forEach(p=>p.kill());return;}let newline;while((newline=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,newline);buffer=buffer.slice(newline+1);try{const event=JSON.parse(line);if(event.cues)result=event.cues;else current={...current,...event};}catch{}}});
  proc.stderr.on('data',c=>{errors=(errors+c).slice(-4000);});
  proc.on('error',e=>{current={...current,status:'failed',error:'Speech runtime unavailable. Run the speech setup script. '+e.message};workers.forEach(p=>p.kill());});
  proc.on('close',async code=>{if(runId!==generation)return;workers.forEach(p=>{if(p!==proc)p.kill();});await decodeDone;if(runId!==generation||current.status==='cancelled')return;
   if(code!==0||(media&&decodeCode!==0)){current={...current,status:'failed',error:errors||'Speech recognition failed.'};return;}
   if(media&&result){const srt=result.map((c,i)=>`${i+1}\n${stamp(c.start)} --> ${stamp(c.end)}\n${c.text}\n`).join('\n');await mkdir(path.join(data,'transcripts'),{recursive:true});await writeFile(path.join(data,'transcripts',media.id+'.srt'),srt);current={...current,status:'completed',srt,cueCount:result.length};}
   else current={...current,status:'completed'};workers=[];
  });
 };
 app.post('/api/speech/install',async(req,res)=>{const language=req.body.language;if(!models[language])return res.status(400).json({error:'Choose English or Hindi.'});if(['installing','transcribing'].includes(current.status))return res.status(409).json({error:'A speech task is already running.'});if(!await available(python))return res.status(400).json({error:'Run scripts/setup-speech.ps1 first.'});start('install',language);res.status(202).json(current);});
 app.post('/api/speech/transcribe',async(req,res)=>{const language=req.body.language;if(!models[language])return res.status(400).json({error:'Choose English or Hindi.'});if(['installing','transcribing'].includes(current.status))return res.status(409).json({error:'A speech task is already running.'});if(!await available(path.join(folder,models[language],'am','final.mdl')))return res.status(400).json({error:'Download the language model first.'});const media=await read('media',req.body.mediaId);if(!media.hasAudio)return res.status(400).json({error:'This file has no audio track.'});start('transcribe',language,media);res.status(202).json(current);});
 app.post('/api/speech/cancel',(req,res)=>{generation++;current={...current,status:'cancelled'};workers.forEach(p=>p.kill());workers=[];res.json({ok:true});});
 app.get('/api/speech/:id/transcript',async(req,res)=>{const media=await read('media',req.params.id);try{res.json({srt:await readFile(path.join(data,'transcripts',media.id+'.srt'),'utf8')});}catch(e){if(e.code==='ENOENT')return res.status(404).json({error:'No saved transcript'});throw e;}});
}
