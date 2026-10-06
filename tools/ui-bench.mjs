#!/usr/bin/env node
/*
 * 渲染性能量测：`npm run bench:ui`
 * 量的是页面渲染部分（桥用同步桩替代），不含真机上 SQLite + JS↔Java 桥接的开销。
 * 结论口径：当前列表被 Bridge.list(200) 硬截断到 200 条，所以渲染不是瓶颈；
 * 真正的缺口是「共 N 条记录」显示总数却只能看到 200 条，且没有分页。
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
    daily: () => JSON.stringify([1,2,3,4,5,6,7]),
    cats: () => JSON.stringify([{id:'canyin',amt:500},{id:'jiaotong',amt:300}]),
    pendingList: () => JSON.stringify([]),
    remove:()=>{}, restore:()=>{}, toast:()=>{}, exportCsv:()=>'', saveFile:()=>{},
    addManual:()=>{}, setCategory:()=>{}, openNotificationSettings:()=>{},
    openSmsPermissionSettings:()=>{}, importSms:()=>{} };`;
}

const b = await chromium.launch({ channel: 'msedge' });
console.log('数据集            单次刷新   5次均值   DOM节点  实际渲染行  界面宣称总数');
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
  console.log(label.padEnd(18) + String(m.one + 'ms').padEnd(11) + String(m.avg + 'ms').padEnd(10)
    + String(m.dom).padEnd(9) + String(m.rows).padEnd(11) + m.count);
  await p.close();
}
await b.close();
