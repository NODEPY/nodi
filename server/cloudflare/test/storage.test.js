import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {RelayHub} from '../src/worker.js';

function memoryStorage(records){
 return {
  async get(k){return structuredClone(records.get(k));},
  async put(k,v){if(typeof k==='string')records.set(k,structuredClone(v));else for(const [key,value]of Object.entries(k)){assert.ok(JSON.stringify(value).length<128*1024);records.set(key,structuredClone(value));}},
  async delete(keys){for(const k of Array.isArray(keys)?keys:[keys])records.delete(k);},
  async list({prefix,startAfter,limit}){return new Map([...records].filter(([k])=>k.startsWith(prefix)&&(!startAfter||k>startAfter)).sort(([a],[b])=>a.localeCompare(b)).slice(0,limit));},
  async transaction(fn){return fn(this);},async setAlarm(){},async deleteAlarm(){}
 };
}

test('legacy state migrates without losing devices and survives object restart',async()=>{
 const records=new Map([['state',{devices:{a:{hash:'existing',peers:['b']}},sessions:{}}]]);
 const storage=memoryStorage(records);
 const hub=new RelayHub({storage},{});const s=await hub.load();
 assert.equal(s.devices.a.hash,'existing');
 for(let day=1;day<=30;day++){s.metrics['2026-10-'+String(day).padStart(2,'0')]={};for(let i=0;i<64;i++)s.metrics['2026-10-'+String(day).padStart(2,'0')][String(i).padStart(64,'0')]={platform:'android',files:1,texts:2};}
 for(let i=0;i<300;i++)s.feedback.push({device:'a',day:'2026-10-01',text:'я'.repeat(1000)});
 await hub.save(s);assert.deepEqual(records.get('state'),{format:2});assert.equal(records.get('legacy-v1-backup').devices.a.hash,'existing');
 const restarted=new RelayHub({storage},{});const loaded=await restarted.load();
 assert.equal(loaded.devices.a.hash,'existing');assert.deepEqual(loaded.devices.a.peers,['b']);
 assert.equal(Object.keys(loaded.metrics).length,30);assert.equal(loaded.feedback.length,300);
 loaded.devices.a.peers.push('c');await restarted.save(loaded);
 assert.deepEqual((await new RelayHub({storage},{}).load()).devices.a.peers,['b','c']);
});

test('deleting a room removes its encrypted blob from persisted state',async()=>{
 const records=new Map(),storage=memoryStorage(records);
 const hub=new RelayHub({storage},{});
 const token='d'.repeat(64),pin='e'.repeat(64),id='f'.repeat(64);
 const blob=Buffer.alloc(100,7).toString('base64');
 let s=await hub.load();
 s.devices[pin]={hash:createHash('sha256').update(token).digest('hex'),peers:[],minute:Math.floor(Date.now()/60000),requests:0};
 s.rooms[id]={host:pin,blob,deadline:Date.now()+300000,approved:false};
 await hub.save(s);
 assert.ok(records.has('v2:rooms:'+id));
 s=await hub.load();
 const request=new Request('https://relay.test/v1/rooms/'+id,{method:'DELETE',headers:{Authorization:'Bearer '+token}});
 assert.equal((await hub.route(request,s)).status,200);
 await hub.save(s);
 assert.ok(!records.has('v2:rooms:'+id));
 assert.ok(!JSON.stringify([...records.values()]).includes(blob));
});
