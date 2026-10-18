/* ============================================================================
 * MDT DO 服务器门户 - 每日一言
 *  - 本地语料：assets/data/quotes.json（1484 条，来源 hitokoto-osc/sentences-bundle：
 *    游戏 / 文学 / 诗词 / 哲学 / 影视 / 动画 六类）；离线可用，无外网依赖。
 *  - 可选联网：若浏览器能访问一言 API（v1.hitokoto.cn），每隔一段时间尝试拉一条新的；
 *    失败静默降级，不影响页面。
 *  - 交互：点击换一条；默认 45 秒自动轮换（页面不可见时暂停）。
 *  数据字段约定：{ t: 正文, f: 出处(作品), w: 作者/UP主, c: 分类 }
 * ==========================================================================*/
(function (global) {
  'use strict';

  var DATA_URL = 'assets/data/quotes.json';
  var REMOTE_URL = 'https://v1.hitokoto.cn/?c=c&c=d&c=i&c=k&c=h&c=a&encode=json&charset=utf-8';
  var DEFAULT_ROTATE_MS = 45 * 1000;
  var REMOTE_MIN_INTERVAL = 10 * 60 * 1000;   // 联网刷新最小间隔
  var REMOTE_TIMEOUT = 2500;
  var CACHE_KEY = 'mdt.portal.quote.pool.v1';

  var els = { root: null, text: null, from: null, cat: null };
  var pool = [];
  var ready = false;
  var lastText = '';
  var rotateTimer = null;
  var lastRemoteAt = 0;
  var busyRemote = false;

  function site() { return global.PORTAL_SITE || {}; }
  function quoteCfg() { return site().quote || {}; }

  function normalize(item) {
    if (!item || typeof item !== 'object') return null;
    var text = String(item.t || item.hitokoto || '').replace(/\s+/g, ' ').trim();
    if (!text) return null;
    return {
      t: text,
      f: String(item.f || item.from || '').replace(/\s+/g, ' ').trim(),
      w: String(item.w || item.from_who || '').replace(/\s+/g, ' ').trim(),
      c: String(item.c || item.cat || '').trim()
    };
  }

  function setPool(list) {
    var out = [];
    var seen = {};
    for (var i = 0; i < list.length; i++) {
      var q = normalize(list[i]);
      if (!q || seen[q.t]) continue;
      seen[q.t] = true;
      out.push(q);
    }
    pool = out;
    ready = pool.length > 0;
    return ready;
  }

  function readCache() {
    try {
      var raw = global.sessionStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      var data = JSON.parse(raw);
      return Array.isArray(data) ? data : null;
    } catch (e) { return null; }
  }

  function writeCache(list) {
    try { global.sessionStorage.setItem(CACHE_KEY, JSON.stringify(list)); } catch (e) { /* 配额/隐私模式忽略 */ }
  }

  function pick() {
    if (!ready) return null;
    if (pool.length === 1) return pool[0];
    for (var i = 0; i < 12; i++) {
      var q = pool[Math.floor(Math.random() * pool.length)];
      if (q.t !== lastText) return q;
    }
    return pool[Math.floor(Math.random() * pool.length)];
  }

  /* 渲染：全部走 textContent，杜绝 HTML 注入（语料为纯文本） */
  function render(q, animated) {
    if (!q || !els.root) return;
    lastText = q.t;
    els.text.textContent = q.t;
    var meta = '';
    if (q.f) meta += '—— ' + q.f;
    if (q.w) meta += (meta ? ' · ' : '—— ') + q.w;
    els.from.textContent = meta;
    els.cat.textContent = q.c || '';
    els.cat.style.display = q.c ? '' : 'none';
    if (animated) {
      els.root.classList.remove('dq-in');
      void els.root.offsetWidth;   // 强制重排以重放动画
      els.root.classList.add('dq-in');
    }
  }

  function next() { render(pick(), true); }

  function loadLocal() {
    var cached = readCache();
    if (cached && setPool(cached)) {
      render(pick(), false);
      return;
    }
    if (!global.fetch) return;
    global.fetch(DATA_URL, { cache: 'force-cache' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (list) {
      if (Array.isArray(list) && setPool(list)) {
        writeCache(list.slice(0, 400));
        render(pick(), true);
      }
    }).catch(function () { /* 离线 / 文件缺失：保持占位，不影响页面 */ });
  }

  function tryRemote() {
    var cfg = quoteCfg();
    if (cfg.remoteEnabled === false) return;
    if (busyRemote || !global.fetch) return;
    var now = Date.now();
    if (now - lastRemoteAt < REMOTE_MIN_INTERVAL) return;
    lastRemoteAt = now;
    busyRemote = true;

    var ctrl = ('AbortController' in global) ? new AbortController() : null;
    var timer = ctrl ? global.setTimeout(function () { ctrl.abort(); }, REMOTE_TIMEOUT) : null;
    global.fetch(REMOTE_URL, { mode: 'cors', signal: ctrl ? ctrl.signal : undefined })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (data) {
        if (timer) global.clearTimeout(timer);
        var q = normalize(data);
        if (q && q.t !== lastText) render(q, true);
      })
      .catch(function () { if (timer) global.clearTimeout(timer); })
      .then(function () { busyRemote = false; });
  }

  function startTimer() {
    var seconds = Number(quoteCfg().rotateSeconds);
    if (!isFinite(seconds) || seconds <= 0) seconds = DEFAULT_ROTATE_MS / 1000;
    var ms = Math.max(10, seconds) * 1000;
    if (rotateTimer) global.clearInterval(rotateTimer);
    rotateTimer = global.setInterval(function () {
      if (global.document.hidden) return;   // 后台标签页不轮换
      next();
      tryRemote();
    }, ms);
  }

  function bind() {
    els.root.setAttribute('role', 'button');
    els.root.setAttribute('tabindex', '0');
    els.root.setAttribute('title', '点击换一条');
    function onNext(e) {
      if (e && e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
      if (e) e.preventDefault();
      next();
      tryRemote();
    }
    els.root.addEventListener('click', onNext);
    els.root.addEventListener('keydown', onNext);
  }

  function init() {
    els.root = document.getElementById('dailyQuote');
    if (!els.root) return;
    els.text = document.getElementById('dqText');
    els.from = document.getElementById('dqFrom');
    els.cat = document.getElementById('dqCat');
    if (!els.text || !els.from || !els.cat) return;
    bind();
    loadLocal();
    startTimer();
    // 页面重新可见时，若已闲置较久则顺手换一条
    global.document.addEventListener('visibilitychange', function () {
      if (!global.document.hidden) tryRemote();
    });
  }

  if (global.document.readyState === 'loading') {
    global.document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(window);
