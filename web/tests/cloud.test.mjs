import {test} from 'node:test';
import assert from 'node:assert/strict';
import {withFallback,normalizeCues} from '../server/cloud-speech.mjs';
import {driveFolder} from '../server/drive.mjs';
test('quota errors switch providers, authorization errors do not',async()=>{
 const calls=[];assert.equal(await withFallback(['nvidia','gemini'],async p=>{calls.push(p);if(p==='nvidia')throw Object.assign(new Error('Quota'),{status:429});return 'ok';}),'ok');assert.deepEqual(calls,['nvidia','gemini']);
 await assert.rejects(withFallback(['nvidia','gemini'],async()=>{throw Object.assign(new Error('Unauthorized'),{status:401});}),/Unauthorized/);
});
test('speech cues discard invalid timing and clamp to audio length',()=>{
 assert.deepEqual(normalizeCues([{start:1,end:40,text:'hello'},{start:10,end:4,text:'bad'},{start:-1,end:2,text:'bad'}],30),[{start:1,end:30,text:'hello'}]);
});
test('Drive accepts folder IDs and links but rejects unrelated hosts and file links',()=>{
 assert.equal(driveFolder('https://drive.google.com/drive/folders/abc123456789?usp=sharing'),'abc123456789');
 assert.throws(()=>driveFolder('https://evil.example/drive/folders/abc123456789'));
 assert.throws(()=>driveFolder('https://drive.google.com/file/d/abc123456789/view'));
});
