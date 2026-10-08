/* =========================================================================
   账迹 BillTrace —— 界面层（DOM + 桥）
   纯函数（格式化 / 转义 / 分类表 / 筛选 / 聚合 / 设置读写 / 形状校验）在 core.js，
   那份不碰 DOM、能被 Node 直接 require 单测。
   数据全部来自 Android 端：通知监听 + 银行短信解析，存在本机 SQLite。
   这个页面里没有任何写死的账目。没有 BT 桥（比如用浏览器打开）时不显示任何记录。
   ========================================================================= */

var HAS_BT = typeof window.BT !== 'undefined' && window.BT !== null;

var S = {status:{}, month:{expense:0,income:0,count:0}, today:{expense:0,count:0}, list:[], daily:[], cats:[], pending:[]};
var lastRow = null;   // 当前已加载列表里最旧的一条 {ts,id}，作为下一页的游标
var SET0 = readSettings(localStorage.getItem(SETTINGS_KEY),
  localStorage.getItem('bt_budget'), localStorage.getItem('bt_mask'));
var budget = SET0.budget;
var masked = SET0.mask;
/* 写设置留在界面层（它碰 localStorage），序列化与夹取交给 core 的 settingsJson。
   写失败（隐私模式、配额满）不打断操作：本次会话内仍然生效，只是下次进来会丢。 */
function saveSettings() {
  try { localStorage.setItem(SETTINGS_KEY, settingsJson(budget, masked)); } catch (e) {}
}
var tab = 'home';
var filter = {type:'all', cats:[], sort:'time'};
var curDetail = null, recAmt = '', recCat = 'canyin', recType = 'exp';

function $(id){ return document.getElementById(id); }

/* ---------- 读桥 ---------- */
function call(name, def, a1, a2, a3){
  if (!HAS_BT || typeof window.BT[name] !== 'function') return def;
  try {
    var r = a3 !== undefined ? window.BT[name](a1, a2, a3)
          : a2 !== undefined ? window.BT[name](a1, a2)
          : a1 !== undefined ? window.BT[name](a1)
          : window.BT[name]();
    return r;
  } catch (e) { noteBridgeFail(name); return def; }
}
/* 桥返回的 JSON 形状不校验的话，字段缺失会一路 undefined 到 money()，
   首页就会打出 ¥NaN，还会把「读取失败」当成「没授权」让用户去反复开权限。 */
/* 桥出错与界面出错要分开：前者说明数据读不到（不能判断授权，要给重建入口），
   后者只是某一段渲染抛了，重试即可，绝不该提示用户去重建账本。 */
var bridgeFails = 0, jsFails = 0, lastJsMsg = '', lastRefreshFailed = false, fixAction = null;
function noteBridgeFail(name){ bridgeFails++; }
function noteJsError(msg){ jsFails++; lastJsMsg = String(msg === undefined ? '' : msg).slice(0, 160); }
function updateBanner(){
  var bar = $('errBar');
  if (!bar) return;
  if (!lastRefreshFailed && !jsFails) { bar.classList.remove('on'); fixAction = null; return; }
  bar.classList.add('on');
  var msg = $('errBarMsg'), fix = $('errBarFix');
  if (lastRefreshFailed) {
    msg.innerHTML = '<b>账本读取失败</b> —— 这个状态下不判断授权，避免让你反复去开已经开好的权限。';
    fix.textContent = '重建账本';
    fixAction = offerRecover;
  } else {
    msg.innerHTML = '<b>界面出现异常</b> —— ' + esc(lastJsMsg);
    fix.textContent = '重试';
    fixAction = function(){ jsFails = 0; refresh(); };
  }
}
function offerFix(){ if (fixAction) fixAction(); }
function offerRecover(){
  showConfirm('重建账本？', '当前账本文件打不开。重建会新建一个空账本；损坏的文件只改名不删除，仍留在本机应用目录里，之后还能找回。',
    '重建', function(){
      var ok = call('recoverDatabase', false);
      call('toast', null, ok ? '已重建空账本' : '重建失败，请重启应用再试');
      refresh();
    });
}
/* 一段渲染抛错不能连带后面的全不执行，否则界面会半新半旧。 */
function safeRender(who, fn){
  try { fn(); } catch (e) { noteJsError(who + ': ' + (e && e.message ? e.message : e)); }
}
/* 连点防抖：回填是全表扫描、导出会起线程，重复触发只会加重负担。 */
var lastAct = {};
function debounce(key, ms){
  var t = Date.now(), p = lastAct[key];
  if (p && t - p < (ms || 1500)) return false;
  lastAct[key] = t;
  return true;
}
function callJson(name, def, a1, a2, a3){
  var r = call(name, null, a1, a2, a3);
  if (r === null || r === undefined) { noteBridgeFail(name); return def; }
  var o;
  try { o = JSON.parse(r); } catch (e) { noteBridgeFail(name); return def; }
  if (!validateShape(name, o)) { noteBridgeFail(name); return def; }
  return o;
}

function refresh(){
  var before = bridgeFails;
  S.status  = callJson('status', {listener:false, sms:false, smsBlocked:false, scanning:false, count:0, pending:0, ver:''});
  S.month   = callJson('month', {expense:0,income:0,count:0});
  S.today   = callJson('today', {expense:0,count:0});
  S.list    = callJson('list', [], PAGE);
  S.daily   = callJson('daily', [], 7);
  S.cats    = callJson('cats', [], 30);
  S.pending = callJson('pendingList', []);
  // 每次整体刷新回到第一页：游标复位到当前窗口最旧一条，往后翻从这里续
  lastRow = S.list.length ? { ts: S.list[S.list.length - 1].ts, id: S.list[S.list.length - 1].id } : null;
  // 有失败就不展示授权引导：此时我们根本不知道真实授权状态，引导只会误导
  lastRefreshFailed = bridgeFails !== before;
  updateBanner();
  renderAll(!lastRefreshFailed);
}
window.BTRefresh = refresh;
window.BTBack = function(){
  var open = document.querySelector('.sheet.on');
  if (open) { closeSheets(); return true; }
  return false;
};

/* ---------- 渲染 ---------- */
function renderAll(trustState){
  var ok = trustState !== false;
  safeRender('top', renderTop);
  safeRender('home', function(){ renderHome(ok); });
  safeRender('report', renderReport);
  safeRender('budget', renderBudget);
  safeRender('me', function(){ renderMe(ok); });
  if (jsFails) updateBanner();
}

function renderTop(){
  var now = new Date();
  $('tbSub').textContent = (now.getMonth()+1) + '月' + now.getDate() + '日 周' + WEEK[now.getDay()];
  $('eyeBtn').classList.toggle('on', masked);
  $('eyeIcon').innerHTML = masked
    ? '<path d="M3 3l18 18"/><path d="M10.6 5.1A9.8 9.8 0 0 1 12 5c7 0 10 7 10 7a17.5 17.5 0 0 1-3.4 4.3"/><path d="M6.6 6.6A17.3 17.3 0 0 0 2 12s3.6 7 10 7a9.7 9.7 0 0 0 4.2-.9"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>'
    : '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>';
  document.body.classList.toggle('masked', masked);
  $('swMask').classList.toggle('on', masked);
  updateFilterBtn();
}
function updateFilterBtn(){
  $('fltBtn').classList.toggle('on', filter.type !== 'all' || filter.cats.length > 0);
}

function renderHome(trustState){
  /* 概览 */
  $('heroTotal').textContent = money(S.month.expense);
  $('heroToday').textContent = '今日 ' + money(S.today.expense) + ' · ' + S.today.count + ' 笔';
  $('heroIncome').textContent = '收入 ' + money(S.month.income);
  var pct = budget > 0 ? Math.min(S.month.expense / budget, 1) : 0;
  $('heroBar').style.width = (pct*100).toFixed(1) + '%';
  $('heroCard').classList.toggle('over', budget > 0 && S.month.expense > budget);
  if (budget > 0) {
    $('heroBudget').textContent = '预算 ' + money(budget);
    $('heroPct').textContent = '已用 ' + Math.round(S.month.expense / budget * 100) + '%';
  } else {
    $('heroBudget').textContent = '还没设预算';
    $('heroPct').textContent = '去「预算」设置';
  }

  /* 引导 */
  var ob = $('homeOnboard');
  if (!HAS_BT) {
    ob.innerHTML = '<div class="card"><h3>请在账迹 App 内打开</h3><div class="muted">这个页面要靠 App 的采集引擎才有数据。</div></div>';
  } else if (trustState !== false && (!S.status.listener || !S.status.sms)) {
    var steps = '';
    if (!S.status.listener) {
      steps += '<div class="step"><div class="n">1</div><div class="t">开启<strong>通知使用权</strong><span>，支付宝、微信付款成功时自动记账</span></div></div>';
    }
    if (!S.status.sms) {
      steps += '<div class="step"><div class="n">'+(S.status.listener?'1':'2')+'</div><div class="t">允许<strong>读取短信</strong><span>，银行卡消费和工资到账靠它，还能把过去几个月的记录补进来'+(S.status.smsBlocked?'<br><strong style="color:var(--warning-ink)">系统已记住「不再询问」，弹窗不会再出现，需要去设置里手动打开</strong>':'')+'</span></div></div>';
    }
    var hint = (!S.status.listener)
      ? '<div class="warn">跳转后如果列表里<strong>找不到「账迹」</strong>，说明这个系统改了入口，去 设置 → 通知 → 通知使用权（小米/红米在 应用设置 → 特殊权限设置，华为在 应用 → 特殊权限管理）。</div>'
      : '';
    ob.innerHTML = '<div class="card onboard"><h3>还差两步就能自动记账</h3>' + steps + hint +
      '<div class="row2">' +
      (S.status.listener ? '' : '<button class="btn" onclick="BT_openListener()">开启通知权限</button>') +
      (S.status.sms ? '' : '<button class="btn '+(S.status.listener?'':'ghost')+'" onclick="'+(S.status.smsBlocked?'BT_openSmsSettings()':'BT_importSms()')+'">'+(S.status.smsBlocked?'去设置里开启':'导入短信账目')+'</button>') +
      '</div></div>';
  } else {
    ob.innerHTML = '';
  }

  /* 待确认 */
  if (S.pending.length) {
    $('pendingBox').innerHTML = '<div class="pending" onclick="openPending()">' +
      '<svg viewBox="0 0 24 24"><path d="M12 9v4"/><path d="M12 17h.01"/><path d="M10.3 3.9L2.4 18a2 2 0 0 0 1.7 3h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>' +
      '<div class="t">' + S.pending.length + ' 笔待确认<span>商户没认出来，点一下选分类</span></div><span style="color:var(--text2)">›</span></div>';
  } else {
    $('pendingBox').innerHTML = '';
  }

  /* 列表 */
  var box = $('homeList');
  if (!HAS_BT) { box.innerHTML = ''; return; }
  if (!S.list.length) {
    box.innerHTML = '<div class="card empty">' +
      '<div class="ic"><svg viewBox="0 0 24 24"><path d="M6 2h12v20l-3-2-3 2-3-2-3 2z"/><path d="M9 8h6M9 12h6"/></svg></div>' +
      '<h3>还没有任何记录</h3>' +
      '<p>' + (S.status.listener || S.status.sms
        ? '采集已经开着，正常付款后 2 秒内就会出现在这里。'
        : '完成上面的授权后，付款时会自动记进来。也可以点下面的 + 手动记一笔。') + '</p>' +
      '</div>';
    return;
  }

  var rows = applyFilter(S.list, filter);
  if (!rows.length) {
    box.innerHTML = '<div class="card empty"><h3>没有符合条件的记录</h3><p>试试放宽筛选条件</p></div>';
    return;
  }
  // 每天的支出合计预扫一遍存起来。原来每个分组头都重扫整条 rows，
  // 200 条跨 180 天就是 3.6 万次循环；预计算后是 200 + 分组数。
  var i;
  var daySums = sumByDay(rows);
  var html = '', lastKey = null;
  for (i = 0; i < rows.length; i++) {
    var t = rows[i];
    var k = dayKey(t.ts);
    if (k !== lastKey) {
      html += '<div class="group-head"><span>' + dayLabel(t.ts) + '</span><span class="sum">支出 ' + money(daySums[k] || 0) + '</span></div>';
      lastKey = k;
    }
    html += txnHtml(t);
  }
  // 桥一次最多给 PAGE 条，账目比这多时给「加载更多」，别让界面谎称已经全部显示。
  var total = S.status.count || 0;
  if (S.list.length && S.list.length < total) {
    html += '<div class="more-row"><button class="btn ghost block" onclick="loadMore()">' +
      '加载更多（还有 ' + (total - S.list.length) + ' 条）</button></div>';
  }
  box.innerHTML = html;
}

/** 用当前窗口最旧一条的 (ts,id) 作键集游标往后取一页，追加到 S.list。 */
function loadMore(){
  if (!lastRow) return;
  var before = bridgeFails;
  var more = callJson('listMore', [], lastRow.ts, lastRow.id, PAGE);
  if (bridgeFails !== before) {
    // 桥失败时 callJson 也返回空数组。这不能当成"到底了"，否则一次读库出错
    // 会把「加载更多」按钮永久藏掉，用户只剩"翻不到账"这一个现象。
    call('toast', null, '读取失败，请重试');
    updateBanner();
    return;
  }
  if (!more.length) { lastRow = null; renderHome(); return; }
  // 去重：新账在这一刻插进来时，游标条件下不会重复，但列表本身可能已被刷新过一轮
  var seen = {}, i;
  for (i = 0; i < S.list.length; i++) seen[S.list[i].id] = 1;
  for (i = 0; i < more.length; i++) if (!seen[more[i].id]) S.list.push(more[i]);
  lastRow = { ts: more[more.length - 1].ts, id: more[more.length - 1].id };
  renderHome();
}

function txnHtml(t){
  var d = new Date(t.ts);
  var c = catOf(t.cat);
  var sub = t.sub ? (' · ' + t.sub) : '';
  return '<div class="txn-wrap" style="margin-bottom:10px">' +
    '<div class="txn" role="button" onclick="openDetail(' + t.id + ')">' + iconHtml(t) +
    '<div class="info"><div class="name">' + esc(t.m) + '</div>' +
    '<div class="meta"><span class="cat-chip" style="background:' + c.color + '14;color:' + c.ink + '">' +
    catSvg(t.cat, 11, c.ink, 2.4) + ' ' + c.name + esc(sub) + '</span>' +
    '<span>' + pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + '</span>' +
    (t.ok ? '' : '<span style="color:var(--warning-ink)">待确认</span>') +
    '</div></div>' +
    '<div class="amt' + (t.amt > 0 ? ' pos' : '') + '">' + (t.amt > 0 ? '+' : '−') + money(t.amt).slice(1) + '</div></div></div>';
}

function renderReport(){
  var d = S.daily || [];
  var sum7 = 0;
  for (var i = 0; i < d.length; i++) sum7 += d[i];
  var sum30 = 0;
  for (var j = 0; j < S.cats.length; j++) sum30 += S.cats[j].amt;
  $('r7').textContent = moneyShort(sum7);
  $('r30').textContent = moneyShort(sum30);
  $('rAvg').textContent = moneyShort(sum30 / 30);

  var bars = '', max = 0;
  for (var k = 0; k < d.length; k++) if (d[k] > max) max = d[k];
  for (var m = 0; m < d.length; m++) {
    var dt = new Date(Date.now() - (d.length - 1 - m) * 86400000);
    var h = max > 0 ? Math.max(d[m] / max * 100, 3) : 3;
    var today = m === d.length - 1;
    bars += '<div class="b' + (today ? ' today' : '') + '"><i style="height:' + h.toFixed(0) + '%"></i><span>' + WEEK[dt.getDay()] + '</span></div>';
  }
  /* 全 0 时不能画七根等高短线，那看起来像一张坏掉的图，而新用户恰好就是这个状态。 */
  $('rBars').innerHTML = (bars && max > 0) ? bars :
    '<div class="muted" style="padding:28px 0;text-align:center">近 7 天还没有支出</div>';
  $('r7note').textContent = max > 0 ? ('最高 ' + moneyShort(max)) : '';

  var stack = '', cats = '', total = sum30, shown = 0;
  for (var n = 0; n < S.cats.length && n < 6; n++) {
    var c = catOf(S.cats[n].id);
    var p = total > 0 ? (S.cats[n].amt / total * 100) : 0;
    shown += S.cats[n].amt;
    stack += '<i style="width:' + p.toFixed(1) + '%;background:' + c.color + '"></i>';
    cats += '<div class="catrow"><div class="ci" style="background:' + c.color + '14">' + catSvg(c.id, 16, c.ink) + '</div>' +
      '<div class="nm">' + c.name + '</div><div class="pc">' + Math.round(p) + '%</div><div class="vv num">' + money(S.cats[n].amt) + '</div></div>';
  }
  /* 只列前 6 类时，剩下的钱必须在堆叠条和列表里显出来，否则占比加起来不到 100%、
     「合计」也对不上可见行，右边那段灰槽看着像没画完。 */
  var restN = S.cats.length - 6, rest = total - shown;
  if (restN > 0 && rest > 0.005) {
    var pr = rest / total * 100;
    // 这段的中性色必须和堆叠条底槽（--line）拉开 3:1 以上。原来用 #C9CFD8
    // 时两者只差 1.4:1，"其余 N 类"这一段几乎看不见，而它正是用来解释剩下的钱的。
    stack += '<i style="width:' + pr.toFixed(1) + '%;background:#6D7787"></i>';
    cats += '<div class="catrow"><div class="ci" style="background:var(--fill)">' + catSvg('qita', 16, '#6D7787') + '</div>' +
      '<div class="nm">其余 ' + restN + ' 类</div><div class="pc">' + Math.round(pr) + '%</div><div class="vv num">' + money(rest) + '</div></div>';
  }
  $('rStack').innerHTML = total > 0 ? stack : '';
  $('rStack').style.display = total > 0 ? 'flex' : 'none';
  $('rCats').innerHTML = cats || '<div class="muted" style="padding:16px 0;text-align:center">近 30 天还没有支出</div>';
  $('rCatNote').textContent = total > 0 ? ('合计 ' + moneyShort(total)) : '';
}
function renderBudget(){
  var used = S.month.expense;
  var pct = budget > 0 ? Math.min(used / budget, 1) : 0;
  var C = 2 * Math.PI * 72;
  $('bRing').setAttribute('stroke-dasharray', String(C));
  $('bRing').setAttribute('stroke-dashoffset', String(C * (1 - pct)));
  // 进度弧要和 #EFF1F4 底弧分得开：#0BA360 对底弧只有 2.88:1，换深一档。
  $('bRing').setAttribute('stroke', (budget > 0 && used > budget) ? '#EF4444' : '#087A48');
  $('bPct').textContent = budget > 0 ? Math.round(used / budget * 100) + '%' : '—';
  $('bUsed').textContent = '已用 ' + money(used);
  $('bTotal').textContent = budget > 0 ? money(budget) : '未设置';
  if (budget > 0) {
    var left = budget - used;
    var now = new Date();
    var days = new Date(now.getFullYear(), now.getMonth()+1, 0).getDate() - now.getDate() + 1;
    $('bLeft').textContent = left >= 0 ? money(left) : ('超支 ' + money(-left));
    $('bLeft').style.color = left >= 0 ? '' : 'var(--danger-ink)';
    $('bDays').textContent = days + ' 天';
    $('bPerDay').textContent = left > 0 ? money(left / days) : '—';
  } else {
    $('bLeft').textContent = '—';
    $('bDays').textContent = '—';
    $('bPerDay').textContent = '—';
  }
}

function renderMe(trustState){
  // 桥有故障时真实授权状态未知，显示「—」，别再让人去反复开一个开好的权限
  var known = trustState !== false;
  $('stListener').textContent = known ? (S.status.listener ? '已开启' : '未开启') : '—';
  $('stListener').className = 'pill ' + (S.status.listener ? 'ok' : 'no');
  $('stSms').textContent = !known ? '—' : (S.status.sms ? '已授权' : (S.status.smsBlocked ? '需手动开启' : '未授权'));
  $('stSms').className = 'pill ' + (S.status.sms ? 'ok' : 'no');
  $('meCount').textContent = '共 ' + (S.status.count || 0) + ' 条记录';
  $('meVer').textContent = S.status.ver ? 'v' + S.status.ver : '—';
  // 回填是全表扫描，没有进行中状态的话用户会以为没点上而反复点
  var scanning = !!S.status.scanning;
  var hint = $('rowImportHint');
  if (hint) hint.textContent = scanning ? '正在扫描本机短信，请稍候…' : '把过去几个月的银行短信补进账本';
  var row = $('rowImport');
  if (row) row.classList.toggle('busy', scanning);
}

/* ---------- 筛选 / 搜索 ---------- */
function openFilter(){
  $('fType').innerHTML = [['all','全部'],['exp','仅支出'],['inc','仅收入']].map(function(p){
    return '<button class="opt' + (filter.type===p[0]?' on':'') + '" onclick="setF(\'type\',\''+p[0]+'\')">' + p[1] + '</button>';
  }).join('');
  $('fCats').innerHTML = CATS.map(function(c){
    var on = filter.cats.indexOf(c.id) >= 0;
    return '<button class="opt' + (on?' on':'') + '" onclick="togF(\''+c.id+'\')">' + catSvg(c.id,13,on?PRIMARY_INK:c.ink,2.2) + ' ' + c.name + '</button>';
  }).join('');
  $('fSort').innerHTML = [['time','按时间'],['amt','按金额']].map(function(p){
    return '<button class="opt' + (filter.sort===p[0]?' on':'') + '" onclick="setF(\'sort\',\''+p[0]+'\')">' + p[1] + '</button>';
  }).join('');
  openSheet('sh-filter');
}
function setF(k,v){ filter[k]=v; openFilter(); }
function togF(id){
  var i = filter.cats.indexOf(id);
  if (i >= 0) filter.cats.splice(i,1); else filter.cats.push(id);
  openFilter();
}
function resetFilter(){ filter = {type:'all', cats:[], sort:'time'}; openFilter(); }
function applyFilterAndClose(){ closeSheets(); updateFilterBtn(); renderHome(); }

function openSearch(){
  $('q').value = '';
  $('qRes').innerHTML = '';
  openSheet('sh-search');
  setTimeout(function(){ $('q').focus(); }, 320);
}
/* 搜索命中里可能包含没翻页到的账，openDetail 只认 S.list 会点不开，
   所以把每次结果按 id 存一份，详情/删除都从这里回退查。 */
var searchHits = {}, qTimer = null;
$('q') && $('q').addEventListener('input', function(e){
  var q = e.target.value.trim();
  if (qTimer) clearTimeout(qTimer);
  if (!q) { $('qRes').innerHTML = ''; return; }
  // 每敲一个字就打一次桥没必要，SQL 检索虽不重但结果会一闪一闪的
  qTimer = setTimeout(function(){
    qTimer = null;
    var failed = false, res;
    if (HAS_BT && typeof window.BT.search === 'function') {
      var before = bridgeFails;
      res = callJson('search', [], q);
      failed = bridgeFails !== before;
    } else {
      res = S.list.filter(function(t){
        return (t.m + ' ' + catOf(t.cat).name + ' ' + (t.sub||'')).toLowerCase().indexOf(q.toLowerCase()) >= 0;
      });
    }
    if (failed) {
      // 和"没搜到"必须分开说：读库出错时告诉用户是查询没成功，而不是"你的账里没有这条"
      searchHits = {};
      $('qRes').innerHTML = '<div class="muted center" style="padding:24px 0">搜索没成功，是账本读取的问题，不是没有这条账</div>';
      return;
    }
    searchHits = {};
    for (var i = 0; i < res.length; i++) searchHits[res[i].id] = res[i];
    $('qRes').innerHTML = res.length
      ? res.slice(0, 50).map(function(t){ return txnHtml(t); }).join('')
        + (res.length > 50 ? '<div class="muted center" style="padding:10px 0">只列出前 50 条，缩小一下关键词</div>' : '')
        + '<div class="muted center" style="padding:4px 0 0">命中 ' + res.length + ' 条</div>'
      : '<div class="muted center" style="padding:24px 0">没找到相关记录</div>';
  }, 260);
});

/* ---------- 详情 ---------- */
function openDetail(id){
  var t = null;
  for (var i = 0; i < S.list.length; i++) if (S.list[i].id === id) t = S.list[i];
  if (!t && searchHits[id]) t = searchHits[id];
  if (!t) return;
  curDetail = t;
  var c = catOf(t.cat), d = new Date(t.ts);
  $('dIcon').innerHTML = iconHtml(t, 'lg');
  $('dName').textContent = t.m;
  $('dAmt').textContent = (t.amt > 0 ? '+ ' : '− ') + money(t.amt);
  $('dAmt').className = 'am num' + (t.amt > 0 ? ' pos' : '');
  $('dList').innerHTML =
    '<div class="kv"><span class="k">分类</span><span class="v"><span class="cat-chip" style="background:'+c.color+'14;color:'+c.ink+'">'+catSvg(c.id,12,c.ink,2.2)+' '+c.name+(t.sub?' · '+esc(t.sub):'')+'</span></span></div>' +
    '<div class="kv"><span class="k">时间</span><span class="v num">'+(d.getMonth()+1)+'月'+d.getDate()+'日 '+pad2(d.getHours())+':'+pad2(d.getMinutes())+'</span></div>' +
    '<div class="kv"><span class="k">来源</span><span class="v">'+esc(t.src||'—')+'</span></div>' +
    '<div class="kv"><span class="k">账户</span><span class="v">'+esc(t.acc||'—')+'</span></div>' +
    '<div class="kv"><span class="k">识别置信度</span><span class="v num">'+(t.conf||0)+'%</span></div>';
  $('dRaw').textContent = t.raw || '（没有原始文本）';
  openSheet('sh-detail');
}
function openRecat(){
  if (!curDetail) return;
  $('recatGrid').innerHTML = CATS.map(function(c){
    return '<div class="rcat'+(curDetail.cat===c.id?' on':'')+'" onclick="doRecat(\''+c.id+'\')">' +
      '<div class="ci" style="background:'+c.color+'14">'+catSvg(c.id,17,c.ink)+'</div><div class="cn">'+c.name+'</div></div>';
  }).join('');
  closeSheets();
  setTimeout(function(){ openSheet('sh-recat'); }, 260);
}
function doRecat(cat){
  if (!curDetail) return;
  var c = catOf(cat);
  call('setCategory', null, curDetail.id, cat, c.name, true);
  closeSheets();
  setTimeout(refresh, 300);
}
/* ---------- 删除确认 + 撤销 ---------- */
var pendingConfirm = null, undoTimer = null, undoPayload = null;

function showConfirm(title, msg, yesLabel, cb){
  $('cfTitle').textContent = title;
  $('cfMsg').textContent = msg;
  $('cfYes').textContent = yesLabel;
  pendingConfirm = cb;
  openSheet('sh-confirm');
}
function confirmYes(){
  var cb = pendingConfirm; pendingConfirm = null;
  closeSheets();
  if (cb) cb();
}
function delTxn(){
  if (!curDetail) return;
  var t = curDetail;
  showConfirm('删除这笔记录？',
    money(t.amt) + ' · ' + (t.m || '未命名') + '。删完 6 秒内可以撤销。',
    '删除', function(){
      undoPayload = JSON.stringify(t);   // 删除前把完整字段留一份，撤销就是原样回插
      call('remove', null, t.id);
      setTimeout(refresh, 300);
      showUndo('已删除「' + (t.m || '未命名') + '」');
    });
}
function showUndo(text){
  $('undoTxt').textContent = text;
  $('undoBar').classList.add('on');
  if (undoTimer) clearTimeout(undoTimer);
  undoTimer = setTimeout(hideUndo, 6000);
}
function hideUndo(){
  $('undoBar').classList.remove('on');
  if (undoTimer) { clearTimeout(undoTimer); undoTimer = null; }
  undoPayload = null;
}
function undoDelete(){
  if (undoTimer) { clearTimeout(undoTimer); undoTimer = null; }
  var payload = undoPayload;
  undoPayload = null;
  $('undoBar').classList.remove('on');
  if (!payload) return;
  call('restore', null, payload);
  setTimeout(refresh, 300);
}

/* ---------- 待确认 ---------- */
function openPending(){
  if (!S.pending.length) return;
  $('pendingList').innerHTML = S.pending.map(function(t){
    var d = new Date(t.ts);
    var opts = CATS.slice(0,6).map(function(c){
      return '<button class="opt" onclick="pickPending('+t.id+',\''+c.id+'\')">'+c.name+'</button>';
    }).join('');
    return '<div class="card" style="padding:13px">' +
      '<div style="display:flex;align-items:center;gap:11px">'+iconHtml(t)+
      '<div style="flex:1;min-width:0"><div style="font-size:14px;font-weight:600">'+esc(t.m)+'</div>' +
      '<div style="font-size:11.5px;color:var(--text2);margin-top:2px">'+(d.getMonth()+1)+'月'+d.getDate()+'日 '+pad2(d.getHours())+':'+pad2(d.getMinutes())+' · '+esc(t.acc||t.src)+'</div></div>' +
      '<div class="amt num" style="font-weight:700">'+(t.amt>0?'+':'−')+money(t.amt).slice(1)+'</div></div>' +
      '<div class="opt-row" style="margin-top:11px">'+opts+'</div></div>';
  }).join('');
  openSheet('sh-pending');
}
function pickPending(id, cat){
  var c = catOf(cat);
  call('setCategory', null, id, cat, c.name, true);
  closeSheets();
  setTimeout(refresh, 300);
}

/* ---------- 记一笔 ---------- */
function openRecord(){
  recAmt = ''; recCat = 'canyin'; recType = 'exp';
  $('recName').value = '';
  $('recAmt').innerHTML = '<small>¥</small> 0.00';
  renderRecCats(); renderPad(); renderRecType();
  openSheet('sh-record');
}
function renderRecType(){
  var bs = $('recType').children;
  for (var i = 0; i < bs.length; i++) bs[i].classList.toggle('on', bs[i].getAttribute('data-rt') === recType);
}
function renderRecCats(){
  $('recCats').innerHTML = CATS.map(function(c){
    return '<div class="rcat'+(recCat===c.id?' on':'')+'" onclick="pickRecCat(\''+c.id+'\')">' +
      '<div class="ci" style="background:'+c.color+'14">'+catSvg(c.id,17,c.ink)+'</div><div class="cn">'+c.name+'</div></div>';
  }).join('');
}
function pickRecCat(id){ recCat = id; renderRecCats(); }
function renderPad(){
  var keys = ['1','2','3','4','5','6','7','8','9','.','0','⌫'];
  $('pad').innerHTML = keys.map(function(k){
    return '<button class="key'+((k==='⌫'||k==='.')?' fn':'')+'" onclick="press(\''+k+'\')">'+k+'</button>';
  }).join('');
}
function press(k){
  if (k === '⌫') recAmt = recAmt.slice(0, -1);
  else if (k === '.') { if (recAmt.indexOf('.') < 0) recAmt = recAmt === '' ? '0.' : recAmt + '.'; }
  else { if (recAmt.replace('.','').length >= 8) return; recAmt += k; }
  $('recAmt').innerHTML = '<small>¥</small> ' + (parseFloat(recAmt) || 0).toFixed(2);
}
function saveManual(){
  var v = parseFloat(recAmt);
  if (!v || v <= 0) { call('toast', null, '先输入金额'); return; }
  if (!debounce('manual', 1200)) return;
  var c = catOf(recCat);
  var amt = recType === 'exp' ? -v : v;
  call('addManual', null, amt, $('recName').value.trim(), recCat, c.name);
  closeSheets();
  setTimeout(refresh, 300);
}
$('recType') && $('recType').addEventListener('click', function(e){
  var b = e.target.closest('button');
  if (!b) return;
  recType = b.getAttribute('data-rt');
  renderRecType();
});

/* ---------- 预算 ---------- */
function openBudget(){
  $('budgetInput').value = budget > 0 ? budget : '';
  openSheet('sh-budget');
  setTimeout(function(){ $('budgetInput').focus(); }, 320);
}
function saveBudget(){
  var raw = $('budgetInput').value.trim();
  var v = parseFloat(raw);
  // 留空 = 保持原样。以前非法输入会把预算静默清零，用户打个错字就丢了设置。
  if (raw === '') { closeSheets(); return; }
  if (!isFinite(v) || v < 0) { call('toast', null, '请输入大于 0 的数字'); return; }
  if (v === 0) {
    budget = 0; saveSettings();
    closeSheets(); call('toast', null, '已清除预算'); renderHome(); renderBudget(); return;
  }
  if (v > BUDGET_MAX) { call('toast', null, '预算太大了，请输入 10 亿以内'); return; }
  budget = v;
  saveSettings();
  closeSheets();
  renderHome(); renderBudget();
}

/* ---------- 我的 ---------- */
function toggleMask(){
  masked = !masked;
  saveSettings();
  renderTop();
}
function doExport(){
  if (!HAS_BT) return;
  // exportCsv 永远带表头，所以判空得看记录数而不是看字符串
  if (!S.status.count) { call('toast', null, '还没有记录可导出'); return; }
  if (!debounce('export', 4000)) return;
  var csv = call('exportCsv', '');
  if (!csv) { call('toast', null, '导出失败'); return; }
  call('toast', null, '正在导出…');
  try { window.BT.saveFile(csv); } catch (e) { call('toast', null, '导出失败'); }
}
function BT_openListener(){ if (!debounce('listener', 1200)) return; call('openNotificationSettings', null); }
function BT_importSms(){
  if (S.status.scanning) { call('toast', null, '正在扫描短信，请稍候'); return; }
  if (!debounce('import', 1500)) return;
  call('importSms', null);
  setTimeout(refresh, 400);   // 让「正在扫描」立刻显示出来，不等下一次回前台
}
function BT_openSmsSettings(){ if (!debounce('smsset', 1200)) return; call('openSmsPermissionSettings', null); }

/* ---------- 弹层 / 导航 ---------- */
function openSheet(id){
  $('mask').classList.add('on');
  $(id).classList.add('on');
}
function closeSheets(){
  $('mask').classList.remove('on');
  var ss = document.querySelectorAll('.sheet.on');
  for (var i = 0; i < ss.length; i++) ss[i].classList.remove('on');
}
function switchTab(name){
  tab = name;
  var ps = document.querySelectorAll('.page');
  for (var i = 0; i < ps.length; i++) ps[i].classList.remove('active');
  $('p-' + name).classList.add('active');
  var ts = document.querySelectorAll('.tab');
  for (var j = 0; j < ts.length; j++) ts[j].classList.toggle('on', ts[j].getAttribute('data-tab') === name);
  var titles = {home:'首页', report:'报表', budget:'预算', me:'我的'};
  $('tbTitle').textContent = titles[name] || '账迹';
  window.scrollTo(0, 0);
}
$('eyeBtn').onclick = toggleMask;
$('searchBtn').onclick = openSearch;
$('fltBtn').onclick = openFilter;
var tabs = document.querySelectorAll('.tab[data-tab]');
for (var ti = 0; ti < tabs.length; ti++) {
  tabs[ti].onclick = function(){ switchTab(this.getAttribute('data-tab')); };
}
// 不再监听 visibilitychange：回前台时原生 onResume 已经调过 BTRefresh，
// 这里再来一遍等于每次回前台把六个同步桥调用全跑两遍。


/* 全局兜底：任何漏网的异常都要在界面上看得见，而不是静默停在半更新状态。
   只留最近若干条在内存里，不把账目内容写进任何持久存储。 */
window.addEventListener('error', function(e){
  noteJsError(e && e.message ? e.message : '未知错误');
  updateBanner();
});
window.addEventListener('unhandledrejection', function(e){
  var r = e && e.reason;
  noteJsError('promise: ' + (r && r.message ? r.message : r));
  updateBanner();
});

/* ---------- 软件更新 ----------
   桥上的四个动作：updateCheck / updateDownload / updateProgress / updateInstall。
   下载交给系统的 DownloadManager（通知栏也有一条进度），这里只轮询状态并显示；
   装的时候打开系统安装界面 —— Android 不允许应用自己静默安装，必须由用户点「下一步」。 */
var UP = {latest:'', url:'', size:0, notes:'', state:'idle', timer:null};

function upFmtSize(n){
  n = Number(n) || 0;
  if (n <= 0) return '';
  return n >= 1048576 ? (n / 1048576).toFixed(1) + ' MB' : Math.round(n / 1024) + ' KB';
}
function upSetBusy(busy, text){
  var b = $('btnUpdate');
  if (!b) return;
  b.disabled = !!busy;
  b.textContent = text || '检查更新';
}
function checkUpdate(){
  if (!HAS_BT) { call('toast', null, '更新只在安装版里可用'); return; }
  upSetBusy(true, '检查中…');
  var r = callJson('updateCheck', null);
  upSetBusy(false);
  if (!r || !r.ok) { call('toast', null, (r && r.error) || '检查更新失败，请检查网络后重试'); return; }
  var cur = r.current || S.status.ver || '';
  if (verCmp(r.latest, cur) <= 0) { call('toast', null, '已是最新版本 v' + cur); return; }
  UP.latest = r.latest; UP.url = r.url || ''; UP.size = r.size || 0; UP.notes = r.notes || '';
  UP.state = 'available';
  renderUpdateSheet();
  openSheet('sh-update');
}
function renderUpdateSheet(){
  $('upTitle').textContent = '发现新版本 v' + UP.latest;
  var bits = ['当前 v' + (S.status.ver || '—')];
  var sz = upFmtSize(UP.size);
  if (sz) bits.push(sz);
  $('upMsg').textContent = bits.join(' · ');
  $('upNotes').textContent = UP.notes || '本次更新没有附带说明。';
  var go = $('upGo'), later = $('upLater'), wrap = $('upProgWrap');
  if (!go || !later || !wrap) return;
  if (UP.state === 'downloading') {
    wrap.hidden = false;
    go.disabled = true; go.textContent = '下载中…';
    later.textContent = '后台下载';
  } else if (UP.state === 'done') {
    wrap.hidden = false;
    go.disabled = false; go.textContent = '安装';
    later.textContent = '以后再说';
  } else {
    wrap.hidden = true;
    go.disabled = false; go.textContent = '更新并重启';
    later.textContent = '以后再说';
  }
}
function upPaintProgress(p){
  var wrap = $('upProgWrap'), bar = $('upProg'), fill = $('upProgFill'), txt = $('upProgTxt');
  if (!wrap || !bar || !fill || !txt) return;
  wrap.hidden = false;
  var pct = pctOf(p.bytes, p.total);
  if (pct < 0) {
    /* 总量还没拿到：报已下载字节数，别让进度条永远停在 0% 看着像卡死 */
    fill.style.width = '0%';
    bar.setAttribute('aria-valuenow', '0');
    txt.textContent = '正在下载…已下载 ' + (upFmtSize(p.bytes) || '0 KB');
  } else {
    fill.style.width = pct + '%';
    bar.setAttribute('aria-valuenow', String(pct));
    txt.textContent = '正在下载…' + pct + '%';
  }
}
function upStopPoll(){ if (UP.timer) { clearInterval(UP.timer); UP.timer = null; } }
function upPoll(){
  var p = callJson('updateProgress', null);
  if (!p) return;
  if (p.state === 'failed') {
    upStopPoll(); UP.state = 'available'; renderUpdateSheet();
    call('toast', null, '下载失败，请稍后重试');
    return;
  }
  if (p.state === 'done') {
    upStopPoll(); UP.state = 'done';
    upPaintProgress({bytes: p.bytes || 0, total: p.total || 0});
    renderUpdateSheet();
    call('toast', null, '下载完成，点「安装」继续');
    return;
  }
  if (p.state === 'idle') { upStopPoll(); UP.state = 'available'; renderUpdateSheet(); return; }
  upPaintProgress(p);
}
function updateGo(){
  if (UP.state === 'done') { upInstall(); return; }
  if (UP.state === 'downloading') return;
  var r = callJson('updateDownload', null, UP.url, UP.latest);
  if (!r || !r.ok) { call('toast', null, (r && r.error) || '开始下载失败'); return; }
  UP.state = 'downloading';
  renderUpdateSheet();
  upStopPoll();
  UP.timer = setInterval(upPoll, 800);
  upPoll();
}
function upInstall(){
  var r = callJson('updateInstall', null);
  if (!r) { call('toast', null, '打开安装程序失败'); return; }
  if (r.needPermission) { call('toast', null, r.error || '请先允许安装未知来源的应用'); return; }
  if (!r.ok) { call('toast', null, r.error || '打开安装程序失败'); return; }
  call('toast', null, '已打开安装界面，按提示点「下一步」完成');
  closeSheets();
}
/* 切走页面时停掉轮询：下载在系统侧继续，回来再点「检查更新」即可看到就绪状态 */
window.addEventListener('pagehide', upStopPoll);

refresh();
