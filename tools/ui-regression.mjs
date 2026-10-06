#!/usr/bin/env node
/*
 * 账迹前端回归套件。不需要 Android SDK/JDK，用本机 Edge 直接跑 WebView 里那份页面。
 *   npm run test:ui      （需先 npm install）
 * 退出码非 0 = 有断言失败，可当 CI 门禁。
 *
 * 为什么能量到：App 的界面全部是 app/src/main/assets/index.html 里的单文件，
 * 数据全靠 window.BT 桥。把桥换成桩，就能在浏览器里复现正常/空/故障三态。
 * 注意桩里引用外部变量必须挂到 window 上再取，直接在生成的源码里写 node 侧变量
 * 会抛 ReferenceError 并被页面 try/catch 静默吞掉，表现为"所有数据都是 0"的假故障。
 */
import { createRequire } from 'node:module';
import path from 'node:path';
import process from 'node:process';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright-core');

const PAGE = 'file:///' + path.resolve('app/src/main/assets/index.html').replace(/\\/g, '/');
const results = [];
function check(name, pass, detail) {
  results.push({ name, pass: !!pass, detail: detail === undefined ? '' : String(detail) });
}

const TXN = [
  { id: 1, ts: Date.now(), amt: -35.8, m: '美团外卖', cat: 'canyin', sub: '外卖', app: 'meituan',
    src: '通知', acc: '招商银行(1234)', raw: '【支付宝】支付成功35.80元 美团外卖', conf: 95, ok: 1 },
  { id: 2, ts: Date.now() - 86400000, amt: -1500, m: '房租', cat: 'juzhu', sub: '房租', app: '',
    src: '短信', acc: '工商银行', raw: '工行支出1500.00元', conf: 90, ok: 1 },
];

function bridge(data) {
  // 故意只写旧键 bt_budget：同时验证「旧版设置能迁移」这条路径
  return `
  ${data.budget ? `localStorage.setItem('bt_budget', '${data.budget}');` : ''}
  window.__removed = []; window.__restored = []; window.__toast = ''; window.__recover = 0;
  window.__S = ${JSON.stringify(data)};
  window.BT = {
    status: () => JSON.stringify(window.__S.status),
    month: () => JSON.stringify(window.__S.month),
    today: () => JSON.stringify(window.__S.today),
    list: (k) => JSON.stringify(window.__S.list.slice(0, k || 200)),
    daily: () => JSON.stringify(window.__S.daily),
    cats: () => JSON.stringify(window.__S.cats),
    pendingList: () => JSON.stringify(window.__S.pending),
    remove: id => window.__removed.push(id),
    restore: j => window.__restored.push(j),
    recoverDatabase: () => { window.__recover++; return true; },
    toast: m => { window.__toast = m; },
    exportCsv: () => 'x', saveFile: () => {}, addManual: () => {}, setCategory: () => {},
    openNotificationSettings: () => { window.__nav = 'listener'; },
    openSmsPermissionSettings: () => { window.__nav = 'sms'; },
    importSms: () => { window.__nav = 'import'; window.__imports = (window.__imports || 0) + 1; },
  };`;
}

const OK = {
  budget: '5000',
  status: { listener: true, sms: true, smsBlocked: false, scanning: false, count: 2, pending: 0, ver: '0.4.3' },
  month: { expense: 1535.8, income: 0, count: 2 }, today: { expense: 35.8, count: 1 },
  list: TXN, daily: [0, 0, 0, 0, 0, 1500, 35.8],
  cats: [
    { id: 'juzhu', amt: 1500 }, { id: 'canyin', amt: 35.8 }, { id: 'gouwu', amt: 20 },
    { id: 'yule', amt: 15 }, { id: 'jiaotong', amt: 12 }, { id: 'yiliao', amt: 9 },
    { id: 'jiaoyu', amt: 7 }, { id: 'renqing', amt: 5 }, { id: 'jinrong', amt: 3 }, { id: 'qita', amt: 1 },
  ],
  pending: [],
};
const EMPTY = {
  budget: '0', status: { listener: false, sms: false, smsBlocked: false, scanning: false, count: 0, pending: 0, ver: '0.4.3' },
  month: { expense: 0, income: 0, count: 0 }, today: { expense: 0, count: 0 },
  list: [], daily: [0, 0, 0, 0, 0, 0, 0], cats: [], pending: [],
};
const BLOCKED = Object.assign({}, EMPTY, {
  status: { listener: false, sms: false, smsBlocked: true, scanning: false, count: 0, pending: 0, ver: '0.4.3' },
});
// 故障注入：桥返回空对象 / 直接抛异常，模拟 Java 侧异常与桥整体失效
const brokenBridge = (mode) => `window.__recover = 0; window.BT = {
    status: () => ${mode === 'throw' ? '{ throw new Error("sqlite disk I/O error"); }' : "'{}'"},
    month: () => '{}', today: () => '{}', list: () => '[]', daily: () => '[]',
    cats: () => '[]', pendingList: () => '[]',
    remove:()=>{}, restore:()=>{}, toast:()=>{}, exportCsv:()=>'', saveFile:()=>{},
    recoverDatabase:() => { window.__recover++; return true; },
    addManual:()=>{}, setCategory:()=>{}, openNotificationSettings:()=>{},
    openSmsPermissionSettings:()=>{}, importSms:()=>{} };`;

async function open(browser, stub, vp) {
  const p = await browser.newPage({ viewport: vp || { width: 393, height: 851 }, deviceScaleFactor: 2 });
  const errs = [];
  p.on('pageerror', e => errs.push('pageerror: ' + e.message));
  p.on('console', m => { if (m.type() === 'error') errs.push('console: ' + m.text()); });
  await p.addInitScript(stub);
  await p.goto(PAGE, { waitUntil: 'load' });
  await p.waitForTimeout(600);
  return { p, errs };
}

const browser = await chromium.launch({ channel: 'msedge' });

/* ---------- 1. 正常态 ---------- */
{
  const { p, errs } = await open(browser, bridge(OK));
  const t = await p.evaluate(() => ({
    hero: document.getElementById('heroTotal').textContent,
    ver: document.getElementById('meVer').textContent,
    bodyText: document.body.innerText,
  }));
  check('正常态 首页金额正确', t.hero === '¥1,535.80', t.hero);
  check('正常态 版本号来自桥', t.ver === 'v0.4.3', t.ver);
  check('正常态 无 NaN', t.bodyText.indexOf('NaN') < 0);
  check('正常态 无 undefined', t.bodyText.indexOf('undefined') < 0);
  check('正常态 无 JS 报错', errs.length === 0, errs.join('|'));

  await p.evaluate(() => openDetail(1));
  await p.waitForTimeout(300);
  const raw = await p.evaluate(() => document.getElementById('dRaw').textContent);
  check('详情 原始文本可见（回归 P2-1）', raw.indexOf('支付成功35.80元') >= 0, raw);

  await p.evaluate(() => { closeSheets(); openDetail(2); });
  await p.waitForTimeout(250);
  await p.evaluate(() => delTxn());
  await p.waitForTimeout(250);
  const c1 = await p.evaluate(() => ({ open: !!document.querySelector('#sh-confirm.on'), removed: window.__removed.slice() }));
  check('删除 先弹确认且未直接删（回归 P1-2）', c1.open && c1.removed.length === 0, JSON.stringify(c1));
  await p.evaluate(() => confirmYes());
  await p.waitForTimeout(300);
  const c2 = await p.evaluate(() => ({ removed: window.__removed.slice(), undo: document.getElementById('undoBar').classList.contains('on') }));
  check('删除 确认后执行并出现撤销条', c2.removed.length === 1 && c2.undo, JSON.stringify(c2));
  await p.evaluate(() => undoDelete());
  await p.waitForTimeout(250);
  const c3 = await p.evaluate(() => ({ n: window.__restored.length, ts: (window.__restored[0] || '').indexOf('"ts"') >= 0 }));
  check('撤销 原样回插且保留时间', c3.n === 1 && c3.ts, JSON.stringify(c3));

  await p.evaluate(() => switchTab('report'));
  await p.waitForTimeout(500);
  const rep = await p.evaluate(() => {
    const st = document.getElementById('rStack');
    return {
      sum: +Array.from(st.querySelectorAll('i')).reduce((a, i) => a + parseFloat(i.style.width), 0).toFixed(1),
      rows: document.querySelectorAll('#rCats .catrow').length,
      rest: Array.from(document.querySelectorAll('#rCats .catrow .nm')).some(e => e.textContent.indexOf('其余') >= 0),
      bars: document.querySelectorAll('#rBars .b').length,
    };
  });
  check('报表 堆叠条填满 100%（回归 P2 分类截断）', rep.sum >= 99.5 && rep.sum <= 100.5, rep.sum);
  check('报表 有「其余 N 类」行', rep.rest && rep.rows === 7, rep.rows);
  check('报表 7 根柱子', rep.bars === 7, rep.bars);

  await p.evaluate(() => switchTab('budget'));
  await p.waitForTimeout(600);
  const bud = await p.evaluate(() => {
    const r = document.getElementById('bRing');
    return { da: +parseFloat(r.getAttribute('stroke-dasharray')).toFixed(3), c: +(2 * Math.PI * 72).toFixed(3) };
  });
  check('预算环 dasharray == 2πr（回归 P3）', bud.da === bud.c, bud.da + ' vs ' + bud.c);

  const bad = await p.evaluate(() => {
    const out = [];
    for (const el of document.querySelectorAll('.iconbtn, .btn, .opt, .key')) {
      const r = el.getBoundingClientRect();
      if (r.width && r.height && (r.height < 44 || r.width < 44)) out.push(el.className + ' ' + Math.round(r.width) + 'x' + Math.round(r.height));
    }
    return out;
  });
  check('触控目标 ≥44px', bad.length === 0, bad.join(', '));
  check('可点行对读屏播报为 button', (await p.evaluate(() => document.querySelectorAll('[role=button]').length)) >= 5);
  const rm = await p.evaluate(() => {
    for (const sh of document.styleSheets) { try { for (const r of sh.cssRules) if (r.media && r.media.mediaText.indexOf('prefers-reduced-motion') >= 0) return true; } catch (e) {} }
    return false;
  });
  check('尊重 prefers-reduced-motion', rm);
  await p.close();
}

/* ---------- 2. 故障态：不得伪装成未授权、不得出 NaN ---------- */
for (const mode of ['emptyObj', 'throw']) {
  const { p } = await open(browser, brokenBridge(mode === 'throw' ? 'throw' : 'ok'));
  const r = await p.evaluate(() => ({
    banner: document.getElementById('errBar').classList.contains('on'),
    body: document.body.innerText,
    onboard: !!document.querySelector('#homeOnboard .onboard'),
    pill: document.getElementById('stListener').textContent,
  }));
  check(`故障(${mode}) 显示错误横幅`, r.banner);
  check(`故障(${mode}) 无 NaN`, r.body.indexOf('NaN') < 0);
  check(`故障(${mode}) 无 undefined`, r.body.indexOf('undefined') < 0);
  check(`故障(${mode}) 不展示授权引导（回归 P1-1）`, !r.onboard);
  check(`故障(${mode}) 权限胶囊显示未知而非「未开启」`, r.pill === '—', r.pill);
  await p.close();
}

/* ---------- 3. 空账本：不得误报故障 ---------- */
{
  const { p, errs } = await open(browser, bridge(EMPTY));
  const r = await p.evaluate(() => ({
    banner: document.getElementById('errBar').classList.contains('on'),
    onboard: !!document.querySelector('#homeOnboard .onboard'),
    barsEmpty: !!document.querySelector('#rBars .muted'),
  }));
  check('空账本 不误报错误横幅（空数组是合法值）', !r.banner);
  check('空账本 展示授权引导', r.onboard);
  check('空账本 柱状图为空态而非七根等高线', r.barsEmpty);
  check('空账本 无 JS 报错', errs.length === 0, errs.join('|'));
  await p.close();
}

/* ---------- 4. 权限被永久拒绝：按钮必须改走设置页 ---------- */
{
  const { p } = await open(browser, bridge(BLOCKED));
  const btns = await p.evaluate(() => Array.from(document.querySelectorAll('#homeOnboard button')).map(b => b.textContent.trim()));
  check('永久拒绝 按钮文案改为去设置里开启', btns.indexOf('去设置里开启') >= 0, JSON.stringify(btns));
  await p.evaluate(() => { window.__nav = null; document.querySelectorAll('#homeOnboard button')[1].click(); });
  check('永久拒绝 点击路由到设置页而非弹框', (await p.evaluate(() => window.__nav)) === 'sms');
  await p.evaluate(() => { window.__nav = null; document.querySelectorAll('#homeOnboard button')[0].click(); });
  check('通知入口路由到通知权限页', (await p.evaluate(() => window.__nav)) === 'listener');
  await p.close();
}

/* ---------- 5. 预算边界（含设置存储版本化与旧键迁移）---------- */
{
  const { p } = await open(browser, bridge(OK));
  await p.evaluate(() => switchTab('budget'));
  await p.waitForTimeout(300);
  check('旧键 bt_budget 迁移生效', (await p.evaluate(() => document.getElementById('bTotal').textContent)) === '¥5,000.00');
  const cases = [['负数', '-100', '¥5,000.00'], ['超限', '1e21', '¥5,000.00'],
                 ['有效值', '3000', '¥3,000.00'], ['清除', '0', '未设置']];
  for (const [label, input, expectShown] of cases) {
    await p.evaluate(v => { window.__toast = ''; document.getElementById('budgetInput').value = v; saveBudget(); }, input);
    await p.waitForTimeout(150);
    const r = await p.evaluate(() => ({
      shown: document.getElementById('bTotal').textContent,
      t: window.__toast,
      stored: localStorage.getItem('bt_settings_v1'),
    }));
    check('预算 ' + label + ' 处理正确', r.shown === expectShown, r.shown + ' | ' + r.stored);
    if (label === '负数' || label === '超限') check('预算 ' + label + ' 有提示', r.t.length > 0, r.t);
    if (label === '有效值') check('预算写入带版本的新键', !!r.stored && JSON.parse(r.stored).v === 1 && JSON.parse(r.stored).budget === 3000, r.stored);
  }
  await p.close();
}

/* ---------- 5b. 坏设置不得白屏 ---------- */
{
  const { p, errs } = await open(browser, `localStorage.setItem('bt_settings_v1','{不是JSON');` + bridge(OK));
  const r = await p.evaluate(() => ({ hero: document.getElementById('heroTotal').textContent, b: budget }));
  check('设置是坏 JSON 时仍能渲染', r.hero === '¥1,535.80' && r.b === 0, JSON.stringify(r));
  await p.close();
}
{
  const { p } = await open(browser, `localStorage.setItem('bt_settings_v1', JSON.stringify({v:1,budget:-9999,mask:'x'}));` + bridge(OK));
  const r = await p.evaluate(() => ({ b: budget, m: masked }));
  check('坏预算值被夹回 0', r.b === 0, JSON.stringify(r));
  check('非布尔的 mask 被当 false', r.m === false);
  await p.close();
}

/* ---------- 5c. 渲染隔离：一段抛错不能连带后面全不执行 ---------- */
{
  const { p } = await open(browser, bridge(OK));
  const r = await p.evaluate(() => {
    const orig = window.renderReport;
    window.renderReport = function(){ throw new Error('boom'); };
    renderAll(true);
    const ok = document.getElementById('heroTotal').textContent;
    window.renderReport = orig;
    return { hero: ok, banner: document.getElementById('errBar').classList.contains('on'),
             fix: document.getElementById('errBarFix').textContent };
  });
  check('某段渲染抛错时其他段仍更新', r.hero === '¥1,535.80', r.hero);
  check('渲染抛错会显示横幅且给「重试」', r.banner && r.fix === '重试', JSON.stringify(r));
  await p.close();
}

/* ---------- 5d. 数据故障给的是「重建账本」并要二次确认 ---------- */
{
  const { p } = await open(browser, brokenBridge('ok'));
  const a = await p.evaluate(() => ({ fix: document.getElementById('errBarFix').textContent, n: window.__recover || 0 }));
  check('数据故障横幅按钮是「重建账本」', a.fix === '重建账本', a.fix);
  await p.evaluate(() => offerFix());
  await p.waitForTimeout(250);
  const b = await p.evaluate(() => ({ confirm: !!document.querySelector('#sh-confirm.on'), n: window.__recover || 0 }));
  check('重建账本先弹确认且不直接执行', b.confirm && b.n === 0, JSON.stringify(b));
  await p.evaluate(() => confirmYes());
  await p.waitForTimeout(300);
  check('确认后才调用 recoverDatabase', (await p.evaluate(() => window.__recover)) === 1);
  await p.close();
}

/* ---------- 5e. 扫描进行中状态与连点防抖 ---------- */
{
  const scanning = Object.assign({}, OK, { status: Object.assign({}, OK.status, { scanning: true }) });
  const { p } = await open(browser, bridge(scanning));
  await p.evaluate(() => switchTab('me'));
  await p.waitForTimeout(300);
  const hint = await p.evaluate(() => document.getElementById('rowImportHint').textContent);
  check('扫描中显示进行中文案', hint.indexOf('正在扫描') >= 0, hint);
  const busy = await p.evaluate(() => document.getElementById('rowImport').classList.contains('busy'));
  check('扫描中行置灰', busy);
  await p.evaluate(() => { window.__imports = 0; BT_importSms(); });
  const t = await p.evaluate(() => ({ n: window.__imports || 0, toast: window.__toast }));
  check('扫描中再点不发起第二次，只提示', t.n === 0 && t.toast.indexOf('正在扫描') >= 0, JSON.stringify(t));
  await p.close();
}
{
  const { p } = await open(browser, bridge(OK));
  await p.evaluate(() => { window.__imports = 0; BT_importSms(); BT_importSms(); BT_importSms(); });
  const n = await p.evaluate(() => window.__imports);
  check('连点三次导入只发一次（防抖）', n === 1, n);
  await p.close();
}

/* ---------- 6. XSS：外部可控文本不得成为节点 ---------- */
{
  const evil = Object.assign({}, OK, {
    list: [Object.assign({}, TXN[0], { m: '<img src=x onerror=alert(1)>"\'\'' })],
  });
  const { p } = await open(browser, bridge(evil));
  const r = await p.evaluate(() => ({
    nodes: document.querySelectorAll('.name img, .name svg').length,
    text: document.querySelector('.name').textContent,
  }));
  check('商户名里的标签被当文本渲染', r.nodes === 0, r.nodes);
  check('商户名原文完整保留', r.text.indexOf('<img src=x') === 0, r.text);
  await p.close();
}

/* ---------- 7. 三视口无横向溢出 ---------- */
for (const [label, w] of [['窄 320', 320], ['基准 393', 393], ['平板 768', 768]]) {
  const { p } = await open(browser, bridge(OK), { width: w, height: 800 });
  const o = await p.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
  check(label + ' 无横向滚动', o.sw <= o.cw + 1, o.sw + '/' + o.cw);
  await p.close();
}

await browser.close();

const failed = results.filter(r => !r.pass);
for (const r of results) console.log((r.pass ? '  PASS  ' : '  FAIL  ') + r.name + (r.detail ? '   [' + r.detail + ']' : ''));
console.log('\n' + (results.length - failed.length) + '/' + results.length + ' 通过');
process.exit(failed.length ? 1 : 0);
