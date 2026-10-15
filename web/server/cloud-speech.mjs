import path from 'node:path';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {spawn} from 'node:child_process';
import {ffmpeg,run,clipSrt} from './media.mjs';

export function normalizeCues(cues,duration){
  if(!Array.isArray(cues))throw new Error('Speech provider returned no timed transcript.');
  return cues.map(c=>({start:Number(c.start),end:Math.min(duration,Number(c.end)),text:String(c.text||'').replace(/[{}<>]/g,'').trim()}))
    .filter(c=>Number.isFinite(c.start)&&Number.isFinite(c.end)&&c.start>=0&&c.end>c.start&&c.text).sort((a,b)=>a.start-b.start);
}
export async function withFallback(providers,call,onSwitch=()=>{}){
  let last;
  for(let i=0;i<providers.length;i++)try{return await call(providers[i]);}catch(e){
    last=e;if(![429,500,502,503,504].includes(e.status))throw e;
    if(i+1<providers.length)onSwitch(providers[i+1]);
  }
  throw last||new Error('Configure a speech provider first.');
}
async function gemini(bytes,config,duration,signal){
  const response=await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(config.geminiModel)}:generateContent`,{
    method:'POST',signal:AbortSignal.any([signal,AbortSignal.timeout(120000)]),headers:{'x-goog-api-key':config.geminiKey,'Content-Type':'application/json'},
    body:JSON.stringify({contents:[{parts:[{text:`Transcribe speech verbatim in its original language. Return JSON array of short subtitle cues with start and end as seconds relative to this audio (0 to ${duration}), and text. Do not invent words in silence or music. Return [] for no speech.`},{inline_data:{mime_type:'audio/wav',data:bytes.toString('base64')}}]}],generationConfig:{responseMimeType:'application/json',temperature:0,responseSchema:{type:'ARRAY',items:{type:'OBJECT',properties:{start:{type:'NUMBER'},end:{type:'NUMBER'},text:{type:'STRING'}},required:['start','end','text']}}}})});
  if(!response.ok)throw Object.assign(new Error(`Gemini request failed (${response.status}). Check the key, model access and quota.`),{status:response.status});
  const result=await response.json();return normalizeCues(JSON.parse(result.candidates?.[0]?.content?.parts?.map(p=>p.text||'').join('')||'null'),duration);
}
function nvidia(audio,config,root,signal){
  return new Promise((resolve,reject)=>{
    const python=process.env.CLIPPER_PYTHON||path.join(root,'.venv',process.platform==='win32'?'Scripts/python.exe':'bin/python');
    const proc=spawn(python,[path.join(root,'server','nvidia-speech.py'),audio],{windowsHide:true,shell:false,signal,env:{...process.env,NVIDIA_API_KEY:config.nvidiaKey,NVIDIA_FUNCTION_ID:config.nvidiaFunctionId},stdio:['ignore','pipe','pipe']});
    let out='';proc.stdout.on('data',c=>out+=c);proc.stderr.resume();
    proc.on('error',()=>reject(new Error('NVIDIA runtime unavailable. Run npm run setup:speech from web.')));
    proc.on('close',code=>{try{const r=JSON.parse(out);if(code||r.error)reject(Object.assign(new Error(r.error||'NVIDIA transcription failed.'),{status:r.status}));else resolve(r.cues);}catch{reject(new Error('Install the NVIDIA runtime with npm run setup:speech.'));}});
  });
}
export function cloudSpeechRoutes(app,{root,data,previews,read}){
  let config={geminiKey:process.env.GEMINI_API_KEY||'',geminiModel:process.env.GEMINI_MODEL||'gemini-2.5-flash',nvidiaKey:process.env.NVIDIA_API_KEY||'',nvidiaFunctionId:process.env.NVIDIA_FUNCTION_ID||'71203149-d3b7-4460-8231-1be2543a1fca'};
  let state={status:'idle'},controller;
  app.get('/api/cloud-speech',(req,res)=>res.json({...state,configured:{gemini:!!config.geminiKey,nvidia:!!config.nvidiaKey},geminiModel:config.geminiModel}));
  app.post('/api/cloud-speech/settings',(req,res)=>{for(const key of ['geminiKey','nvidiaKey','geminiModel'])if(typeof req.body[key]==='string'&&req.body[key].trim())config[key]=req.body[key].trim().slice(0,500);res.json({ok:true});});
  app.post('/api/cloud-speech/cancel',(req,res)=>{controller?.abort();res.json({ok:true});});
  app.post('/api/cloud-speech/transcribe',async(req,res)=>{
    if(state.status==='transcribing')return res.status(409).json({error:'A cloud transcript is already running.'});
    const m=await read('media',req.body.mediaId);if(!m.hasAudio)throw new Error('This video has no audio track.');
    if(m.duration>10800)throw new Error('Maximum video duration is 3 hours.');
    const order=req.body.provider==='gemini'?['gemini','nvidia']:['nvidia','gemini'];
    const providers=order.filter(p=>config[p+'Key']);if(!providers.length)throw new Error('Add a Gemini or NVIDIA key first.');
    const snapshot={...config};controller=new AbortController();const signal=controller.signal;
    state={status:'transcribing',mediaId:m.id,seconds:0,duration:m.duration,provider:providers[0],message:'Preparing audio…'};
    res.status(202).json(state);
    void (async()=>{let dir;try{
      dir=await mkdtemp(path.join(previews,'speech-'));const cues=[];
      // Short bounded requests keep RAM flat even for three-hour sources.
      for(let offset=0;offset<m.duration;offset+=30){
        signal.throwIfAborted();const duration=Math.min(30,m.duration-offset),audio=path.join(dir,'chunk.wav');
        await run(ffmpeg,['-v','error','-y','-ss',String(offset),'-i',path.join(data,'media',m.id+m.ext),'-t',String(duration),'-vn','-ac','1','-ar','16000','-c:a','pcm_s16le',audio],{signal});
        const chunk=await withFallback(providers,async provider=>{
          state={...state,provider,message:`Transcribing with ${provider}…`};
          return provider==='gemini'?gemini(await readFile(audio),snapshot,duration,signal):normalizeCues(await nvidia(audio,snapshot,root,signal),duration);
        },next=>{state={...state,message:`Provider limit reached. Switching to ${next}.`};});
        if(state.provider!==providers[0])providers.reverse();
        cues.push(...chunk.map(c=>({...c,start:c.start+offset,end:c.end+offset})));
        state={...state,seconds:offset+duration};
      }
      state={...state,status:'completed',srt:clipSrt(cues,0,m.duration),cueCount:cues.length,message:'Review the transcript before rendering.'};
    }catch(e){state={...state,status:signal.aborted?'cancelled':'failed',error:signal.aborted?'Cancelled.':e.message};}
    finally{if(dir)await rm(dir,{recursive:true,force:true});}})();
  });
}
