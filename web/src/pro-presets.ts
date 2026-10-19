import {upsertPathKeyframe} from '../vendor/artcraft-video-editor/src/lib/animation';
import {resolveAnimationTarget} from '../vendor/artcraft-video-editor/src/lib/timeline/animation-targets';
import {mediaTime,mediaTimeFromSeconds} from '../vendor/artcraft-video-editor/src/lib/wasm';
import type {TimelineElement} from '../vendor/artcraft-video-editor/src/lib/timeline/types';

export function animate(element:TimelineElement,channels:Record<string,Array<[number,number]>>,smooth=false){
  let animations={...element.animations};
  for(const [propertyPath,keys] of Object.entries(channels)){
    const target=resolveAnimationTarget({element,path:propertyPath});
    if(!target)throw new Error(`This clip does not support ${propertyPath}.`);
    delete animations[propertyPath];
    for(const [time,value] of keys)animations=upsertPathKeyframe({animations,propertyPath,time:mediaTime({ticks:Math.round(time)}),value,interpolation:smooth?'bezier':'linear',channelLayout:target.channelLayout,coerceValue:target.coerceValue})||{};
  }
  return animations;
}

export function motionPreset(element:TimelineElement,preset:string,seconds:number,height:number,frameTicks:number){
  if(element.duration<frameTicks*3)throw new Error('This preset needs a clip at least three frames long.');
  // Finish on the last displayed frame, before the exclusive clip end.
  const end=Math.max(1,element.duration-frameTicks),ramp=Math.max(1,Math.min(mediaTimeFromSeconds({seconds}),Math.floor(end/2)));
  const number=(key:string,fallback:number)=>typeof element.params[key]==='number'?element.params[key] as number:fallback;
  if(preset==='fade')return animate(element,{opacity:[[0,0],[ramp,number('opacity',1)],[end-ramp,number('opacity',1)],[end,0]]});
  if(preset==='audio')return animate(element,{volume:[[0,-60],[ramp,number('volume',0)],[end-ramp,number('volume',0)],[end,-60]]});
  if(preset==='rise')return animate(element,{'transform.positionY':[[0,number('transform.positionY',0)+height*.12],[ramp,number('transform.positionY',0)]]},true);
  if(preset==='zoom')return animate(element,{'transform.scaleX':[[0,number('transform.scaleX',1)],[end,number('transform.scaleX',1)*1.12]],'transform.scaleY':[[0,number('transform.scaleY',1)],[end,number('transform.scaleY',1)*1.12]]},true);
  throw new Error('Unknown motion preset.');
}

export function withoutChannels(element:TimelineElement,paths:string[]){
  const animations={...element.animations};for(const path of paths)delete animations[path];return animations;
}
