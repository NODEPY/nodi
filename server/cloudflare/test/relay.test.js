import {execFileSync} from 'node:child_process';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join as pathJoin} from 'node:path';
import {test, before, after} from 'node:test';
import assert from 'node:assert/strict';
import {Miniflare} from 'miniflare';
import {readFile} from 'node:fs/promises';
import {randomBytes, X509Certificate, sign, createHash} from 'node:crypto';
import {Duplex} from 'node:stream';
import tls from 'node:tls';
import {once} from 'node:events';
const admin='a'.repeat(64), fresh=()=>randomBytes(32).toString('hex');
let mf, worker;
before(async()=>{
 mf=new Miniflare({modules:true,scriptPath:decodeURIComponent(new URL('../src/worker.js',import.meta.url).pathname),compatibilityFlags:['nodejs_compat'],script:await readFile(new URL('../src/worker.js',import.meta.url),'utf8'),compatibilityDate:'2026-08-06',durableObjects:{HUB:{className:'RelayHub',useSQLite:true}},bindings:{ADMIN_TOKEN:admin}});
 worker=await mf.getWorker();
});
after(async()=>{await mf.dispose();});
async function call(path,token,method='GET',data,headers={}) {
 return mf.dispatchFetch(`https://relay.test${path}`,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(data?{'Content-Type':'application/json'}:{}),...headers},body:data?JSON.stringify(data):undefined});
}
async function device(){const id=fresh();const r=await call('/v1/admin/devices',admin,'POST',{id});assert.equal(r.status,201);return r.json();}
async function pair(a,b){for(const [x,y] of [[a,b],[b,a]])assert.equal((await call(`/v1/peers/${y.id}`,x.token,'PUT')).status,200);}
async function room(a,b){const r=await call('/v1/sessions',a.token,'POST',{peer:b.id});assert.equal(r.status,201);const outgoing=await r.json();const incoming=await (await call('/v1/incoming',b.token)).json();return [outgoing,incoming.sessions.find(x=>x.id===outgoing.id)];}
function messages(ws){const queue=[],waiters=[];ws.addEventListener('message',e=>{if(waiters.length)waiters.shift()(e.data);else queue.push(e.data);});return ()=>new Promise((resolve,reject)=>{if(queue.length)return resolve(queue.shift());const timer=setTimeout(()=>reject(Error('message timeout')),3000);waiters.push(x=>{clearTimeout(timer);resolve(x);});});}
async function join(d,r){return call(`/v1/sessions/${r.id}`,d.token,'GET',undefined,{Upgrade:'websocket','X-Nodi-Ticket':r.ticket});}

test('TLS-only, no query credentials, native clients and admin authentication',async()=>{
 assert.equal((await mf.dispatchFetch('http://relay.test/health')).status,403);
 assert.equal((await call('/health?token=no')).status,400);
 assert.equal((await call('/health',null,'GET',undefined,{Origin:'https://evil.test'})).status,400);
 assert.equal((await call('/health')).status,200);
 assert.equal((await call('/v1/admin/devices',fresh(),'POST',{id:fresh()})).status,401);
 assert.equal((await call('/v1/incoming')).status,401);
});
test('mutual authorization, private inbox and endpoint-bound tickets',async()=>{
 const a=await device(),b=await device(),c=await device();
 assert.equal((await call('/v1/sessions',a.token,'POST',{peer:b.id})).status,403);
 await call(`/v1/peers/${b.id}`,a.token,'PUT');
 assert.equal((await call('/v1/sessions',a.token,'POST',{peer:b.id})).status,403);
 await pair(a,b);const [out,inc]=await room(a,b);
 assert.deepEqual((await (await call('/v1/incoming',c.token)).json()).sessions,[]);
 assert.equal((await join(c,out)).status,404);
 assert.equal((await join(a,{...out,ticket:inc.ticket})).status,401);
 assert.equal((await call(`/v1/sessions/${out.id}`,a.token,'DELETE')).status,200);
 assert.equal((await join(a,out)).status,404);
});
test('binary duplex forwarding, flow control, replay and revocation',async()=>{
 const a=await device(),b=await device();await pair(a,b);const [out,inc]=await room(a,b);
 const ra=await join(a,out);assert.equal(ra.status,101);const wa=ra.webSocket;wa.accept();const nextA=messages(wa);
 assert.equal((await join(a,out)).status,409);
 const rb=await join(b,inc);assert.equal(rb.status,101);const wb=rb.webSocket;wb.accept();const nextB=messages(wb);
 assert.equal(JSON.parse(await nextA()).type,'ready');assert.equal(JSON.parse(await nextB()).type,'ready');
 wa.send(new Uint8Array([1,2,3]));assert.deepEqual(new Uint8Array(await nextB()),new Uint8Array([1,2,3]));
 wb.send(new ArrayBuffer(0));assert.equal((await nextA()).byteLength,0);
 wb.send(new Uint8Array([4,5]));assert.deepEqual(new Uint8Array(await nextA()),new Uint8Array([4,5]));
 wa.send(new ArrayBuffer(0));await nextB();
 await call(`/v1/admin/devices/${b.id}`,admin,'DELETE');
 assert.equal((await call('/v1/incoming',b.token)).status,401);
 assert.equal((await join(a,out)).status,404);
 wa.close();wb.close();
});
test('unpair closes authorization; session and request quotas',async()=>{
 const a=await device(),b=await device();await pair(a,b);
 const [first]=await room(a,b);await room(a,b);
 assert.equal((await call('/v1/sessions',a.token,'POST',{peer:b.id})).status,429);
 await call(`/v1/peers/${b.id}`,a.token,'DELETE');
 assert.equal((await join(a,first)).status,404);
 assert.equal((await call('/v1/sessions',a.token,'POST',{peer:b.id})).status,403);
 let response;for(let i=0;i<121;i++)response=await call('/v1/incoming',b.token);
 assert.equal(response.status,429);
});
test('malformed and oversized bodies rejected without registration',async()=>{
 assert.equal((await call('/v1/admin/devices',admin,'POST',{id:'bad'})).status,400);
 assert.equal((await call('/v1/admin/devices',admin,'POST',{id:fresh(),padding:'x'.repeat(2000)})).status,400);
});
function streamedBody(text,{close=false}={}){
 let controller;
 const body=new ReadableStream({start(c){controller=c;c.enqueue(new TextEncoder().encode(text));if(close)c.close();}});
 return {body,append(text){controller.enqueue(new TextEncoder().encode(text));},close(){try{controller.close();}catch{ /* already closed or cancelled */ }}};
}
// Call the Worker entrypoint directly: an HTTP client that is still uploading
// sees a reset when the server abandons a slow body, which is runtime-specific.
function bodyCall(stream){
 return worker.fetch('https://relay.test/v1/admin/devices',{method:'POST',duplex:'half',headers:{Authorization:`Bearer ${admin}`,'Content-Type':'application/json'},body:stream});
}
test('slow and truncated request bodies are bounded and never register a device',{timeout:15000},async()=>{
 const active=await device();
 const slowId=fresh(),slow=streamedBody(`{"id":"${slowId}"`);
 const started=Date.now(),pending=bodyCall(slow.body);
 await new Promise(resolve=>setTimeout(resolve,100));
 const otherStarted=Date.now();
 assert.equal((await call('/v1/incoming',active.token)).status,200);
 assert.ok(Date.now()-otherStarted<2000,'slow upload must not lock out other devices');
 const slowResponse=await pending,slowElapsed=Date.now()-started;
 slow.close();
 assert.equal(slowResponse.status,400);
 assert.ok(slowElapsed>=4500,`expected the 5s body timeout, got ${slowElapsed}ms`);
 const truncatedId=fresh(),truncatedStarted=Date.now();
 assert.equal((await bodyCall(streamedBody(`{"id":"${truncatedId}"`,{close:true}).body)).status,400);
 assert.ok(Date.now()-truncatedStarted<4500,'truncated body must fail before the timeout');
 // A 400 is only proof of rejection if the partial body did not create the device.
 for(const id of [slowId,truncatedId])assert.equal((await call('/v1/admin/devices',admin,'POST',{id})).status,201);
});

test('presence visible only for mutually authorized peers',async()=>{
 const a=await device(),b=await device(),c=await device();await pair(a,b);
 let peers=(await (await call('/v1/peers',a.token)).json()).peers;
 assert.deepEqual(peers,[{id:b.id,online:false}]);
 await call('/v1/incoming',b.token);
 peers=(await (await call('/v1/peers',a.token)).json()).peers;
 assert.deepEqual(peers,[{id:b.id,online:true}]);
 assert.deepEqual((await (await call('/v1/peers',c.token)).json()).peers,[]);
});

function closed(ws){return new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(Error('close timeout')),3000);ws.addEventListener('close',()=>{clearTimeout(timeout);resolve();},{once:true});});}
for (const kind of ['text','oversized','unsolicited-ack','before-ready','no-backpressure']) {
 test(`reject ${kind} frame and invalidate session`,async()=>{
  const a=await device(),b=await device();await pair(a,b);const [out,inc]=await room(a,b);
  const ra=await join(a,out),wa=ra.webSocket;wa.accept();const nextA=messages(wa);
  let wb;
  if(kind!=='before-ready'){
   const rb=await join(b,inc);wb=rb.webSocket;wb.accept();const nextB=messages(wb);
   await nextA();await nextB();
  }
  const ended=closed(wa);
  if(kind==='text')wa.send('plaintext');
  else if(kind==='oversized')wa.send(new Uint8Array(65537));
  else if(kind==='unsolicited-ack')wa.send(new ArrayBuffer(0));
  else {wa.send(new Uint8Array([1]));if(kind==='no-backpressure')wa.send(new Uint8Array([2]));}
  await ended;
  assert.equal((await join(a,out)).status,404);
  wa.close();wb?.close();
 });
}

test('unclaimed tickets expire and cannot be reused', {timeout:35000}, async()=>{
 const a=await device(),b=await device();await pair(a,b);const [out]=await room(a,b);
 await new Promise(resolve=>setTimeout(resolve,31000));
 assert.equal((await join(a,out)).status,404);
 assert.deepEqual((await (await call('/v1/incoming',b.token)).json()).sessions,[]);
});

// Test adapter only: production clients must implement the same bounded framing.
class RelayStream extends Duplex {
 constructor(ws) {
  super(); this.ws=ws; this.pending=null; this.ackNeeded=false;
  ws.addEventListener('error',()=>this.destroy());
  ws.addEventListener('message',e=>{
   if(this.destroyed || typeof e.data==='string') return;
   const chunk=Buffer.from(e.data);
   if(!chunk.length){const callback=this.pending;this.pending=null;callback?.();return;}
   if(this.push(chunk))this.ack();else this.ackNeeded=true;
  });
  ws.addEventListener('close',()=>{this.push(null);this.destroy();});
 }
 // A closed WebSocket throws on send(); TLS veto/teardown races must not escape
 // as an uncaught exception from an event listener.
 send(data){if(this.destroyed || this.ws.readyState!==1)return false;
  try{this.ws.send(data);return true;}catch{return false;}}
 ack(){this.ackNeeded=false;return this.send(new ArrayBuffer(0));}
 _read(){if(this.ackNeeded)this.ack();}
 _write(data,encoding,callback){
  let offset=0;
  const send=()=>{if(offset===data.length){callback();return;}
   const chunk=data.subarray(offset,offset+65536);offset+=chunk.length;
   this.pending=send;
   if(!this.send(chunk)){this.pending=null;callback(this.destroyed?undefined:Error('relay socket closed'));}
  };send();
 }
 _destroy(error,callback){
  if(this.ws.readyState===1){try{this.ws.close();}catch{ /* already closing */ }}
  callback(error);
 }
}

for (const badPin of [false,true]) test(badPin ? 'inner TLS rejects an incorrect certificate pin' : 'end-to-end TLS over Cloudflare relay preserves a binary payload', {timeout:10000}, async()=>{
 const a=await device(),b=await device();await pair(a,b);const [out,inc]=await room(a,b);
 const ra=await join(a,out),wa=ra.webSocket;wa.accept();const nextA=messages(wa);
 const rb=await join(b,inc),wb=rb.webSocket;wb.accept();const nextB=messages(wb);
 await nextA();await nextB();
 const cert=await readFile(new URL('fixtures/cert.pem',import.meta.url));
 const key=await readFile(new URL('fixtures/key.pem',import.meta.url));
 const expected=badPin ? 'incorrect' : new X509Certificate(cert).fingerprint256;
 const receiver=new tls.TLSSocket(new RelayStream(wb),{isServer:true,secureContext:tls.createSecureContext({cert,key,minVersion:'TLSv1.3'})});
 receiver.on('error',()=>{});
 let received=0;receiver.on('data',chunk=>{received+=chunk.length;});
 const sender=tls.connect({socket:new RelayStream(wa),ca:cert,servername:'localhost',minVersion:'TLSv1.3',checkServerIdentity:(_host,peer)=>peer.fingerprint256===expected?undefined:Error('pin mismatch')});
 try {
  if(badPin){
   await assert.rejects(once(sender,'secureConnect'),/pin mismatch/);
   assert.notEqual(sender.authorized,true);
   assert.equal(received,0,'receiver must not accept application data after pin rejection');
   return;
  }
  await once(sender,'secureConnect');assert.equal(sender.authorized,true);
  const payload=randomBytes(150000), chunks=[];
  const delivered=new Promise((resolve,reject)=>{let bytes=0;receiver.on('error',reject);receiver.on('data',chunk=>{chunks.push(chunk);bytes+=chunk.length;if(bytes===payload.length)resolve();});});
  sender.write(payload);await delivered;assert.deepEqual(Buffer.concat(chunks),payload);
  const reply=once(sender,'data');receiver.write('received');assert.equal((await reply)[0].toString(),'received');
 } finally {sender.destroy();receiver.destroy();}
});


test('revoking a credential during upload prevents the pending mutation',async()=>{
 const a=await device(),b=await device();await pair(a,b);
 const stream=streamedBody('{"peer":');
 const pending=worker.fetch('https://relay.test/v1/sessions',{method:'POST',duplex:'half',headers:{Authorization:`Bearer ${a.token}`,'Content-Type':'application/json'},body:stream.body});
 await new Promise(resolve=>setTimeout(resolve,100));
 assert.equal((await call(`/v1/admin/devices/${a.id}`,admin,'DELETE')).status,200);
 stream.append(`"${b.id}"}`);stream.close();
 assert.equal((await pending).status,401);
 assert.deepEqual((await (await call('/v1/incoming',b.token)).json()).sessions,[]);
});

for (const limit of ['bytes','frames']) test(`exact ${limit} limit forwards no excess data`,{timeout:60000},async()=>{
 const a=await device(),b=await device();await pair(a,b);const [out,inc]=await room(a,b);
 const wa=(await join(a,out)).webSocket;wa.accept();const nextA=messages(wa);
 const wb=(await join(b,inc)).webSocket;wb.accept();const nextB=messages(wb);
 await nextA();await nextB();
 const chunk=new Uint8Array(limit==='bytes'?65536:1);
 const count=limit==='bytes'?1024:2048;
 let delivered=0;
 wb.addEventListener('message',e=>{if(typeof e.data!=='string')delivered+=e.data.byteLength;});
 for(let i=0;i<count;i++){
  wa.send(chunk);assert.equal((await nextB()).byteLength,chunk.length);
  wb.send(new ArrayBuffer(0));assert.equal((await nextA()).byteLength,0);
 }
 const ended=closed(wb);
 wa.send(new Uint8Array([99]));await ended;
 assert.equal(delivered,count*chunk.length);
 assert.equal((await join(a,out)).status,404);
 wa.close();wb.close();
});

test('admin quota admits 120 requests and rejects the next',async()=>{
 const isolated=new Miniflare({modules:true,scriptPath:decodeURIComponent(new URL('../src/worker.js',import.meta.url).pathname),compatibilityFlags:['nodejs_compat'],script:await readFile(new URL('../src/worker.js',import.meta.url),'utf8'),compatibilityDate:'2026-08-06',durableObjects:{HUB:{className:'RelayHub',useSQLite:true}},bindings:{ADMIN_TOKEN:admin}});
 try {
  const request=token=>isolated.dispatchFetch('https://relay.test/v1/admin/unknown',{headers:{Authorization:`Bearer ${token}`}});
  assert.equal((await request(fresh())).status,401);
  for(let i=0;i<120;i++)assert.equal((await request(admin)).status,404);
  assert.equal((await request(admin)).status,429);
 } finally {await isolated.dispose();}
});


test('self enrollment proves certificate ownership and rejects replay',async()=>{
 const dir=mkdtempSync(pathJoin(tmpdir(),'nodi-enroll-'));
 let cert,key;
 try {
   execFileSync('openssl',['req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:prime256v1','-nodes','-keyout',pathJoin(dir,'key.pem'),'-out',pathJoin(dir,'cert.pem'),'-subj','/CN=Nodi test','-days','1'],{stdio:'ignore'});
   cert=new X509Certificate(await readFile(pathJoin(dir,'cert.pem')));key=await readFile(pathJoin(dir,'key.pem'));
 }finally{rmSync(dir,{recursive:true,force:true});}
 const id=createHash('sha256').update(cert.raw).digest('hex');
 let nonce=(await (await call('/v1/enroll/challenge',null,'POST',{id})).json()).nonce;
 const payload={id,nonce,certificate:cert.raw.toString('base64'),signature:sign('sha256',Buffer.from('nodi-enroll-v1\n'+id+'\n'+nonce),key).toString('base64'),testInstall:true};
 let response=await call('/v1/enroll',null,'POST',payload);assert.equal(response.status,201);
 const enrolled=await response.json();assert.equal((await call('/v1/incoming',enrolled.token)).status,200);
 assert.equal((await call('/v1/enroll',null,'POST',payload)).status,401);
 const bad=fresh();nonce=(await (await call('/v1/enroll/challenge',null,'POST',{id:bad})).json()).nonce;
 assert.equal((await call('/v1/enroll',null,'POST',{...payload,id:bad,nonce})).status,401);
 await call('/v1/admin/devices/'+id,admin,'DELETE');
 assert.equal((await call('/v1/enroll',null,'POST',payload)).status,403);
});
test('rooms require owner approval and isolate encrypted payloads',async()=>{
 const a=await device(),b=await device(),c=await device(),id=fresh(),blob=Buffer.alloc(100,5).toString('base64');
 assert.equal((await call('/v1/rooms',a.token,'POST',{id,blob})).status,201);
 assert.equal((await call('/v1/rooms/'+id,c.token)).status,404);
 assert.equal((await call('/v1/rooms/join',b.token,'POST',{id,blob})).status,200);
 assert.equal((await call('/v1/rooms/join',c.token,'POST',{id,blob})).status,404);
 assert.equal((await (await call('/v1/rooms/'+id,b.token)).json()).blob,'');
 assert.equal((await call('/v1/rooms/'+id+'/approve',b.token,'POST',{})).status,405);
 assert.equal((await call('/v1/rooms/'+id+'/approve',a.token,'POST',{})).status,200);
 assert.equal((await (await call('/v1/rooms/'+id,b.token)).json()).blob,blob);
 assert.equal((await call('/v1/sessions',a.token,'POST',{peer:b.id})).status,201);
 // A consumed room is still isolated: a third device cannot read or reuse it.
 assert.equal((await call('/v1/rooms/'+id,c.token)).status,404);
 assert.equal((await call('/v1/rooms/join',c.token,'POST',{id,blob})).status,404);
 assert.equal((await call('/v1/rooms/join',b.token,'POST',{id,blob})).status,404);
 await call('/v1/rooms/'+id,b.token,'DELETE');
 assert.equal((await call('/v1/rooms/'+id,a.token)).status,404);
 assert.equal((await call('/v1/rooms/join',c.token,'POST',{id,blob})).status,404);
 // Cancelling before approval invalidates the code immediately.
 const cancelled=fresh();
 assert.equal((await call('/v1/rooms',a.token,'POST',{id:cancelled,blob})).status,201);
 assert.equal((await call('/v1/rooms/join',b.token,'POST',{id:cancelled,blob})).status,200);
 await call('/v1/rooms/'+cancelled,a.token,'DELETE');
 assert.equal((await call('/v1/rooms/'+cancelled,b.token)).status,404);
 assert.equal((await call('/v1/rooms/join',c.token,'POST',{id:cancelled,blob})).status,404);
});
test('room join attempts share the per-device rate limit',async()=>{
 const a=await device(),id=fresh(),blob=Buffer.alloc(100,5).toString('base64');
 const statuses=[];
 for(let i=0;i<121;i++)statuses.push((await call('/v1/rooms/join',a.token,'POST',{id,blob})).status);
 assert.equal(statuses.filter(status=>status===404).length,120);
 assert.equal(statuses.at(-1),429);
});
test('statistics are admin-only, require consent and exclude test installs',async()=>{
 const a=await device(),b=await device();
 assert.equal((await call('/v1/admin/stats',a.token)).status,401);
 assert.equal((await call('/v1/usage',a.token,'POST',{event:'active',platform:'android'})).status,400);
 assert.equal((await call('/v1/usage',a.token,'POST',{event:'file',platform:'android',consent:true})).status,200);
 const before=await (await call('/v1/admin/stats',admin)).json();
 assert.equal((await call('/v1/admin/test-devices/'+b.id,admin,'PUT')).status,200);
 await call('/v1/usage',b.token,'POST',{event:'file',platform:'macos',consent:true});
 await call('/v1/feedback',b.token,'POST',{text:'test feedback'});
 assert.deepEqual(await (await call('/v1/admin/stats',admin)).json(),before);
 await call('/v1/feedback',a.token,'POST',{text:'Helpful feedback'});
 const after=await (await call('/v1/admin/stats',admin)).json();assert.equal(after.feedback.at(-1).text,'Helpful feedback');
 assert.ok(!JSON.stringify(after).includes(a.id));assert.ok(!JSON.stringify(after).includes(a.token));
});

test('download redirects count separately and test links are excluded',async()=>{
 const isolated=new Miniflare({modules:true,scriptPath:decodeURIComponent(new URL('../src/worker.js',import.meta.url).pathname),compatibilityFlags:['nodejs_compat'],script:await readFile(new URL('../src/worker.js',import.meta.url),'utf8'),compatibilityDate:'2026-08-06',durableObjects:{HUB:{className:'RelayHub',useSQLite:true}},bindings:{ADMIN_TOKEN:admin,DOWNLOAD_ANDROID_URL:'https://github.com/NODEPY/nodi/releases/download/test/app.apk'}});
 try {
  const get=path=>isolated.dispatchFetch('https://relay.test'+path,{redirect:'manual'});
  assert.equal((await get('/download/android')).status,302);
  assert.equal((await get('/download/test/android')).status,302);
  assert.equal((await get('/download/macos')).status,503);
  assert.equal((await get('/v1/admin/stats')).status,401);
  const stats=await (await isolated.dispatchFetch('https://relay.test/v1/admin/stats',{headers:{Authorization:'Bearer '+admin}})).json();
  assert.deepEqual(Object.values(stats.downloadRequests),[{android:1,macos:0}]);
 }finally{await isolated.dispose();}
});
