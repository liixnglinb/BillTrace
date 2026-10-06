#!/usr/bin/env node
/*
 * 渲染性能量测：`npm run bench:ui`
 * 量的是页面渲染部分（桥用同步桩替代），不含真机上 SQLite + JS↔Java 桥接的开销。
 * 结论口径：列表已改成键集分页，一次 200 条 + 「加载更多」；
 * 这里同时量「翻满 5000 条后单次刷新」的成本，因为那时 DOM 才是真的变大了。
 */
import { createRequire } from 'node:module';
import path from 'node:path';
import process from 'node:process';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright-core');
const PAGE = 'file:///' + path.resolve('app/src/main/assets/index.html').replace(/\\/g, '/');

function rows(n) {
  const out = [];
  const day = 86400000;
  for (let i = 0; i < n; i++) {
    out.push({ id: i + 1, ts: Date.now() - i * (day * 3), amt: -(10 + (i % 90)), m: '商户' + i,
      cat: ['canyin', 'jiaotong', 'gouwu', 'juzhu', 'yule'][i % 5], sub: '子类', app: '',
      src: '通知', acc: '', raw: '', conf: 90, ok: 1 });
  }
  return out;
}

function stub(n, total) {
  return `window.__ROWS = ${JSON.stringify(rows(n))};
  window.BT = {
    status: () => JSON.stringify({listener:true,sms:true,smsBlocked:false,count:${total},pending:0,ver:'0.4.3'}),
    month: () => JSON.stringify({expense:${n * 50},income:0,count:${total}}),
    today: () => JSON.stringify({expense:100,count:1}),
    list: (k) => JSON.stringify(window.__ROWS.slice(0, k)),
    listMore: (ts, id, k) => JSON.stringify(window.__ROWS
      .filter(t => t.ts < ts || (t.ts === ts && t.id < id)).slice(0, k)),
    search: (q) => JSON.stringify(window.__ROWS.filter(t => t.m.includes(q)).slice(0, 500)),
    daily: () => JSON.stringify([1,2,3,4,5,6,7]),
    cats: () => JSON.stringify([{id:'canyin',amt:500},{id:'jiaotong',amt:300}]),
    pendingList: () => JSON.stringify([]),
    remove:()=>{}, restore:()=>{}, toast:()=>{}, exportCsv:()=>'', saveFile:()=>{},
    addManual:()=>{}, setCategory:()=>{}, openNotificationSettings:()=>{},
    openSmsPermissionSettings:()=>{}, importSms:()=>{} };`;
}

const b = await chromium.launch({ channel: 'msedge' });
console.log('数据集              单次刷新  5次均值   DOM节点  渲染行  界面宣称总数');
for (const [label, n, total] of [['200 条 / 总 200', 200, 200], ['200 条 / 总 1500', 200, 1500],
                                  ['1500 条 / 总 1500', 1500, 1500], ['5000 条 / 总 5000', 5000, 5000]]) {
  const p = await b.newPage({ viewport: { width: 393, height: 851 }, deviceScaleFactor: 2 });
  await p.addInitScript(stub(n, total));
  await p.goto(PAGE, { waitUntil: 'load' });
  await p.waitForTimeout(400);
  const m = await p.evaluate(() => {
    const t0 = performance.now(); window.BTRefresh(); const one = performance.now() - t0;
    const t1 = performance.now();
    for (let i = 0; i < 5; i++) window.BTRefresh();
    const avg = (performance.now() - t1) / 5;
    return { one: +one.toFixed(1), avg: +avg.toFixed(1), dom: document.querySelectorAll('*').length,
      rows: document.querySelectorAll('#homeList .txn').length,
      count: document.getElementById('meCount').textContent };
  });
  console.log(label.padEnd(20) + String(m.one + 'ms').padEnd(10) + String(m.avg + 'ms').padEnd(10)
    + String(m.dom).padEnd(9) + String(m.rows).padEnd(8) + m.count);
  await p.close();
}

/* 分页累积：翻到 5000 条全在内存里之后，单次刷新和一次翻页的实际成本 */
{
  const p = await b.newPage({ viewport: { width: 393, height: 851 }, deviceScaleFactor: 2 });
  await p.addInitScript(stub(5000, 5000));
  await p.goto(PAGE, { waitUntil: 'load' });
  await p.waitForTimeout(400);
  const m = await p.evaluate(() => {
    const t0 = performance.now();
    let clicks = 0;
    while (document.querySelector('.more-row .btn') && clicks < 40) { loadMore(); clicks++; }
    const fill = performance.now() - t0;
    const t1 = performance.now();
    for (let i = 0; i < 5; i++) window.BTRefresh();
    const refresh = (performance.now() - t1) / 5;
    return { clicks: clicks, fill: +fill.toFixed(1), loaded: S.list.length,
      refresh: +refresh.toFixed(1), dom: document.querySelectorAll('*').length };
  });
  console.log('\n翻满 5000 条：点 ' + m.clicks + ' 次加载更多耗时 ' + m.fill + 'ms，'
    + '加载后单次刷新 ' + m.refresh + 'ms，DOM 节点 ' + m.dom);
  await p.close();
}
await b.close();
