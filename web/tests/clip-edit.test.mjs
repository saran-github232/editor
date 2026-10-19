import {test} from 'node:test';
import assert from 'node:assert/strict';
import {validateLayers} from '../server/clip-edit.mjs';
const png='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=';
test('finished clip layers reject invalid timing, payloads and oversized images',()=>{
  assert.equal(validateLayers([{start:0,end:2,png}],2).length,1);
  for(const layer of [{start:-1,end:2,png},{start:1,end:1,png},{start:0,end:3,png},{start:NaN,end:2,png},{start:0,end:2,png:'file:///secret'}])assert.throws(()=>validateLayers([layer],2));
  assert.throws(()=>validateLayers([],2));
  assert.throws(()=>validateLayers(Array(21).fill({start:0,end:2,png}),2));
  const bytes=Buffer.from(png.split(',')[1],'base64');bytes.writeUInt32BE(100000,16);
  assert.throws(()=>validateLayers([{start:0,end:2,png:'data:image/png;base64,'+bytes.toString('base64')}],2));
});
