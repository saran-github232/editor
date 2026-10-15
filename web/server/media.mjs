import { spawn } from 'node:child_process';
import ffmpegStatic from 'ffmpeg-static';
import ffprobeStatic from 'ffprobe-static';
export const ffmpeg = process.env.CLIPPER_FFMPEG || ffmpegStatic;
export const ffprobe = process.env.CLIPPER_FFPROBE || ffprobeStatic.path;
export function run(executable, args, {signal, cwd, progress}={}) {
  return new Promise((resolve,reject)=>{
    const child=spawn(executable,args,{windowsHide:true,shell:false,signal,cwd});
    let out='',err='';
    child.stdout.on('data',chunk=>{out=(out+chunk).slice(-2_000_000);progress?.(chunk.toString());});
    child.stderr.on('data',chunk=>{err=(err+chunk).slice(-6000);});
    child.on('error',reject);
    child.on('close',code=>code===0?resolve(out):reject(new Error(err || `Media process exited (${code})`)));
  });
}
export async function probe(file) {
  const data=JSON.parse(await run(ffprobe,['-v','error','-show_format','-show_streams','-of','json',file]));
  const v=data.streams.find(s=>s.codec_type==='video');
  const a=data.streams.find(s=>s.codec_type==='audio');
  const rate=v?.avg_frame_rate?.split('/').map(Number);
  return {duration:Number(data.format.duration)||0,width:v?.width,height:v?.height,pixelFormat:v?.pix_fmt,colorTransfer:v?.color_transfer,fps:rate?.[1]?rate[0]/rate[1]:30,hasAudio:!!a,codec:v?.codec_name,audioCodec:a?.codec_name};
}
export function planClips(duration, interval) {
  if(!Number.isFinite(duration)||duration<=0||duration>10800) throw new Error('Source duration must be between zero and 3 hours.');
  if(![60,90,120,150,180].includes(interval)) throw new Error('Choose a supported clip length.');
  return Array.from({length:Math.ceil(duration/interval)},(_,i)=>({part:i+1,start:i*interval,duration:Math.min(interval,duration-i*interval)}));
}
export function planRanges(duration, ranges, merge=false) {
  if(!Number.isFinite(duration)||duration<=0||duration>10800)throw new Error('Videos must be at most 3 hours.');
  if(!Array.isArray(ranges)||!ranges.length||ranges.length>100)throw new Error('Add between 1 and 100 time ranges.');
  if(merge&&ranges.length<2)throw new Error('Choose at least two ranges to merge.');
  return ranges.map((r,i)=>{
    const start=r.start,end=r.end;
    if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end>duration||end<=start)throw new Error(`Range ${i+1}: start and end must be inside the video, with end after start.`);
    if(merge&&end-start>180.001)throw new Error(`Range ${i+1}: each merged clip must be at most 3 minutes.`);
    return {part:i+1,start,duration:end-start};
  });
}
export function dimensions(width,height,resolution) {
  const ratio=resolution==='original'?1:Math.min(1,Number(resolution)/Math.min(width,height));
  return {width:Math.max(2,Math.floor(width*ratio/2)*2),height:Math.max(2,Math.floor(height*ratio/2)*2)};
}
export function stamp(seconds) {
  const ms=Math.round(seconds*1000),h=Math.floor(ms/3600000),m=Math.floor(ms/60000)%60,s=Math.floor(ms/1000)%60;
  return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')},${String(ms%1000).padStart(3,'0')}`;
}
export function parseSrt(text) {
  if(typeof text!=='string'||text.length>2_000_000) throw new Error('Subtitle file is too large.');
  if(!text.trim()) return [];
  return text.trim().replace(/\r/g,'').split(/\n\s*\n/).map(block=>{
    const lines=block.split('\n');if(/^\d+$/.test(lines[0]))lines.shift();
    const match=lines.shift()?.match(/^(\d{2,}):(\d{2}):(\d{2})[,.](\d{3})\s+-->\s+(\d{2,}):(\d{2}):(\d{2})[,.](\d{3})$/);
    if(!match||!lines.length)throw new Error('Invalid SRT. Each cue needs a timestamp and text.');
    const n=match.slice(1).map(Number),time=i=>n[i]*3600+n[i+1]*60+n[i+2]+n[i+3]/1000;
    const start=time(0),end=time(4);if(end<=start)throw new Error('Subtitle end must follow its start.');
    return {start,end,text:lines.join('\n').replace(/<[^>]*>/g,'').replace(/[{}]/g,'')};
  });
}
export function clipSrt(cues,start,duration){return cues.filter(c=>c.end>start&&c.start<start+duration).map((c,i)=>`${i+1}\n${stamp(Math.max(0,c.start-start))} --> ${stamp(Math.min(duration,c.end-start))}\n${c.text}\n`).join('\n');}
