import {writeFile,rename,rm} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

// Windows readers and antivirus can briefly lock a destination. Keep the old
// complete JSON until replacement succeeds; never delete it to force a rename.
export async function saveJson(target,value,{replace=rename,pause=delay}={}){
  const temp=target+'.'+randomUUID()+'.tmp';
  try{
    await writeFile(temp,JSON.stringify(value));
    for(let attempt=0;;attempt++){
      try{await replace(temp,target);return;}
      catch(error){
        if(!['EPERM','EACCES','EBUSY'].includes(error.code)||attempt>=6)throw error;
        await pause(20*2**attempt);
      }
    }
  }finally{await rm(temp,{force:true}).catch(()=>{});}
}
