import {FRAME_PRESETS} from '../shared/frame-presets.mjs';
export const DEFAULT_PREFERENCES={frame:'auto',fps:30,background:'#000000',interval:'180',resolution:'original',mode:'exact',language:'en',provider:'nvidia',autoplay:true};
export type Preferences=typeof DEFAULT_PREFERENCES;
export const PREFERENCES_KEY='clipper.preferences.v1';
export function cleanPreferences(input:any):Preferences{
  const p=input&&typeof input==='object'?input:{};
  const pick=(key:keyof Preferences,values:any[])=>values.includes(p[key])?p[key]:DEFAULT_PREFERENCES[key];
  return {frame:pick('frame',['auto',...FRAME_PRESETS.map(p=>p.id)]),fps:pick('fps',[24,25,30,50,60]),background:typeof p.background==='string'&&/^#[0-9a-f]{6}$/i.test(p.background)?p.background:DEFAULT_PREFERENCES.background,interval:pick('interval',['60','90','120','150','180']),resolution:p.mode==='copy'?'original':pick('resolution',['original','720','1080','2160']),mode:pick('mode',['exact','copy']),language:pick('language',['en','hi']),provider:pick('provider',['nvidia','gemini']),autoplay:typeof p.autoplay==='boolean'?p.autoplay:true};
}
export function readPreferences():Preferences{try{return cleanPreferences(JSON.parse(localStorage.getItem(PREFERENCES_KEY)||'null'));}catch{return {...DEFAULT_PREFERENCES};}}
export function savePreferences(value:Preferences){const clean=cleanPreferences(value);localStorage.setItem(PREFERENCES_KEY,JSON.stringify(clean));return clean;}
