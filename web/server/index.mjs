import express from 'express';
import {validateLayers,prepareEdit,renderEdit} from './clip-edit.mjs';
import {createServer as createHttpServer} from 'node:http';
import { randomBytes, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile, rename, readdir, stat, rm, statfs } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import { ffmpeg, probe, run, planClips, planRanges, dimensions, parseSrt, clipSrt, stamp } from './media.mjs';
import {speechRoutes} from './speech.mjs';
import {cloudSpeechRoutes} from './cloud-speech.mjs';
import {driveRoutes} from './drive.mjs';
import {ZipArchive} from 'archiver';

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const data=path.resolve(process.env.CLIPPER_DATA_DIR||path.join(root,'.clipper-data'));
const previews=path.resolve(process.env.CLIPPER_PREVIEW_DIR||path.join(os.tmpdir(),'clipper-previews'));
await mkdir(previews,{recursive:true});
const exportDir=j=>path.join(j.temporary?previews:path.join(data,'exports'),j.id);
const port=Number(process.env.PORT||4317), origin=`http://127.0.0.1:${port}`;
const token=randomBytes(32).toString('hex'), app=express();
const directories=['projects','media','jobs','exports'];
for(const name of directories)await mkdir(path.join(data,name),{recursive:true});
const uuid=/^[a-f0-9-]{36}$/;
function id(value){if(!uuid.test(value||''))throw Object.assign(new Error('Invalid identifier'),{status:400});return value;}
const file=(kind,value)=>path.join(data,kind,`${id(value)}.json`);
async function read(kind,value){try{return JSON.parse(await readFile(file(kind,value),'utf8'));}catch(e){if(e.code==='ENOENT')throw Object.assign(new Error('Not found'),{status:404});throw e;}}
async function save(kind,value){const target=file(kind,value.id),temp=target+'.'+randomUUID()+'.tmp';await writeFile(temp,JSON.stringify(value));await rename(temp,target);}
async function list(kind){const names=(await readdir(path.join(data,kind))).filter(n=>n.endsWith('.json'));return Promise.all(names.map(n=>read(kind,n.slice(0,-5))));}
app.disable('x-powered-by');
app.use((req,res,next)=>{
  if(req.headers.host!==`127.0.0.1:${port}`)return res.status(403).json({error:'Use the local 127.0.0.1 address.'});
  if(req.headers.origin&&req.headers.origin!==origin)return res.status(403).json({error:'Cross-origin access denied.'});
  if(['cross-site','same-site'].includes(req.headers['sec-fetch-site'])&&!(req.method==='GET'&&req.path==='/oauth/drive/callback'))return res.status(403).json({error:'External website access denied.'});
  res.set({'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','Cross-Origin-Opener-Policy':'same-origin','Cross-Origin-Resource-Policy':'same-origin','X-Frame-Options':'DENY'});
  if(req.method==='GET' && !req.path.startsWith('/api/') && (req.path==='/' || req.headers.accept?.includes('text/html')))res.cookie('clipper_session',token,{httpOnly:true,sameSite:'strict',path:'/'});
  if(req.path.startsWith('/api/')&&!req.headers.cookie?.split(';').some(c=>c.trim()===`clipper_session=${token}`))return res.status(401).json({error:'Open Clipper in this browser first.'});
  next();
});
app.get('/api/status',async(req,res)=>{const disk=await statfs(data);res.json({version:'0.1.0',storage:data,freeBytes:disk.bavail*disk.bsize,activeJob:active?.id||null});});
app.post('/api/media',async(req,res)=>{
  const name=decodeURIComponent(String(req.headers['x-file-name']||'media')).slice(0,240);
  const ext=path.extname(name).toLowerCase();
  const types={'.mp4':['video','video/mp4'],'.mov':['video','video/quicktime'],'.mkv':['video','video/x-matroska'],'.webm':['video','video/webm'],'.mp3':['audio','audio/mpeg'],'.wav':['audio','audio/wav'],'.m4a':['audio','audio/mp4'],'.png':['image','image/png'],'.jpg':['image','image/jpeg'],'.jpeg':['image','image/jpeg'],'.webp':['image','image/webp']};
  if(!types[ext])return res.status(400).json({error:'Unsupported media extension.'});
  const disk=await statfs(data), declared=Number(req.headers['content-length']);
  const cap=Math.min(100*1024**3,disk.bavail*disk.bsize-512*1024**2);
  if(!Number.isFinite(declared)||declared<=0||declared>cap)return res.status(413).json({error:'Not enough free storage or file is too large.'});
  const mediaId=randomUUID(),target=path.join(data,'media',mediaId+ext),temp=target+'.upload';let bytes=0;
  try{
    await pipeline(req,new Transform({transform(chunk,enc,cb){bytes+=chunk.length;cb(bytes>cap?new Error('Upload exceeds available storage'):null,chunk);}}),createWriteStream(temp,{flags:'wx'}));
    const meta=await probe(temp);
    if(types[ext][0]==='video'&&(!meta.width||!meta.duration))throw new Error('No readable video track.');
    if(meta.duration>10800)throw new Error('Maximum video duration is 3 hours.');
    await rename(temp,target);
    const media={id:mediaId,name,ext,kind:types[ext][0],mime:types[ext][1],size:bytes,...meta};
    await save('media',media);res.status(201).json(media);
  }catch(e){await rm(temp,{force:true});await rm(target,{force:true});throw e;}
});
app.use(express.json({limit:'12mb'}));
speechRoutes(app,{root,data,read});
cloudSpeechRoutes(app,{root,data,previews,read});
driveRoutes(app,{origin,read,exportDir});
app.get('/api/media',async(req,res)=>res.json(await list('media')));
app.get('/api/media/:id',async(req,res)=>res.json(await read('media',req.params.id)));
app.get('/api/media/:id/content',async(req,res)=>{const m=await read('media',req.params.id);res.type(m.mime);res.sendFile(path.join(data,'media',m.id+m.ext),{dotfiles:'allow'});});
const proxyTasks=new Map();
app.post('/api/media/:id/preview',async(req,res)=>{
  const m=await read('media',req.params.id);
  if(m.kind!=='video')return res.status(400).json({error:'Choose a video.'});
  let task=proxyTasks.get(m.id);
  if(!task){
    task={status:'preparing'};proxyTasks.set(m.id,task);
    const target=path.join(previews,m.id+'.mp4');
    void run(ffmpeg,['-v','error','-y','-i',path.join(data,'media',m.id+m.ext),'-map','0:v:0','-map','0:a:0?','-vf',"scale='min(1280,iw)':-2",'-c:v','libx264','-preset','veryfast','-crf','23','-pix_fmt','yuv420p','-c:a','aac','-movflags','+faststart',target])
      .then(()=>Object.assign(task,{status:'ready',url:`/api/media/${m.id}/preview`}))
      .catch(()=>Object.assign(task,{status:'failed',error:'Unable to create playback preview. Check source and free disk space.'}));
  }
  res.json(task);
});
app.get('/api/media/:id/preview',async(req,res)=>{const m=await read('media',req.params.id);if(proxyTasks.get(m.id)?.status!=='ready')return res.status(404).end();res.type('mp4').sendFile(path.join(previews,m.id+'.mp4'),{dotfiles:'allow'});});
app.get('/api/projects',async(req,res)=>res.json((await list('projects')).map(({id,name,updatedAt})=>({id,name,updatedAt})).sort((a,b)=>b.updatedAt-a.updatedAt)));
app.get('/api/projects/:id',async(req,res)=>res.json(await read('projects',req.params.id)));
app.put('/api/projects/:id',async(req,res)=>{const p=req.body;if(p.id!==id(req.params.id)||typeof p.name!=='string'||p.name.length>200||!Number.isFinite(p.updatedAt))return res.status(400).json({error:'Invalid project'});await save('projects',p);res.json({ok:true});});
app.delete('/api/projects/:id',async(req,res)=>{await rm(file('projects',req.params.id),{force:true});res.json({ok:true});});

let active=null;
// Restarted jobs retain finished clips but require an explicit user resume.
for(const j of await list('jobs'))if(['running','queued'].includes(j.status)){j.status='paused';j.message='Server restarted. Resume to continue.';await save('jobs',j);}
app.get('/api/jobs',async(req,res)=>res.json((await list('jobs')).sort((a,b)=>b.createdAt-a.createdAt)));
app.post('/api/jobs',async(req,res)=>{
  const b=req.body,m=await read('media',b.mediaId);
  if(m.kind!=='video')return res.status(400).json({error:'Choose a video.'});
  const merge=b.merge===true;
  const clips=b.ranges?planRanges(m.duration,b.ranges,merge):planClips(m.duration,Number(b.interval));
  if(merge&&!b.ranges)return res.status(400).json({error:'Select time ranges to merge.'});
  if(!['original','420','720','1080','2160'].includes(b.resolution)||!['exact','copy'].includes(b.mode))return res.status(400).json({error:'Invalid export settings.'});
  if(merge&&b.mode==='copy')return res.status(400).json({error:'Use exact cuts for a frame-accurate merged sequence.'});
  if(b.mode==='exact'&&['smpte2084','arib-std-b67'].includes(m.colorTransfer))return res.status(400).json({error:'HDR footage: choose Original copy to preserve its color. SDR caption rendering is not suitable for this source.'});
  const caption=String(b.caption||'').slice(0,1000),label=String(b.label||'').slice(0,100),srt=String(b.srt||'');parseSrt(srt);
  if(b.mode==='copy'&&(caption||label||srt||b.resolution!=='original'))return res.status(400).json({error:'Original copy requires no overlays or scaling.'});
  if(b.mode==='copy'&&!['h264','hevc','mpeg4','av1'].includes(m.codec))return res.status(400).json({error:'This codec cannot be copied to MP4. Choose exact export.'});
  const job={id:randomUUID(),mediaId:m.id,name:m.name,createdAt:Date.now(),temporary:true,merge,custom:!!b.ranges,status:'queued',interval:Number(b.interval)||60,resolution:b.resolution,mode:b.mode,caption,label,srt,position:b.position==='top'?'top':'bottom',clips,outputs:[],progress:0};
  await save('jobs',job);res.status(201).json(job);void pump();
});
app.post('/api/jobs/:id/edit',async(req,res)=>{
  const parent=await read('jobs',req.params.id),output=parent.outputs.find(o=>o.name===req.body.name);
  if(parent.status!=='completed'||!output)return res.status(409).json({error:'Choose a completed preview.'});
  const source=path.join(exportDir(parent),output.name);
  const meta=await probe(source);
  if(['smpte2084','arib-std-b67'].includes(meta.colorTransfer))return res.status(400).json({error:'Text editing currently supports SDR video only.'});
  const layers=validateLayers(req.body.layers,meta.duration);
  const job={id:randomUUID(),mediaId:parent.mediaId,name:'Edited '+output.name,createdAt:Date.now(),temporary:true,edited:true,status:'queued',mode:'exact',outputs:[],progress:0,subtitle:!!output.subtitle};
  const dir=exportDir(job);await mkdir(dir,{recursive:true});
  try{await prepareEdit(dir,source,layers,output.subtitle?path.join(exportDir(parent),output.subtitle):undefined);await save('jobs',job);}
  catch(e){await rm(dir,{recursive:true,force:true});throw e;}
  res.status(201).json(job);void pump();
});
app.post('/api/jobs/:id/pause',async(req,res)=>{const j=await read('jobs',req.params.id);if(active?.id===j.id)active.controller.abort();else if(j.status==='queued'){j.status='paused';await save('jobs',j);}res.json({ok:true});});
app.post('/api/jobs/:id/resume',async(req,res)=>{const j=await read('jobs',req.params.id);if(!['paused','failed'].includes(j.status))return res.status(409).json({error:'Job cannot be resumed now.'});j.status='queued';j.message='';await save('jobs',j);res.json(j);void pump();});
app.get('/api/jobs/:id/files/:name',async(req,res)=>{const j=await read('jobs',req.params.id),name=req.params.name;if(!j.outputs.some(o=>o.name===name||o.subtitle===name))return res.status(404).end();const target=path.join(exportDir(j),name);if(req.query.preview==='1'){res.type(name.endsWith('.mp4')?'mp4':'text/plain').sendFile(target,{dotfiles:'allow'});}else res.download(target,name,{dotfiles:'allow'});});
app.get('/api/jobs/:id/bundle/:name',async(req,res)=>{
  const j=await read('jobs',req.params.id),output=j.outputs.find(o=>o.name===req.params.name);
  if(!output)return res.status(404).end();
  const files=[output.name,output.subtitle].filter(Boolean);
  for(const name of files)await stat(path.join(exportDir(j),name));
  res.attachment(output.name.replace(/\.mp4$/,'.zip'));
  const archive=new ZipArchive({store:true,forceZip64:true});
  archive.on('error',()=>res.destroy());archive.on('warning',()=>res.destroy());res.on('close',()=>archive.abort());archive.pipe(res);
  for(const name of files)archive.file(path.join(exportDir(j),name),{name});
  await archive.finalize();
});
app.delete('/api/jobs/:id',async(req,res)=>{const j=await read('jobs',req.params.id);if(active?.id===j.id||j.status==='queued')return res.status(409).json({error:'Pause this job before discarding it.'});await rm(exportDir(j),{recursive:true,force:true});await rm(file('jobs',j.id),{force:true});res.json({ok:true});});
async function pump(){
  if(active)return;const job=(await list('jobs')).filter(j=>j.status==='queued').sort((a,b)=>a.createdAt-b.createdAt)[0];if(!job||active)return;
  active={id:job.id,controller:new AbortController()};job.status='running';await save('jobs',job);
  const dir=exportDir(job);await mkdir(dir,{recursive:true});
  try{
    if(job.edited){
      const meta=await renderEdit(dir,active.controller.signal);
      job.outputs=[{name:'Final.mp4',duration:meta.duration,width:meta.width,height:meta.height,subtitle:job.subtitle?'Final.srt':undefined}];
      job.status='completed';job.progress=1;job.message='Edited clip ready for review.';return;
    }
    const m=await read('media',job.mediaId),source=path.join(data,'media',m.id+m.ext),signal=active.controller.signal;
    const disk=await statfs(data);if(disk.bavail*disk.bsize<512*1024**2)throw new Error('At least 512 MB of free disk space is required.');
    if(job.mode==='copy'&&!job.custom){
      // One continuous demux pass: each compressed packet belongs to exactly one output.
      job.outputs=[];
      await run(ffmpeg,['-hide_banner','-v','error','-y','-i',source,'-map','0:v:0','-map','0:a:0?','-c','copy','-f','segment','-segment_time',String(job.interval),'-segment_start_number','1','-reset_timestamps','1','-segment_format','mp4','Part-%04d.mp4'],{signal,cwd:dir});
      for(const name of (await readdir(dir)).filter(n=>/^Part-\d+\.mp4$/.test(n)).sort()){const meta=await probe(path.join(dir,name));job.outputs.push({name,duration:meta.duration,width:meta.width,height:meta.height});}
    }else{
      const cues=parseSrt(job.srt),size=dimensions(m.width,m.height,job.resolution);
      for(const clip of job.clips){
        if(signal.aborted)throw new Error('Paused');
        const name=`Part-${String(clip.part).padStart(4,'0')}.mp4`;
        if(job.outputs.some(o=>o.name===name))continue;
        let subtitle=clipSrt(cues,clip.start,clip.duration);
        const title=[job.label.replaceAll('{part}',String(clip.part)),job.caption].filter(Boolean).join('\n').replace(/[{}<>]/g,'');
        const partCues=[...(title?[{start:clip.start,end:clip.start+clip.duration,text:title}]:[]),...cues];
        const burn=clipSrt(partCues,clip.start,clip.duration);
        await writeFile(path.join(dir,'overlay.srt'),burn);
        const filter=[`scale=${size.width}:${size.height}`];
        if(burn)filter.push(`subtitles=overlay.srt:force_style='FontName=Arial,FontSize=22,Alignment=${job.position==='top'?8:2},MarginV=24,Outline=1'`);
        const partial=name+'.partial.mp4';
        try{
          const encoding=job.mode==='copy'?['-c','copy','-avoid_negative_ts','make_zero']:['-vf',filter.join(','),'-c:v','libx264','-preset','veryfast','-crf','16','-pix_fmt','yuv420p','-c:a','aac','-b:a','320k'];
          await run(ffmpeg,['-hide_banner','-v','error','-y','-ss',String(clip.start),'-i',source,'-t',String(clip.duration),'-map','0:v:0','-map','0:a:0?',...encoding,'-movflags','+faststart',partial],{signal,cwd:dir});
          const meta=await probe(path.join(dir,partial));
          if(job.mode!=='copy'&&(meta.width!==size.width||meta.height!==size.height))throw new Error('Output dimensions did not match the requested size.');
          await rename(path.join(dir,partial),path.join(dir,name));
          const srtName=name.replace('.mp4','.srt');if(subtitle)await writeFile(path.join(dir,srtName),subtitle);
          job.outputs.push({name,duration:meta.duration,width:meta.width,height:meta.height,subtitle:subtitle?srtName:undefined});job.progress=job.outputs.length/job.clips.length;await save('jobs',job);
        }finally{await rm(path.join(dir,partial),{force:true});}
      }
    }
    if(job.merge){
      const parts=job.outputs.filter(o=>o.name.startsWith('Part-'));
      await writeFile(path.join(dir,'concat.txt'),parts.map(o=>`file '${o.name}'`).join('\n'));
      const inputs=parts.flatMap(o=>['-i',o.name]);
      const filters=parts.flatMap((o,i)=>[`[${i}:v]trim=duration=${job.clips[i].duration},setpts=PTS-STARTPTS[v${i}]`,...(m.hasAudio?[`[${i}:a]atrim=duration=${job.clips[i].duration},asetpts=PTS-STARTPTS,apad=whole_dur=${job.clips[i].duration}[a${i}]`]:[])]);
      filters.push(parts.map((o,i)=>`[v${i}]${m.hasAudio?`[a${i}]`:''}`).join('')+`concat=n=${parts.length}:v=1:a=${m.hasAudio?1:0}[v]${m.hasAudio?'[a]':''}`);
      await run(ffmpeg,['-v','error','-y',...inputs,'-filter_complex',filters.join(';'),'-map','[v]',...(m.hasAudio?['-map','[a]']:[]),'-c:v','libx264','-preset','veryfast','-crf','16','-pix_fmt','yuv420p','-c:a','aac','-b:a','320k','-movflags','+faststart','Merged.partial.mp4'],{signal,cwd:dir});
      await rename(path.join(dir,'Merged.partial.mp4'),path.join(dir,'Merged.mp4'));
      let offset=0;const mergedCues=[];
      for(let i=0;i<job.clips.length;i++){const c=job.clips[i];for(const cue of parseSrt(clipSrt(parseSrt(job.srt),c.start,c.duration)))mergedCues.push({...cue,start:cue.start+offset,end:cue.end+offset});offset+=c.duration;}
      const subtitle=mergedCues.length?'Merged.srt':undefined;
      if(subtitle)await writeFile(path.join(dir,subtitle),clipSrt(mergedCues,0,offset));
      const meta=await probe(path.join(dir,'Merged.mp4'));
      job.outputs=[{name:'Merged.mp4',duration:meta.duration,width:meta.width,height:meta.height,subtitle}];
      for(const o of parts){await rm(path.join(dir,o.name),{force:true});if(o.subtitle)await rm(path.join(dir,o.subtitle),{force:true});}
      await rm(path.join(dir,'concat.txt'),{force:true});
    }
    job.status='completed';job.progress=1;job.message='Export complete.';
  }catch(e){job.status=active.controller.signal.aborted?'paused':'failed';job.message=job.status==='paused'?'Paused. Finished clips are safe.':String(e.message).slice(-3000);}
  finally{await rm(path.join(dir,'overlay.srt'),{force:true});await save('jobs',job);active=null;void pump();}
}
const server=createHttpServer(app);
if(process.argv.includes('--dev')){const {createServer}=await import('vite');const vite=await createServer({root,server:{middlewareMode:true,hmr:{server}},appType:'spa'});app.use(vite.middlewares);}
else {app.use(express.static(path.join(root,'dist')));app.get('/{*path}',(req,res)=>res.sendFile(path.join(root,'dist','index.html')));}
app.use((err,req,res,next)=>{console.error(err.message);if(!res.headersSent)res.status(err.status||400).json({error:err.status===404?'Not found':err.message});});
server.listen(port,'127.0.0.1',()=>console.log(`Clipper Studio: ${origin}\nLocal media and projects: ${data}\nTemporary previews: ${previews}`));
server.requestTimeout=0;
server.on('error',error=>{console.error(error.code==='EADDRINUSE'?`Port ${port} is already in use. Open ${origin} if Clipper is running, or set PORT to another number.`:error.message);process.exit(1);});
async function expirePreviews(){
  for(const j of await list('jobs'))if(j.temporary&&j.createdAt<Date.now()-86400000&&!['running','queued','expired'].includes(j.status)&&active?.id!==j.id){
    await rm(exportDir(j),{recursive:true,force:true});j.status='expired';j.outputs=[];j.message='Temporary preview expired. Edit settings and render again.';await save('jobs',j);
  }
}
void expirePreviews().catch(console.error);
setInterval(()=>void expirePreviews().catch(console.error),600000).unref();
