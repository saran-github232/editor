import {test,before,after} from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {request as httpRequest} from 'node:http';
import {mkdir,readFile} from 'node:fs/promises';
import path from 'node:path';
import {ffmpeg,ffprobe,run,probe} from '../server/media.mjs';
const base='http://127.0.0.1:4318',root=path.resolve('test-results/api'),fixture=path.join(root,'fixture.mp4');let child,cookie,media;
async function request(route,method='GET',body,extra={}){return fetch(base+route,{method,headers:{cookie,...(body?{'Content-Type':'application/json'}:{}),...extra},body:body?JSON.stringify(body):undefined});}
async function completed(id){for(let i=0;i<120;i++){const jobs=await(await request('/api/jobs')).json(),job=jobs.find(j=>j.id===id);if(['completed','failed'].includes(job.status)){assert.equal(job.status,'completed',job.message);return job;}await new Promise(r=>setTimeout(r,500));}throw new Error('Job timed out');}
before(async()=>{
 await mkdir(root,{recursive:true});
 await run(ffmpeg,['-v','error','-y','-f','lavfi','-i','testsrc2=size=320x180:rate=12','-f','lavfi','-i','sine=frequency=440:sample_rate=44100','-t','125','-c:v','libx264','-preset','ultrafast','-g','24','-c:a','aac',fixture]);
 child=spawn(process.execPath,['server/index.mjs'],{env:{...process.env,PORT:'4318',CLIPPER_DATA_DIR:path.join(root,'.data'),CLIPPER_PREVIEW_DIR:path.join(root,'previews')},windowsHide:true,stdio:'pipe'});
 let logs='';child.stderr.on('data',c=>logs+=c);child.on('exit',code=>{if(code)console.error('Test server exited:',code,logs);});
 for(let i=0;i<60;i++){try{const response=await fetch(base);cookie=response.headers.get('set-cookie')?.split(';')[0];if(cookie)break;}catch{}await new Promise(r=>setTimeout(r,250));}assert.ok(cookie,'Server startup failed: '+logs);
 const bytes=await readFile(fixture);const res=await fetch(base+'/api/media',{method:'POST',headers:{cookie,'X-File-Name':'fixture.mp4'},body:bytes});assert.equal(res.status,201,await res.clone().text());media=await res.json();
});
after(()=>child?.kill());
test('requires local session and rejects foreign origins and hosts',async()=>{
 assert.equal((await fetch(base+'/api/media')).status,401);
 assert.equal((await request('/api/media','GET',null,{Origin:'https://example.com'})).status,403);
 const hostileHost=await new Promise((resolve,reject)=>{const r=httpRequest(base+'/api/status',{headers:{Host:'evil.example',Cookie:cookie}},s=>{s.resume();resolve(s.statusCode);});r.on('error',reject);r.end();});
 assert.equal(hostileHost,403);
 assert.equal((await request('/api/media/not-a-uuid')).status,400);
});
test('media endpoint supports bounded range reads',async()=>{const response=await request(`/api/media/${media.id}/content`,'GET',null,{Range:'bytes=0-127'});assert.equal(response.status,206);assert.equal((await response.arrayBuffer()).byteLength,128);});
test('exact export produces ordered clips, original dimensions and rebased subtitle sidecars',async()=>{
 const response=await request('/api/jobs','POST',{mediaId:media.id,interval:60,resolution:'original',mode:'exact',caption:'Test caption',label:'Part {part}',srt:'1\n00:00:59,000 --> 00:01:02,000\nCrossing boundary'});assert.equal(response.status,201,await response.clone().text());const job=await completed((await response.json()).id);
 assert.deepEqual(job.outputs.map(o=>o.name),['Part-0001.mp4','Part-0002.mp4','Part-0003.mp4']);
 for(const [i,o]of job.outputs.entries()){assert.equal(o.width,320);assert.equal(o.height,180);assert.ok(Math.abs(o.duration-(i===2?5:60))<.25);}
 const srt=await(await request(`/api/jobs/${job.id}/files/Part-0002.srt`)).text();assert.match(srt,/00:00:00,000 --> 00:00:02,000/);
 assert.equal((await request(`/api/jobs/${job.id}/files/secret.txt`)).status,404);
});
test('copy export conserves every compressed video packet in order',async()=>{
 const response=await request('/api/jobs','POST',{mediaId:media.id,interval:60,resolution:'original',mode:'copy'});const job=await completed((await response.json()).id);
 async function hashes(file){const text=await run(ffprobe,['-v','error','-select_streams','v:0','-show_packets','-show_data_hash','sha256','-show_entries','packet=data_hash','-of','json',file]);return JSON.parse(text).packets.map(p=>p.data_hash);}
 const original=await hashes(fixture),parts=[];for(const o of job.outputs)parts.push(...await hashes(path.join(root,'previews',job.id,o.name)));assert.deepEqual(parts,original);
});
test('persistent project JSON survives a round trip',async()=>{const id=crypto.randomUUID(),p={id,name:'Saved test',updatedAt:Date.now(),data:{scenes:[]}};assert.equal((await request('/api/projects/'+id,'PUT',p)).status,200);assert.deepEqual(await(await request('/api/projects/'+id)).json(),p);});
test('upload rejects footage over three hours',async()=>{
 const longFile=path.join(root,'long-source.mp4');
 await run(ffmpeg,['-v','error','-y','-f','lavfi','-i','color=c=navy:size=32x32:rate=1','-t','10801','-c:v','libx264','-preset','ultrafast','-g','60',longFile]);
 const upload=await fetch(base+'/api/media',{method:'POST',headers:{cookie,'X-File-Name':'long-source.mp4'},body:await readFile(longFile)});assert.equal(upload.status,400);assert.match((await upload.json()).error,/3 hours/);
});
test('custom merge is previewable, downloadable and has rebased captions in a ZIP',async()=>{
 const response=await request('/api/jobs','POST',{mediaId:media.id,ranges:[{start:60,end:63},{start:10,end:12}],merge:true,resolution:'original',mode:'exact',srt:'1\n00:01:01,000 --> 00:01:02,000\nFirst\n\n2\n00:00:10,000 --> 00:00:11,000\nSecond'});
 assert.equal(response.status,201,await response.clone().text());const job=await completed((await response.json()).id);
 assert.equal(job.temporary,true);assert.equal(job.outputs.length,1);assert.equal(job.outputs[0].name,'Merged.mp4');assert.ok(Math.abs(job.outputs[0].duration-5)<.3);
 const preview=await request(`/api/jobs/${job.id}/files/Merged.mp4?preview=1`,'GET',null,{Range:'bytes=0-127'});assert.equal(preview.status,206);assert.equal(preview.headers.get('content-disposition'),null);assert.equal((await preview.arrayBuffer()).byteLength,128);
 const download=await request(`/api/jobs/${job.id}/files/Merged.mp4`);assert.match(download.headers.get('content-disposition'),/attachment/);await download.arrayBuffer();
 const srt=await(await request(`/api/jobs/${job.id}/files/Merged.srt`)).text();assert.match(srt,/00:00:01,000 --> 00:00:02,000/);assert.match(srt,/Second/);
 const zip=await request(`/api/jobs/${job.id}/bundle/Merged.mp4`);assert.equal(zip.status,200);const bytes=Buffer.from(await zip.arrayBuffer());assert.equal(bytes.subarray(0,2).toString(),'PK');assert.ok(bytes.includes(Buffer.from('Merged.srt')));assert.ok(bytes.includes(Buffer.from('Merged.mp4')));
 assert.equal((await request(`/api/jobs/${job.id}`,'DELETE')).status,200);assert.equal((await request(`/api/jobs/${job.id}/files/Merged.mp4`)).status,404);
});
test('compatible proxy has playable H264 and range support',async()=>{
 let result;for(let i=0;i<120;i++){result=await(await request(`/api/media/${media.id}/preview`,'POST')).json();if(result.status!=='preparing')break;await new Promise(r=>setTimeout(r,250));}
 assert.equal(result.status,'ready',result.error);const response=await request(result.url,'GET',null,{Range:'bytes=0-127'});assert.equal(response.status,206);assert.equal((await response.arrayBuffer()).byteLength,128);
 const meta=await probe(path.join(root,'previews',media.id+'.mp4'));assert.equal(meta.codec,'h264');assert.equal(meta.pixelFormat,'yuv420p');
});
