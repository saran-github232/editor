// Starting layouts, not a guarantee of acceptance by every publishing surface.
export const FRAME_PRESETS = [
  {id:'16:9',width:1920,height:1080,title:'YouTube landscape',description:'Widescreen videos, tutorials and standard YouTube uploads.'},
  {id:'9:16',width:1080,height:1920,title:'Reels, Shorts & Stories',description:'Full-height vertical video for Instagram Reels, YouTube Shorts and Stories.'},
  {id:'1:1',width:1080,height:1080,title:'Square posts',description:'Square Instagram and Facebook feed layouts. 9:9 is the same shape as 1:1.'},
  {id:'4:5',width:1080,height:1350,title:'Portrait feed posts',description:'A taller Instagram or Facebook feed layout. Choose 9:16 for full-screen Reels.'},
  {id:'3:4',width:1080,height:1440,title:'Tall portrait',description:'Portrait compositions and photo-style layouts. Check the destination preview for video cropping.'},
  {id:'4:3',width:1440,height:1080,title:'Classic landscape',description:'Older footage, presentations and a traditional TV frame.'},
  {id:'3:2',width:1620,height:1080,title:'Landscape photo layout',description:'Photo-based videos and moderately wide compositions.'},
  {id:'2:3',width:1080,height:1620,title:'Portrait photo layout',description:'Tall photo-based videos and poster-style compositions.'},
  {id:'21:9',width:2520,height:1080,title:'Ultrawide',description:'Cinematic compositions and ultrawide screens. Expect padding on many phones.'},
  {id:'1.91:1',width:1910,height:1000,title:'Wide social layout',description:'Wide feed and link-style compositions. Check the chosen placement before posting.'},
];

export function frameSettings(id='original',fit='contain') {
  const preset=FRAME_PRESETS.find(p=>p.id===id);
  if(id!=='original'&&!preset)throw new Error('Choose a supported frame ratio.');
  if(!['contain','cover'].includes(fit))throw new Error('Choose fit or fill for the video frame.');
  return {id,fit,...(preset?{width:preset.width,height:preset.height}:{})};
}
