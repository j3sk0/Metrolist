package com.metrolist.music.kidj

/** Guest page served by [WishServer]. Plain HTML/JS without the dollar sign so it can live in a raw string. */
internal const val WISH_PAGE_HTML = """<!doctype html><html lang="de"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Musikwunsch</title>
<style>
:root{color-scheme:dark}body{margin:0;font:16px system-ui,sans-serif;background:#111;color:#eee}
main{max-width:520px;margin:auto;padding:16px}h1{font-size:22px}h2{font-size:17px;margin:22px 0 4px}
.row{display:flex;gap:8px}input[type=text]{flex:1;min-width:0;padding:12px;border-radius:10px;border:1px solid #444;background:#222;color:#eee;font-size:16px}
button,.btn{padding:12px 14px;border:0;border-radius:10px;background:#7c4dff;color:#fff;font-size:16px;cursor:pointer}
.btn.sec,button.sec{background:#2a2a2a;border:1px solid #444}
#msg{min-height:24px;margin:10px 0;color:#b9a6ff}
ul{list-style:none;padding:0;margin:0}
li{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #222}
li img{width:48px;height:48px;border-radius:6px;object-fit:cover;background:#222;flex:none}
li div{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
li small{color:#999;display:block;overflow:hidden;text-overflow:ellipsis}
.trend{color:#ffb74d}.up{color:#7bd88f}.down{color:#ff8a80}
button.rate{background:#2a2a2a;border:1px solid #444;padding:10px 12px}button.rate.on{background:#7c4dff;border-color:#7c4dff}
li{transition:transform .15s}.hint{color:#999;font-size:14px;margin:0 0 4px}#sug{background:#1a1a1a;border-radius:10px;padding:0 10px;margin-top:8px}
#sug:empty{display:none}#sugHead{color:#b9a6ff;padding:8px 0 0;font-weight:600}
.empty{color:#777;padding:8px 0}
</style></head><body><main>
<h1>Wünsch dir einen Song</h1>
<form id="f" class="row"><input type="text" id="q" maxlength="300" placeholder="Titel, Interpret oder Link" autocomplete="off" aria-label="Titel, Interpret oder Link" required><button>Senden</button></form>
<div id="sug" aria-live="polite"></div>
<p><label class="btn sec" for="shot">Screenshot hochladen</label>
<input id="shot" type="file" accept="image/*" hidden></p>
<div id="msg" role="status"></div>
<h2>Läuft gerade</h2><ul id="now"></ul>
<h2>Als Nächstes</h2><p class="hint">Nach rechts wischen oder 👍 = gefällt mir, nach links oder 👎 = abwählen. Beliebte Songs rücken nach vorn.</p><ul id="queue"></ul>
<h2>Wünsche</h2><ul id="wishes"></ul>
</main><script>
function el(id){return document.getElementById(id)}
function say(t){el('msg').textContent=t}
async function post(url,data){
  const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams(data)});
  return (await r.json()).message}
function item(x,mode,action){
  const li=document.createElement('li');
  const img=document.createElement('img');img.alt='';if(x.thumbnail)img.src=x.thumbnail;
  const d=document.createElement('div');d.textContent=x.title;
  const s=document.createElement('small');s.textContent=x.artist||'';
  if(x.votes){const v=document.createElement('span');v.className=x.votes>0?'up':'down';
    v.textContent=(x.votes>0?' · ▲ +':' · ▼ ')+x.votes;s.append(v)}
  if(x.trend){const t=document.createElement('span');t.className='trend';t.textContent=' · '+x.trend;s.append(t)}
  d.append(s);li.append(img,d);
  if(mode=='rate'){
    li.append(rateBtn(x,-1,'👎','Abwählen'),rateBtn(x,1,'👍','Gefällt mir'));swipe(li,x)}
  else if(mode){const b=document.createElement('button');b.type='button';b.textContent=mode;
    b.setAttribute('aria-label',mode+' '+x.title);b.onclick=function(){action(x)};li.append(b)}
  return li}
function rateBtn(x,v,sign,label){
  const b=document.createElement('button');b.type='button';b.textContent=sign;
  b.className='rate'+(x.mine==v?' on':'');b.setAttribute('aria-label',label+': '+x.title);
  b.setAttribute('aria-pressed',x.mine==v);b.onclick=function(){vote(x,v)};return b}
function swipe(li,x){
  let x0=null,dx=0;
  li.addEventListener('touchstart',function(e){x0=e.touches[0].clientX;dx=0},{passive:true});
  li.addEventListener('touchmove',function(e){if(x0===null)return;dx=e.touches[0].clientX-x0;
    if(Math.abs(dx)>12){li.style.transform='translateX('+dx+'px)';li.style.background=dx>0?'#1b3a22':'#3a1b1b'}},{passive:true});
  li.addEventListener('touchend',function(){const go=Math.abs(dx)>90;li.style.transform='';li.style.background='';
    x0=null;if(go)vote(x,dx>0?1:-1)})}
function fill(ul,list,label,action,empty){
  ul.replaceChildren();
  if(!list.length){const li=document.createElement('li');li.className='empty';li.textContent=empty;ul.append(li);return}
  for(const x of list)ul.append(item(x,label,action))}
async function vote(x,v){say(await post('/api/vote',{id:x.id,v:v}));load()}
async function pick(x){say(await post('/api/wish',{id:x.id}));el('sug').replaceChildren();el('q').value='';load()}
async function load(){
  try{const s=await (await fetch('/api/state')).json();
    fill(el('now'),s.now?[s.now]:[],null,null,'Gerade läuft nichts');
    fill(el('queue'),s.queue,'rate',null,'Warteschlange ist leer');
    fill(el('wishes'),s.wishes,'rate',null,'Noch keine Wünsche')}catch(e){}}
function showSug(r){
  const box=el('sug');box.replaceChildren();
  if(!r.items||!r.items.length)return;
  if(r.artist){const h=document.createElement('div');h.id='sugHead';h.textContent=r.artist+' – beste Hits';box.append(h)}
  const ul=document.createElement('ul');for(const x of r.items)ul.append(item(x,'Wünschen',pick));box.append(ul)}
let timer=0,seq=0;
el('q').oninput=function(){
  clearTimeout(timer);const q=el('q').value.trim();
  if(q.length<3||/^https?:/i.test(q)){el('sug').replaceChildren();return}
  timer=setTimeout(async function(){const my=++seq;
    try{const r=await (await fetch('/api/suggest?q='+encodeURIComponent(q))).json();if(my==seq)showSug(r)}catch(e){}},400)};
el('f').onsubmit=async function(e){
  e.preventDefault();clearTimeout(timer);seq++;say('Suche…');
  say(await post('/api/wish',{q:el('q').value}));el('q').value='';el('sug').replaceChildren();load()};
el('shot').onchange=async function(){
  const file=el('shot').files[0];if(!file)return;say('Screenshot wird ausgewertet…');
  try{
    const bmp=await createImageBitmap(file);const k=Math.min(1,1600/Math.max(bmp.width,bmp.height));
    const c=document.createElement('canvas');c.width=Math.round(bmp.width*k);c.height=Math.round(bmp.height*k);
    c.getContext('2d').drawImage(bmp,0,0,c.width,c.height);
    const blob=await new Promise(function(ok){c.toBlob(ok,'image/jpeg',0.8)});
    const r=await (await fetch('/api/screenshot',{method:'POST',headers:{'Content-Type':'image/jpeg'},body:blob})).json();
    if(r.items&&r.items.length){say('Erkannt: '+r.query+' – tippe auf den richtigen Titel');showSug(r)}
    else say(r.query?'Nichts gefunden zu: '+r.query:'Auf dem Bild konnte ich keinen Titel lesen.')
  }catch(e){say('Das Bild konnte nicht gesendet werden.')}
  el('shot').value=''};
load();setInterval(load,8000);
</script></body></html>
"""
