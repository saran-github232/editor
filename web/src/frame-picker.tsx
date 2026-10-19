import React from 'react';
import {FRAME_PRESETS} from '../shared/frame-presets.mjs';

export function FramePicker({value,onChange,original,disabled=false}:{value:string,onChange:(id:string)=>void,original?:{width:number,height:number},disabled?:boolean}){
  const selected=FRAME_PRESETS.find(p=>p.id===value);
  return <div className="frame-picker"><label>Video frame ratio<select aria-label="Video frame ratio" value={value} disabled={disabled} onChange={e=>onChange(e.target.value)}>
    {original&&<option value="original">Original · {original.width} × {original.height}</option>}
    {!original&&!selected&&<option value="custom">Custom frame</option>}
    {FRAME_PRESETS.map(p=><option key={p.id} value={p.id}>{p.id} · {p.title}</option>)}
  </select></label><aside className="frame-advice" aria-live="polite"><strong>{selected?`${selected.id} · ${selected.title}`:'Keep your current frame'}</strong><p>{selected?.description||'Preserves the current width and height.'}</p><p><b>Width × height:</b> {selected?`${selected.width} × ${selected.height}`:original?`${original.width} × ${original.height}`:'Custom'} pixels.</p><p>Ratio describes the shape, not the video duration. Keep titles away from edges and check the platform preview before posting.</p></aside>
  <details><summary>Which ratio should I use?</summary><div className="ratio-guide">{FRAME_PRESETS.map(p=><div key={p.id}><strong>{p.id} · {p.title}</strong><span>{p.width} × {p.height} px</span><p>{p.description}</p></div>)}</div><p>1:1 and 9:9 are identical square ratios. Video posts may be shown as Reels; use 9:16 when you want full-screen vertical playback.</p><a href="https://support.google.com/youtube/answer/6375112" target="_blank" rel="noreferrer">YouTube frame guide</a> · <a href="https://www.facebook.com/business/ads/facebook-instagram-reels-ads" target="_blank" rel="noreferrer">Meta Reels guide</a></details></div>;
}
