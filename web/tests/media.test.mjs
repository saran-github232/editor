import {test} from 'node:test';
import assert from 'node:assert/strict';
import {planClips,dimensions,parseSrt,clipSrt,planRanges} from '../server/media.mjs';
test('Three-hour input is covered exactly once including final remainder',()=>{
 for(const interval of [60,90,120,150,180]){const clips=planClips(10800,interval);assert.equal(clips.reduce((n,c)=>n+c.duration,0),10800);clips.forEach((c,i)=>{assert.equal(c.part,i+1);if(i)assert.equal(c.start,clips[i-1].start+clips[i-1].duration);});}
});
test('rejects invalid or unbounded clip plans',()=>{for(const n of [NaN,0,-2,Infinity,90000])assert.throws(()=>planClips(n,60));assert.throws(()=>planClips(600,1));});
test('resolution preserves aspect ratio and never upscales',()=>{assert.deepEqual(dimensions(3840,2160,'1080'),{width:1920,height:1080});assert.deepEqual(dimensions(720,1280,'2160'),{width:720,height:1280});assert.deepEqual(dimensions(1920,1080,'original'),{width:1920,height:1080});});
test('subtitles crossing boundaries appear with rebased timestamps',()=>{const cues=parseSrt('1\n00:00:59,000 --> 00:01:02,000\nHello');assert.match(clipSrt(cues,0,60),/00:00:59,000 --> 00:01:00,000/);assert.match(clipSrt(cues,60,60),/00:00:00,000 --> 00:00:02,000/);assert.equal(clipSrt(cues,120,60),'');});
test('malformed subtitle timing is rejected',()=>{assert.throws(()=>parseSrt('1\n00:00:02,000 --> 00:00:01,000\nBad'));assert.throws(()=>parseSrt('not subtitles'));});

test('manual ranges support hours, preserve order, and limit each merged clip',()=>{
 assert.deepEqual(planRanges(10800,[{start:3750,end:7280}]),[{part:1,start:3750,duration:3530}]);
 assert.equal(planRanges(10800,[{start:600,end:780},{start:0,end:180}],true).reduce((n,c)=>n+c.duration,0),360);
 for(const ranges of [[{start:-1,end:2}],[{start:2,end:1}],[{start:0,end:10801}],[{start:NaN,end:20}]])assert.throws(()=>planRanges(10800,ranges));
 assert.throws(()=>planRanges(10800,[{start:0,end:181},{start:500,end:600}],true));
 assert.throws(()=>planRanges(10800,[{start:0,end:10}],true));
});
