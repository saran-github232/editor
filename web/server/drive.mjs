import {randomBytes} from 'node:crypto';
import {stat} from 'node:fs/promises';
import {createReadStream} from 'node:fs';
import path from 'node:path';

export function driveFolder(value){
  const text=String(value||'').trim();
  if(/^[\w-]{10,}$/.test(text))return text;
  let url;try{url=new URL(text);}catch{throw new Error('Enter a Google Drive folder link.');}
  if(url.hostname!=='drive.google.com')throw new Error('Use a drive.google.com folder link.');
  const id=url.pathname.match(/\/folders\/([\w-]+)/)?.[1];
  if(!id)throw new Error('Use a folder link, not a link to a video.');return id;
}
export function driveRoutes(app,{origin,read,exportDir}){
  const client=process.env.GOOGLE_CLIENT_ID,secret=process.env.GOOGLE_CLIENT_SECRET;
  const redirect=origin+'/oauth/drive/callback';let pending,credentials;
  async function accessToken(){
    if(!credentials)throw new Error('Connect Google Drive first.');
    if(Date.now()<credentials.expiresAt)return credentials.access_token;
    if(!credentials.refresh_token)throw new Error('Google session expired. Connect Google Drive again.');
    const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',body:new URLSearchParams({client_id:client,client_secret:secret,grant_type:'refresh_token',refresh_token:credentials.refresh_token}),signal:AbortSignal.timeout(30000)});
    if(!response.ok)throw new Error('Google authorization expired. Reconnect Drive.');
    const token=await response.json();credentials={...credentials,...token,expiresAt:Date.now()+token.expires_in*1000-60000};return token.access_token;
  }
  app.get('/api/drive',(req,res)=>res.json({configured:!!(client&&secret),connected:!!credentials,redirectUri:redirect}));
  app.post('/api/drive/connect',(req,res)=>{
    if(!client||!secret)throw new Error('Set GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in web/.env, then restart.');
    pending={state:randomBytes(32).toString('hex'),expires:Date.now()+600000};
    const params=new URLSearchParams({client_id:client,redirect_uri:redirect,response_type:'code',scope:'https://www.googleapis.com/auth/drive',access_type:'offline',prompt:'consent',state:pending.state});
    res.json({url:'https://accounts.google.com/o/oauth2/v2/auth?'+params});
  });
  app.get('/oauth/drive/callback',async(req,res)=>{
    if(!pending||req.query.state!==pending.state||Date.now()>pending.expires)return res.status(400).send('Authorization expired. Connect Drive again.');pending=null;
    if(!req.query.code)return res.status(400).send('Google authorization was cancelled.');
    const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',body:new URLSearchParams({client_id:client,client_secret:secret,redirect_uri:redirect,grant_type:'authorization_code',code:String(req.query.code)}),signal:AbortSignal.timeout(30000)});
    if(!response.ok)return res.status(400).send('Google authorization failed. Check OAuth settings.');
    const token=await response.json();credentials={...token,expiresAt:Date.now()+token.expires_in*1000-60000};
    res.type('html').send('<h1>Google Drive connected</h1><p>You can close this tab and return to Clipper.</p>');
  });
  app.post('/api/drive/disconnect',(req,res)=>{credentials=null;res.json({ok:true});});
  app.post('/api/jobs/:id/drive',async(req,res)=>{
    const job=await read('jobs',req.params.id),output=job.outputs.find(o=>o.name===req.body.name);
    if(!output)throw new Error('Choose a finished clip.');
    const folder=driveFolder(req.body.folder),token=await accessToken(),headers={Authorization:'Bearer '+token};
    const folderResponse=await fetch(`https://www.googleapis.com/drive/v3/files/${folder}?fields=mimeType,capabilities(canAddChildren)&supportsAllDrives=true`,{headers,signal:AbortSignal.timeout(30000)});
    if(!folderResponse.ok)throw new Error('Cannot access this folder. Use a folder accessible to this app and Google account.');
    const info=await folderResponse.json();if(info.mimeType!=='application/vnd.google-apps.folder'||!info.capabilities?.canAddChildren)throw new Error('The connected account cannot add files to this folder.');
    const files=[];
    for(const name of [output.name,output.subtitle].filter(Boolean)){
      const file=path.join(exportDir(job),name),size=(await stat(file)).size,mime=name.endsWith('.srt')?'application/x-subrip':'video/mp4';
      const start=await fetch('https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true&fields=id,name,webViewLink',{
        method:'POST',headers:{...headers,'Content-Type':'application/json','X-Upload-Content-Type':mime,'X-Upload-Content-Length':String(size)},body:JSON.stringify({name,parents:[folder]}),signal:AbortSignal.timeout(30000)});
      if(!start.ok)throw new Error(`Drive upload could not start (${start.status}). ${files.length?'The video may already be uploaded; retrying can create a duplicate.':''}`);
      const location=start.headers.get('location');const destination=new URL(location);
      if(destination.protocol!=='https:'||destination.hostname!=='www.googleapis.com')throw new Error('Invalid Drive upload destination.');
      const uploaded=await fetch(destination,{method:'PUT',headers:{...headers,'Content-Type':mime,'Content-Length':String(size)},body:createReadStream(file),duplex:'half',signal:AbortSignal.timeout(3600000)});
      if(!uploaded.ok)throw new Error(`Drive upload failed (${uploaded.status}). Check Drive before retrying to avoid duplicates.`);
      files.push(await uploaded.json());
    }
    res.json({files});
  });
}
