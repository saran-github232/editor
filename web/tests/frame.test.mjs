import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdir} from 'node:fs/promises';
import path from 'node:path';
import {FRAME_PRESETS,frameSettings} from '../shared/frame-presets.mjs';
import {prepareEdit,renderEdit} from '../server/clip-edit.mjs';
import {ffmpeg,run} from '../server/media.mjs';

test('frame settings only accept known ratios and fit modes',()=>{
  assert.throws(()=>frameSettings('bad'));
  assert.throws(()=>frameSettings('9:16','stretch'));
  assert.equal(frameSettings().id,'original');
});

test('every frame preset exports correct dimensions and audio with fit and fill',async()=>{
  const root=path.resolve('test-results/frame-presets');await mkdir(root,{recursive:true});
  const source=path.join(root,'source.mp4');
  await run(ffmpeg,['-v','error','-y','-f','lavfi','-i','color=c=red:size=160x90:rate=10','-f','lavfi','-i','sine=frequency=440','-t','0.3','-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac',source]);
  for(const [i,preset] of FRAME_PRESETS.entries())for(const fit of ['contain','cover']){
    const dir=path.join(root,`${i}-${fit}`);await mkdir(dir,{recursive:true});
    await prepareEdit(dir,source,[],undefined,frameSettings(preset.id,fit));
    const meta=await renderEdit(dir);
    assert.equal(meta.width,preset.width,`${preset.id} ${fit} width`);
    assert.equal(meta.height,preset.height,`${preset.id} ${fit} height`);
    assert.equal(meta.hasAudio,true);
    if(preset.id==='9:16'){
      const stats=await run(ffmpeg,['-v','error','-i',path.join(dir,'Final.mp4'),'-vf','crop=100:100:0:0,signalstats,metadata=print:file=-','-frames:v','1','-f','null','-']);
      const brightness=Number(String(stats).match(/lavfi.signalstats.YAVG=([\d.]+)/)?.[1]);
      assert.ok(fit==='contain'?brightness<20:brightness>60,`${fit} should ${fit==='contain'?'pad':'fill'} the corner`);
    }
  }
});
