#!/usr/bin/env node
/*
 * 纯函数层单测：`npm run test:core`
 * 只依赖 Node 自带的 assert，CI 里不需要 npm install 任何包。
 * 这些用例原先只能靠开浏览器间接测（函数埋在 index.html 里），
 * 拆出 core.js 之后可以直接断言，包括跨语言的 CATS ↔ Rules.java 一致性。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const C = require('../app/src/main/assets/core.js');

const results = [];
function t(name, fn) {
  try { fn(); results.push({ name, pass: true }); }
  catch (e) { results.push({ name, pass: false, detail: e.message }); }
}

/* ---------- 分类表 ---------- */
t('catOf 返回的是匹配到的那一类，不是兜底类', () => {
  assert.equal(C.catOf('canyin').name, '餐饮');
  assert.equal(C.catOf('jiaotong').name, '交通');
  assert.equal(C.catOf('jinrong').name, '金融');
});
t('catOf 未知 id 落到「其他」', () => {
  assert.equal(C.catOf('weird').id, 'qita');
  assert.equal(C.catOf(undefined).id, 'qita');
});
t('每个分类都有 color 和 ink，且 ink 不等于 color', () => {
  for (const c of C.CATS) {
    assert.match(c.color, /^#[0-9A-Fa-f]{6}$/, c.id + ' color 不是十六进制');
    assert.match(c.ink, /^#[0-9A-Fa-f]{6}$/, c.id + ' ink 不是十六进制');
    assert.notEqual(c.ink, c.color, c.id + ' 的墨色和填充色相同，小字对比度必然不达标');
  }
});
// 跨语言一致性：Rules.catName 的注释就写着"必须与 index.html 里的 CATS 表逐字一致"，
// 但那条约束此前只靠人眼守。这里真去读 Java 源文，任何一边改了都会红。
t('CATS 与 Rules.java 的分类 id/中文名逐字一致', () => {
  const java = fs.readFileSync(
    path.resolve('app/src/main/java/com/voyra/billtrace/Rules.java'), 'utf8');
  const ids = [...java.matchAll(/if \("([a-z]+)"\.equals\(id\)\) return "([^"]+)";/g)]
    .map(m => ({ id: m[1], name: m[2] }));
  assert.equal(ids.length, 10, 'Rules.catName 里的分类数变了：' + ids.length);
  assert.equal(ids.length, C.CATS.length, '两边分类数量不一致');
  for (const j of ids) {
    const c = C.CATS.find(x => x.id === j.id);
    assert.ok(c, 'CATS 里找不到 Rules 声明的分类 ' + j.id);
    assert.equal(c.name, j.name, j.id + ' 的中文名两边不一致（导出 CSV 会出现界面看不到的分类名）');
  }
  const catIds = [...java.matchAll(/"([a-z]+)", ?(?=")/g)].map(m => m[1]);
  assert.ok(/CAT_IDS[\s\S]*?canyin[\s\S]*?qita/.test(java), 'Rules.CAT_IDS 少了成员');
});

/* ---------- 金额与日期 ---------- */
t('money 对非数值给占位符而不是 ¥NaN', () => {
  assert.equal(C.money(undefined), '¥—');
  assert.equal(C.money(null), '¥—');
  assert.equal(C.money('abc'), '¥—');
  assert.equal(C.money(Infinity), '¥—');
  assert.equal(C.money(NaN), '¥—');
});
t('money 取绝对值并固定两位小数', () => {
  assert.equal(C.money(-35.8), '¥35.80');
  assert.equal(C.money(1234567.5), '¥1,234,567.50');
  assert.equal(C.money(0), '¥0.00');
});
t('moneyShort 过万折成「万」且同样防 NaN', () => {
  assert.equal(C.moneyShort(1535.8), '¥1,536');
  assert.equal(C.moneyShort(12660), '¥1.3万');
  assert.equal(C.moneyShort(undefined), '¥—');
});
t('pad2 补零', () => {
  assert.equal(C.pad2(3), '03');
  assert.equal(C.pad2(12), '12');
  assert.equal(C.pad2(0), '00');
});
t('dayKey 同一天内一致、跨天不一致', () => {
  const a = new Date(2026, 9, 6, 1, 0, 0).getTime();
  const b = new Date(2026, 9, 6, 23, 59, 59).getTime();
  const c = new Date(2026, 9, 7, 0, 0, 1).getTime();
  assert.equal(C.dayKey(a), C.dayKey(b));
  assert.notEqual(C.dayKey(b), C.dayKey(c));
});
t('dayLabel 的今天/昨天/前天按自然日而不是 24 小时', () => {
  const now = Date.now();
  assert.equal(C.dayLabel(now), '今天');
  const noonToday = new Date(new Date().getFullYear(), new Date().getMonth(),
    new Date().getDate(), 12, 0, 0).getTime();
  assert.equal(C.dayLabel(noonToday - 86400000), '昨天');
  assert.equal(C.dayLabel(noonToday - 2 * 86400000), '前天');
  assert.match(C.dayLabel(noonToday - 5 * 86400000), /^\d+月\d+日 周[日一二三四五六]$/);
});

/* ---------- 转义 ---------- */
t('esc 把五个危险字符全部实体化', () => {
  assert.equal(C.esc('<img src=x onerror=alert(1)>'), '&lt;img src=x onerror=alert(1)&gt;');
  assert.equal(C.esc('a&b'), 'a&amp;b');
  assert.equal(C.esc('say "hi"'), 'say &quot;hi&quot;');
  assert.equal(C.esc("it's"), 'it&#39;s');
});
t('esc 对 null/undefined/数字不抛错', () => {
  assert.equal(C.esc(null), '');
  assert.equal(C.esc(undefined), '');
  assert.equal(C.esc(0), '0');
});
t('esc 的 & 必须先替换，否则实体会被二次转义', () => {
  assert.equal(C.esc('&lt;'), '&amp;lt;');
});

/* ---------- 设置读写 ---------- */
t('clampBudget 把坏值夹回 0', () => {
  assert.equal(C.clampBudget('abc'), 0);
  assert.equal(C.clampBudget(-5), 0);
  assert.equal(C.clampBudget(Infinity), 0);
  assert.equal(C.clampBudget(1e12), 0);
  assert.equal(C.clampBudget(undefined), 0);
  assert.equal(C.clampBudget(5000), 5000);
});
t('readSettings 优先读 v1 JSON', () => {
  const s = C.readSettings('{"v":1,"budget":3000,"mask":true}', '9999', '1');
  assert.equal(s.budget, 3000);
  assert.equal(s.mask, true);
});
t('readSettings 没有 v1 时迁移旧的分开两键', () => {
  const s = C.readSettings(null, '5000', '1');
  assert.equal(s.budget, 5000);
  assert.equal(s.mask, true);
  const off = C.readSettings(null, '5000', null);
  assert.equal(off.mask, false);
});
t('readSettings 坏 JSON / 越界值退回默认而不抛', () => {
  assert.deepEqual(C.readSettings('{坏数据', null, null), {v: 1, budget: 0, mask: false});
  assert.equal(C.readSettings('{"budget":-9}', null, null).budget, 0);
  assert.equal(C.readSettings('{"budget":1e18}', null, null).budget, 0);
  assert.equal(C.readSettings('{"mask":"yes"}', null, null).mask, false);
  assert.equal(C.readSettings('null', null, null).budget, 0);
  assert.equal(C.readSettings('[]', null, null).budget, 0);
});
t('settingsJson 写出去的值能被 readSettings 原样读回', () => {
  const raw = C.settingsJson(4200, true);
  const back = C.readSettings(raw, null, null);
  assert.equal(back.budget, 4200);
  assert.equal(back.mask, true);
});

/* ---------- 筛选与聚合 ---------- */
const ROWS = [
  {id: 1, ts: 300, amt: -10, cat: 'canyin'},
  {id: 2, ts: 100, amt: 500, cat: 'jinrong'},
  {id: 3, ts: 200, amt: -400, cat: 'gouwu'},
  {id: 4, ts: 400, amt: 0, cat: 'qita'},
];
t('applyFilter 按收支筛', () => {
  assert.deepEqual(C.applyFilter(ROWS, {type: 'exp'}).map(r => r.id), [1, 3]);
  assert.deepEqual(C.applyFilter(ROWS, {type: 'inc'}).map(r => r.id), [2]);
  assert.deepEqual(C.applyFilter(ROWS, {type: 'all'}).map(r => r.id), [4, 1, 3, 2]);
});
t('applyFilter 金额为 0 的既不算支出也不算收入', () => {
  assert.equal(C.applyFilter(ROWS, {type: 'exp'}).some(r => r.id === 4), false);
  assert.equal(C.applyFilter(ROWS, {type: 'inc'}).some(r => r.id === 4), false);
});
t('applyFilter 按分类筛且多分类取并集', () => {
  assert.deepEqual(C.applyFilter(ROWS, {type: 'all', cats: ['canyin', 'gouwu']}).map(r => r.id), [1, 3]);
});
t('applyFilter 排序：金额档按绝对值倒序，时间档按 ts 倒序', () => {
  assert.deepEqual(C.applyFilter(ROWS, {sort: 'amt'}).map(r => r.id), [2, 3, 1, 4]);
  assert.deepEqual(C.applyFilter(ROWS, {sort: 'time'}).map(r => r.id), [4, 1, 3, 2]);
});
t('applyFilter 不改动传进来的数组', () => {
  const before = ROWS.map(r => r.id).join();
  C.applyFilter(ROWS, {sort: 'amt'});
  assert.equal(ROWS.map(r => r.id).join(), before);
});
t('applyFilter 空筛选条件等价于全量按时间倒序', () => {
  assert.deepEqual(C.applyFilter(ROWS, {}).map(r => r.id), [4, 1, 3, 2]);
});
t('sumByDay 只累计支出，收入不进分组头', () => {
  const dayA = new Date(2026, 9, 6, 10).getTime();
  const rows = [
    {ts: dayA, amt: -30, cat: 'canyin'},
    {ts: dayA, amt: -20, cat: 'gouwu'},
    {ts: dayA, amt: 9000, cat: 'jinrong'},
  ];
  const m = C.sumByDay(rows);
  assert.equal(m[C.dayKey(dayA)], 50);
});
t('sumByDay 跨天分开统计', () => {
  const d1 = new Date(2026, 9, 6, 23, 59).getTime();
  const d2 = new Date(2026, 9, 7, 0, 1).getTime();
  const m = C.sumByDay([{ts: d1, amt: -5}, {ts: d2, amt: -7}]);
  assert.equal(Object.keys(m).length, 2);
  assert.equal(m[C.dayKey(d1)], 5);
  assert.equal(m[C.dayKey(d2)], 7);
});

/* ---------- 桥返回形状校验 ---------- */
t('validateShape 数组类接受空数组', () => {
  for (const n of ['list', 'listMore', 'search', 'daily', 'cats', 'pendingList']) {
    assert.equal(C.validateShape(n, []), true, n + ' 应接受空数组（新用户账本就是空的）');
    assert.equal(C.validateShape(n, [1]), true, n);
    assert.equal(C.validateShape(n, {}), false, n + ' 不该接受对象');
  }
});
t('validateShape 拒绝带 error 字段的返回', () => {
  assert.equal(C.validateShape('list', {error: 'sqlite disk I/O'}), false);
  assert.equal(C.validateShape('status', {error: 'x'}), false);
});
t('validateShape status 必须有两个布尔加一个计数', () => {
  assert.equal(C.validateShape('status', {listener: true, sms: false, count: 3}), true);
  assert.equal(C.validateShape('status', {listener: true, sms: false}), false);
  assert.equal(C.validateShape('status', {listener: 'yes', sms: false, count: 3}), false);
  assert.equal(C.validateShape('status', {listener: true, sms: false, count: '3'}), false);
});
t('validateShape month/today 的 expense 必须是数字', () => {
  assert.equal(C.validateShape('month', {expense: 0}), true);
  assert.equal(C.validateShape('today', {expense: undefined}), false);
});
t('validateShape 拒绝 null 和字符串', () => {
  assert.equal(C.validateShape('list', null), false);
  assert.equal(C.validateShape('list', '[]'), false);
});

/* ---------- 常量 ---------- */
t('PAGE 与桥侧上限自洽', () => {
  assert.ok(C.PAGE >= 20 && C.PAGE <= 500, 'PAGE 超出 Bridge.MAX_PAGE 钳制范围');
  const bridge = fs.readFileSync(
    path.resolve('app/src/main/java/com/voyra/billtrace/Bridge.java'), 'utf8');
  const m = bridge.match(/MAX_PAGE = (\d+)/);
  assert.ok(m, 'Bridge 里找不到 MAX_PAGE');
  assert.ok(C.PAGE <= +m[1], '一页要的量超过桥上限会被静默截断');
});

/* ---------- 更新：版本比较与进度 ---------- */
t('verCmp 三段数字逐位比较', () => {
  assert.equal(C.verCmp('0.5.0', '0.4.4'), 1);
  assert.equal(C.verCmp('0.4.4', '0.5.0'), -1);
  assert.equal(C.verCmp('0.4.4', '0.4.4'), 0);
  assert.equal(C.verCmp('v0.5.0', '0.4.4'), 1, '带 v 前缀要能剥掉');
  assert.equal(C.verCmp('0.10.0', '0.9.9'), 1, '按数字比，不是按字符串比');
});
t('verCmp 对预发布后缀不返回 NaN 判定', () => {
  // 0.5.0-beta.1 的三段数字等于 0.5.0：既不更新也不更旧
  assert.equal(C.verCmp('0.5.0-beta.1', '0.5.0'), 0);
  assert.equal(C.verCmp('0.5.1-beta.1', '0.5.0'), 1);
});
t('verCmp 对空值与脏值不抛异常', () => {
  assert.equal(C.verCmp(null, '0.4.4'), -1);
  assert.equal(C.verCmp('', '0.4.4'), -1);
  assert.equal(C.verCmp('abc', '0.4.4'), -1, '非数字段按 0 处理');
  assert.equal(C.verCmp(undefined, undefined), 0);
});
t('pctOf 正常区间', () => {
  assert.equal(C.pctOf(0, 100), 0);
  assert.equal(C.pctOf(50, 100), 50);
  assert.equal(C.pctOf(100, 100), 100);
  assert.equal(C.pctOf(150, 100), 100, '超过总量要钳到 100');
});
t('pctOf 总量未知时返回 -1（界面据此显示已下载字节数）', () => {
  assert.equal(C.pctOf(1024, -1), -1);
  assert.equal(C.pctOf(1024, 0), -1);
  assert.equal(C.pctOf(1024, NaN), -1);
});
t('pctOf 对负值与脏值不返回 NaN', () => {
  assert.equal(C.pctOf(-5, 100), 0);
  assert.equal(C.pctOf(NaN, 100), 0);
  assert.equal(C.pctOf(undefined, undefined), -1);
});

const failed = results.filter(r => !r.pass);
for (const r of results) console.log((r.pass ? '  PASS  ' : '  FAIL  ') + r.name + (r.detail ? '   [' + r.detail + ']' : ''));
console.log('\n' + (results.length - failed.length) + '/' + results.length + ' 通过');
process.exit(failed.length ? 1 : 0);
