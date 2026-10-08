/* =========================================================================
   账迹 BillTrace —— 纯函数层
   这里只放"不碰 DOM、不碰桥、不碰 localStorage"的逻辑：格式化、转义、
   分类表、筛选、按天聚合、设置读写、桥返回值形状校验。
   放进来就同时能被浏览器全局使用和被 Node 单测 require，
   因为原先这些函数埋在 index.html 里，只有开浏览器才能测到。

   约定：新增函数只有在确实不需要 document/window/BT/localStorage 时才准放进来。
   ========================================================================= */
(function (root, factory) {
  var api = factory();
  // Node：整体导出，测试里 require 的就是这个对象
  if (typeof module === 'object' && module.exports) module.exports = api;
  // 浏览器：铺成全局，app.js 里的调用点不用改成 BTCore.money(...) 这种写法
  for (var k in api) {
    if (Object.prototype.hasOwnProperty.call(api, k)) root[k] = api[k];
  }
  root.BTCore = api;
})(typeof self !== 'undefined' ? self : this, function () {

  var CATS = [
    {id:'canyin',  name:'餐饮', color:'#F59E0B', ink:'#A36907'},
    {id:'jiaotong',name:'交通', color:'#3B82F6', ink:'#1E6FF5'},
    {id:'gouwu',   name:'购物', color:'#A855F7', ink:'#9F44F6'},
    {id:'juzhu',   name:'居住', color:'#6366F1', ink:'#6164F1'},
    {id:'yule',    name:'娱乐', color:'#EC4899', ink:'#E2177B'},
    {id:'yiliao',  name:'医疗', color:'#22C55E', ink:'#178841'},
    {id:'jiaoyu',  name:'教育', color:'#0EA5E9', ink:'#0B7EB2'},
    {id:'renqing', name:'人情', color:'#F472B6', ink:'#E2127E'},
    {id:'jinrong', name:'金融', color:'#EAB308', ink:'#947105'},
    {id:'qita',    name:'其他', color:'#9CA3AF', ink:'#6D7787'}
  ];
  var CICON = {
    canyin:'<path d="M3 11h18a9 9 0 0 1-18 0Z"/><path d="M8 7c0-1.5 1-1.5 1-3"/><path d="M13 7c0-1.5 1-1.5 1-3"/>',
    jiaotong:'<rect x="4" y="3" width="16" height="15" rx="3"/><path d="M4 10h16"/><path d="M8 21v-3M16 21v-3"/><path d="M9 14.5h.01M15 14.5h.01" stroke-width="3"/>',
    gouwu:'<path d="M6.5 7.5h11L18.5 21h-13L6.5 7.5Z"/><path d="M9 10.5V6a3 3 0 0 1 6 0v4.5"/>',
    juzhu:'<path d="M3 11.5L12 4l9 7.5"/><path d="M5.5 10.5V20h13v-9.5"/><path d="M10 20v-5h4v5"/>',
    yule:'<circle cx="12" cy="12" r="9"/><path d="M10 8.5l5.5 3.5-5.5 3.5Z"/>',
    yiliao:'<path d="M10 3h4v7h7v4h-7v7h-4v-7H3v-4h7V3Z"/>',
    jiaoyu:'<path d="M4 19V5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2Z"/><path d="M19 19H6a2 2 0 0 0-2 2"/><path d="M9 7h6"/>',
    renqing:'<rect x="4" y="11" width="16" height="9" rx="1"/><path d="M4 11h16M12 11v9"/><path d="M12 11c-1.8-.4-4.5-1.3-4.5-3.4A2.1 2.1 0 0 1 9.6 5.5c1.4 0 2.4 1.6 2.4 5.5 0-3.9 1-5.5 2.4-5.5a2.1 2.1 0 0 1 2.1 2.1c0 2.1-2.7 3-4.5 3.4Z"/>',
    jinrong:'<circle cx="12" cy="12" r="9"/><path d="M9 7.5l3 4.5 3-4.5"/><path d="M12 12v4.5"/><path d="M9.5 13h5M9.5 15h5"/>',
    qita:'<path d="M5 12h.01M12 12h.01M19 12h.01" stroke-width="3.5"/>'
  };
  var ICON_KEYS = {alipay:1,wechat:1,meituan:1,taobao:1,starbucks:1,kfc:1,jd:1,pdd:1,luckin:1,didi:1,amap:1,music163:1,tvideo:1,cmb:1};
  var WEEK = ['日','一','二','三','四','五','六'];
  /* SVG 描边只能吃字面量，拿不到 CSS 变量；与 ui.css 里 :root 的 --primary-ink 保持同值。 */
  var PRIMARY_INK = '#087A48';
  var SETTINGS_KEY = 'bt_settings_v1';
  var BUDGET_MAX = 1e9;
  /* 列表分页：Bridge.list 一次最多给 PAGE 条。 */
  var PAGE = 200;

  function catOf(id) {
    for (var i = 0; i < CATS.length; i++) if (CATS[i].id === id) return CATS[i];
    return CATS[9];
  }
  function catSvg(id, size, color, sw) {
    return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 24 24" fill="none" stroke="' + color +
      '" stroke-width="' + (sw || 2) + '" stroke-linecap="round" stroke-linejoin="round">' +
      (CICON[id] || CICON.qita) + '</svg>';
  }
  /* 金额格式化。null/undefined/非数一律给占位符：Number(null) === 0，
     不挡住的话桥里少给一个字段就会显示成 "¥0.00"，把缺失值伪装成一条真实的零金额账。 */
  function money(n) {
    if (n === null || n === undefined || n === '') return '¥—';
    n = Number(n);
    if (!isFinite(n)) return '¥—';
    return '¥' + Math.abs(n).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});
  }
  function moneyShort(n) {
    if (n === null || n === undefined || n === '') return '¥—';
    n = Number(n);
    if (!isFinite(n)) return '¥—';
    var v = Math.abs(n);
    if (v >= 10000) return '¥' + (v / 10000).toFixed(1) + '万';
    return '¥' + Math.round(v).toLocaleString('zh-CN');
  }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function dayKey(ts) { var d = new Date(ts); return d.getFullYear() + '-' + d.getMonth() + '-' + d.getDate(); }
  function dayLabel(ts) {
    var d = new Date(ts), now = new Date();
    var t0 = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    var d0 = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    var diff = Math.round((t0 - d0) / 86400000);
    if (diff === 0) return '今天';
    if (diff === 1) return '昨天';
    if (diff === 2) return '前天';
    return (d.getMonth() + 1) + '月' + d.getDate() + '日 周' + WEEK[d.getDay()];
  }
  function iconHtml(t, cls) {
    var app = t.app || '';
    if (ICON_KEYS[app]) return '<div class="bic ' + (cls || '') + '"><img src="assets/icons/' + app + '.webp" alt=""></div>';
    var c = catOf(t.cat);
    return '<div class="bic ' + (cls || '') + '" style="background:' + c.color + '14">' + catSvg(t.cat, 20, c.ink) + '</div>';
  }
  /* 外部可控文本（商户名、短信原文）只能以文本节点的身份出现，不能成为节点。
     引号也要转义，因为这些字符串会拼进 style="..." 和 onclick="..." 里。 */
  function esc(s) {
    if (s === null || s === undefined) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function clampBudget(v) {
    var b = Number(v);
    if (!isFinite(b) || b < 0 || b > BUDGET_MAX) return 0;
    return b;
  }

  /**
   * 设置读取。三个入参是从 localStorage 里取出的原始字符串，取值本身由调用方负责。
   * 坏数据一律退回默认值：这里存的值可能被旧版本写过、被用户手改过、或压根不是 JSON，
   * 任何一种都不能让界面白屏。
   */
  function readSettings(rawV1, rawOldBudget, rawOldMask) {
    var s = {v: 1, budget: 0, mask: false};
    try {
      if (rawV1) {
        var o = JSON.parse(rawV1);
        if (o && typeof o === 'object') { s.budget = clampBudget(o.budget); s.mask = o.mask === true; }
      } else {
        // 兼容 v0.4.3 之前分开的两个键
        s.budget = clampBudget(parseFloat(rawOldBudget));
        s.mask = rawOldMask === '1';
      }
    } catch (e) { /* 坏 JSON 退回默认值 */ }
    return s;
  }
  function settingsJson(budget, masked) {
    return JSON.stringify({v: 1, budget: clampBudget(budget), mask: masked === true});
  }

  /** 筛选 + 排序。rows 与 filter 都显式传入，不读全局，才测得动。 */
  function applyFilter(rows, f) {
    var type = f && f.type ? f.type : 'all';
    var cats = (f && f.cats) || [];
    var sort = f && f.sort ? f.sort : 'time';
    var out = [];
    for (var i = 0; i < rows.length; i++) {
      var t = rows[i];
      if (type === 'exp' && t.amt >= 0) continue;
      if (type === 'inc' && t.amt <= 0) continue;
      if (cats.length && cats.indexOf(t.cat) < 0) continue;
      out.push(t);
    }
    if (sort === 'amt') out.sort(function (a, b) { return Math.abs(b.amt) - Math.abs(a.amt); });
    else out.sort(function (a, b) { return b.ts - a.ts; });
    return out;
  }

  /**
   * 按天聚合支出合计，Map<dayKey, 正数金额>。
   * 收入（amt>0）不计入：分组头标的是"支出"，把工资算进去会把当天数字抬高。
   * 一次遍历。原先每个分组头都重扫整条 rows，2000 条跨 2000 天要 400 万次比较。
   */
  function sumByDay(rows) {
    var m = {};
    for (var i = 0; i < rows.length; i++) {
      if (rows[i].amt < 0) {
        var k = dayKey(rows[i].ts);
        m[k] = (m[k] || 0) + (-rows[i].amt);
      }
    }
    return m;
  }

  /**
   * 桥返回值的形状校验。不校验的话，Java 侧字段缺失会一路 undefined 到 money()，
   * 首页打出 ¥NaN，还会把"读取失败"当成"没授权"让用户反复去开已经开好的权限。
   * 返回 true 表示形状可信。数组类的合法值包括空数组（新用户账本就是空的）。
   */
  function validateShape(name, o) {
    if (!o || typeof o !== 'object') return false;
    if (o.error) return false;
    if (name === 'list' || name === 'listMore' || name === 'search' ||
        name === 'daily' || name === 'cats' || name === 'pendingList') return Array.isArray(o);
    if (name === 'status') {
      return typeof o.listener === 'boolean' && typeof o.sms === 'boolean' && typeof o.count === 'number';
    }
    if (name === 'month' || name === 'today') return typeof o.expense === 'number';
    return true;
  }

  /**
   * 语义化版本比较：a 比 b 新返回 1，相同返回 0，更旧返回 -1。
   * 只比三段数字，预发布后缀（0.5.0-beta.1）先剥掉 —— 否则 parseInt 出 NaN，
   * 而 NaN 的所有比较都是 false，"有新版本"就永远判不出来。
   */
  function verCmp(a, b) {
    var pa = String(a == null ? '' : a).replace(/^v/i, '').split('-')[0].split('.');
    var pb = String(b == null ? '' : b).replace(/^v/i, '').split('-')[0].split('.');
    for (var i = 0; i < 3; i++) {
      var x = parseInt(pa[i], 10) || 0;
      var y = parseInt(pb[i], 10) || 0;
      if (x > y) return 1;
      if (x < y) return -1;
    }
    return 0;
  }

  /**
   * 下载进度百分比（0-100 整数）。
   * total 未知时返回 -1：DownloadManager 在拿到 Content-Length 之前会报 -1，
   * 直接按 0 处理的话进度条永远停在 0%，看着像卡死。
   */
  function pctOf(done, total) {
    var d = Number(done), t = Number(total);
    if (!isFinite(d) || d < 0) d = 0;
    if (!isFinite(t) || t <= 0) return -1;
    var p = Math.round(d / t * 100);
    if (p < 0) return 0;
    return p > 100 ? 100 : p;
  }

  return {
    CATS: CATS, CICON: CICON, ICON_KEYS: ICON_KEYS, WEEK: WEEK, PRIMARY_INK: PRIMARY_INK,
    verCmp: verCmp, pctOf: pctOf,
    SETTINGS_KEY: SETTINGS_KEY, BUDGET_MAX: BUDGET_MAX, PAGE: PAGE,
    catOf: catOf, catSvg: catSvg, money: money, moneyShort: moneyShort, pad2: pad2,
    dayKey: dayKey, dayLabel: dayLabel, iconHtml: iconHtml, esc: esc,
    clampBudget: clampBudget, readSettings: readSettings, settingsJson: settingsJson,
    applyFilter: applyFilter, sumByDay: sumByDay, validateShape: validateShape
  };
});
