import {readPreferences} from './preferences';
import {FRAME_PRESETS} from '../shared/frame-presets.mjs';
import { createDefaultAdapters } from '../vendor/artcraft-video-editor/src/lib/adapters/default';
import { EditorCore } from '../vendor/artcraft-video-editor/src/lib/core';
import { buildDefaultScene } from '../vendor/artcraft-video-editor/src/lib/timeline/scenes';
import { ZERO_MEDIA_TIME } from '../vendor/artcraft-video-editor/src/lib/wasm';
import { serializeProjectDocument } from '../vendor/artcraft-video-editor/src/lib/services/storage/serialization';
import type { EditorProject, MediaHandle } from '../vendor/artcraft-video-editor/src/lib/adapters/types';

export async function api(url:string, options:RequestInit={}) {
  const response=await fetch('/api'+url,options);
  if(!response.headers.get('content-type')?.includes('application/json'))throw new Error(`Server returned an unexpected response (${response.status}). Restart Clipper and reopen this page.`);
  const data=await response.json();if(!response.ok)throw new Error(data.error||'Request failed');return data;
}
export const json=(method:string,body?:unknown)=>({method,headers:{'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
export function notify(message:string,options?:{description?:string}){window.dispatchEvent(new CustomEvent('clipper-notice',{detail:[message,options?.description].filter(Boolean).join(': ')}));}
export async function upload(file:File){return api('/media',{method:'POST',headers:{'X-File-Name':encodeURIComponent(file.name),'Content-Type':'application/octet-stream'},body:file});}
export const adapters={...createDefaultAdapters(),
  exportSink:{async accept(artifact:any,options:any={}){
    if(options.saveToLibrary){options.onProgress?.({destination:'library',status:'uploading'});try{await upload(new File([artifact.blob],artifact.filename,{type:artifact.mime}));options.onProgress?.({destination:'library',status:'success'});}catch(e){options.onProgress?.({destination:'library',status:'error',error:(e as Error).message});throw e;}}
    if(options.saveToDisk!==false){const url=URL.createObjectURL(artifact.blob);const a=document.createElement('a');a.href=url;a.download=artifact.filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),30000);options.onProgress?.({destination:'disk',status:'success'});}return null;
  }},
  toast:{info:notify,success:notify,warning:notify,error:notify},
  projectStorage:{
    listProjects:()=>api('/projects'),
    async loadProject(id:string){try{return await api('/projects/'+id);}catch(e){throw e;}},
    saveProject:(project:EditorProject)=>api('/projects/'+project.id,json('PUT',project)),
    deleteProject:(id:string)=>api('/projects/'+id,json('DELETE')),
    async createProject(name:string){return createProject(name);},
  },
  mediaSource:{
    async uploadLocalFile(file:File){const m=await upload(file);return {id:m.id,kind:m.kind};},
    async resolveMedia(handle:MediaHandle){const m=await api('/media/'+handle.id);return {url:'/api/media/'+m.id+'/content',mime:m.mime,durationMs:m.duration*1000};},
    async probe(handle:MediaHandle){const m=await api('/media/'+handle.id);return {durationMs:m.duration*1000,widthPx:m.width,heightPx:m.height,mime:m.mime,fps:m.fps};},
  },
};
EditorCore.initialize({adapters});
export const editor=EditorCore.getInstance();
export async function createProject(name:string){
  const prefs=readPreferences(),frame=FRAME_PRESETS.find(p=>p.id===prefs.frame);
  const scene=buildDefaultScene({name:'Main sequence',isMain:true});
  const project={metadata:{id:crypto.randomUUID(),name:name.trim()||'Untitled sequence',duration:ZERO_MEDIA_TIME,createdAt:new Date(),updatedAt:new Date()},scenes:[scene],currentSceneId:scene.id,settings:{fps:{numerator:prefs.fps,denominator:1},canvasSize:{width:frame?.width||1920,height:frame?.height||1080},...(frame?{canvasSizeMode:'preset' as const}:{}),background:{type:'color' as const,color:prefs.background}},version:1};
  const envelope={id:project.metadata.id,name:project.metadata.name,updatedAt:Date.now(),data:serializeProjectDocument({project,media:[]})};
  await adapters.projectStorage.saveProject(envelope);return envelope;
}
