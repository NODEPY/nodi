import { X509Certificate, verify, createHash } from "node:crypto";
import { Buffer } from "node:buffer";
// Private-alpha relay. Device credentials are provisioned by the operator.
// TLS with the existing device certificate pin MUST run inside binary frames.
const MAX_DEVICES = 1000, MAX_SESSIONS = 32, MAX_FRAME = 64 * 1024;
// Receivers that send `X-Nodi-Wait: 1` are held on /v1/incoming until a session arrives
// or LONG_POLL_MS passes, which cuts requests ~5x versus 10-second polling. Presence
// covers the hold plus a margin. Neither presence nor rate counters are persisted, so
// polling writes no Durable Object rows (the free plan allows 100,000 rows written/day).
const LONG_POLL_MS = 50_000, PRESENCE_MS = 75_000;
const MAX_BYTES = 64 * 1024 * 1024, WAIT_MS = 30_000, LIFE_MS = 600_000;
const hex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('');
const secret = () => hex(crypto.getRandomValues(new Uint8Array(32)));
const hash = async s => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const json = (data, status = 200) => Response.json(data, {status, headers: {'Cache-Control': 'no-store'}});
const fail = (status, error) => json({error}, status);
const idOK = s => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s);
// Downloads: owner browsers opt out via a cookie set by a link derived from ADMIN_TOKEN;
// link-preview bots and crawlers are not counted either.
const DOWNLOAD_PATH = /^\/download\/(?:(test)\/(android|macos)|(android|macos)|owner\/([a-f0-9]{64}))$/;
const BOT_UA = /bot|crawl|spider|preview|facebookexternalhit|embedly|whatsapp|skype|curl|wget|python|go-http|headless/i;
const ownerLink = token => hash('nodi-owner-link:' + token);
const ownerCookie = token => hash('nodi-owner-cookie:' + token);
const cookie = (request, name) => (request.headers.get('Cookie') || '').split(/;\s*/).find(c => c.startsWith(name + '='))?.slice(name.length + 1);
async function body(request, max = 1024) {
  if (!request.headers.get('Content-Type')?.startsWith('application/json')) throw Error('body');
  const reader = request.body?.getReader(); if (!reader) throw Error('body');
  let chunks = [], size = 0;
  const deadline = Date.now() + 5000;
  for (;;) {
    let timeout, part;
    try {
      part = await Promise.race([reader.read(), new Promise((_, reject) => {
        timeout = setTimeout(() => { reader.cancel().catch(() => {}); reject(Error('body_timeout')); }, Math.max(0, deadline - Date.now()));
      })]);
    } finally { clearTimeout(timeout); }
    const {value, done} = part; if (done) break;
    size += value.length; if (size > max) { await reader.cancel(); throw Error('body'); } chunks.push(value); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) {bytes.set(chunk, offset); offset += chunk.length;}
  const result = JSON.parse(new TextDecoder().decode(bytes));
  if (!result || typeof result !== 'object' || Array.isArray(result)) throw Error('body');
  return result;
}
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.protocol !== 'https:' && !(env.LOCAL_TEST === 'true' && ['localhost', '127.0.0.1'].includes(url.hostname))) return fail(403, 'https_required');
    if (url.search || request.headers.has('Origin')) return fail(400, 'native_clients_only');
    if (request.method === 'GET' && url.pathname === '/health') return json({status:'ok', protocol:1});
    if (!env.ADMIN_TOKEN || !/^[a-f0-9]{64}$/.test(env.ADMIN_TOKEN)) return fail(503, 'not_configured');
    return env.HUB.get(env.HUB.idFromName('private-alpha-v1')).fetch(request);
  }
};

export class RelayHub {
  constructor(ctx, env) {
    this.ctx = ctx; this.env = env;
    // Ephemeral state: lost on restart by design (rate windows and presence refill quickly).
    this.mem = {rates: new Map(), seen: new Map(), waiters: new Map(), wake: new Set(), adminRate: null, publicRate: null, alarmAt: undefined};
  }
  async load() {
    // Cache survives requests in this object; persist bounded records separately
    // rather than growing one value beyond Durable Object's value-size limit.
    if(!this.persisted) {
      this.persisted=new Map();
      const legacy=await this.ctx.storage.get('state');
      if(legacy?.format!==2) {
        this.legacy=legacy||{devices:{},sessions:{}};
      } else {
        let startAfter;
        for(;;){const page=await this.ctx.storage.list({prefix:'v2:',limit:1000,...(startAfter?{startAfter}:{})});for(const [k,v]of page)this.persisted.set(k,v);if(page.size<1000)break;startAfter=[...page.keys()].at(-1);}
      }
    }
    let s=this.legacy?structuredClone(this.legacy):{devices:{},sessions:{}};
    if(!this.legacy)for(const [key,value]of this.persisted){const [,group,id]=key.split(':');if(group==='meta')s[id]=structuredClone(value);else{(s[group]||={})[id]=structuredClone(value);}}
    if(!Array.isArray(s.feedback))s.feedback=Object.values(s.feedback||{}).sort((a,b)=>a.created-b.created);
    s.rooms ||= {};s.challenges ||= {};s.metrics ||= {};s.feedback ||= [];s.downloads ||= {};s.revoked ||= {};
    return s;
  }
  async save(s) {
    const next=new Map();
    for(const group of ['devices','sessions','rooms','challenges','metrics','downloads','revoked'])for(const [id,value]of Object.entries(s[group]||{}))next.set('v2:'+group+':'+id,value);
    for(const f of s.feedback){f.id ||= secret();f.created ||= Date.now();next.set('v2:feedback:'+f.id,f);}
    const changed=[...next].filter(([k,v])=>JSON.stringify(this.persisted.get(k))!==JSON.stringify(v));
    const removed=[...this.persisted.keys()].filter(k=>!next.has(k));
    if(changed.length||removed.length||this.legacy) await this.ctx.storage.transaction(async tx=>{
      for(let i=0;i<changed.length;i+=128)await tx.put(Object.fromEntries(changed.slice(i,i+128)));
      for(let i=0;i<removed.length;i+=128)await tx.delete(removed.slice(i,i+128));
      if(this.legacy){await tx.put('legacy-v1-backup',this.legacy);await tx.put('state',{format:2});}
    });
    this.persisted=new Map([...next].map(([k,v])=>[k,structuredClone(v)]));this.legacy=null;
    const deadlines=[...Object.values(s.sessions),...Object.values(s.rooms),...Object.values(s.challenges)].map(x=>x.deadline);
    if(Object.keys(s.metrics).length||Object.keys(s.downloads).length||s.feedback.length)deadlines.push((Math.floor(Date.now()/86400000)+1)*86400000);
    // Each setAlarm/deleteAlarm is a written row, so only touch the alarm when its time changes.
    const alarmAt=deadlines.length?Math.min(...deadlines):null;
    if(alarmAt!==this.mem.alarmAt){if(alarmAt===null)await this.ctx.storage.deleteAlarm();else await this.ctx.storage.setAlarm(alarmAt);this.mem.alarmAt=alarmAt;}
  }
  sockets(id) { return this.ctx.getWebSockets(id); }
  send(ws, data) {try {ws.send(data);return true;} catch {return false;}}
  end(s, id, reason = 'session_ended') {
    delete s.sessions[id];
    for (const ws of this.sockets(id)) {try {ws.close(1000, reason);} catch { /* already closed */ }}
  }
  prune(s) {
    for (const collection of [s.rooms,s.challenges]) for (const [id,item] of Object.entries(collection)) if(item.deadline <= Date.now()) delete collection[id];
    const oldest = new Date(Date.now()-30*86400000).toISOString().slice(0,10);
    for (const day of Object.keys(s.downloads)) if(day<oldest) delete s.downloads[day];
    for (const day of Object.keys(s.metrics)) if(day<oldest) delete s.metrics[day];
    s.feedback = s.feedback.filter(x=>x.day>=oldest);
    for (const [id, room] of Object.entries(s.sessions)) if (room.deadline <= Date.now()) this.end(s,id,'expired');}
  // Authenticate and charge the quota before reading a body. Then release the
  // object lock while the client uploads; revalidate credentials before mutation.
  async fetch(request) {
    const admission = await this.ctx.blockConcurrencyWhile(async () => {
      const s = await this.load(); this.prune(s);
      const auth = (['/v1/enroll/challenge','/v1/enroll'].includes(new URL(request.url).pathname)||DOWNLOAD_PATH.test(new URL(request.url).pathname)) ? await this.publicAdmission(request,s) : await this.authorize(request, s, true);
      await this.save(s); return auth;
    });
    if (admission instanceof Response) return admission;
    const path = new URL(request.url).pathname;
    let payload;
    if (request.method === 'POST') {
      try { payload = await body(request, ['/v1/enroll','/v1/rooms','/v1/rooms/join','/v1/feedback'].includes(path) ? 4096 : 1024); } catch { return fail(400, 'invalid_request'); }
    }
    const run = () => this.ctx.blockConcurrencyWhile(async () => {
      const s = await this.load(); this.prune(s);
      const result = await this.route(request, s, payload);
      await this.save(s);
      for (const id of this.mem.wake) this.mem.waiters.get(id)?.();
      this.mem.wake.clear();
      return result;
    });
    const first = await run();
    if (path !== '/v1/incoming' || request.method !== 'GET' || request.headers.get('X-Nodi-Wait') !== '1' || !admission.me || first.status !== 200) return first;
    if ((await first.clone().json()).sessions.length) return first;
    // Hold outside the object lock; a new session for this device (or a newer wait) wakes it.
    await new Promise(resolve => {
      const waiters = this.mem.waiters, me = admission.me;
      waiters.get(me)?.();
      const timer = setTimeout(wake, Number(this.env.LONG_POLL_MS) || LONG_POLL_MS); // env override is for tests
      function wake() { clearTimeout(timer); if (waiters.get(me) === wake) waiters.delete(me); resolve(); }
      waiters.set(me, wake);
    });
    return run();
  }
  async publicAdmission(request,s) {
    if(request.method!=='POST' && !(request.method==='GET' && new URL(request.url).pathname.startsWith('/download/')))return fail(405,'method_not_allowed');
    const minute=Math.floor(Date.now()/60000);
    const rate=(this.mem.publicRate?.minute===minute)?this.mem.publicRate:(this.mem.publicRate={minute,count:0,ips:{}});
    const ip=await hash(request.headers.get('CF-Connecting-IP') || 'local');
    if(++rate.count>100 || (rate.ips[ip]=(rate.ips[ip]||0)+1)>12)return fail(429,'rate_limit');
    return {public:true};
  }
  async enroll(request,s,payload) {
    const path=new URL(request.url).pathname;
    if(path==='/v1/enroll/challenge') {
      if(!idOK(payload?.id))return fail(400,'invalid_device');
      if(Object.keys(s.challenges).length>=128)return fail(429,'challenge_limit');
      const nonce=secret();s.challenges[nonce]={id:payload.id,deadline:Date.now()+60000};
      return json({nonce},201);
    }
    const {id,nonce,certificate,signature,testInstall}=payload||{};
    if(s.revoked[id])return fail(403,'device_revoked');
    const challenge=s.challenges[nonce];
    if(!challenge || challenge.id!==id)return fail(401,'invalid_proof');
    delete s.challenges[nonce];
    try {
      if(typeof certificate!=='string'||typeof signature!=='string'||certificate.length>2400||signature.length>200)throw Error();
      const cert=new X509Certificate(Buffer.from(certificate,'base64'));
      if(createHash('sha256').update(cert.raw).digest('hex')!==id || cert.publicKey.asymmetricKeyType!=='ec' ||
        !verify('sha256',Buffer.from('nodi-enroll-v1\n'+id+'\n'+nonce),cert.publicKey,Buffer.from(signature,'base64')))throw Error();
    }catch{return fail(401,'invalid_proof');}
    if(!s.devices[id]&&Object.keys(s.devices).length>=MAX_DEVICES)return fail(429,'device_limit');
    const token=secret(),old=s.devices[id];
    s.devices[id]={...old,hash:await hash(token),peers:old?.peers||[],minute:0,requests:0,test:old?.test===true||testInstall===true};
    for(const [sid,r] of Object.entries(s.sessions))if([r.sender,r.receiver].includes(id))this.end(s,sid,'credential_changed');
    return json({id,token},201);
  }
  async authorize(request, s, charge) {
    const path = new URL(request.url).pathname;
    const bearer = request.headers.get('Authorization') || '';
    if (!/^Bearer [a-f0-9]{64}$/.test(bearer)) return fail(401,'unauthorized');
    const digest = await hash(bearer.slice(7));
    if (path.startsWith('/v1/admin/')) {
      if (digest !== await hash(this.env.ADMIN_TOKEN)) return fail(401,'unauthorized');
      if (charge) {
        const now = Date.now();
        if (!this.mem.adminRate || now - this.mem.adminRate.start >= 60_000) this.mem.adminRate = {start:now, count:0};
        if (++this.mem.adminRate.count > 120) return fail(429,'rate_limit');
      }
      return {admin:true};
    }
    const found = Object.entries(s.devices).find(([,d]) => d.hash === digest);
    if (!found) return fail(401,'unauthorized');
    const [me, device] = found;
    if (charge) {
      const minute = Math.floor(Date.now()/60_000);
      let rate = this.mem.rates.get(me);
      if (rate?.minute !== minute) this.mem.rates.set(me, rate = {minute, requests: 0});
      if (++rate.requests > 120) return fail(429,'rate_limit');
    }
    return {me, device};
  }
  async route(request, s, payload) {
    const path = new URL(request.url).pathname, method = request.method;
    const download=path.match(DOWNLOAD_PATH);
    if(download&&method==='GET') {
      if(download[4]) {
        if(download[4]!==await ownerLink(this.env.ADMIN_TOKEN))return fail(404,'not_found');
        return new Response('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Nodi</title><p>Завантаження Nodi з цього браузера більше не враховуються в статистиці.</p>',{status:200,headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store','Content-Security-Policy':"default-src 'none'",'Referrer-Policy':'no-referrer','Set-Cookie':`nodi_owner=${await ownerCookie(this.env.ADMIN_TOKEN)}; Path=/download; Max-Age=34560000; Secure; HttpOnly; SameSite=Lax`}});
      }
      const platform=download[2]||download[3];
      const target=this.env[platform==='android'?'DOWNLOAD_ANDROID_URL':'DOWNLOAD_MACOS_URL'];
      if(typeof target!=='string'||!target.startsWith('https://github.com/NODEPY/nodi/releases/download/'))return fail(503,'download_not_published');
      const owner=cookie(request,'nodi_owner')===await ownerCookie(this.env.ADMIN_TOKEN);
      const bot=BOT_UA.test(request.headers.get('User-Agent')||'');
      if(download[3]&&!owner&&!bot){const day=new Date().toISOString().slice(0,10);const d=(s.downloads[day]||={android:0,macos:0});d[platform]++;}
      return new Response(null,{status:302,headers:{Location:target,'Cache-Control':'no-store'}});
    }
    if(['/v1/enroll/challenge','/v1/enroll'].includes(path))return this.enroll(request,s,payload);
    const auth = await this.authorize(request, s, false);
    if (auth instanceof Response) return auth;
    if (auth.admin) {
      if(path==='/v1/admin/stats'&&method==='GET') {
        const days=Object.entries(s.metrics).sort().map(([day,devices])=>{
          const values=Object.entries(devices).filter(([id])=>!s.devices[id]?.test).map(([,v])=>v);
          return {day,activeInstalls:values.length,files:values.reduce((n,v)=>n+v.files,0),texts:values.reduce((n,v)=>n+v.texts,0),platforms:values.reduce((r,v)=>(r[v.platform]=(r[v.platform]||0)+1,r),{})};
        });
        return json({downloadRequests:s.downloads,days,feedback:s.feedback.filter(x=>!s.devices[x.device]?.test).map(({device,id,created,...x})=>x),retentionDays:30});
      }
      const testPath=path.match(/^\/v1\/admin\/test-devices\/([a-f0-9]{64})$/);
      if(testPath&&method==='PUT') {if(!s.devices[testPath[1]])return fail(404,'not_found');s.devices[testPath[1]].test=true;for(const day of Object.values(s.metrics))delete day[testPath[1]];s.feedback=s.feedback.filter(x=>x.device!==testPath[1]);return json({ok:true});}
      if (path === '/v1/admin/devices' && method === 'POST') {
        const {id} = payload;
        if (!idOK(id)) return fail(400,'invalid_device');
        if (s.devices[id]) return fail(409,'already_registered');
        if (Object.keys(s.devices).length >= MAX_DEVICES) return fail(429,'device_limit');
        delete s.revoked[id];
        const token = secret(); s.devices[id] = {hash:await hash(token), peers:[], minute:0, requests:0};
        return json({id, token},201);
      }
      const match = path.match(/^\/v1\/admin\/devices\/([a-f0-9]{64})$/);
      if (match && method === 'DELETE') {
        if(s.devices[match[1]])s.revoked[match[1]]=true;
        delete s.devices[match[1]];
        for(const day of Object.values(s.metrics))delete day[match[1]];
        s.feedback=s.feedback.filter(x=>x.device!==match[1]);
        for (const d of Object.values(s.devices)) d.peers = d.peers.filter(p => p !== match[1]);
        for (const [id,r] of Object.entries(s.sessions)) if (r.sender===match[1] || r.receiver===match[1]) this.end(s,id,'revoked');
        return json({ok:true});
      }
      return fail(404,'not_found');
    }
    const {me, device} = auth;
    if(path==='/v1/usage'&&method==='POST') {
      if(device.test||payload?.testInstall===true){device.test=true;return json({ok:true});}
      if(payload?.consent!==true||!['android','macos'].includes(payload.platform)||!['active','file','text'].includes(payload.event))return fail(400,'invalid_usage');
      const day=new Date().toISOString().slice(0,10),daily=(s.metrics[day]||={});
      const v=(daily[me]||={platform:payload.platform,files:0,texts:0});
      if(payload.event==='file')v.files++;if(payload.event==='text')v.texts++;
      return json({ok:true});
    }
    if(path==='/v1/feedback'&&method==='POST') {
      if(typeof payload?.text!=='string'||payload.text.trim().length<1||payload.text.length>1000)return fail(400,'invalid_feedback');
      const day=new Date().toISOString().slice(0,10);
      if(s.feedback.filter(x=>x.device===me&&x.day===day).length>=3||s.feedback.length>=300)return fail(429,'feedback_limit');
      if(!device.test)s.feedback.push({device:me,day,text:payload.text.trim()});
      return json({ok:true});
    }
    // Only encrypted pairing payloads are stored; the room code never reaches us.
    const blobOK=x=>typeof x==='string'&&x.length>=40&&x.length<=3000&&/^[A-Za-z0-9+/=]+$/.test(x);
    if(path==='/v1/rooms'&&method==='POST') {
      if(!idOK(payload?.id)||!blobOK(payload.blob))return fail(400,'invalid_room');
      if(s.rooms[payload.id])return fail(409,'room_exists');
      if(Object.keys(s.rooms).length>=32||Object.values(s.rooms).filter(r=>r.host===me).length>=2)return fail(429,'room_limit');
      s.rooms[payload.id]={host:me,blob:payload.blob,deadline:Date.now()+300000,approved:false};
      return json({id:payload.id},201);
    }
    if(path==='/v1/rooms/join'&&method==='POST') {
      const r=s.rooms[payload?.id];
      if(!r||r.host===me||r.guest||!blobOK(payload.blob))return fail(404,'room_unavailable');
      r.guest=me;r.guestBlob=payload.blob;return json({host:r.host});
    }
    const roomPath=path.match(/^\/v1\/rooms\/([a-f0-9]{64})(\/approve)?$/);
    if(roomPath) {
      const r=s.rooms[roomPath[1]];
      if(!r||![r.host,r.guest].includes(me))return fail(404,'room_unavailable');
      if(method==='DELETE'){delete s.rooms[roomPath[1]];return json({ok:true});}
      if(method==='POST'&&roomPath[2]&&me===r.host&&r.guest) {
        if(!s.devices[r.guest])return fail(404,'peer_unavailable');
        for(const [a,b] of [[r.host,r.guest],[r.guest,r.host]])if(!s.devices[a].peers.includes(b))s.devices[a].peers.push(b);
        r.approved=true;return json({ok:true});
      }
      if(method==='GET'&&!roomPath[2])return json(me===r.host?{guest:r.guest||'',blob:r.guestBlob||'',approved:r.approved}:{host:r.host,approved:r.approved,blob:r.approved?r.blob:''});
      return fail(405,'method_not_allowed');
    }
    const peerPath = path.match(/^\/v1\/peers\/([a-f0-9]{64})$/);
    if (peerPath && ['PUT','DELETE'].includes(method)) {
      const peer = peerPath[1];
      if (peer===me || !s.devices[peer]) return fail(404,'peer_unavailable');
      device.peers = device.peers.filter(p=>p!==peer);
      if (method==='PUT') device.peers.push(peer);
      else for (const [id,r] of Object.entries(s.sessions)) if ([r.sender,r.receiver].includes(me) && [r.sender,r.receiver].includes(peer)) this.end(s,id,'unpaired');
      return json({ok:true});
    }
    if (path==='/v1/peers' && method==='GET') {
      return json({peers:device.peers.filter(id=>s.devices[id]?.peers.includes(me)).map(id=>({id,online:this.mem.waiters.has(id)||Date.now()-(this.mem.seen.get(id)||0)<PRESENCE_MS}))});
    }
    if (path==='/v1/incoming' && method==='GET') {
      this.mem.seen.set(me,Date.now());
      return json({sessions:Object.entries(s.sessions).filter(([,r])=>r.receiver===me && !r.usedReceiver).map(([id,r])=>({id,from:r.sender,ticket:r.receiverTicket,expiresAt:r.deadline}))});
    }
    if (path==='/v1/sessions' && method==='POST') {
      const {peer} = payload;
      if (!idOK(peer) || !device.peers.includes(peer) || !s.devices[peer]?.peers.includes(me)) return fail(403,'peer_not_authorized');
      const rooms = Object.values(s.sessions);
      if (rooms.length>=MAX_SESSIONS || rooms.filter(r=>[r.sender,r.receiver].includes(me)).length>=2 || rooms.filter(r=>[r.sender,r.receiver].includes(peer)).length>=2) return fail(429,'session_limit');
      const id=secret(), ticket=secret();
      s.sessions[id]={sender:me,receiver:peer,senderHash:await hash(ticket),receiverTicket:secret(),usedSender:false,usedReceiver:false,deadline:Date.now()+WAIT_MS,bytes:0,messages:0};
      this.mem.wake.add(peer); // woken after save(), so the receiver sees the stored session
      return json({id,ticket,expiresAt:s.sessions[id].deadline},201);
    }
    const sessionPath = path.match(/^\/v1\/sessions\/([a-f0-9]{64})$/);
    if (sessionPath) {
      const id=sessionPath[1], r=s.sessions[id];
      if (!r || ![r.sender,r.receiver].includes(me)) return fail(404,'session_unavailable');
      if (method==='DELETE') {this.end(s,id,'cancelled');return json({ok:true});}
      if (method!=='GET' || request.headers.get('Upgrade')?.toLowerCase()!=='websocket') return fail(426,'websocket_required');
      const sender = r.sender===me, used = sender ? 'usedSender' : 'usedReceiver';
      const ticket = request.headers.get('X-Nodi-Ticket');
      if (!idOK(ticket) || await hash(ticket)!==(sender ? r.senderHash : await hash(r.receiverTicket))) return fail(401,'invalid_ticket');
      if (r[used]) return fail(409,'ticket_used');
      const pair = new WebSocketPair();
      r[used]=true;
      this.ctx.acceptWebSocket(pair[1],[id]); pair[1].serializeAttachment({id,me});
      if (r.usedSender && r.usedReceiver) {
        r.deadline=Date.now()+LIFE_MS;
        for (const ws of this.sockets(id)) this.send(ws, JSON.stringify({type:'ready'}));
      }
      return new Response(null,{status:101,webSocket:pair[0]});
    }
    return fail(404,'not_found');
  }
  webSocketMessage(ws,message) {
    return this.ctx.blockConcurrencyWhile(async()=>{
      const s=await this.load(); this.prune(s);
      // Per-frame counters live in each socket's attachment (kept across hibernation, never
      // written to storage), so forwarding a transfer writes no Durable Object rows.
      const mine=ws.deserializeAttachment(), {id,me}=mine, r=s.sessions[id];
      if (!r) {ws.close(1000,'expired');await this.save(s);return;}
      const peers=this.sockets(id).filter(x=>x.deserializeAttachment().me!==me);
      const theirs=peers.length===1 ? peers[0].deserializeAttachment() : null;
      const messages=(mine.messages||0)+1;
      if (messages+(theirs?.messages||0)>4096 || typeof message==='string' || message.byteLength>MAX_FRAME || !r.usedSender || !r.usedReceiver || !theirs) this.end(s,id,'invalid_frame');
      else if (message.byteLength===0) {
        // Only one chunk may be in flight in each direction. An empty frame acknowledges
        // the peer's chunk; a slow reader therefore bounds the sender's queue.
        if (!theirs.pending) this.end(s,id,'unexpected_ack');
        else {
          ws.serializeAttachment({...mine,messages}); peers[0].serializeAttachment({...theirs,pending:false});
          if(!this.send(peers[0],message))this.end(s,id,'peer_closed');
        }
      } else if (mine.pending || message.byteLength>MAX_BYTES-(mine.bytes||0)-(theirs.bytes||0)) this.end(s,id,'transfer_limit');
      else {
        ws.serializeAttachment({...mine,messages,bytes:(mine.bytes||0)+message.byteLength,pending:true});
        if(!this.send(peers[0],message))this.end(s,id,'peer_closed');
      }
      await this.save(s);
    });
  }
  webSocketClose(ws) {return this.cleanup(ws);}
  webSocketError(ws) {return this.cleanup(ws);}
  cleanup(ws) {return this.ctx.blockConcurrencyWhile(async()=>{const s=await this.load();this.end(s,ws.deserializeAttachment().id);await this.save(s);});}
  alarm() {return this.ctx.blockConcurrencyWhile(async()=>{const s=await this.load();this.prune(s);await this.save(s);});}
}
