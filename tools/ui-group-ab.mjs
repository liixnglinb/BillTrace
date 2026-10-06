#!/usr/bin/env node
/*
 * 分组聚合的算法对照：`npm run ab:group`
 *
 * 拆分之前这个量法要在浏览器里造一份"退回旧算法"的 HTML 副本，量出来的是
 * 渲染 + 聚合的混合值（2000 行那档 344ms vs 86ms，大头其实是建 DOM）。
 * sumByDay 现在是 core.js 里的纯函数，可以单独计时，把算法成本和渲染成本分开看。
 *
 * 旧写法：每个分组头都重扫整条 rows —— O(行数 × 分组数)。
 * 新写法：先一趟算出 Map<dayKey, 合计>，分组头只查表 —— O(行数 + 分组数)。
 */
import path from 'node:path';
import process from 'node:process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const C = require('../app/src/main/assets/core.js');

function rows(n, spacingMs) {
  const out = [];
  const base = new Date(2026, 9, 6, 12).getTime();
  for (let i = 0; i < n; i++) out.push({ id: i + 1, ts: base - i * spacingMs, amt: -(10 + (i % 90)) });
  return out;
}

/** 拆分前 renderHome 里的原始写法，逐字搬过来做对照 */
function oldSumPerGroupHead(rows) {
  const seen = {};
  let heads = 0;
  let lastKey = null;
  for (let i = 0; i < rows.length; i++) {
    const k = C.dayKey(rows[i].ts);
    if (k !== lastKey) {
      let daySum = 0;
      for (let j = 0; j < rows.length; j++) {
        if (C.dayKey(rows[j].ts) === k && rows[j].amt < 0) daySum += -rows[j].amt;
      }
      seen[k] = daySum;
      heads++;
      lastKey = k;
    }
  }
  return { sums: seen, heads: heads };
}

function bench(fn, r) {
  const t0 = process.hrtime.bigint();
  const out = fn(r);
  const t1 = process.hrtime.bigint();
  return { ms: Number(t1 - t0) / 1e6, heads: out.heads || Object.keys(out.sums || out).length };
}

console.log('行数 / 分组数        旧 O(N·D)      新 O(N)      倍率');
let mismatch = 0;
for (const [n, spacing] of [[200, 300000], [200, 3 * 86400000], [2000, 86400000], [5000, 86400000]]) {
  const r = rows(n, spacing);
  const o = bench(oldSumPerGroupHead, r);
  const w = bench((rr) => ({ sums: C.sumByDay(rr) }), r);
  // 两个实现必须给出逐日相同的合计，否则这个对照毫无意义
  for (const k in o.sums) if (Math.abs((o.sums[k] || 0) - (w.sums[k] || 0)) > 1e-9) mismatch++;
  console.log(
    (n + '行/' + o.heads + '组').padEnd(18) +
    (o.ms.toFixed(1) + 'ms').padEnd(15) +
    (w.ms.toFixed(1) + 'ms').padEnd(13) +
    (o.ms / w.ms).toFixed(1) + '×');
}
console.log(mismatch === 0 ? '\n两种实现的逐日合计完全一致' : '\n合计不一致 ' + mismatch + ' 处，对照不成立');
process.exit(mismatch === 0 ? 0 : 1);
