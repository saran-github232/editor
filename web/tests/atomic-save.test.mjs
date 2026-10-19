import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,writeFile,readFile,rename,readdir,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {saveJson} from '../server/atomic-save.mjs';

test('JSON save retries transient locks without removing the previous value',async()=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'clipper-save-')),target=path.join(dir,'state.json');
  try{
    await writeFile(target,'{"old":true}');let attempts=0;const waits=[];
    await saveJson(target,{saved:true},{replace:async(from,to)=>{
      assert.equal(await readFile(to,'utf8'),'{"old":true}');
      if(attempts++<2)throw Object.assign(new Error('locked'),{code:'EPERM'});
      await rename(from,to);
    },pause:async ms=>{waits.push(ms);}});
    assert.equal(await readFile(target,'utf8'),'{"saved":true}');assert.deepEqual(waits,[20,40]);assert.deepEqual(await readdir(dir),['state.json']);
  }finally{await rm(dir,{recursive:true,force:true});}
});

test('persistent save locks stop after bounded retries and retain the previous JSON',async()=>{
  const dir=await mkdtemp(path.join(os.tmpdir(),'clipper-save-')),target=path.join(dir,'state.json');
  try{
    await writeFile(target,'{"old":true}');let attempts=0;
    await assert.rejects(saveJson(target,{saved:true},{replace:async()=>{attempts++;throw Object.assign(new Error('locked'),{code:'EPERM'});},pause:async()=>{}}),{code:'EPERM'});
    assert.equal(attempts,7);assert.equal(await readFile(target,'utf8'),'{"old":true}');assert.deepEqual(await readdir(dir),['state.json']);
  }finally{await rm(dir,{recursive:true,force:true});}
});
