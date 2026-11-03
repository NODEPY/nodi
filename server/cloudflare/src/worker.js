// Private-alpha relay. Device credentials are provisioned by the operator.
// TLS with the existing device certificate pin MUST run inside binary frames.
const MAX_DEVICES = 64, MAX_SESSIONS = 16, MAX_FRAME = 64 * 1024;
const MAX_BYTES = 64 * 1024 * 1024, WAIT_MS = 30_000, LIFE_MS = 600_000;
const hex = b => [...new Uint8Array(b)].map(x => x.toString(16).padStart(2, '0')).join('');
const secret = () => hex(crypto.getRandomValues(new Uint8Array(32)));
const hash = async s => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const json = (data, status = 200) => Response.json(data, {status, headers: {'Cache-Control': 'no-store'}});
const fail = (status, error) => json({error}, status);
const idOK = s => typeof s === 'string' && /^[a-f0-9]{64}$/.test(s);
async function body(request) {
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
    size += value.length; if (size > 1024) { await reader.cancel(); throw Error('body'); } chunks.push(value); }
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
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }
  async load() {return await this.ctx.storage.get('state') || {devices:{}, sessions:{}};}
  async save(s) {
    await this.ctx.storage.put('state', s);
    const deadlines = Object.values(s.sessions).map(x => x.deadline);
    if (deadlines.length) await this.ctx.storage.setAlarm(Math.min(...deadlines));
    else await this.ctx.storage.deleteAlarm();
  }
  sockets(id) { return this.ctx.getWebSockets(id); }
  send(ws, data) {try {ws.send(data);return true;} catch {return false;}}
  end(s, id, reason = 'session_ended') {
    delete s.sessions[id];
    for (const ws of this.sockets(id)) {try {ws.close(1000, reason);} catch { /* already closed */ }}
  }
  prune(s) {for (const [id, room] of Object.entries(s.sessions)) if (room.deadline <= Date.now()) this.end(s,id,'expired');}
  // Authenticate and charge the quota before reading a body. Then release the
  // object lock while the client uploads; revalidate credentials before mutation.
  async fetch(request) {
    const admission = await this.ctx.blockConcurrencyWhile(async () => {
      const s = await this.load(); this.prune(s);
      const auth = await this.authorize(request, s, true);
      await this.save(s); return auth;
    });
    if (admission instanceof Response) return admission;
    const path = new URL(request.url).pathname;
    let payload;
    if (request.method === 'POST' && ['/v1/admin/devices', '/v1/sessions'].includes(path)) {
      try { payload = await body(request); } catch { return fail(400, 'invalid_request'); }
    }
    return this.ctx.blockConcurrencyWhile(async () => {
      const s = await this.load(); this.prune(s);
      const result = await this.route(request, s, payload);
      await this.save(s); return result;
    });
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
        if (!s.adminRate || now - s.adminRate.start >= 60_000) s.adminRate = {start:now, count:0};
        if (++s.adminRate.count > 120) return fail(429,'rate_limit');
      }
      return {admin:true};
    }
    const found = Object.entries(s.devices).find(([,d]) => d.hash === digest);
    if (!found) return fail(401,'unauthorized');
    const [me, device] = found;
    if (charge) {
      const minute = Math.floor(Date.now()/60_000);
      if (device.minute !== minute) {device.minute=minute; device.requests=0;}
      if (++device.requests > 120) return fail(429,'rate_limit');
    }
    return {me, device};
  }
  async route(request, s, payload) {
    const path = new URL(request.url).pathname, method = request.method;
    const auth = await this.authorize(request, s, false);
    if (auth instanceof Response) return auth;
    if (auth.admin) {
      if (path === '/v1/admin/devices' && method === 'POST') {
        const {id} = payload;
        if (!idOK(id)) return fail(400,'invalid_device');
        if (s.devices[id]) return fail(409,'already_registered');
        if (Object.keys(s.devices).length >= MAX_DEVICES) return fail(429,'device_limit');
        const token = secret(); s.devices[id] = {hash:await hash(token), peers:[], minute:0, requests:0};
        return json({id, token},201);
      }
      const match = path.match(/^\/v1\/admin\/devices\/([a-f0-9]{64})$/);
      if (match && method === 'DELETE') {
        delete s.devices[match[1]];
        for (const d of Object.values(s.devices)) d.peers = d.peers.filter(p => p !== match[1]);
        for (const [id,r] of Object.entries(s.sessions)) if (r.sender===match[1] || r.receiver===match[1]) this.end(s,id,'revoked');
        return json({ok:true});
      }
      return fail(404,'not_found');
    }
    const {me, device} = auth;
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
      return json({peers:device.peers.filter(id=>s.devices[id]?.peers.includes(me)).map(id=>({id,online:Date.now()-(s.devices[id].lastSeen || 0)<45_000}))});
    }
    if (path==='/v1/incoming' && method==='GET') {
      device.lastSeen=Date.now();
      return json({sessions:Object.entries(s.sessions).filter(([,r])=>r.receiver===me && !r.usedReceiver).map(([id,r])=>({id,from:r.sender,ticket:r.receiverTicket,expiresAt:r.deadline}))});
    }
    if (path==='/v1/sessions' && method==='POST') {
      const {peer} = payload;
      if (!idOK(peer) || !device.peers.includes(peer) || !s.devices[peer]?.peers.includes(me)) return fail(403,'peer_not_authorized');
      const rooms = Object.values(s.sessions);
      if (rooms.length>=MAX_SESSIONS || rooms.filter(r=>[r.sender,r.receiver].includes(me)).length>=2 || rooms.filter(r=>[r.sender,r.receiver].includes(peer)).length>=2) return fail(429,'session_limit');
      const id=secret(), ticket=secret();
      s.sessions[id]={sender:me,receiver:peer,senderHash:await hash(ticket),receiverTicket:secret(),usedSender:false,usedReceiver:false,deadline:Date.now()+WAIT_MS,bytes:0,messages:0};
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
      const {id,me}=ws.deserializeAttachment(), r=s.sessions[id];
      if (!r) {ws.close(1000,'expired');await this.save(s);return;}
      const peers=this.sockets(id).filter(x=>x.deserializeAttachment().me!==me);
      if (++r.messages>4096 || typeof message==='string' || message.byteLength>MAX_FRAME || !r.usedSender || !r.usedReceiver || peers.length!==1) this.end(s,id,'invalid_frame');
      else {
        // Only one chunk may be in flight in each direction. The peer must ack
        // consumption before more data, bounding slow-reader queues.
        const key = me===r.sender ? 'senderPending' : 'receiverPending';
        const other = me===r.sender ? 'receiverPending' : 'senderPending';
        if (message.byteLength===0) {
          if (!r[other]) this.end(s,id,'unexpected_ack');
          else {r[other]=false;if(!this.send(peers[0],message))this.end(s,id,'peer_closed');}
        } else if (r[key] || message.byteLength>MAX_BYTES-r.bytes) this.end(s,id,'transfer_limit');
        else {r.bytes+=message.byteLength;r[key]=true;if(!this.send(peers[0],message))this.end(s,id,'peer_closed');}
      }
      await this.save(s);
    });
  }
  webSocketClose(ws) {return this.cleanup(ws);}
  webSocketError(ws) {return this.cleanup(ws);}
  cleanup(ws) {return this.ctx.blockConcurrencyWhile(async()=>{const s=await this.load();this.end(s,ws.deserializeAttachment().id);await this.save(s);});}
  alarm() {return this.ctx.blockConcurrencyWhile(async()=>{const s=await this.load();this.prune(s);await this.save(s);});}
}
