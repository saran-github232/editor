import {copyFile, readFile, writeFile} from 'node:fs/promises';
import path from 'node:path';
import {ffmpeg, run, probe} from './media.mjs';

export function validateLayers(layers, duration) {
  if (!Array.isArray(layers) || !layers.length || layers.length > 20) throw new Error('Add between 1 and 20 text layers.');
  return layers.map(l => {
    if (!Number.isFinite(l.start) || !Number.isFinite(l.end) || l.start < 0 || l.end <= l.start || l.end > duration + .001) throw new Error('Layer times must be inside the finished clip.');
    if (typeof l.png !== 'string' || !/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(l.png) || l.png.length > 1_500_000) throw new Error('Invalid text image.');
    const bytes = Buffer.from(l.png.split(',')[1], 'base64');
    if (bytes.length < 24 || bytes.subarray(0,8).toString('hex') !== '89504e470d0a1a0a' || bytes.readUInt32BE(16) > 4096 || bytes.readUInt32BE(20) > 4096) throw new Error('Text image is too large or invalid.');
    return {start:l.start,end:l.end,png:l.png};
  });
}

export async function prepareEdit(dir, source, layers, subtitle) {
  await copyFile(source, path.join(dir,'input.mp4'));
  await writeFile(path.join(dir,'layers.json'),JSON.stringify(layers));
  if (subtitle) await copyFile(subtitle,path.join(dir,'Final.srt'));
}

export async function renderEdit(dir, signal) {
  const layers=JSON.parse(await readFile(path.join(dir,'layers.json'),'utf8'));
  const meta=await probe(path.join(dir,'input.mp4'));
  const args=['-v','error','-y','-i','input.mp4'];
  for (const [i,l] of layers.entries()) {
    await writeFile(path.join(dir,`text-${i}.png`),Buffer.from(l.png.split(',')[1],'base64'));
    args.push('-i',`text-${i}.png`);
  }
  const filters=layers.flatMap((l,i)=>[
    `[${i+1}:v]scale=${meta.width}:${meta.height}[text${i}]`,
    `[${i===0?'0:v':`v${i-1}`}][text${i}]overlay=0:0:enable='gte(t,${l.start})*lt(t,${l.end})'[v${i}]`
  ]);
  args.push('-filter_complex',filters.join(';'),'-map',`[v${layers.length-1}]`,'-map','0:a:0?','-c:v','libx264','-preset','veryfast','-crf','16','-pix_fmt','yuv420p','-c:a','copy','-t',String(meta.duration),'-movflags','+faststart','Final.mp4');
  await run(ffmpeg,args,{cwd:dir,signal});
  return probe(path.join(dir,'Final.mp4'));
}
