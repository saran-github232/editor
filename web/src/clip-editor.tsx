import {TextStyleControls,TEXT_FONTS} from './text-style-controls';
import {FramePicker} from './frame-picker';
import {FRAME_PRESETS} from '../shared/frame-presets.mjs';
import React,{useEffect,useRef,useState} from 'react';
import {api,json} from './adapters';

type Layer={id:string,text:string,start:number,end:number,x:number,y:number,size:number,color:string,background:boolean,font?:string,bold?:boolean,italic?:boolean};
function paint(canvas:HTMLCanvasElement,layer:Layer){
  const ctx=canvas.getContext('2d')!;ctx.clearRect(0,0,canvas.width,canvas.height);
  const size=canvas.height*layer.size/100;
  const font=TEXT_FONTS.includes(layer.font||'')?layer.font:'Segoe UI';
  ctx.font=`${layer.italic?'italic ':''}${layer.bold===false?'normal':'bold'} ${size}px "${font}", "Segoe UI Emoji", sans-serif`;
  ctx.textAlign='center';ctx.textBaseline='middle';
  const lines=layer.text.split('\n'),x=canvas.width*layer.x/100,y=canvas.height*layer.y/100;
  lines.forEach((line,i)=>{const cy=y+(i-(lines.length-1)/2)*size*1.25;
    if(layer.background){ctx.fillStyle='#000b';ctx.fillRect(x-ctx.measureText(line).width/2-size*.3,cy-size*.65,ctx.measureText(line).width+size*.6,size*1.3);}
    ctx.fillStyle=layer.color;ctx.fillText(line,x,cy,canvas.width*.95);
  });
}
export function ClipEditor({output,onBack,onRendered}:{output:any,onBack:()=>void,onRendered:(o:any,j:any)=>void}){
  const key=`clipper-edit:${output.jobId}:${output.name}`;
  const [layers,setLayers]=useState<Layer[]>(()=>{try{return JSON.parse(sessionStorage.getItem(key)||'[]');}catch{return [];}});
  const [frame,setFrame]=useState(()=>{try{return JSON.parse(sessionStorage.getItem(key+':frame')||'null')||{id:'original',fit:'contain'};}catch{return {id:'original',fit:'contain'};}});
  const [current,setCurrent]=useState(0),[pending,setPending]=useState(''),[error,setError]=useState('');
  const canvas=useRef<HTMLCanvasElement>(null),video=useRef<HTMLVideoElement>(null);
  const preset=FRAME_PRESETS.find(p=>p.id===frame.id);
  const targetWidth=preset?.width||output.width,targetHeight=preset?.height||output.height;
  const scale=Math.min(1,1920/targetWidth,1920/targetHeight),width=Math.round(targetWidth*scale),height=Math.round(targetHeight*scale);
  useEffect(()=>{try{sessionStorage.setItem(key+':frame',JSON.stringify(frame));}catch{}},[frame,key]);
  useEffect(()=>{try{sessionStorage.setItem(key,JSON.stringify(layers));}catch{}},[layers,key]);
  useEffect(()=>{const target=canvas.current;if(!target)return;const ctx=target.getContext('2d')!;ctx.clearRect(0,0,width,height);const scratch=document.createElement('canvas');scratch.width=width;scratch.height=height;for(const l of layers)if(current>=l.start&&current<l.end){paint(scratch,l);ctx.drawImage(scratch,0,0);}},[layers,current,width,height]);
  useEffect(()=>{if(!pending||pending==='starting')return;let alive=true;const poll=async()=>{try{const jobs=await api('/jobs'),j=jobs.find((j:any)=>j.id===pending);if(!alive)return;if(j?.status==='completed'){setPending('');onRendered(j.outputs[0],j);}else if(j&&['failed','paused','expired'].includes(j.status)){setPending('');setError(j.message||'Render stopped. Your edits are still here.');}}catch(e){if(alive){setPending('');setError((e as Error).message);}}};void poll();const t=setInterval(()=>void poll(),1000);return()=>{alive=false;clearInterval(t);};},[pending]);
  function update(id:string,patch:Partial<Layer>){setLayers(ls=>ls.map(l=>l.id===id?{...l,...patch}:l));}
  async function render(){setError('');setPending('starting');video.current?.pause();try{
    if(layers.some(l=>!l.text.trim()||!Number.isFinite(l.start)||!Number.isFinite(l.end)||l.start<0||l.end<=l.start||l.end>output.duration))throw new Error('Every layer needs text and valid start/end times inside this clip.');
    await document.fonts.ready;
    const scratch=document.createElement('canvas');scratch.width=width;scratch.height=height;
    const images=layers.map(l=>{paint(scratch,l);return {start:l.start,end:l.end,png:scratch.toDataURL('image/png')};});
    const j=await api(`/jobs/${output.jobId}/edit`,json('POST',{name:output.name,layers:images,frame}));setPending(j.id);
  }catch(e){setPending('');setError((e as Error).message);}}
  return <div className="clip-editor"><header><div><h2>Edit finished clip</h2><p className="field-note">Text, captions and emojis use this clip's timeline, starting at 0 seconds.</p></div><button className="secondary" disabled={!!pending} onClick={onBack}>Back to review</button></header>
    <fieldset disabled={!!pending} className="edit-fields"><FramePicker value={frame.id} original={output} onChange={id=>setFrame({...frame,id})}/>{frame.id!=='original'&&<label className="frame-fit">Picture placement<select aria-label="Picture placement" value={frame.fit} onChange={e=>setFrame({...frame,fit:e.target.value})}><option value="contain">Fit entire video · black padding</option><option value="cover">Fill frame · crop from center</option></select><p>Fit preserves the whole picture. Fill removes edges; check faces, captions and titles in the preview. A larger frame may upscale the source without adding detail.</p></label>}</fieldset>
    <div className="overlay-stage" style={{aspectRatio:`${width}/${height}`,maxWidth:`min(100%, ${55*width/height}vh)`,marginInline:"auto"}}><video style={{objectFit:frame.fit}} ref={video} controls controlsList="nofullscreen" disablePictureInPicture src={`/api/jobs/${output.jobId}/files/${encodeURIComponent(output.name)}?preview=1`} onTimeUpdate={e=>setCurrent(e.currentTarget.currentTime)} onSeeked={e=>setCurrent(e.currentTarget.currentTime)} onError={()=>setError('Preview unavailable. Go back to review and rebuild it before editing.')}/><canvas ref={canvas} width={width} height={height}/></div>
    <p className="field-note"><strong>Export: {targetWidth} × {targetHeight} px.</strong> Changes to frame, fonts, colors and emojis appear here before rendering. {current.toFixed(2)} / {output.duration.toFixed(2)} seconds. Scrub the player to check timed layers. Drafts stay in this browser tab.</p>
    <fieldset disabled={!!pending} className="edit-fields"><div className="range-actions"><button className="secondary" disabled={layers.length>=20} onClick={()=>setLayers(ls=>[...ls,{id:crypto.randomUUID(),text:`Part ${ls.length+1}`,start:0,end:output.duration,x:50,y:15,size:7,color:'#ffffff',background:true}])}>Add text or emoji</button><span className="field-note">{layers.length}/20 layers · Use Choose emoji below to browse or search.</span></div>
    {layers.map((l,i)=><div className="batch-form text-layer" key={l.id}><div className="section-heading"><h3>Layer {i+1}</h3><button className="text-link" onClick={()=>setLayers(ls=>ls.filter(x=>x.id!==l.id))}>Remove layer {i+1}</button></div><TextStyleControls layer={l} index={i+1} onChange={patch=>update(l.id,patch)}/><div className="two-col"><label>Start (seconds)<input type="number" step="0.01" min="0" max={output.duration} value={l.start} onChange={e=>update(l.id,{start:e.target.valueAsNumber})}/></label><label>End (seconds)<input type="number" step="0.01" min="0" max={output.duration} value={l.end} onChange={e=>update(l.id,{end:e.target.valueAsNumber})}/></label></div><div className="two-col"><label>Horizontal position<input type="range" min="5" max="95" value={l.x} onChange={e=>update(l.id,{x:+e.target.value})}/></label><label>Vertical position<input type="range" min="5" max="95" value={l.y} onChange={e=>update(l.id,{y:+e.target.value})}/></label><label>Text size<input type="range" min="2" max="20" value={l.size} onChange={e=>update(l.id,{size:+e.target.value})}/></label></div><label><input type="checkbox" checked={l.background} onChange={e=>update(l.id,{background:e.target.checked})}/> Dark background for readability</label></div>)}
    <button className="primary" disabled={!layers.length&&frame.id==='original'} onClick={()=>void render()}>Render final clip</button></fieldset>
    {pending&&<div><p role="status">Rendering your edited clip. It will open here for review before download.</p><button className="secondary" disabled={pending==='starting'} onClick={()=>void api(`/jobs/${pending}/pause`,json('POST')).catch(e=>setError(e.message))}>Pause render</button></div>}{error&&<p className="error-text" role="alert">{error}</p>}
  </div>;
}
