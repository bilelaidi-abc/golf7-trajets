# Génère www/index.html (app Android, sans OBD) à partir de la version web app.html
import sys
src = sys.argv[1] if len(sys.argv) > 1 else 'web/app.html'
s = open(src).read()

def cut(a, b, repl=''):
    global s
    i = s.index(a); j = s.index(b, i) + len(b); s = s[:i] + repl + s[j:]

s = s.replace('        <span class="pill" id="obdPill"><span class="dot"></span>OBD non connecté</span>\n', '')
cut('      <div class="grid3" id="obdLive" hidden>', '</div>\n      </div>\n')
cut('    <div class="panel">\n      <div class="row between"><h3>Boîtier OBD-II</h3>', '<div class="note" id="obdNote" hidden></div>\n    </div>\n')
s = s.replace("La conso moyenne sert d'estimation quand l'OBD n'est pas branché.", "La conso moyenne sert à estimer le carburant de chaque trajet.")
s = s.replace('obd:km>50?{rpmAvg:2150,tempMax:92,samples:120}:null,obdFuelL:km>50?km*0.061:0,', 'obd:null,obdFuelL:0,')
cut('/* ---------- OBD-II via Bluetooth LE (ELM327) ---------- */', '/* ---------- démarrage ---------- */', '/* ---------- démarrage ---------- */')

a = s.index('/* ---------- enregistrement GPS ---------- */'); b = s.index('$("btnTrip").onclick=')
rec_js = r'''/* ---------- enregistrement GPS ---------- */
// Dans l'app Android, le GPS passe par un service de premier plan : il continue écran éteint.
const NATIVE=!!(window.Capacitor&&Capacitor.isNativePlatform&&Capacitor.isNativePlatform());
const BG=NATIVE?Capacitor.registerPlugin("BackgroundGeolocation"):null;
const RKEY="golf7.rec.v1";
let rec=null,watchId=null,timer=null,wakeLock=null,lastSave=0,resumedNote=false;
function setPill(el,cls,txt){el.className="pill"+(cls?" "+cls:"");el.innerHTML='<span class="dot"></span>';el.append(txt)}
function liveNote(t){$("liveNote").hidden=!t;$("liveNote").textContent=t||""}
async function startTrip(resumed){
  if(!NATIVE&&!("geolocation" in navigator)){liveNote("Ce téléphone ne donne pas accès au GPS.");return}
  rec=resumed||{id:"t"+Date.now(),start:Date.now(),pts:[],distKm:0,maxKmh:0,cat:"perso",title:"",note:"",consManual:0,obdFuelL:0,last:null};
  resumedNote=!!resumed;
  $("btnTrip").textContent="Terminer le trajet";$("btnTrip").classList.remove("primary");setPill($("gpsPill"),"rec","Enregistrement");
  liveNote(resumed?"Trajet en cours repris après la fermeture de l'app.":NATIVE?"Recherche du signal GPS… Tu peux éteindre l'écran : l'enregistrement continue.":"Recherche du signal GPS… Garde l'écran allumé pendant le trajet.");
  save(RKEY,rec);
  if(NATIVE){
    try{const LN=Capacitor.registerPlugin("LocalNotifications");const p=await LN.checkPermissions();if(p.display!=="granted")await LN.requestPermissions()}catch(e){}
    try{
      watchId=await BG.addWatcher({backgroundTitle:"Golf 7 · trajet en cours",backgroundMessage:"Enregistrement du trajet. Ouvre l'app pour le terminer.",requestPermissions:true,stale:false,distanceFilter:5},(loc,err)=>{
        if(err){if(err.code==="NOT_AUTHORIZED"){stopTrip(true);liveNote("La localisation est refusée. Dans les réglages Android de l'app, autorise la position, puis relance le trajet.");BG.openSettings().catch(()=>{})}return}
        if(loc)onPos({coords:{latitude:loc.latitude,longitude:loc.longitude,accuracy:loc.accuracy,speed:loc.speed},timestamp:loc.time||Date.now()})});
    }catch(e){stopTrip(true);liveNote("Impossible de démarrer le GPS : "+(e.message||e));return}
  }else{
    try{wakeLock=await navigator.wakeLock?.request("screen")}catch(e){}
    watchId=navigator.geolocation.watchPosition(onPos,err=>{
      if(err.code===1)stopTrip(true);
      liveNote(err.code===1?"L'accès à la position est refusé. Autorise la localisation dans les réglages, puis relance le trajet.":"Signal GPS indisponible pour l'instant ("+err.message+").")},
      {enableHighAccuracy:true,maximumAge:1000,timeout:20000});
  }
  timer=setInterval(updateLive,1000);
}
function onPos(p){if(!rec)return;const c=p.coords;if(c.accuracy>60)return;if(!resumedNote)liveNote("");
  const pt=[+c.latitude.toFixed(6),+c.longitude.toFixed(6),p.timestamp,0];
  let kmh=c.speed!=null&&c.speed>=0?c.speed*3.6:null;
  if(rec.last){const d=hav(rec.last,pt),dt=(pt[2]-rec.last[2])/1000;if(kmh==null&&dt>0)kmh=d/dt*3.6;
    if(d<Math.max(4,c.accuracy*.5)&&(kmh||0)<5){rec.lastKmh=kmh||0;return} // à l'arrêt : on ignore la dérive GPS
    if(dt>0&&d/dt*3.6>250)return; // saut aberrant
    rec.distKm+=d/1000}
  pt[3]=Math.round(kmh||0);rec.maxKmh=Math.max(rec.maxKmh,pt[3]);rec.lastKmh=pt[3];rec.pts.push(pt);rec.last=pt;updateLive();
  if(Date.now()-lastSave>15000){lastSave=Date.now();save(RKEY,rec)}} // sauvegarde régulière au cas où Android ferme l'app
function updateLive(){if(!rec)return;const ms=Date.now()-rec.start;$("lvSpeed").textContent=Math.round(rec.lastKmh||0);
  $("lvDist").textContent=fmt(rec.distKm);$("lvDur").textContent=dur(ms);$("lvAvg").textContent=Math.round(ms>0?rec.distKm/(ms/3600000):0)}
function stopTrip(discard){
  if(watchId!=null){if(NATIVE)BG.removeWatcher({id:watchId}).catch(()=>{});else navigator.geolocation.clearWatch(watchId)}
  watchId=null;clearInterval(timer);try{wakeLock&&wakeLock.release()}catch(e){}wakeLock=null;
  try{localStorage.removeItem(RKEY)}catch(e){}
  $("btnTrip").textContent="Démarrer le trajet";$("btnTrip").classList.add("primary");setPill($("gpsPill"),"","GPS inactif");
  const r=rec;rec=null;if(!r||discard)return;
  r.end=Date.now();r.durMs=r.end-r.start;
  delete r.last;delete r.lastKmh;
  if(r.distKm<0.1){liveNote("Trajet trop court (moins de 100 m) : il n'a pas été enregistré.");return}
  if(r.pts.length>800){const k=Math.ceil(r.pts.length/800);r.pts=r.pts.filter((p,i)=>i%k===0||i===r.pts.length-1)}
  trips=trips.filter(t=>!t.example);trips.unshift(r);
  if(settings.odo)settings.odo=String(Math.round(parseFloat(settings.odo)+r.distKm)),save(SKEY,settings);
  if(!persist())toast("Attention : impossible d'enregistrer le trajet");
  openDetail(r.id);toast("Trajet enregistré");
}
'''
s = s[:a] + rec_js + s[b:]
s = s.replace('$("btnTrip").onclick=()=>rec?stopTrip(false):startTrip();',
              '$("btnTrip").onclick=()=>rec?stopTrip(false):startTrip();\n{const pending=load(RKEY,null);if(pending&&pending.id)startTrip(pending)} // reprise d\'un trajet interrompu')
s = s.replace('function offer(name,text,type){\n', '''async function offer(name,text,type){
  if(NATIVE){try{const FS=Capacitor.registerPlugin("Filesystem"),SH=Capacitor.registerPlugin("Share");
    const w=await FS.writeFile({path:name,data:text,directory:"CACHE",encoding:"utf8"});await SH.share({title:name,files:[w.uri]})}
    catch(e){if(!/cancel/i.test(e.message||""))toast("Partage impossible : "+(e.message||e))}return}
''')
s = s.replace('if(!("geolocation" in navigator))liveNote("Ce navigateur ne donne pas accès au GPS.");\n', '')
# ---------- démarrage automatique au Bluetooth de la voiture ----------
s = s.replace('''    <div class="panel">
      <h3>Mes données</h3>''', '''    <div class="panel" id="autoPanel" hidden>
      <div class="row between"><h3>Démarrage automatique</h3><span class="pill" id="autoPill"><span class="dot"></span>Désactivé</span></div>
      <p class="muted" style="margin:0">Le trajet démarre seul quand le téléphone se connecte au Bluetooth de la voiture, et s'arrête une minute après la déconnexion. Pas besoin d'ouvrir l'app.</p>
      <div class="note" id="autoNote" hidden></div>
      <div class="row" id="autoActions"></div>
      <div class="day" id="autoDevices"></div>
    </div>
    <div class="panel">
      <h3>Mes données</h3>''', 1)
s = s.replace('if(t.example){const e=document.createElement("span");e.className="tag ex";',
              'if(t.auto){const e=document.createElement("span");e.className="tag";e.textContent="Auto";t2.appendChild(e)}\n    if(t.example){const e=document.createElement("span");e.className="tag ex";', 1)
auto_js = r'''/* ---------- démarrage automatique (service Android) ---------- */
const AUTO=NATIVE?Capacitor.registerPlugin("AutoTrip"):null;
async function autoRefresh(){
  if(!AUTO)return;let st;try{st=await AUTO.getStatus()}catch(e){return}
  $("autoPanel").hidden=false;
  const perms=st.bluetooth&&st.location&&st.background,on=!!st.carAddress&&perms;
  setPill($("autoPill"),on?"on":"",on?"Activé":"Désactivé");
  const note=$("autoNote"),act=$("autoActions");act.textContent="";
  if(!perms){note.hidden=false;note.textContent="Autorisations nécessaires : Bluetooth, et position « Toujours autoriser ». Sans elles, Android empêche le trajet de démarrer seul."+(st.location&&!st.background?" Il ne manque plus que Position › Toujours autoriser.":"")}
  else if(st.carAddress){note.hidden=false;note.textContent="Voiture : "+st.carName}
  else{note.hidden=false;note.textContent="Autorisations accordées. Choisis maintenant le Bluetooth de ta Golf."}
  const b=(t,cls,fn)=>{const x=document.createElement("button");x.textContent=t;if(cls)x.className=cls;x.onclick=fn;act.appendChild(x)};
  if(!perms)b("Autoriser l'accès","primary",autoGrant);
  else b(st.carAddress?"Changer de voiture":"Choisir le Bluetooth de la voiture",st.carAddress?"":"primary",autoPick);
  if(st.carAddress)b("Désactiver","danger",async()=>{await AUTO.setCar({});$("autoDevices").textContent="";autoRefresh();toast("Démarrage automatique désactivé")});
  if(!rec){
    if(st.current){setPill($("gpsPill"),"rec","Trajet automatique");$("btnTrip").disabled=true;
      liveNote("Trajet automatique en cours depuis "+hm(st.current.start)+" · "+fmt(st.current.distKm)+" km. Il apparaîtra dans l'historique une minute après la déconnexion du Bluetooth.")}
    else if($("btnTrip").disabled){$("btnTrip").disabled=false;setPill($("gpsPill"),"","GPS inactif");liveNote("");importAuto()}
  }
}
async function autoGrant(){
  try{const r=await AUTO.requestNext();if(!r.asked)toast("Autorisations accordées");else if(r.asked==="background")toast("Choisis Autorisations › Position › Toujours autoriser, puis reviens")}
  catch(e){toast(e.message||String(e))}
}
async function autoPick(){
  const box=$("autoDevices");box.textContent="";
  try{const {devices}=await AUTO.listPairedDevices();
    if(!devices.length){const n=document.createElement("div");n.className="note";n.textContent="Aucun appareil Bluetooth appairé. Connecte d'abord le téléphone à la Golf, puis reviens ici.";box.appendChild(n);return}
    const h=document.createElement("div");h.className="lbl";h.textContent="Choisis ta voiture";box.appendChild(h);
    for(const d of devices){const x=document.createElement("button");x.className="trip";x.style.gridTemplateColumns="1fr";x.textContent=d.name;
      x.onclick=async()=>{await AUTO.setCar({address:d.address,name:d.name});box.textContent="";toast("Voiture enregistrée : "+d.name);autoRefresh()};box.appendChild(x)}
  }catch(e){const n=document.createElement("div");n.className="note";n.textContent=e.message||String(e);box.appendChild(n)}
}
async function importAuto(){
  if(!AUTO)return;
  try{const {trips:arr}=await AUTO.takePendingTrips();if(!arr||!arr.length)return;
    trips=trips.filter(t=>!t.example);for(const t of arr)if(!trips.some(x=>x.id===t.id))trips.push(t);
    trips.sort((a,b)=>b.start-a.start);persist();
    toast(arr.length>1?arr.length+" trajets automatiques ajoutés":"1 trajet automatique ajouté");if(view==="history")renderList()}catch(e){}
}
if(AUTO){importAuto();autoRefresh();setInterval(autoRefresh,3000);
  document.addEventListener("visibilitychange",()=>{if(document.visibilityState==="visible"){importAuto();autoRefresh()}})}

/* ---------- démarrage ---------- */'''
s = s.replace('/* ---------- démarrage ---------- */', auto_js, 1)

for bad in ('btnObd', 'obdLive', 'obdPill', 'pollObd', 'navigator.bluetooth'):
    assert bad not in s, bad

head = '''<!doctype html>
<html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>:root{padding-top:env(safe-area-inset-top,0px)}body{margin:0;font:14px system-ui,sans-serif}img{max-width:100%}[hidden]{display:none!important}</style>
<script src="capacitor.js"></script>
</head><body>
'''
open('www/index.html', 'w').write(head + s + '\n</body></html>\n')
print('www/index.html écrit')
