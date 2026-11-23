// Owner-only local report. Admin credentials never enter HTML or browser URLs.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
const root=new URL('../',import.meta.url);
const text=await readFile(new URL('server/cloudflare/.dev.vars.relay-admin',root),'utf8');
const token=text.trim().split('=',2)[1];
if(!/^[a-f0-9]{64}$/.test(token))throw Error('Local admin credential unavailable');
const response=await fetch('https://nodi-relay.igor-shura2011faik.workers.dev/v1/admin/stats',{headers:{Authorization:`Bearer ${token}`},redirect:'error'});
if(!response.ok)throw Error(`Private report unavailable (${response.status})`);
const data=await response.json();
const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const rows=data.days.map(d=>`<tr><td>${escape(d.day)}</td><td>${d.activeInstalls}</td><td>${d.files}</td><td>${d.texts}</td></tr>`).join('');
const downloads=Object.entries(data.downloadRequests||{}).map(([day,d])=>`<li>${escape(day)}: Android ${d.android}, Mac ${d.macos}</li>`).join('');
const feedback=data.feedback.map(f=>`<article><small>${escape(f.day)}</small><p>${escape(f.text)}</p></article>`).join('');
await mkdir(new URL('build/private-report/',root),{recursive:true,mode:0o700});
const path=new URL('build/private-report/index.html',root);
await writeFile(path,`<!doctype html><html lang="uk"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'"><title>Nodi · приватний звіт</title><style>body{max-width:850px;margin:40px auto;padding:20px;background:#151820;color:#edf1ff;font:17px system-ui}table{width:100%;border-collapse:collapse}td,th{padding:12px;text-align:left;border-bottom:1px solid #555}article{padding:15px;background:#252a38;margin:12px 0}small{color:#afb5c9}p{white-space:pre-wrap}</style><h1>Nodi · приватний звіт</h1><p>Оновлено ${escape(new Date().toLocaleString('uk-UA',{timeZone:'Europe/Kyiv'}))}. Зберігання на сервері — 30 днів. Тестові встановлення виключені.</p><h2>Використання за добровільною згодою</h2><p>Це активні встановлення, а не кількість людей. Після перевстановлення публічної збірки встановлення може рахуватися заново.</p><table><tr><th>День (UTC)</th><th>Активні встановлення</th><th>Файли</th><th>Тексти</th></tr>${rows}</table><h2>Переходи до завантаження</h2><p>Це запити за посиланням, а не підтверджені завантаження. Прямі посилання GitHub не враховуються. Тестові посилання виключені. Потрібні налаштовані адреси опублікованих збірок.</p><ul>${downloads||'<li>Даних поки немає</li>'}</ul><h2>Відгуки</h2>${feedback||'<p>Відгуків поки немає</p>'}</html>`,{mode:0o600});
console.log('Приватний звіт збережено: '+fileURLToPath(path));
