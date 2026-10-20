/* Droplet website: the real-looking phones and computers in the pictures. */
(function(){
'use strict';
let clipN=0, PFX='', FS=1, PHOTO_BASE='photos/';
const LOGO_INNER=`<path d="M81.60 40.15 Q88.14 58.24 89.52 77.43 A22.00 22.00 0 1 1 53.28 65.65 Q65.67 50.94 81.60 40.15Z" fill="url(#dg)" stroke="url(#dg)" stroke-width="3.00" stroke-linejoin="round"/><path d="M74.71 29.13 A13.00 13.00 0 0 1 93.65 35.28" fill="none" stroke="#7EF0DC" stroke-width="6.50" stroke-linecap="round" opacity="1.0"/><path d="M68.35 18.95 A25.00 25.00 0 0 1 104.78 30.79" fill="none" stroke="#7EF0DC" stroke-width="6.50" stroke-linecap="round" opacity="0.5"/><g stroke="#7EF0DC" stroke-width="5.00" stroke-linecap="round" opacity=".5"><line x1="20.00" y1="76.00" x2="34.00" y2="76.00"/><line x1="14.00" y1="90.00" x2="32.00" y2="90.00"/><line x1="22.00" y1="104.00" x2="36.00" y2="104.00"/></g>
`;
const C={navy:'#0E1A33',navy2:'#16264A',aqua:'#7EF0DC',blue:'#3B7BFF',ink:'#0E1A33',screen:'#F5F8FD',
  muted:'#6B7896',line:'#E1E8F4',green:'#22C38E',amber:'#FFB547',soft:'#A9B7D6'};
const cl=(x,a=0,b=1)=>Math.max(a,Math.min(b,x));
const seg=(t,a,b)=>cl((t-a)/(b-a));
const eo=x=>1-Math.pow(1-x,3);
const eio=x=>x<.5?4*x*x*x:1-Math.pow(-2*x+2,3)/2;
const back=x=>{const c=1.70158;return 1+(c+1)*Math.pow(x-1,3)+c*Math.pow(x-1,2)};
const lerp=(a,b,p)=>a+(b-a)*p;
const esc=s=>String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;');

const DEFS=`<defs>
<linearGradient id="dg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${C.aqua}"/><stop offset="1" stop-color="${C.blue}"/></linearGradient>
<linearGradient id="dgh" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${C.aqua}"/><stop offset="1" stop-color="${C.blue}"/></linearGradient>
<radialGradient id="glow" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="${C.blue}" stop-opacity=".42"/><stop offset="1" stop-color="${C.blue}" stop-opacity="0"/></radialGradient>
<radialGradient id="glow2" cx=".5" cy=".5" r=".5"><stop offset="0" stop-color="${C.aqua}" stop-opacity=".22"/><stop offset="1" stop-color="${C.aqua}" stop-opacity="0"/></radialGradient>
<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#BFE3FF"/><stop offset="1" stop-color="#EAF6FF"/></linearGradient>
<filter id="sh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="18" stdDeviation="22" flood-color="#000" flood-opacity=".35"/></filter>
<filter id="dglow" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="10" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
</defs>`;
const DROP='M0 -44 L19.05 -11 A22 22 0 1 1 -19.05 -11 Z';
function drop(x,y,s=1,rot=0,op=1,glow=true){
  return `<g transform="translate(${x} ${y}) rotate(${rot}) scale(${s})" opacity="${op}"${glow?' filter="url(#dglow)"':''}><path d="${DROP}" fill="url(#dg)" stroke="url(#dg)" stroke-width="4" stroke-linejoin="round"/><ellipse cx="-7" cy="-6" rx="5" ry="8" fill="#fff" opacity=".55" transform="rotate(25 -7 -6)"/></g>`;
}
function bez(a,b,lift,p){
  const cx=(a[0]+b[0])/2, cy=Math.min(a[1],b[1])-lift;
  const x=(1-p)*(1-p)*a[0]+2*(1-p)*p*cx+p*p*b[0];
  const y=(1-p)*(1-p)*a[1]+2*(1-p)*p*cy+p*p*b[1];
  const dx=2*(1-p)*(cx-a[0])+2*p*(b[0]-cx), dy=2*(1-p)*(cy-a[1])+2*p*(b[1]-cy);
  return [x,y,Math.atan2(dy,dx)*180/Math.PI];
}
// a droplet carrying something from a to b; ripple where it lands
function fly(t,t0,dur,a,b,lift=260){
  let s='';
  const raw=(t-t0)/dur;
  if(raw>0&&raw<1){
    const p=eio(raw);
    for(let k=4;k>=1;k--){
      const q=eio(cl(raw-k*.035));
      if(q<=0)continue;
      const [tx,ty]=bez(a,b,lift,q);
      s+=`<circle cx="${tx}" cy="${ty}" r="${(10-k*1.8)*FS}" fill="${C.aqua}" opacity="${.5-k*.1}"/>`;
    }
    const [x,y,ang]=bez(a,b,lift,p);
    const sc=raw<.15?back(raw/.15):raw>.88?1-(raw-.88)/.12*.6:1;
    s+=drop(x,y,1.25*sc*FS,ang-90+180);
  }
  s+=ripple(b[0],b[1],t,t0+dur);
  return s;
}
function ripple(x,y,t,ts){
  let s='';
  for(const d of [0,.18]){
    const p=seg(t,ts+d,ts+d+.8);
    if(p>0&&p<1) s+=`<circle cx="${x}" cy="${y}" r="${(20+eo(p)*130)*FS}" fill="none" stroke="${C.aqua}" stroke-width="${(6*(1-p)+1)*FS}" opacity="${1-p}"/>`;
  }
  return s;
}
function pop(t,t0,d=.45){return back(seg(t,t0,t0+d))}
function fade(t,t0,d=.35){return eo(seg(t,t0,t0+d))}
// small Droplet mark (drop on navy tile)
function mark(x,y,s){
  return `<g transform="translate(${x} ${y}) scale(${s/128})"><rect width="128" height="128" rx="30" fill="${C.navy}"/>
  <rect width="128" height="128" rx="30" fill="url(#glow)"/>${LOGO_INNER}</g>`;
}
function appBar(w,title='Droplet',top=56){
  return `${mark(22,top,40)}<text x="74" y="${top+29}" font-size="26" font-weight="800" fill="${C.ink}">${esc(title)}</text>`;
}
function photo(x,y,w,h,rx=16,name='giraffe'){
  if(typeof PHOTO_BASE!=='undefined'&&PHOTO_BASE){const id=PFX+PFX+'rp'+(clipN++);return `<clipPath id="${id}"><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="${rx}"/></clipPath><g clip-path="url(#${id})">${realPhoto(name,x,y,w,h)}</g>`}
  return `<g transform="translate(${x} ${y})"><clipPath id="${PFX}ph${clipN}"><rect width="${w}" height="${h}" rx="${rx}"/></clipPath>
  <g clip-path="url(#${PFX}ph${clipN++})"><rect width="${w}" height="${h}" fill="url(#sky)"/>
  <circle cx="${w*.74}" cy="${h*.3}" r="${h*.13}" fill="#FFC94D"/>
  <path d="M0 ${h*.78} L${w*.3} ${h*.42} L${w*.52} ${h*.66} L${w*.7} ${h*.5} L${w} ${h*.8} V${h} H0 Z" fill="#5CC08A"/>
  <path d="M0 ${h*.9} L${w*.25} ${h*.68} L${w*.6} ${h*.95} V${h} H0 Z" fill="#3FA06E"/></g></g>`;
}
function pdf(x,y,s=1){
  return `<g transform="translate(${x} ${y}) scale(${s})"><path d="M0 0 H52 L72 20 V92 H0 Z" fill="#fff" stroke="${C.line}" stroke-width="3"/>
  <path d="M52 0 V20 H72" fill="#E9EEF7"/><rect x="12" y="34" width="46" height="6" rx="3" fill="#C9D3E6"/><rect x="12" y="48" width="38" height="6" rx="3" fill="#C9D3E6"/>
  <rect x="-6" y="62" width="44" height="22" rx="5" fill="#E5484D"/><text x="16" y="79" text-anchor="middle" font-size="15" font-weight="800" fill="#fff">PDF</text></g>`;
}
function btn(x,y,w,h,label,press=0,fill=C.blue){
  const s=1-press*.06;
  return `<g transform="translate(${x+w/2} ${y+h/2}) scale(${s})"><rect x="${-w/2}" y="${-h/2}" width="${w}" height="${h}" rx="${h/2}" fill="${fill}"/>
  <text y="10" text-anchor="middle" font-size="28" font-weight="700" fill="#fff">${esc(label)}</text></g>`;
}
function toast(x,y,w,icon,title,body,p){
  if(p<=0)return '';
  return `<g transform="translate(${x} ${y-(1-p)*60})" opacity="${p}"><rect width="${w}" height="104" rx="24" fill="#fff" stroke="${C.line}" stroke-width="2" filter="url(#sh)"/>
  ${mark(18,24,56)}<text x="90" y="44" font-size="23" font-weight="800" fill="${C.ink}">${esc(title)}</text>
  <text x="90" y="76" font-size="21" fill="${C.muted}">${esc(body)}</text></g>`;
}
function cursor(x,y,press=0){
  const s=1-press*.15;
  return `<g transform="translate(${x} ${y}) scale(${s})"><path d="M0 0 L0 40 L10 30 L18 48 L25 45 L17 27 L31 27 Z" fill="#fff" stroke="#0B1222" stroke-width="3" stroke-linejoin="round"/></g>`;
}
function key(x,y,label,p){
  if(p<=0)return '';
  return `<g transform="translate(${x} ${y}) scale(${p})" opacity="${cl(p)}"><rect x="-90" y="-50" width="180" height="100" rx="22" fill="#fff" filter="url(#sh)"/>
  <rect x="-90" y="-50" width="180" height="92" rx="22" fill="#fff" stroke="${C.line}" stroke-width="3"/>
  <text y="10" text-anchor="middle" font-size="38" font-weight="800" fill="${C.ink}">${esc(label)}</text></g>`;
}
// ---------- real-looking systems ----------
// Droplet's own palettes: Android and Windows apps (dark, aqua accent), the Mac/Linux desktop app
const D={bg:'#061419',card:'#0B1F26',card2:'#11292F',line:'#173640',line2:'#22474F',text:'#E3F1EF',dim:'#7D9DA0',acc:'#4DE8D4',onAcc:'#032824',coral:'#FF8468'};
const DK={bg:'#232629',side:'#1D2023',card:'#2A2E32',line:'#3B4045',text:'#FCFCFC',dim:'#A1A9B1',acc:'#3DAEE9'};
const AF='font-family="Roboto, Noto Sans, sans-serif"';
const WF='font-family="Segoe UI, Open Sans, Noto Sans, sans-serif"';
const MF='font-family="SF Pro Text, Inter, sans-serif"';
const T=(x,y,s,size,fill,o={})=>`<text x="${x}" y="${y}" font-size="${size}" font-weight="${o.w??400}" fill="${fill}" ${o.f??AF}${o.a?` text-anchor="${o.a}"`:''}${o.ls?` letter-spacing="${o.ls}"`:''}${o.op!=null?` opacity="${o.op}"`:''}>${esc(s)}</text>`;
const OS_DEFS=`<defs>
<linearGradient id="w11" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0A1636"/><stop offset="1" stop-color="#123A86"/></linearGradient>
<linearGradient id="petal" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#7CC4FF"/><stop offset=".55" stop-color="#2F6FEA"/><stop offset="1" stop-color="#173FB0"/></linearGradient>
<linearGradient id="macw" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2B1E5C"/><stop offset=".5" stop-color="#7A3F8F"/><stop offset="1" stop-color="#E0876A"/></linearGradient>
<linearGradient id="aw" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#173B45"/><stop offset="1" stop-color="#0A1A22"/></linearGradient>
<linearGradient id="iw" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5B3FD1"/><stop offset="1" stop-color="#E25C9A"/></linearGradient>
<linearGradient id="lw" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#14323A"/><stop offset="1" stop-color="#2F6A6E"/></linearGradient>
<linearGradient id="silver" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D5D9E0"/><stop offset="1" stop-color="#9AA0AA"/></linearGradient>
</defs>`;

const ICON={
  file:'M6 3h8l4 4v14H6z M14 3v4h4 M12 17v-6 M9.5 13.5 12 11l2.5 2.5',
  msg:'M4 5h16v11H9l-5 4z',
  bell:'M6 16v-5a6 6 0 0 1 12 0v5l2 2H4z M10 20a2 2 0 0 0 4 0',
  clip:'M9 3h6v3H9z M7 5H5v16h14V5h-2 M9 12h6 M9 16h4',
  phone:'M8 2h8a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z M11 19h2',
  laptop:'M4 5h16v11H4z M2 19h20',
  wifi:'M2 9a15 15 0 0 1 20 0 M5 12.5a10 10 0 0 1 14 0 M8.5 16a5 5 0 0 1 7 0 M12 19.5v.01',
  bt:'M7 7l10 10-5 5V2l5 5L7 17',
  hotspot:'M12 12v.01 M8.5 15.5a5 5 0 1 1 7 0 M5.6 18.4a9 9 0 1 1 12.8 0',
  flash:'M9 2h6l-1 7h4l-8 13 1-9H7z',
  moon:'M20 14A8 8 0 1 1 10 4a6 6 0 0 0 10 10z',
  plane:'M12 2v8l9 5v2l-9-3v5l3 2v1l-3-1-3 1v-1l3-2v-5l-9 3v-2l9-5z',
  share:'M21 5a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M9 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M21 19a3 3 0 1 1-6 0 3 3 0 0 1 6 0z M8.6 10.5l6.8-4 M8.6 13.5l6.8 4',
  gear:'M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6z M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2',
  back:'M20 12H5 M11 6l-6 6 6 6', plus:'M12 5v14 M5 12h14', check:'M5 12l5 5 9-10',
  search:'M11 4a7 7 0 1 1 0 14 7 7 0 0 1 0-14z M16 16l5 5', dl:'M12 3v12 M7 10l5 5 5-5 M4 20h16',
  edit:'M4 20h4L19 9l-4-4L4 16z', trash:'M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13', send:'M3 12l18-8-7 18-3-7z',
  copy:'M8 8h12v12H8z M4 16V4h12', lock:'M6 11h12v10H6z M8 11V8a4 4 0 0 1 8 0v3', mic:'M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3',
};
const icon=(n,x,y,s,col,sw=2)=>`<g transform="translate(${x} ${y}) scale(${s/24})"><path d="${ICON[n]}" fill="none" stroke="${col}" stroke-width="${sw*24/s}" stroke-linecap="round" stroke-linejoin="round"/></g>`;
const tapAt=(t,x,y,t0)=>{const p=seg(t,t0,t0+.5);if(p<=0||p>=1)return '';
  return `<circle cx="${x}" cy="${y}" r="${18+eo(p)*36}" fill="#fff" opacity="${.4*(1-p)}"/><circle cx="${x}" cy="${y}" r="15" fill="#fff" opacity="${.65*(1-p)}"/>`};

// ===== Android =====
function android(x,y,w,h,inner,o={}){
  const id=PFX+'ac'+(clipN++), b=11, sw=w-2*b, sh=h-2*b, sc=o.scale??1, rot=o.rot??0;
  return `<g transform="translate(${x+w/2} ${y+h/2}) rotate(${rot}) scale(${sc}) translate(${-w/2} ${-h/2})" opacity="${o.op??1}">
  <rect x="${w-3}" y="${h*.22}" width="7" height="74" rx="3" fill="#4A505A"/><rect x="${w-3}" y="${h*.36}" width="7" height="40" rx="3" fill="#4A505A"/>
  <rect width="${w}" height="${h}" rx="50" fill="#2A2E35" stroke="#5A606B" stroke-width="2.5" filter="url(#sh)"/>
  <rect x="5" y="5" width="${w-10}" height="${h-10}" rx="46" fill="#000"/>
  <clipPath id="${id}"><rect x="${b}" y="${b}" width="${sw}" height="${sh}" rx="40"/></clipPath>
  <g clip-path="url(#${id})"><rect x="${b}" y="${b}" width="${sw}" height="${sh}" fill="${o.bg??D.bg}"/>
  <g transform="translate(${b} ${b})">${inner(sw,sh)}${aBars(sw,sh,o.dark)}</g></g></g>`;
}
function aBars(w,h,dark){
  const c=dark?'#111':'#fff';
  return `${T(26,32,'10:24',17,c,{w:500})}<circle cx="${w/2}" cy="23" r="9" fill="#000" stroke="#222" stroke-width="2"/>
  ${icon('wifi',w-96,14,17,c,2.6)}<path d="M${w-70} 31 l13 -15 v15 z" fill="${c}"/>
  <rect x="${w-48}" y="18" width="22" height="12" rx="3" fill="none" stroke="${c}" stroke-width="1.6"/><rect x="${w-46}" y="20" width="14" height="8" rx="1.5" fill="${c}"/><rect x="${w-25.5}" y="22" width="2" height="4" fill="${c}"/>
  <rect x="${w/2-55}" y="${h-14}" width="110" height="5" rx="2.5" fill="${c}" opacity=".75"/>`;
}
// Droplet for Android: home screen
function aHeader(w){
  return `${mark(18,58,40)}${T(68,84,'droplet',27,D.text,{w:700})}${T(68,104,'This phone: Pixel 8',14,D.dim)}${icon('gear',w-48,66,24,D.text)}`;
}
function aDevice(x,y,w,name,status,o={}){
  const chips=['Files','Message','Ring','Clipboard'], ic=['file','msg','bell','clip'];
  let s=`<rect x="${x}" y="${y}" width="${w}" height="152" rx="22" fill="${D.card}" stroke="${o.hl?D.acc:D.line}" stroke-width="${o.hl?3:1.5}"/>
  <circle cx="${x+38}" cy="${y+40}" r="24" fill="${D.card2}"/>${icon(o.kind??'laptop',x+27,y+29,22,D.text)}
  ${T(x+74,y+36,name,21,D.text,{w:700})}<circle cx="${x+79}" cy="${y+55}" r="5" fill="${o.off?D.dim:D.acc}"/>${T(x+91,y+61,status,14,D.dim)}`;
  const cw=(w-24-3*8)/4;
  chips.forEach((c,i)=>{const cx=x+12+i*(cw+8);
    s+=`<rect x="${cx}" y="${y+80}" width="${cw}" height="60" rx="14" fill="${o.chip===i?D.acc:D.card2}" stroke="${D.line2}" stroke-width="1"/>
    ${icon(ic[i],cx+cw/2-10,y+88,20,o.chip===i?D.onAcc:D.text)}${T(cx+cw/2,y+130,c,12,o.chip===i?D.onAcc:D.text,{a:'middle',w:500})}`});
  return s;
}
function aHome(w,h,o={}){
  let s=aHeader(w);
  if(o.paired){
    s+=T(20,150,'YOUR DEVICES · 1',13,D.dim,{w:700,ls:1})+aDevice(14,162,w-28,o.name??'My laptop','Connected on Wi-Fi',{chip:o.chip});
  }else{
    s+=T(20,150,'YOUR DEVICES',13,D.dim,{w:700,ls:1})+`<rect x="14" y="162" width="${w-28}" height="110" rx="22" fill="none" stroke="${D.line}" stroke-width="2" stroke-dasharray="7 7"/>`
      +T(w/2,224,'No devices yet',17,D.dim,{a:'middle'});
  }
  const py=o.paired?336:296, pr=o.press??0;
  s+=`<g transform="translate(${w/2} ${py+28}) scale(${1-pr*.05})"><rect x="${-(w-28)/2}" y="-28" width="${w-28}" height="56" rx="28" fill="${D.acc}"/>
    ${T(0,7,'+  Pair a device',19,D.onAcc,{a:'middle',w:700})}</g>`;
  if(o.received){
    s+=T(20,py+100,'RECEIVED',13,D.dim,{w:700,ls:1});
    o.received.forEach(([n,f],i)=>{const y=py+114+i*70;
      s+=`<rect x="14" y="${y}" width="${w-28}" height="62" rx="16" fill="${D.card}" stroke="${D.line}"/>${icon('dl',28,y+19,22,D.acc)}
      ${T(62,y+28,n,15,D.text,{w:500})}${T(62,y+49,f,12,D.dim)}`});
  }
  return s;
}
function aTop(w,title){return `${icon('back',18,62,26,D.text)}${T(62,84,title,23,D.text,{w:500})}`}
function aPick(w,h,sel){
  return aTop(w,'Pair a device')+T(20,140,'NEARBY',13,D.dim,{w:700,ls:1})+
  `<rect x="14" y="154" width="${w-28}" height="78" rx="18" fill="${sel?'#123A3A':D.card}" stroke="${sel?D.acc:D.line}" stroke-width="2"/>
   ${icon('laptop',32,180,26,D.text)}${T(72,188,'My laptop',19,D.text,{w:700})}${T(72,210,'Windows · on this Wi-Fi',13,D.dim)}
   <rect x="14" y="242" width="${w-28}" height="78" rx="18" fill="${D.card}" stroke="${D.line}" stroke-width="2" opacity=".45"/>
   ${T(w/2,288,'Looking for more…',14,D.dim,{a:'middle'})}`;
}
function aCode(w,h,glow,press){
  return aTop(w,'Pair a device')+T(20,138,'CHECK THE CODE',13,D.dim,{w:700,ls:1})+T(20,170,'Pairing with My laptop',20,D.text,{w:700})+
  `<rect x="14" y="190" width="${w-28}" height="104" rx="22" fill="${D.card2}" stroke="${D.acc}" stroke-width="${2+glow*3}"/>
   ${glow>0?`<rect x="8" y="184" width="${w-16}" height="116" rx="26" fill="none" stroke="${D.acc}" stroke-width="3" opacity="${glow*.5}"/>`:''}
   ${T(w/2,262,'4 8 1 7',54,D.acc,{a:'middle',w:500,ls:6})}
   ${T(w/2,330,'Pair only if My laptop shows',15,D.dim,{a:'middle'})}${T(w/2,352,'these same four digits.',15,D.dim,{a:'middle'})}
   <g transform="translate(${w/2} 410) scale(${1-press*.05})"><rect x="${-(w-28)/2}" y="-28" width="${w-28}" height="56" rx="28" fill="${D.acc}"/>${T(0,7,'They match',19,D.onAcc,{a:'middle',w:700})}</g>
   <rect x="14" y="452" width="${w-28}" height="56" rx="28" fill="none" stroke="${D.line2}" stroke-width="2"/>${T(w/2,486,"They don't match",17,D.text,{a:'middle',w:500})}`;
}
function aHeads(w,app,title,body,p,o={}){
  if(p<=0)return '';
  const hh=o.action?132:96;
  return `<g transform="translate(10 ${46-(1-p)*130})"><rect width="${w-20}" height="${hh}" rx="24" fill="#2B3033" filter="url(#sh)"/>
   ${o.appIcon??mark(16,14,22)}${T(46,31,app+' · now',13,'#C4CBCF')}
   ${T(16,62,title,17,'#fff',{w:700})}${T(16,84,body,15,'#C4CBCF')}
   ${o.action?`<rect x="16" y="98" width="${o.aw??150}" height="26" rx="13" fill="${o.pressed?'#3E5A60':'#3A4044'}"/>${icon('copy',26,103,15,'#A8E6DE')}${T(48,116,o.action,13,'#A8E6DE',{w:500})}`:''}
   ${o.progress!=null?`<rect x="16" y="${hh-12}" width="${w-52}" height="5" rx="2.5" fill="#444"/><rect x="16" y="${hh-12}" width="${(w-52)*o.progress}" height="5" rx="2.5" fill="${D.acc}"/>`:''}</g>`;
}
// Android Quick Settings
function aQS(w,h,o={}){
  const tile=(x,y,tw,ic,label,sub,on)=>`<rect x="${x}" y="${y}" width="${tw}" height="70" rx="35" fill="${on?'#A8C7FA':'#2E3135'}"/>
    ${icon(ic,x+20,y+23,24,on?'#062E6F':'#E3E3E3')}${T(x+56,y+32,label,15,on?'#062E6F':'#E3E3E3',{w:700})}${T(x+56,y+52,sub,12,on?'#062E6F':'#B8BCC2')}`;
  const tw=(w-36)/2;
  let s=`<rect width="${w}" height="${h}" fill="#121417"/>${T(22,96,'10:24',44,'#E3E3E3')}${T(24,122,'Sat, 18 Oct',15,'#B8BCC2')}
  <rect x="14" y="146" width="${w-28}" height="44" rx="22" fill="#2E3135"/><rect x="14" y="146" width="${(w-28)*.62}" height="44" rx="22" fill="#A8C7FA"/>`;
  const tiles=o.tiles??[['wifi','Internet','Home Wi-Fi',1],['bt','Bluetooth','Off',0],['hotspot','Hotspot','Off',0],['flash','Flashlight','Off',0],['moon','Do not disturb','Off',0],['plane','Airplane mode','Off',0]];
  tiles.forEach(([ic,l,sb,on],i)=>{s+=tile(14+(i%2)*(tw+8),206+Math.floor(i/2)*82,tw,ic,l,sb,on)});
  return s+(o.extra??'');
}
// Android share sheet
function aShare(w,h,p,o={}){
  if(p<=0)return '';
  const y0=h-320+(1-p)*340;
  let s=`<rect width="${w}" height="${h}" fill="#000" opacity="${.45*p}"/><g transform="translate(0 ${y0})">
  <rect width="${w}" height="360" rx="30" fill="#1E2225"/><rect x="${w/2-18}" y="10" width="36" height="4" rx="2" fill="#5C6266"/>`;
  if(o.picker){
    s+=`${mark(20,32,30)}${T(60,54,'Send with droplet',18,'#fff',{w:700})}
      <rect x="14" y="80" width="${w-28}" height="76" rx="18" fill="${o.sel?'#123A3A':'#2A2F32'}" stroke="${o.sel?D.acc:'#3A4044'}" stroke-width="2"/>
      ${icon('laptop',32,106,26,'#fff')}${T(72,113,'My laptop',18,'#fff',{w:700})}${T(72,135,'Connected on Wi-Fi',13,'#C4CBCF')}`;
  }else{
    s+=`${o.thumb??photo(20,30,52,52,10)}${T(84,52,o.what??'Sharing 1 image',16,'#fff',{w:500})}${T(84,74,o.file??'IMG_2048.jpg',13,'#C4CBCF')}
      <line x1="14" y1="100" x2="${w-14}" y2="100" stroke="#3A4044"/>`;
    const apps=[['Droplet'],['Messages','#1A73E8','msg'],['Bluetooth','#3B4A5A','bt'],['Copy','#3B4A5A','copy']];
    apps.forEach(([n,col,ic],i)=>{const cx=20+i*(w-40)/4+(w-40)/8;
      s+=n==='Droplet'?mark(cx-30,122,60):`<circle cx="${cx}" cy="152" r="30" fill="${col}"/>${icon(ic,cx-12,140,24,'#fff')}`;
      s+=T(cx,206,n,13,'#E3E3E3',{a:'middle'})});
  }
  return s+'</g>';
}
// Android gallery
function aPhotos(w,h,sel){
  let s=`<rect width="${w}" height="${h}" fill="#0F1113"/>${T(20,92,'Photos',26,'#fff',{w:500})}`;
  const cw=(w-12)/3;
  for(let i=0;i<12;i++){const x=3+(i%3)*(cw+3), y=112+Math.floor(i/3)*(cw+3);
    s+=photoV(x,y,cw,cw,i);
    if(i===sel)s+=`<rect x="${x+3}" y="${y+3}" width="${cw-6}" height="${cw-6}" fill="none" stroke="#A8C7FA" stroke-width="5"/><circle cx="${x+22}" cy="${y+22}" r="12" fill="#A8C7FA"/>${icon('check',x+14,y+14,16,'#062E6F',3)}`;}
  s+=`<rect y="${h-96}" width="${w}" height="96" fill="#1A1C1E"/>`;
  [['share','Share'],['edit','Edit'],['trash','Delete']].forEach(([ic,l],i)=>{const cx=w/6+i*w/3;s+=icon(ic,cx-12,h-82,24,'#E3E3E3')+T(cx,h-40,l,13,'#E3E3E3',{a:'middle'})});
  return s;
}
// little photo variety for galleries
const PHOTOS=['skyline','elephant','sunset','expressway','giraffe','lions','safari'];
const realPhoto=(name,x,y,w,h)=>`<image href="${PHOTO_BASE}${name}.webp" x="${x}" y="${y}" width="${w}" height="${h}" preserveAspectRatio="xMidYMid slice"/>`;
function photoV(x,y,w,h,i){
  if(typeof PHOTO_BASE!=='undefined'&&PHOTO_BASE)return realPhoto(PHOTOS[i%7],x,y,w,h);
  const skies=[['#BFE3FF','#EAF6FF'],['#FFD9B0','#FFF1E0'],['#C9C2FF','#ECE9FF'],['#B5F0E0','#E9FFF8']];
  const [a,b]=skies[i%4], gid=PFX+'pv'+(clipN++), cid=PFX+'pc'+(clipN++);
  return `<defs><linearGradient id="${gid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs>
  <g transform="translate(${x} ${y})"><clipPath id="${cid}"><rect width="${w}" height="${h}"/></clipPath><g clip-path="url(#${cid})">
  <rect width="${w}" height="${h}" fill="url(#${gid})"/><circle cx="${w*(.3+(i%3)*.2)}" cy="${h*.3}" r="${h*.12}" fill="${i%2?'#FFC94D':'#FFF3C4'}"/>
  <path d="M0 ${h*.8} L${w*.35} ${h*(.4+(i%3)*.08)} L${w*.6} ${h*.68} L${w*.8} ${h*.5} L${w} ${h*.75} V${h} H0Z" fill="${i%3?'#5CC08A':'#4A9BD8'}"/></g></g>`;
}
// Android app installer
function aInstall(w,h,stage,p,press){
  let s=`<rect width="${w}" height="${h}" fill="url(#aw)"/><rect width="${w}" height="${h}" fill="#000" opacity=".45"/>
  <g transform="translate(16 ${h/2-150})"><rect width="${w-32}" height="300" rx="28" fill="#2B3033"/>
  ${mark(24,26,52)}${T(90,60,'droplet',22,'#fff',{w:500})}`;
  if(stage==='ask')s+=T(24,120,'Do you want to install',18,'#E3E3E3')+T(24,144,'this app?',18,'#E3E3E3')+
    T(w-150,262,'Cancel',16,'#A8C7FA',{w:700,a:'end'})+`<g transform="translate(${w-90} 256) scale(${1-press*.08})"><rect x="-40" y="-24" width="80" height="40" rx="20" fill="#A8C7FA"/>${T(0,2,'Install',16,'#062E6F',{a:'middle',w:700})}</g>`;
  else if(stage==='installing')s+=T(24,130,'Installing…',18,'#E3E3E3')+`<rect x="24" y="160" width="${w-80}" height="6" rx="3" fill="#444"/><rect x="24" y="160" width="${(w-80)*p}" height="6" rx="3" fill="#A8C7FA"/>`;
  else s+=`<circle cx="36" cy="122" r="12" fill="#A8C7FA"/>${icon('check',28,114,16,'#062E6F',3)}`+T(58,128,'App installed.',18,'#E3E3E3')+
    T(w-150,262,'Done',16,'#A8C7FA',{w:700,a:'end'})+`<g transform="translate(${w-90} 256) scale(${1-press*.08})"><rect x="-40" y="-24" width="80" height="40" rx="20" fill="#A8C7FA"/>${T(0,2,'Open',16,'#062E6F',{a:'middle',w:700})}</g>`;
  return s+'</g>';
}
// Droplet ringing a phone
function aRing(w,h,t){
  const pul=1+Math.sin(t*12)*.06;
  return `<rect width="${w}" height="${h}" fill="#0B1F26"/><circle cx="${w/2}" cy="${h*.4}" r="${150*pul}" fill="${D.coral}" opacity=".12"/>
  ${T(w/2,110,'droplet',16,D.dim,{a:'middle'})}
  <g transform="translate(${w/2} ${h*.4}) scale(${pul})"><circle r="70" fill="#FFB547"/>${icon('bell',-30,-30,60,'#3A2600',2.5)}</g>
  ${T(w/2,h*.4+130,'Ringing',30,'#fff',{a:'middle',w:700})}${T(w/2,h*.4+160,'My laptop is looking for this phone',14,D.dim,{a:'middle'})}
  <rect x="40" y="${h-130}" width="${w-80}" height="60" rx="30" fill="${D.coral}"/>${T(w/2,h-92,'Stop',20,'#3A1206',{a:'middle',w:700})}`;
}
// Android messages
function aChat(w,h,o={}){
  let s=`<rect width="${w}" height="${h}" fill="#111316"/>${icon('back',16,62,24,'#E3E3E3')}<circle cx="72" cy="74" r="18" fill="#7C4DFF"/>${T(72,80,'A',17,'#fff',{a:'middle',w:700})}
  ${T(100,82,'Alex',20,'#E3E3E3',{w:500})}
  <rect x="${w-210}" y="130" width="190" height="46" rx="20" fill="#0B57D0"/>${T(w-196,159,'Did you see it?',15,'#fff')}
  <rect x="16" y="190" width="200" height="46" rx="20" fill="#2B2F33"/>${T(30,219,'Send me the link!',15,'#E3E3E3')}`;
  s+=`<rect x="12" y="${h-92}" width="${w-74}" height="56" rx="28" fill="#2B2F33"/>${T(32,h-57,o.text||'Text message',15,o.text?'#A8C7FA':'#8E9499',{w:o.text?500:400})}
  <circle cx="${w-32}" cy="${h-64}" r="26" fill="${o.text?'#A8C7FA':'#2B2F33'}"/>${icon(o.text?'send':'mic',w-43,h-75,22,o.text?'#062E6F':'#C4CBCF')}`;
  if(o.paste>0)s+=`<g opacity="${o.paste}"><rect x="24" y="${h-150}" width="96" height="46" rx="12" fill="#3A3F44" filter="url(#sh)"/>${T(72,h-121,'Paste',16,'#fff',{a:'middle',w:500})}</g>`;
  return s;
}

// ===== Windows 11 =====
function winLaptop(x,y,w,inner,o={}){
  const id=PFX+'wc'+(clipN++), h=Math.round(w*.64), b=12, sw=w-2*b, sh=h-2*b-4;
  return `<g transform="translate(${x} ${y}) scale(${o.k??1})" opacity="${o.op??1}">
  <rect width="${w}" height="${h}" rx="18" fill="#1C1E22" stroke="#4A4E57" stroke-width="2.5" filter="url(#sh)"/><circle cx="${w/2}" cy="7" r="2.5" fill="#3A3D44"/>
  <clipPath id="${id}"><rect x="${b}" y="${b}" width="${sw}" height="${sh}" rx="4"/></clipPath>
  <g clip-path="url(#${id})"><g transform="translate(${b} ${b})">${o.screen?o.screen(sw,sh):w11Wall(sw,sh)+inner(sw,sh)+w11Taskbar(sw,sh,o.tb??{})+(o.over?o.over(sw,sh):'')}</g></g>
  <path d="M-38 ${h} H${w+38} L${w+22} ${h+22} Q${w/2} ${h+30} -22 ${h+22} Z" fill="url(#silver)"/>
  <path d="M${w/2-64} ${h} h128 a8 8 0 0 1 -8 7 h-112 a8 8 0 0 1 -8 -7z" fill="#80868F"/></g>`;
}
function w11Wall(w,h){
  let s=`<rect width="${w}" height="${h}" fill="url(#w11)"/>`;
  [-50,-15,20,55].forEach((a,i)=>{s+=`<ellipse cx="${w*.55}" cy="${h*.58}" rx="${h*.36}" ry="${h*.11}" fill="url(#petal)" opacity="${.55+i*.1}" transform="rotate(${a} ${w*.55} ${h*.58}) translate(${h*.16} 0)"/>`});
  return s;
}
function w11Taskbar(w,h,o){
  const y=h-34, cx=w/2, step=34;
  let s=`<rect y="${y}" width="${w}" height="34" fill="#1C1C1C" opacity=".94"/><line x1="0" y1="${y}" x2="${w}" y2="${y}" stroke="#333"/>`;
  const at=i=>cx+(i-2.5)*step;
  // start
  s+=`<g transform="translate(${at(0)-9} ${y+8})"><rect width="8.5" height="8.5" fill="#4CC2FF"/><rect x="9.5" width="8.5" height="8.5" fill="#4CC2FF"/><rect y="9.5" width="8.5" height="8.5" fill="#4CC2FF"/><rect x="9.5" y="9.5" width="8.5" height="8.5" fill="#4CC2FF"/></g>`;
  s+=icon('search',at(1)-9,y+8,18,'#fff');
  s+=`<path d="M${at(2)-10} ${y+11} h7 l2 2 h11 v11 h-20z" fill="#F6C343"/>`;
  s+=`<circle cx="${at(3)}" cy="${y+17}" r="9.5" fill="#1F8FD6"/><path d="M${at(3)-9} ${y+19} a9.5 9.5 0 0 0 18 -3 a7 7 0 0 1 -9 6z" fill="#3ACB8C"/>`;
  s+=`<path d="M${at(4)-9} ${y+11} h18 v14 h-18z M${at(4)-4} ${y+11} v-3 h8 v3" fill="none" stroke="#C9CDD3" stroke-width="2"/>`;
  s+=mark(at(5)-10,y+7,20)+(o.running===false?'':`<rect x="${at(5)-5}" y="${y+30}" width="10" height="3" rx="1.5" fill="#9AA0A6"/>`);
  if(o.extra)s+=o.extra;
  s+=icon('wifi',w-118,y+9,16,'#fff',2.4)+`<rect x="${w-94}" y="${y+12}" width="16" height="9" rx="2" fill="none" stroke="#fff" stroke-width="1.4"/><rect x="${w-92.5}" y="${y+13.5}" width="10" height="6" fill="#fff"/>`;
  s+=T(w-12,y+15,'10:24',10,'#fff',{a:'end',f:WF})+T(w-12,y+28,'18/10/2026',10,'#fff',{a:'end',f:WF});
  return s;
}
function w11TaskX(w,i){return w/2+(i-2.5)*34}
function w11Window(x,y,w,h,title,content,o={}){
  const id=PFX+'ww'+(clipN++);
  return `<g transform="translate(${x} ${y})" opacity="${o.op??1}"><rect width="${w}" height="${h}" rx="8" fill="${o.bg??'#202020'}" stroke="#3E3E3E" filter="url(#sh)"/>
  <clipPath id="${id}"><rect width="${w}" height="${h}" rx="8"/></clipPath><g clip-path="url(#${id})">
  ${o.noicon?'':mark(10,8,15)}${T(o.noicon?12:32,20,title,11,'#fff',{f:WF})}
  <line x1="${w-98}" y1="16" x2="${w-88}" y2="16" stroke="#fff" stroke-width="1.2"/><rect x="${w-62}" y="11" width="9" height="9" fill="none" stroke="#fff" stroke-width="1.2"/>
  <path d="M${w-25} 11 l9 9 M${w-16} 11 l-9 9" stroke="#fff" stroke-width="1.2"/>
  <g transform="translate(0 30)">${content(w,h-30)}</g></g></g>`;
}
// Droplet for Windows: the Devices window
function wDroplet(w,h,o={}){
  const A='#4DE8D4', dim='#7D9DA0';
  let s=`<rect width="${w}" height="${h}" fill="#1B1F21"/>${T(16,30,'Your devices',18,'#fff',{w:700,f:WF})}${T(16,48,'Drop files on a device to send them.',10.5,dim,{f:WF})}
  <rect x="${w-104}" y="14" width="90" height="26" rx="5" fill="#2B2F31" stroke="#3E4446"/>${T(w-59,31,'Pair a device…',10.5,'#fff',{a:'middle',f:WF})}`;
  let y=62;
  if(o.ask){
    const g=o.glow??0;
    s+=`<rect x="12" y="${y}" width="${w-24}" height="80" rx="8" fill="#2E2A1E" stroke="#FBBF24" stroke-width="1.2"/>
    ${T(24,y+20,'Wants to pair',10.5,'#FBBF24',{w:700,f:WF})}${T(24,y+40,'My phone (Android)',14,'#fff',{w:700,f:WF})}
    ${T(24,y+64,'Code',11,dim,{f:WF})}${T(56,y+66,'4817',22,A,{w:700,f:WF,ls:2})}
    ${g>0?`<rect x="50" y="${y+44}" width="72" height="30" rx="6" fill="none" stroke="${A}" stroke-width="2.5" opacity="${g}"/>`:''}
    <g transform="translate(${w-130} ${y+48})"><rect width="64" height="24" rx="5" fill="${A}" transform="scale(${1-(o.press??0)*.06})"/>${T(32,16,'Accept…',10.5,'#032824',{a:'middle',w:700,f:WF})}</g>
    <rect x="${w-58}" y="${y+48}" width="44" height="24" rx="5" fill="#2B2F31" stroke="#3E4446"/>${T(w-36,y+64,'Deny',10.5,'#fff',{a:'middle',f:WF})}`;
    y+=92;
  }
  if(o.ringing){
    s+=`<rect x="12" y="${y}" width="${w-24}" height="34" rx="8" fill="#3A2A12" stroke="#FFB547"/>${icon('bell',22,y+9,16,'#FFB547')}${T(44,y+22,'Ringing My phone',11,'#FFB547',{w:700,f:WF})}
    <rect x="${w-100}" y="${y+5}" width="80" height="24" rx="5" fill="#FFB547"/>${T(w-60,y+21,'Stop ringing',10,'#3A2600',{a:'middle',w:700,f:WF})}`;
    y+=46;
  }
  if(o.device){
    const hl=o.hl, ch=w<380?116:84;
    s+=`<rect x="12" y="${y}" width="${w-24}" height="${ch}" rx="8" fill="${hl?'#123A3A':'#24292B'}" stroke="${hl?A:'#33393B'}" stroke-width="${hl?2:1}"/>
    ${icon('phone',22,y+12,26,'#fff')}${T(56,y+26,'My phone',14,'#fff',{w:700,f:WF})}<circle cx="60" cy="${y+40}" r="3.5" fill="#22C38E"/>${T(68,y+44,'Connected on this network · Android',10,dim,{f:WF})}`;
    const btns=[['Send files…'],['Message…'],['Send clipboard'],['Ring']];
    let bx=22, by=y+56;
    btns.forEach(([b],i)=>{const bw=b.length*5.6+22;
      if(bx+bw>w-20){bx=22;by+=30}
      const on=o.btn===b;
      s+=`<rect x="${bx}" y="${by}" width="${bw}" height="22" rx="4" fill="${on?A:'#2F3436'}" stroke="#454B4E"/>${T(bx+bw/2,by+15,b,10,on?'#032824':'#fff',{a:'middle',f:WF,w:on?700:400})}`;
      if(o.btnPos)o.btnPos[b]=[bx+bw/2,by+11];
      bx+=bw+6;});
    if(o.drop)s+=T(w/2,y+ch+16,'Drop to send to My phone',10,A,{a:'middle',f:WF,w:700});
  }else if(o.empty){
    s+=T(w/2,y+60,'No devices yet',13,dim,{a:'middle',f:WF});
  }
  return s;
}
function w11Toast(w,h,title,body,p,o={}){
  if(p<=0)return '';
  const tw=250, th=o.thumb?104:84, x=w-tw-10+(1-p)*270, y=h-34-th-10;
  return `<g transform="translate(${x} ${y})"><rect width="${tw}" height="${th}" rx="8" fill="#2B2B2B" stroke="#3F3F3F" filter="url(#sh)"/>
  ${mark(12,10,14)}${T(32,21,'droplet',10,'#C8C8C8',{f:WF})}${T(12,44,title,13,'#fff',{w:700,f:WF})}${T(12,62,body,11,'#C8C8C8',{f:WF})}
  ${o.thumb?`${photo(12,70,48,26,4)}`:''}</g>`;
}
function w11Wifi(w,h,rows,p){
  if(p<=0)return '';
  const pw=250, ph=60+rows.length*54, x=w-pw-10, y=h-34-ph-10+(1-p)*30;
  let s=`<g transform="translate(${x} ${y})" opacity="${p}"><rect width="${pw}" height="${ph}" rx="8" fill="#2B2B2B" stroke="#3F3F3F" filter="url(#sh)"/>
  ${T(16,30,'‹   Wi-Fi',13,'#fff',{w:700,f:WF})}<rect x="${pw-52}" y="16" width="36" height="18" rx="9" fill="#4CC2FF"/><circle cx="${pw-25}" cy="25" r="5.5" fill="#000"/>`;
  rows.forEach(([name,status,on,ic],i)=>{const ry=48+i*54;
    s+=`<rect x="8" y="${ry}" width="${pw-16}" height="48" rx="6" fill="${on?'#383838':'transparent'}"/>${icon(ic??'wifi',18,ry+12,20,'#fff',2.4)}
    ${T(48,ry+22,name,12,'#fff',{w:on?700:400,f:WF})}${T(48,ry+38,status,10,'#C8C8C8',{f:WF})}`;
  });
  return s+'</g>';
}
function wStore(w,h,stage,p,press){
  let s=`<rect width="${w}" height="${h}" fill="#1C1C1C"/><rect width="40" height="${h}" fill="#181818"/>`;
  ['search','dl','gear'].forEach((ic,i)=>s+=icon(ic,11,20+i*36,18,'#C8C8C8'));
  s+=`${mark(60,26,84)}${T(160,54,'droplet',24,'#fff',{w:700,f:WF})}${T(160,74,'NOXERA',11,'#4CC2FF',{f:WF})}${T(160,94,'Free · Utilities & tools',11,'#C8C8C8',{f:WF})}`;
  if(stage==='get')s+=`<g transform="translate(200 128) scale(${1-press*.06})"><rect x="-40" y="-15" width="80" height="30" rx="5" fill="#4CC2FF"/>${T(0,5,'Get',13,'#000',{a:'middle',w:700,f:WF})}</g>`;
  else if(stage==='installing')s+=T(160,124,'Installing…',11,'#C8C8C8',{f:WF})+`<rect x="160" y="134" width="180" height="4" rx="2" fill="#444"/><rect x="160" y="134" width="${180*p}" height="4" rx="2" fill="#4CC2FF"/>`;
  else s+=`<g transform="translate(200 128) scale(${1-press*.06})"><rect x="-40" y="-15" width="80" height="30" rx="5" fill="#4CC2FF"/>${T(0,5,'Open',13,'#000',{a:'middle',w:700,f:WF})}</g>`;
  s+=T(60,186,'Pass photos, files, links and your clipboard',11,'#C8C8C8',{f:WF})+T(60,202,'between your phone and computer.',11,'#C8C8C8',{f:WF});
  for(let i=0;i<3;i++)s+=`<rect x="${60+i*112}" y="220" width="104" height="${Math.min(70,h-230)}" rx="6" fill="#2B2B2B"/>`;
  return s;
}
function wBrowser(w,h,url,sel,page){
  let s=`<rect width="${w}" height="${h}" fill="#1F1F1F"/><rect width="${w}" height="34" fill="#2B2B2B"/>
  <rect x="34" y="4" width="${w-80}" height="26" rx="13" fill="#3B3B3B"/>${icon('lock',44,10,13,'#C8C8C8')}`;
  if(sel>0)s+=`<rect x="62" y="9" width="${(url.length*6.3)*sel}" height="16" fill="#2B5FAF"/>`;
  s+=T(64,21,url,11,'#fff',{f:WF});
  return s+`<g transform="translate(0 34)">${page(w,h-34)}</g>`;
}
function w11Explorer(w,h,files){
  let s=`<rect width="${w}" height="${h}" fill="#1C1C1C"/><rect width="${w}" height="30" fill="#232323"/>${T(12,20,'‹  ›    Downloads',11,'#fff',{f:WF})}
  <rect y="30" width="80" height="${h-30}" fill="#191919"/>`;
  ['Home','Desktop','Downloads','Documents'].forEach((n,i)=>s+=`${i===2?`<rect x="4" y="${38+i*22}" width="72" height="20" rx="4" fill="#333"/>`:''}${T(12,52+i*22,n,10,'#ddd',{f:WF})}`);
  files.forEach((f,i)=>{const x=94+(i%3)*84, y=44+Math.floor(i/3)*96, q=f.p??1; if(q<=0)return;
    s+=`<g transform="translate(${x+36} ${y+34}) scale(${back(q)}) translate(${-x-36} ${-y-34})">`+(f.kind==='photo'?photo(x+6,y+6,60,56,4):pdf(x+16,y+6,.6))+T(x+36,y+82,f.name,9.5,'#ddd',{a:'middle',f:WF})+'</g>'});
  return s;
}

// ===== macOS =====
function macLaptop(x,y,w,inner,o={}){
  const id=PFX+'mc'+(clipN++), h=Math.round(w*.64), b=12, sw=w-2*b, sh=h-2*b-2;
  return `<g transform="translate(${x} ${y}) scale(${o.k??1})" opacity="${o.op??1}">
  <rect width="${w}" height="${h}" rx="20" fill="#16171A" stroke="#6D7178" stroke-width="2.5" filter="url(#sh)"/>
  <clipPath id="${id}"><rect x="${b}" y="${b}" width="${sw}" height="${sh}" rx="8"/></clipPath>
  <g clip-path="url(#${id})"><g transform="translate(${b} ${b})"><rect width="${sw}" height="${sh}" fill="url(#macw)"/>
   ${inner(sw,sh)}${macMenu(sw,o.app??'Finder')}${macDock(sw,sh)}${o.over?o.over(sw,sh):''}
   <path d="M${sw/2-44} 0 h88 v12 a6 6 0 0 1 -6 6 h-76 a6 6 0 0 1 -6 -6z" fill="#000"/></g></g>
  <path d="M-40 ${h} H${w+40} L${w+28} ${h+18} Q${w/2} ${h+24} -28 ${h+18} Z" fill="url(#silver)"/>
  <path d="M${w/2-60} ${h} h120 a6 6 0 0 1 -6 6 h-108 a6 6 0 0 1 -6 -6z" fill="#8A9099"/></g>`;
}
function macMenu(w,app){
  return `<rect width="${w}" height="20" fill="#000" opacity=".28"/>
  <path d="M14 5 c3 0 4 2 4 2 c-3 2 -2 6 1 7 c-1 3 -3 5 -5 4 c-1 -1 -3 -1 -4 0 c-3 1 -6 -5 -5 -9 c1 -3 4 -4 6 -3 z" transform="translate(4 -1) scale(.7)" fill="#fff"/>
  ${T(34,14,app,11,'#fff',{w:700,f:MF})}${T(34+app.length*7.2+14,14,'File   Edit   View   Window   Help',11,'#fff',{f:MF})}
  ${icon('wifi',w-120,4,13,'#fff',2.6)}${T(w-12,14,'Sat 18 Oct  10:24',11,'#fff',{a:'end',f:MF})}`;
}
function macDock(w,h){
  const n=6, iw=26, gap=6, dw=n*(iw+gap)+gap, x0=w/2-dw/2, y=h-38;
  let s=`<rect x="${x0}" y="${y}" width="${dw}" height="34" rx="10" fill="#fff" opacity=".22" stroke="#fff" stroke-opacity=".3"/>`;
  const cols=['#3D8BFD','#FFFFFF','#34C759','#FF9F0A','#AF52DE'];
  for(let i=0;i<n;i++){const ix=x0+gap+i*(iw+gap);
    s+=i===5?mark(ix,y+4,iw):`<rect x="${ix}" y="${y+4}" width="${iw}" height="${iw}" rx="7" fill="${cols[i]}"/>`;}
  s+=`<circle cx="${x0+gap+5*(iw+gap)+iw/2}" cy="${y+32}" r="1.6" fill="#fff"/>`;
  return s;
}
function macWindow(x,y,w,h,title,content,o={}){
  const id=PFX+'mw'+(clipN++);
  return `<g transform="translate(${x} ${y})"><rect width="${w}" height="${h}" rx="10" fill="${o.bg??'#1E1E1E'}" stroke="#4A4A4A" filter="url(#sh)"/>
  <clipPath id="${id}"><rect width="${w}" height="${h}" rx="10"/></clipPath><g clip-path="url(#${id})">
  <rect width="${w}" height="28" fill="${o.bar??'#2A2A2A'}"/><circle cx="14" cy="14" r="5.5" fill="#FF5F57"/><circle cx="31" cy="14" r="5.5" fill="#FEBC2E"/><circle cx="48" cy="14" r="5.5" fill="#28C840"/>
  ${o.url!=null?'':T(w/2,18,title,11,'#ddd',{a:'middle',w:700,f:MF})}
  ${o.url!=null?`<rect x="${w/2-150}" y="5" width="300" height="18" rx="6" fill="#3A3A3A"/>${o.sel>0?`<rect x="${w/2-o.url.length*3.1}" y="8" width="${o.url.length*6.2*o.sel}" height="12" fill="#2B5FAF"/>`:''}${T(w/2,18,o.url,10.5,'#eee',{a:'middle',f:MF})}`:''}
  <g transform="translate(0 28)">${content(w,h-28)}</g></g></g>`;
}
function macNotif(w,title,body,p){
  if(p<=0)return '';
  const nw=240, x=w-nw-10+(1-p)*260;
  return `<g transform="translate(${x} 28)"><rect width="${nw}" height="58" rx="12" fill="#3A3A3C" opacity=".96" filter="url(#sh)"/>
  ${mark(10,12,32)}${T(52,26,title,11.5,'#fff',{w:700,f:MF})}${T(52,44,body,10.5,'#D0D0D0',{f:MF})}</g>`;
}

// ===== small device pictures for "works on everything" =====
function miniAndroid(){return `<rect x="-36" y="-66" width="72" height="132" rx="14" fill="#2A2E35" stroke="#5A606B" stroke-width="2"/><rect x="-32" y="-62" width="64" height="124" rx="11" fill="${D.bg}"/>
  <circle cy="-55" r="2.6" fill="#000"/><rect x="-14" y="56" width="28" height="2" rx="1" fill="#fff" opacity=".7"/>${T(-25,-50,'10:24',6,'#fff')}${mark(-18,-18,36)}`}
function miniIphone(){return `<rect x="-36" y="-66" width="72" height="132" rx="17" fill="#C9CDD3" stroke="#8E949C" stroke-width="2"/><rect x="-32" y="-62" width="64" height="124" rx="14" fill="url(#iw)"/>
  <rect x="-11" y="-58" width="22" height="7" rx="3.5" fill="#000"/><rect x="-14" y="56" width="28" height="2" rx="1" fill="#fff" opacity=".8"/>${mark(-18,-18,36)}`}
function miniWindows(){const id=(typeof PFX==='undefined'?'':PFX)+PFX+'mwc'+(clipN++);return `<rect x="-72" y="-48" width="144" height="92" rx="8" fill="#1C1E22" stroke="#4A4E57" stroke-width="2"/>
  <clipPath id="${id}"><rect x="-66" y="-42" width="132" height="80"/></clipPath><g clip-path="url(#${id})"><g transform="translate(-66 -42)">${w11Wall(132,80)}</g>
  <rect x="-66" y="30" width="132" height="8" fill="#1C1C1C"/><g transform="translate(-4 32)"><rect width="3" height="3" fill="#4CC2FF"/><rect x="3.6" width="3" height="3" fill="#4CC2FF"/><rect y="3.6" width="3" height="3" fill="#4CC2FF"/><rect x="3.6" y="3.6" width="3" height="3" fill="#4CC2FF"/></g></g>
  ${mark(-16,-26,32)}<path d="M-84 44 H84 L78 54 H-78 Z" fill="url(#silver)"/>`}
function miniMac(){return `<rect x="-72" y="-48" width="144" height="92" rx="8" fill="#16171A" stroke="#6D7178" stroke-width="2"/><rect x="-66" y="-42" width="132" height="80" fill="url(#macw)"/>
  <rect x="-66" y="-42" width="132" height="6" fill="#000" opacity=".3"/><rect x="-12" y="-42" width="24" height="5" rx="2" fill="#000"/>
  <rect x="-30" y="27" width="60" height="9" rx="3" fill="#fff" opacity=".3"/>${mark(-16,-26,32)}<path d="M-84 44 H84 L78 52 Q0 56 -78 52 Z" fill="url(#silver)"/>`}
function miniLinux(){return `<rect x="-74" y="-58" width="148" height="96" rx="6" fill="#222" stroke="#555" stroke-width="2"/><rect x="-68" y="-52" width="136" height="84" fill="url(#lw)"/>
  <rect x="-68" y="24" width="136" height="8" fill="#1B1E20"/><circle cx="-62" cy="28" r="2.5" fill="${DK.acc}"/>${mark(-16,-32,32)}
  <path d="M-8 38 h16 l4 16 h-24z" fill="#555"/><rect x="-30" y="54" width="60" height="6" rx="3" fill="#666"/>`}

// ---------- the website's pictures ----------
// Each scene draws a real-looking phone and computer at its own size; geometry is set per scene.
let G={};
function setGeo(o){
  G=o; FS=o.fs;
  G.ps=(x,y)=>[o.px+165+(11+x-165)*o.pk, o.py+345+(11+y-345)*o.pk];
  G.ls=(x,y)=>[o.lx+(12+x)*o.lk, o.ly+(12+y)*o.lk];
}
function geo(lx,ly,lk,cx,cy,pk,fs){setGeo({lx,ly,lk,px:cx-165,py:cy-345,pk,fs})}
const PHONE=(inner,o={})=>android(G.px,G.py,330,690,inner,Object.assign({scale:G.pk},o));
const WIN=(inner,o={})=>winLaptop(G.lx,G.ly,630,inner,Object.assign({k:G.lk},o));
const tapG=(t,x,y,t0)=>{const [px,py]=G.ps(x,y);const p=seg(t,t0,t0+.5);if(p<=0||p>=1)return '';
  const k=G.pk*2.2;return `<circle cx="${px}" cy="${py}" r="${(18+eo(p)*36)*k/2}" fill="#fff" opacity="${.45*(1-p)}"/><circle cx="${px}" cy="${py}" r="${15*k/2}" fill="#fff" opacity="${.7*(1-p)}"/>`};
const curG=(x,y,press)=>{const [px,py]=G.ls(x,y);return `<g transform="translate(${px} ${py}) scale(${G.lk*.62}) translate(${-px} ${-py})">${cursor(px,py,press)}</g>`};
const clickP=(t,t0)=>seg(t,t0,t0+.12)-seg(t,t0+.15,t0+.27);
const PHOTO_TAP=[154,263], SHARE_TAP=[51,598], DROPLET_TAP=[53,500], PICK_TAP=[154,466];
function shareFlow(w,h,t,t0,o={}){
  const p=eo(seg(t,t0+.9,t0+1.2))*(1-eo(seg(t,t0+2.45,t0+2.7)));
  return (o.base?o.base:aPhotos(w,h,t>t0?4:-1))+aShare(w,h,p,Object.assign({picker:t>t0+1.75,sel:t>t0+2.15},o.sheet||{}));
}
function shareTaps(t,t0){
  return tapG(t,...PHOTO_TAP,t0)+tapG(t,...SHARE_TAP,t0+.7)+tapG(t,...DROPLET_TAP,t0+1.55)+tapG(t,...PICK_TAP,t0+2.05);
}
// Chrome on Android, on the Wikipedia article about Nairobi
const SERIF='font-family="Linux Libertine, Georgia, Times, serif"';
function aWeb(w,h){
  const pw=w-32;
  return `<rect width="${w}" height="${h}" fill="#fff"/><rect y="0" width="${w}" height="102" fill="#202124"/>
  <rect x="12" y="54" width="${w-64}" height="38" rx="19" fill="#3C4043"/>${icon('lock',24,64,16,'#C4CBCF')}${T(46,78,'en.m.wikipedia.org/wiki/Nairobi',13.5,'#E8EAED')}
  ${icon('share',w-42,61,24,'#E8EAED')}
  <rect y="102" width="${w}" height="50" fill="#F8F9FA"/><path d="M18 120h20M18 127h20M18 134h20" stroke="#202122" stroke-width="2.4"/>
  ${T(52,134,'WIKIPEDIA',15,'#202122',{f:SERIF,ls:2})}${icon('search',w-42,116,22,'#202122')}
  ${T(16,196,'Nairobi',32,'#202122',{f:SERIF})}${T(16,218,'Capital and largest city of Kenya',13,'#54595D')}
  <line x1="16" y1="230" x2="${w-16}" y2="230" stroke="#C8CCD1"/>
  ${realPhoto('skyline',16,242,pw,pw*.56)}
  ${T(16,268+pw*.56,'Nairobi is the capital and largest city',14,'#202122')}${T(16,288+pw*.56,'of Kenya. The name comes from the',14,'#202122')}
  ${T(16,308+pw*.56,'Maasai phrase Enkare Nairobi, "place',14,'#202122')}${T(16,328+pw*.56,'of cool waters".',14,'#202122')}`;
}
// Wikipedia on the laptop's browser
function wWiki(w,h){
  const ib=w*.3;
  return `<rect width="${w}" height="${h}" fill="#fff"/>${T(18,30,'WIKIPEDIA',12,'#202122',{f:SERIF,ls:2})}${T(18,42,'The Free Encyclopedia',7,'#54595D',{f:SERIF})}
  <rect x="${w*.32}" y="16" width="${w*.36}" height="22" rx="2" fill="#fff" stroke="#A2A9B1"/>${T(w*.32+8,31,'Search Wikipedia',9,'#72777D',{f:WF})}
  ${T(18,82,'Nairobi',24,'#202122',{f:SERIF})}<line x1="18" y1="90" x2="${w-18}" y2="90" stroke="#A2A9B1"/>
  ${T(18,106,'From Wikipedia, the free encyclopedia',9,'#54595D',{f:WF})}
  ${['Nairobi is the capital and largest city of Kenya. The name','comes from the Maasai phrase Enkare Nairobi, which means','"place of cool waters", a reference to the Nairobi River.'].map((l,i)=>T(18,128+i*15,l,10.5,'#202122',{f:WF})).join('')}
  ${[0,1,2,3,4,5].map(i=>`<rect x="18" y="${178+i*13}" width="${(w-ib-60)*(i%3===2?.7:1)}" height="6" rx="3" fill="#D5D8DC"/>`).join('')}
  <rect x="${w-ib-18}" y="100" width="${ib}" height="${h-110}" fill="#F8F9FA" stroke="#A2A9B1"/>${T(w-ib/2-18,118,'Nairobi',13,'#202122',{a:'middle',w:700,f:WF})}
  ${realPhoto('skyline',w-ib-12,126,ib-12,(ib-12)*.62)}${T(w-ib/2-18,140+(ib-12)*.62,'Nairobi skyline',8,'#54595D',{a:'middle',f:WF})}`;
}
// The hero: Android phone and Windows laptop pass a photo, a PDF and a link, round and round.
const HERO_WORDS=[[0,'photo'],[6.0,'pdf'],[10.6,'link']];
function hero(t){
  geo(96,22,.86,86,300,.48,.46);
  const linkMode=t>=10.6&&t<17.6;
  const dragging=t>=6.4&&t<7.4;
  const ph=PHONE((w,h)=>{
    let c;
    if(linkMode) c=shareFlow(w,h,t,10.6,{base:aWeb(w,h),sheet:{what:'Sharing link',file:'en.wikipedia.org/wiki/Nairobi',thumb:photo(20,30,52,52,10,'skyline')}});
    else c=shareFlow(w,h,t,.8);
    c+=aHeads(w,'droplet','Report.pdf','From My laptop · tap to open',fade(t,8.4)-fade(t,10.2));
    return c;
  });
  const browserP=pop(t,14.4,.5)*(1-fade(t,17.4,.4));
  const lp=WIN((w,h)=>
    w11Window(8,8,300,h-56,'Downloads',(ww,hh)=>w11Explorer(ww,hh,[{kind:'pdf',name:'Report.pdf',p:dragging?.35:1},{kind:'photo',name:'IMG_2048.jpg',p:t<17.6?seg(t,4.2,4.65):0}]),{noicon:true})+
    w11Window(290,60,306,240,'droplet',(ww,hh)=>wDroplet(ww,hh,{device:true,hl:t>6.95&&t<7.45,drop:t>6.95&&t<7.45}))+
    (browserP>0?`<g transform="translate(300 180) scale(${browserP}) translate(-300 -180)">${w11Window(40,18,526,300,'Nairobi - Wikipedia',(ww,hh)=>wBrowser(ww,hh,'en.wikipedia.org/wiki/Nairobi',0,wWiki),{noicon:true})}</g>`:''),
    {over:(w,h)=>w11Toast(w,h,'Photo from My phone','IMG_2048.jpg · saved to Downloads',fade(t,4.2)-fade(t,6.0),{thumb:true})+
      w11Toast(w,h,'Link from My phone','en.wikipedia.org/wiki/Nairobi',fade(t,14.1)-fade(t,15.8))});
  let s=lp+ph;
  s+=shareTaps(t,.8);
  s+=fly(t,3.25,1.0,G.ps(154,380),G.ls(222,116));
  if(t>=6.2&&t<7.5){const p=eio(seg(t,6.4,7.3)), x=lerp(140,443,p), y=lerp(116,205,p), [ox,oy]=G.ls(0,0);
    s+=`<g transform="translate(${ox} ${oy}) scale(${G.lk})" opacity="${dragging?.9:0}">${pdf(x-22,y-28,.6)}</g>`+curG(x+6,y+4,seg(t,6.25,6.35));}
  s+=fly(t,7.4,1.0,G.ls(443,205),G.ps(154,100),120);
  s+=tapG(t,270,70,11.3)+tapG(t,...DROPLET_TAP,12.15)+tapG(t,...PICK_TAP,12.65);
  s+=fly(t,13.1,1.0,G.ps(154,300),G.ls(450,250));
  return s;
}
// How it works, step 1: install on both
function install(t){
  geo(70,12,.36,52,124,.27,.22);
  const ph=PHONE((w,h)=>aInstall(w,h,t<1.6?'ask':t<3.6?'installing':'done',eio(seg(t,1.6,3.6)),t<1.6?clickP(t,1.4):0));
  const lp=WIN((w,h)=>w11Window(16,12,w-32,h-58,'Microsoft Store',(ww,hh)=>wStore(ww,hh,t<1.8?'get':t<4?'installing':'open',eio(seg(t,1.8,4)),t<1.8?clickP(t,1.6):0),{noicon:true}));
  return lp+ph+tapG(t,234,440,1.4)+(t>.6&&t<2.2?curG(lerp(320,216,eio(seg(t,.7,1.3))),lerp(260,170,eio(seg(t,.7,1.3))),clickP(t,1.6)):'');
}
// step 2: the same four digits on both
function pair(t){
  geo(70,12,.36,52,124,.27,.22);
  const g=t<3?.5+.5*Math.sin(t*4):0, done=t>=3.3;
  const ph=PHONE((w,h)=>done?aHome(w,h,{paired:true}):aCode(w,h,g,clickP(t,3.0)));
  const lp=WIN((w,h)=>w11Window(40,16,w-80,h-70,'droplet',(ww,hh)=>wDroplet(ww,hh,done?{device:true}:{ask:true,glow:g,press:clickP(t,2.9)})));
  return lp+ph+tapG(t,154,410,3.0)+(t>2&&t<3.3?curG(468,168,clickP(t,2.9)):'');
}
// step 3: share a photo, it lands on the laptop
function send(t){
  geo(70,12,.36,52,124,.27,.22);
  const ph=PHONE((w,h)=>shareFlow(w,h,t,.4));
  const lp=WIN((w,h)=>w11Window(120,20,w-150,250,'droplet',(ww,hh)=>wDroplet(ww,hh,{device:true})),
    {over:(w,h)=>w11Toast(w,h,'Photo from My phone','IMG_2048.jpg · saved to Downloads',fade(t,3.6)-fade(t,5.6),{thumb:true})});
  return lp+ph+shareTaps(t,.4)+fly(t,2.8,.8,G.ps(154,380),G.ls(470,300),60);
}
// no Wi-Fi: the phone's hotspot, the laptop joins it
function hotspotS(t){
  geo(70,12,.36,52,124,.27,.22);
  const on=t>=1.0;
  const ph=PHONE((w,h)=>aQS(w,h,{tiles:[['wifi','Internet','No Wi-Fi',0],['bt','Bluetooth','Off',0],['hotspot','Hotspot',on?(t>2.4?'1 device':'On'):'Off',on],['flash','Flashlight','Off',0],['moon','Do not disturb','Off',0],['plane','Airplane mode','Off',0]]}));
  const rows=t<1.3?[['No networks found','Turn on a hotspot nearby',0]]:[['My phone',t<1.9?'Secured':t<2.4?'Connecting…':'Connected, secured',t>=1.9,'hotspot']];
  const lp=WIN(()=>'',{over:(w,h)=>w11Wifi(w,h,rows,1)});
  return lp+ph+tapG(t,82,323,.9)+(t>1.2&&t<2.4?curG(lerp(330,471,eio(seg(t,1.2,1.6))),lerp(150,241,eio(seg(t,1.2,1.6))),clickP(t,1.75)):'');
}


// ---------- the engine: draws each picture while it's on screen ----------
const SCENES={hero:{d:18,still:4.4,f:hero},install:{d:7,still:5,f:install},pair:{d:5,still:1.4,f:pair},send:{d:6,still:4.2,f:send},hotspot:{d:5,still:3,f:hotspotS}};
// ---------- the phone as a remote ----------
function aTabs(w,y,act){
  const tabs=['Touchpad','Keys','Media'], tw=(w-28)/3;
  let s=`<rect x="14" y="${y}" width="${w-28}" height="44" rx="22" fill="${D.card}" stroke="${D.line}"/>`;
  tabs.forEach((n,i)=>{const x=14+i*tw; if(i===act)s+=`<rect x="${x+3}" y="${y+3}" width="${tw-6}" height="38" rx="19" fill="${D.acc}"/>`;
    s+=T(x+tw/2,y+28,n,15,i===act?D.onAcc:D.text,{a:'middle',w:700})});
  return s;
}
function aBtTop(w){
  return aTop(w,'Mouse & keyboard')+
  `<rect x="14" y="112" width="${w-28}" height="58" rx="16" fill="${D.card}" stroke="${D.line}"/>${T(30,135,'Connected to',12,D.dim)}${T(30,157,'My laptop',17,D.text,{w:700})}
  <rect x="${w-118}" y="126" width="92" height="30" rx="15" fill="none" stroke="${D.line2}" stroke-width="1.5"/>${T(w-72,146,'Disconnect',12,D.text,{a:'middle'})}`;
}
function aBtMedia(w,h,o){
  const pr=o.press||{};
  const b=(cx,cy,r,ic,on,k)=>`<g transform="translate(${cx} ${cy}) scale(${1-(pr[k]||0)*.08})"><circle r="${r}" fill="${on?D.acc:D.card2}" stroke="${D.line2}"/>${ic}</g>`;
  const play=o.playing?`<rect x="-12" y="-16" width="8" height="32" rx="2" fill="${D.onAcc}"/><rect x="4" y="-16" width="8" height="32" rx="2" fill="${D.onAcc}"/>`:`<path d="M-9 -16 L16 0 L-9 16z" fill="${D.onAcc}"/>`;
  return aBtTop(w)+aTabs(w,188,2)+
   b(w/2,330,54,play,1,'play')+b(w/2-104,330,34,`<path d="M8 -12 L-6 0 L8 12z M-10 -12 v24" fill="${D.text}" stroke="${D.text}" stroke-width="3"/>`,0,'prev')+
   b(w/2+104,330,34,`<path d="M-8 -12 L6 0 L-8 12z M10 -12 v24" fill="${D.text}" stroke="${D.text}" stroke-width="3"/>`,0,'next')+
   T(w/2,420,'Play / pause',14,D.dim,{a:'middle'})+
   `<rect x="30" y="460" width="${w-60}" height="60" rx="30" fill="${D.card}" stroke="${D.line}"/>${T(64,498,'−',28,D.text,{a:'middle'})}${T(w-64,498,'+',26,D.text,{a:'middle'})}
   <rect x="96" y="486" width="${w-192}" height="8" rx="4" fill="${D.line2}"/><rect x="96" y="486" width="${(w-192)*(o.vol??.6)}" height="8" rx="4" fill="${D.acc}"/>`;
}
function aBtPad(w,h,fx,fy,on){
  return aBtTop(w)+aTabs(w,188,0)+
   `<rect x="14" y="248" width="${w-28}" height="${h-290}" rx="24" fill="${D.card}" stroke="${D.line}"/>
   ${T(w/2,300,'Slide to move · tap to click',13,D.dim,{a:'middle'})}${T(w/2,320,'Two fingers: scroll or right-click',13,D.dim,{a:'middle'})}
   ${on?`<circle cx="${fx}" cy="${fy}" r="30" fill="${D.acc}" opacity=".18"/><circle cx="${fx}" cy="${fy}" r="16" fill="${D.acc}" opacity=".55"/>`:''}`;
}
function aRemote(w,h,o){
  const pr=o.press||0;
  return aTop(w,'Remote')+`<circle cx="${w-52}" cy="77" r="5" fill="#22C38E"/>`+T(w-18,83,'Live',13,D.text,{a:'end',w:700})+
   T(20,136,'Presenting on',13,D.dim)+T(20,160,'My laptop',18,D.text,{w:700})+
   T(w/2,232,o.timer,56,D.text,{a:'middle',w:300,ls:2})+T(w/2,258,'Tap to pause · hold to reset',12,D.dim,{a:'middle'})+
   `<g transform="translate(${w/2} 370) scale(${1-pr*.04})"><rect x="${-(w-28)/2}" y="-80" width="${w-28}" height="160" rx="28" fill="${D.coral}"/>${T(0,14,'Next',38,'#3A1206',{a:'middle',w:700})}</g>
   <rect x="14" y="462" width="${w-28}" height="64" rx="22" fill="${D.card}" stroke="${D.line}"/>${T(w/2,501,'Previous',18,D.text,{a:'middle',w:500})}
   ${['Start','Black','End'].map((n,i)=>{const bw=(w-44)/3,x=14+i*(bw+8);return `<rect x="${x}" y="540" width="${bw}" height="44" rx="22" fill="none" stroke="${D.line2}"/>${T(x+bw/2,567,n,14,D.text,{a:'middle'})}`}).join('')}`;
}
function aFound(w,h,t){
  const pul=1+Math.sin(t*12)*.05;
  return `<rect width="${w}" height="${h}" fill="${D.bg}"/><circle cx="${w/2}" cy="230" r="${120*pul}" fill="${D.acc}" opacity=".08"/>
   <g transform="translate(${w/2} 230) scale(${pul}) translate(-60 -60)">${mark(0,0,120)}</g>
   ${T(w/2,360,'Found me!',32,D.text,{a:'middle',w:700})}${T(w/2,392,'Ringing from My laptop',16,D.dim,{a:'middle'})}${T(w/2,414,'Tap Stop to silence it.',14,D.dim,{a:'middle'})}
   <rect x="34" y="${h-140}" width="${w-68}" height="62" rx="31" fill="${D.acc}"/>${T(w/2,h-101,'Stop',20,D.onAcc,{a:'middle',w:700})}`;
}
function aTV(w,h,dir,okP){
  const cx=w/2, cy=360, R=96;
  const arc=(d,a0)=>{const on=dir===d;return `<path d="M${cx} ${cy} L${cx+R*Math.cos(a0-.78)} ${cy+R*Math.sin(a0-.78)} A${R} ${R} 0 0 1 ${cx+R*Math.cos(a0+.78)} ${cy+R*Math.sin(a0+.78)} Z" fill="${on?D.acc:D.card2}" stroke="${D.bg}" stroke-width="4"/>`};
  const ch=(d,x,y,r)=>`<path d="M-7 -5 L0 3 L7 -5" transform="translate(${x} ${y}) rotate(${r})" fill="none" stroke="${dir===d?D.onAcc:D.text}" stroke-width="3" stroke-linecap="round"/>`;
  return aTop(w,'TV remote')+
   `<rect x="14" y="112" width="${w-28}" height="64" rx="18" fill="${D.card}" stroke="${D.line}"/>${icon('laptop',30,130,26,D.text)}
   ${T(68,140,'Living room TV',17,D.text,{w:700})}<circle cx="72" cy="158" r="4" fill="#22C38E"/>${T(82,163,'Connected',12,D.dim)}
   <circle cx="${w-44}" cy="144" r="18" fill="${D.card2}"/><path d="M${w-44} 134 v9 M${w-51} 139 a10 10 0 1 0 14 0" fill="none" stroke="${D.coral}" stroke-width="2.6" stroke-linecap="round"/>
   <rect x="14" y="192" width="${w-28}" height="40" rx="20" fill="${D.card}" stroke="${D.line}"/><rect x="17" y="195" width="${(w-34)/2}" height="34" rx="17" fill="${D.acc}"/>
   ${T(14+(w-28)/4,217,'Buttons',14,D.onAcc,{a:'middle',w:700})}${T(14+3*(w-28)/4,217,'Touchpad',14,D.text,{a:'middle'})}
   ${arc('up',-Math.PI/2)}${arc('right',0)}${arc('down',Math.PI/2)}${arc('left',Math.PI)}
   ${ch('up',cx,cy-66,180)}${ch('down',cx,cy+66,0)}${ch('left',cx-66,cy,90)}${ch('right',cx+66,cy,-90)}
   <circle cx="${cx}" cy="${cy}" r="${40-okP*3}" fill="${okP?D.acc:D.card}" stroke="${D.line2}"/>${T(cx,cy+6,'OK',17,okP?D.onAcc:D.text,{a:'middle',w:700})}
   ${[['back',-1],['home',0],['menu',1]].map(([n,i])=>`<circle cx="${cx+i*78}" cy="500" r="26" fill="${D.card2}"/>`+(n==='back'?icon('back',cx+i*78-11,489,22,D.text):n==='home'?`<circle cx="${cx}" cy="500" r="9" fill="none" stroke="${D.text}" stroke-width="2.4"/>`:`<path d="M${cx+78-9} 494 h18 M${cx+78-9} 500 h18 M${cx+78-9} 506 h18" stroke="${D.text}" stroke-width="2.4"/>`)).join('')}`;
}
// computer side
// Windows Media Player, playing a real album
const ALBUM=['SKINNY','LUNCH','CHIHIRO','BIRDS OF A FEATHER','WILDFLOWER','THE GREATEST',"L'AMOUR DE MA VIE",'THE DINER','BITTERSUITE','BLUE'];
function albumArt(x,y,s){
  const g=PFX+PFX+'aa'+(clipN++), c=PFX+PFX+'ac'+(clipN++);
  return `<defs><radialGradient id="${g}" cx=".35" cy=".3" r=".9"><stop offset="0" stop-color="#5FB4E8"/><stop offset=".45" stop-color="#14508A"/><stop offset="1" stop-color="#04162E"/></radialGradient></defs>
   <clipPath id="${c}"><rect x="${x}" y="${y}" width="${s}" height="${s}" rx="${s*.04}"/></clipPath><g clip-path="url(#${c})"><rect x="${x}" y="${y}" width="${s}" height="${s}" fill="url(#${g})"/>
   ${[0,1,2,3].map(i=>`<path d="M${x} ${y+s*(.55+i*.1)} q${s*.25} ${-s*.05} ${s*.5} 0 t${s*.5} 0" fill="none" stroke="#9FD8FF" stroke-width="${s*.012}" opacity="${.35-i*.07}"/>`).join('')}</g>`;
}
function wMusic(w,h,playing,track,prog){
  const cur=3+track, len=track?'4:21':'3:30', el=track?'0:02':'1:12';
  let s=`<rect width="${w}" height="${h}" fill="#202020"/><rect width="120" height="${h}" fill="#1A1A1A"/>`;
  ['Home','Music library','Video library','Play queue','Playlists'].forEach((n,i)=>{const y=18+i*24;
    if(i===1)s+=`<rect x="6" y="${y-12}" width="108" height="20" rx="4" fill="#2D2D2D"/><rect x="6" y="${y-7}" width="3" height="10" rx="1.5" fill="#4CC2FF"/>`;
    s+=T(18,y+2,n,9.5,i===1?'#fff':'#C8C8C8',{f:WF})});
  s+=albumArt(136,14,74)+T(222,30,'Album',8,'#C8C8C8',{f:WF})+T(222,48,'HIT ME HARD AND SOFT',14,'#fff',{w:700,f:WF})+T(222,64,'Billie Eilish · 2024 · 10 songs',9,'#C8C8C8',{f:WF});
  s+=`<rect x="222" y="72" width="60" height="16" rx="8" fill="#4CC2FF"/>${T(252,83,'Play all',8,'#000',{a:'middle',w:700,f:WF})}`;
  for(let i=0;i<6;i++){const k=i+1, y=98+i*18, on=k===cur;
    s+=`${on?`<rect x="130" y="${y}" width="${w-140}" height="16" rx="3" fill="#2E3A44"/>`:''}${T(140,y+11.5,String(k+1),8.5,on?'#4CC2FF':'#999',{f:WF})}${T(160,y+11.5,ALBUM[k],8.5,on?'#4CC2FF':'#E6E6E6',{w:on?700:400,f:WF})}${T(w-110,y+11.5,'Billie Eilish',8.5,'#999',{f:WF})}`;}
  // now-playing bar
  const by=h-46;
  s+=`<rect y="${by}" width="${w}" height="46" fill="#2B2B2B"/>${albumArt(8,by+7,32)}${T(46,by+20,ALBUM[cur],9.5,'#fff',{w:700,f:WF})}${T(46,by+34,'Billie Eilish',8.5,'#C8C8C8',{f:WF})}
   <g transform="translate(${w/2} ${by+16})"><circle r="10" fill="#fff"/>${playing?'<rect x="-4" y="-5" width="3" height="10" fill="#000"/><rect x="1" y="-5" width="3" height="10" fill="#000"/>':'<path d="M-3 -5 L5 0 L-3 5z" fill="#000"/>'}
   <path d="M-26 -4 L-33 0 L-26 4z M-35 -4 v8" fill="#ddd" stroke="#ddd" stroke-width="1.5"/><path d="M26 -4 L33 0 L26 4z M35 -4 v8" fill="#ddd" stroke="#ddd" stroke-width="1.5"/></g>
   ${T(w/2-110,by+38,el,8,'#C8C8C8',{a:'end',f:WF})}<rect x="${w/2-104}" y="${by+34}" width="208" height="3" rx="1.5" fill="#555"/><rect x="${w/2-104}" y="${by+34}" width="${208*prog}" height="3" rx="1.5" fill="#4CC2FF"/>${T(w/2+110,by+38,len,8,'#C8C8C8',{f:WF})}`;
  return s;
}
// PowerPoint slide show, from the Droplet presentation
function wSlide(w,h,n){
  const navy='#0E1A33', acc='#2F6FDB';
  let s=`<rect width="${w}" height="${h}" fill="#fff"/><rect width="${w}" height="6" fill="${acc}"/>${mark(w-46,16,28)}`;
  const title=(t)=>T(36,58,t,26,navy,{w:800,f:'font-family="Segoe UI, Inter, sans-serif"'});
  if(n===0){
    s+=title('Why Droplet?');
    [['Sending a photo to your own laptop today:','email it, WhatsApp it to yourself, or find a cable.'],
     ['With Droplet it takes one tap,','straight over your own Wi-Fi.'],
     ['No account, no cloud,','and none of your data bundle.']].forEach(([a,b],i)=>{const y=104+i*62;
       s+=`<circle cx="44" cy="${y-5}" r="5" fill="${acc}"/>`+T(60,y,a,15,navy,{w:700,f:WF})+T(60,y+20,b,13,'#4B5570',{f:WF})});
  }else if(n===1){
    s+=title('How it works');
    [['1','Install','on two devices'],['2','Pair','with a 4-digit code'],['3','Send','photos, files, links']].forEach(([k,a,b],i)=>{const x=36+i*((w-72)/3);
      s+=`<rect x="${x}" y="96" width="${(w-72)/3-16}" height="150" rx="12" fill="#F1F5FB"/><circle cx="${x+30}" cy="128" r="16" fill="${acc}"/>`+
        T(x+30,134,k,16,'#fff',{a:'middle',w:800,f:WF})+T(x+18,182,a,18,navy,{w:800,f:WF})+T(x+18,204,b,12,'#4B5570',{f:WF})});
  }else{
    s+=title('Works on every device');
    [['Android',miniAndroid],['iPhone',miniIphone],['Windows',miniWindows],['Mac',miniMac],['Linux',miniLinux]].forEach(([p,f],i)=>{const x=36+i*((w-72)/5), cw=(w-72)/5-12;
      s+=`<rect x="${x}" y="100" width="${cw}" height="110" rx="12" fill="#F1F5FB"/><g transform="translate(${x+cw/2} 142) scale(.36)">${f()}</g>`+T(x+cw/2,194,p,13,navy,{a:'middle',w:700,f:WF})});
    s+=T(36,246,'Free and open source · built in Nairobi',13,'#4B5570',{f:WF});
  }
  return s+T(w-16,h-12,`${n+3}`,10,'#8A93A6',{a:'end',f:WF});
}
function tvSet(x,y,w,inner){
  const h=w*.56, id=PFX+PFX+'tv'+(clipN++);
  return `<g transform="translate(${x} ${y})"><rect width="${w}" height="${h}" rx="8" fill="#0B0D10" stroke="#2A2E35" stroke-width="2" filter="url(#sh)"/>
   <clipPath id="${id}"><rect x="6" y="6" width="${w-12}" height="${h-12}" rx="3"/></clipPath><g clip-path="url(#${id})"><g transform="translate(6 6)">${inner(w-12,h-12)}</g></g>
   <path d="M${w/2-50} ${h} l-16 26 h132 l-16 -26z" fill="#2A2E35"/><rect x="${w/2-80}" y="${h+24}" width="160" height="6" rx="3" fill="#3A3F48"/></g>`;
}
function tvHome(w,h,sel){
  let s=`<rect width="${w}" height="${h}" fill="#111418"/><rect x="14" y="12" width="${w*.18}" height="8" rx="4" fill="#666"/><rect x="${w*.18+24}" y="12" width="${w*.12}" height="8" rx="4" fill="#444"/>
   <rect x="14" y="30" width="${w-28}" height="${h*.36}" rx="8" fill="url(#iw)"/><rect x="28" y="${30+h*.36-30}" width="${w*.3}" height="10" rx="5" fill="#fff" opacity=".85"/>`;
  const cols=['#E53935','#1E88E5','#43A047','#FDD835','#8E24AA','#FB8C00'], tw=(w-28-5*8)/6, y=h*.36+46;
  cols.forEach((c,i)=>{const x=14+i*(tw+8);s+=`<rect x="${x}" y="${y}" width="${tw}" height="${tw*.62}" rx="6" fill="${c}" opacity=".9"/>`;
    if(i===sel)s+=`<rect x="${x-3}" y="${y-3}" width="${tw+6}" height="${tw*.62+6}" rx="8" fill="none" stroke="#fff" stroke-width="3"/>`});
  for(let i=0;i<5;i++)s+=`<rect x="${14+i*((w-28-32)/5+8)}" y="${y+tw*.62+16}" width="${(w-28-32)/5}" height="${tw*.5}" rx="6" fill="#2A2E35"/>`;
  return s;
}
function remoteGeo(){geo(150,58,.55,82,205,.42,.3)}
function rMusic(t){
  remoteGeo();
  const playing=!(t>=1.2&&t<2.6), track=t>=3.8?1:0, prog=track?.01+(t-3.8)*.004:.34+(playing?t*.005:.006);
  const ph=PHONE((w,h)=>aBtMedia(w,h,{playing,press:{play:clickP(t,1.1)+clickP(t,2.5),next:clickP(t,3.7)},vol:.6}));
  const lp=WIN((w,h)=>w11Window(14,10,w-28,h-50,'Media Player',(ww,hh)=>wMusic(ww,hh,playing,track,prog)));
  return lp+ph+tapG(t,154,341,1.1)+tapG(t,154,341,2.5)+tapG(t,258,341,3.7);
}
function rPad(t){
  remoteGeo();
  const p=(t%5)/5, fx=110+Math.sin(p*Math.PI*2)*70, fy=420+Math.sin(p*Math.PI*4)*70, on=(t%5)<4.3;
  const cx=300+Math.sin(p*Math.PI*2)*150, cy=150+Math.sin(p*Math.PI*4)*70;
  const ph=PHONE((w,h)=>aBtPad(w,h,fx,fy,on));
  const lp=WIN((w,h)=>w11Window(30,16,w-60,h-62,'Documents',(ww,hh)=>w11Explorer(ww,hh,[{kind:'pdf',name:'Report.pdf'},{kind:'photo',name:'IMG_2048.jpg'},{kind:'pdf',name:'Notes.pdf'},{kind:'photo',name:'IMG_2051.jpg'}]),{noicon:true}));
  return lp+ph+curG(cx,cy,(t%5)>4.3&&(t%5)<4.5?1:0);
}
function rSlides(t){
  remoteGeo();
  const n=t<2?0:t<4?1:2, sec=252+Math.floor(t);
  const ph=PHONE((w,h)=>aRemote(w,h,{timer:`0${Math.floor(sec/60)}:${String(sec%60).padStart(2,'0')}`,press:clickP(t,1.9)+clickP(t,3.9)}));
  const lp=WIN(()=>'',{screen:(w,h)=>{const f=1-Math.min(seg(t,2,2.25)*(t<2.5?1:0)+seg(t,4,4.25)*(t<4.5?1:0),1)*0;const k=(t>=2&&t<2.25)||(t>=4&&t<4.25)?seg(t%2,0,.25):1;return `<g opacity="${k}">${wSlide(w,h,n)}</g>`}});
  return lp+ph+tapG(t,154,370,1.9)+tapG(t,154,370,3.9);
}
// the phone has slid under the sofa; the laptop on the coffee table rings it
function rRing(t){
  const ringing=t>=1.6;
  let s=realPhoto('sofa',0,0,520,380);
  // the laptop sits on the coffee table
  geo(304,128,.27,0,0,.2,.25);
  s+=WIN((w,h)=>w11Window(20,12,w-40,h-58,'droplet',(ww,hh)=>wDroplet(ww,hh,{device:true,ringing})));
  // the phone, lying flat on the rug, half under the sofa
  const cx=150, cy=284, sh=ringing?Math.sin(t*70)*1.2:0;
  if(ringing){const q=.6+.4*Math.sin(t*10);
    s+=`<ellipse cx="${cx}" cy="${cy+10}" rx="${64+q*10}" ry="${15+q*3}" fill="#7EF0DC" opacity="${.3*q}"/><ellipse cx="${cx}" cy="${cy-6}" rx="60" ry="10" fill="#7EF0DC" opacity="${.18*q}"/>`;}
  s+=`<g transform="translate(${cx+sh} ${cy}) scale(1 .3) rotate(-18) scale(.21) translate(-165 -345)">${android(0,0,330,690,(w,h)=>ringing?aFound(w,h,t):`<rect width="${w}" height="${h}" fill="#000"/>`)}</g>`;
  // the sofa's base in front of the phone's top half
  const cid=PFX+PFX+'sf'+(clipN++);
  s+=`<clipPath id="${cid}"><rect x="60" y="0" width="178" height="276"/></clipPath><g clip-path="url(#${cid})">${realPhoto('sofa',0,0,520,380)}</g>`;
  if(ringing)for(let k=0;k<3;k++){const q=((t-1.6)*.9+k/3)%1;
    s+=`<path d="M${cx-46-q*26} ${cy-20-q*10} a${40+q*24} ${30+q*16} 0 0 0 0 ${44+q*20}" fill="none" stroke="#FFB547" stroke-width="3.5" stroke-linecap="round" opacity="${(1-q)*.9}"/>
     <path d="M${cx+46+q*26} ${cy-20-q*10} a${40+q*24} ${30+q*16} 0 0 1 0 ${44+q*20}" fill="none" stroke="#FFB547" stroke-width="3.5" stroke-linecap="round" opacity="${(1-q)*.9}"/>`;}
  // click Ring on the laptop
  const rb=[20+312, 12+30+129];
  if(t<1.7)s+=curG(lerp(rb[0]+90,rb[0],eio(seg(t,.3,1))),lerp(rb[1]+60,rb[1],eio(seg(t,.3,1))),clickP(t,1.2));
  return s+fly(t,1.3,.6,G.ls(rb[0],rb[1]),[cx,cy],50);
}
// a real TV on the wall; the phone in your hand moves the selection
function tvHomeReal(w,h,sel){
  let s=`<rect width="${w}" height="${h}" fill="#0F1114"/>${T(14,18,'For you',9,'#fff',{w:700,f:AF})}${T(64,18,'Movies',9,'#9AA0A6',{f:AF})}${T(104,18,'Shows',9,'#9AA0A6',{f:AF})}${T(w-14,18,'10:24',9,'#fff',{a:'end',f:AF})}`;
  const id=PFX+PFX+'tvb'+(clipN++);
  s+=`<clipPath id="${id}"><rect x="12" y="26" width="${w-24}" height="${h*.44}" rx="6"/></clipPath><g clip-path="url(#${id})">${realPhoto('safari',12,26,w-24,h*.44)}
    <rect x="12" y="26" width="${w*.5}" height="${h*.44}" fill="url(#tvfade)"/></g>
    ${T(24,26+h*.44-28,'Into the Maasai Mara',13,'#fff',{w:700,f:AF})}${T(24,26+h*.44-14,'Nature · Kenya',8,'#ddd',{f:AF})}`;
  const tw=(w-24-4*6)/5, y=h*.44+40;
  s+=T(14,y-6,'Kenya on camera',9,'#fff',{w:700,f:AF});
  for(let i=0;i<5;i++){const x=12+i*(tw+6), cid=PFX+PFX+'tvt'+(clipN++);
    s+=`<clipPath id="${cid}"><rect x="${x}" y="${y}" width="${tw}" height="${tw*.6}" rx="4"/></clipPath><g clip-path="url(#${cid})">${realPhoto(PHOTOS[(i+1)%7],x,y,tw,tw*.6)}</g>`;
    if(i===sel)s+=`<rect x="${x-2}" y="${y-2}" width="${tw+4}" height="${tw*.6+4}" rx="5" fill="none" stroke="#fff" stroke-width="2.5"/>`;}
  return s;
}
function rTV(t){
  geo(0,0,1,74,318,.4,.3);
  const seq=[[.8,'right'],[1.8,'right'],[2.8,'right'],[3.8,'left'],[4.8,'ok']];
  let sel=0, dir=null, okP=0;
  seq.forEach(([tt,d])=>{if(t>=tt){if(d==='right')sel++;else if(d==='left')sel--;} if(t>=tt&&t<tt+.3){if(d==='ok')okP=1;else dir=d}});
  const ph=PHONE((w,h)=>aTV(w,h,dir,okP),{rot:-6});
  const map={up:[154,294],down:[154,426],left:[88,360],right:[220,360],ok:[154,360]};
  // the screen inside the TV's white frame, nearly square-on
  const cid=PFX+PFX+'tvs'+(clipN++), W=258, H=206;
  const scr=`<clipPath id="${cid}"><path d="M154 75 L412 68 L412 282 L154 274Z"/></clipPath><g clip-path="url(#${cid})">
    <g transform="matrix(${258/W} ${-7/W} 0 ${206/H} 154 75)">${tvHomeReal(W,H,sel)}</g></g>`;
  return `<defs><linearGradient id="tvfade" x1="0" x2="1"><stop offset="0" stop-color="#000" stop-opacity=".7"/><stop offset="1" stop-color="#000" stop-opacity="0"/></linearGradient></defs>`+
    realPhoto('tvroom',0,0,520,380)+scr+ph+seq.map(([tt,d])=>tapG(t,...map[d],tt)).join('');
}
Object.assign(SCENES,{music:{d:6,still:.5,f:rMusic},pad:{d:5,still:2,f:rPad},slides:{d:6,still:1,f:rSlides},ring:{d:6,still:3,f:rRing},tv:{d:6,still:2,f:rTV}});

function start(){
  const reduce=matchMedia('(prefers-reduced-motion: reduce)').matches;
  const defs=document.createElementNS('http://www.w3.org/2000/svg','svg');
  defs.setAttribute('aria-hidden','true');defs.setAttribute('focusable','false');
  defs.style.cssText='position:absolute;width:0;height:0;overflow:hidden';
  defs.innerHTML=DEFS+OS_DEFS; document.body.prepend(defs);
  [].forEach.call(document.querySelectorAll('svg[data-scene]'),function(svg,i){
    const sc=SCENES[svg.getAttribute('data-scene')]; if(!sc) return;
    const pfx='s'+i+'_'; let word=null;
    const draw=function(t){PFX=pfx;clipN=0;svg.innerHTML=sc.f(t);
      if(sc===SCENES.hero&&window.dropletWord){let k=null;HERO_WORDS.forEach(function(w){if(t>=w[0])k=w[1]});if(k!==word){word=k;window.dropletWord(k)}}};
    draw(sc.still);
    if(reduce||!window.requestAnimationFrame) return;
    let on=false, t0=0, last=0, run=false;
    const loop=function(now){
      if(!on||document.hidden){run=false;return}
      if(now-last>=33){last=now;draw(((now-t0)/1000)%sc.d)}
      requestAnimationFrame(loop);
    };
    const go=function(v){on=v;if(on&&!run){run=true;if(!t0)t0=performance.now();requestAnimationFrame(loop)}};
    if('IntersectionObserver' in window) new IntersectionObserver(function(es){go(es[0].isIntersecting)},{rootMargin:'60px 0px'}).observe(svg); else go(true);
    document.addEventListener('visibilitychange',function(){if(!document.hidden)go(on)});
  });
}
if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',start); else start();

})();
