import {frameSettings} from '../shared/frame-presets.mjs';
import {copyFile, readFile, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {ffmpeg, run, probe} from './media.mjs';

export function validateLayers(layers, duration, allowEmpty=false) {
  if (!Array.isArray(layers) || (!allowEmpty && !layers.length) || layers.length > 20) throw new Error('Add between 1 and 20 text layers.');
  return layers.map(l => {
    if (!Number.isFinite(l.start) || !Number.isFinite(l.end) || l.start < 0 || l.end <= l.start || l.end > duration + .001) throw new Error('Layer times must be inside the finished clip.');
    if (typeof l.png !== 'string' || !/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(l.png) || l.png.length > 1_500_000) throw new Error('Invalid text image.');
    const bytes = Buffer.from(l.png.split(',')[1], 'base64');
    if (bytes.length < 24 || bytes.subarray(0,8).toString('hex') !== '89504e470d0a1a0a' || bytes.readUInt32BE(16) > 4096 || bytes.readUInt32BE(20) > 4096) throw new Error('Text image is too large or invalid.');
    return {start:l.start,end:l.end,png:l.png};
  });
}

export async function prepareEdit(dir, source, layers, subtitle, frame) {
  await copyFile(source, path.join(dir,'input.mp4'));
  await writeFile(path.join(dir,'frame.json'),JSON.stringify(frame||frameSettings()));
  await writeFile(path.join(dir,'layers.json'),JSON.stringify(layers));
  if (subtitle) await copyFile(subtitle,path.join(dir,'Final.srt'));
}

export async function renderEdit(dir, signal) {
  const layers=JSON.parse(await readFile(path.join(dir,'layers.json'),'utf8'));
  const meta=await probe(path.join(dir,'input.mp4'));
  const frame=frameSettings(...await readFile(path.join(dir,'frame.json'),'utf8').then(s=>{const f=JSON.parse(s);return [f.id,f.fit];}).catch(e=>{if(e.code==='ENOENT')return [];throw e;}));
  const width=frame.width||meta.width,height=frame.height||meta.height;
  const args=['-v','error','-y','-i','input.mp4'];
  for (const [i,l] of layers.entries()) {
    await writeFile(path.join(dir,`text-${i}.png`),Buffer.from(l.png.split(',')[1],'base64'));
    args.push('-i',`text-${i}.png`);
  }
  const base=frame.id==='original'?'setsar=1':frame.fit==='cover'?`scale=${width}:${height}:force_original_aspect_ratio=increase,crop=${width}:${height},setsar=1`:`scale=${width}:${height}:force_original_aspect_ratio=decrease,pad=${width}:${height}:(ow-iw)/2:(oh-ih)/2:black,setsar=1`;
  const filters=[`[0:v]${base}[base]`,...layers.flatMap((l,i)=>[
    `[${i+1}:v]scale=${width}:${height}[text${i}]`,
    `[${i===0?'base':`v${i-1}`}][text${i}]overlay=0:0:enable='gte(t,${l.start})*lt(t,${l.end})'[v${i}]`
  ])];
  args.push('-filter_complex',filters.join(';'),'-map',layers.length?`[v${layers.length-1}]`:'[base]','-map','0:a:0?','-c:v','libx264','-preset','veryfast','-crf','16','-pix_fmt','yuv420p','-c:a','copy','-t',String(meta.duration),'-movflags','+faststart','Final.mp4');
  await run(ffmpeg,args,{cwd:dir,signal});
  return probe(path.join(dir,'Final.mp4'));
}
