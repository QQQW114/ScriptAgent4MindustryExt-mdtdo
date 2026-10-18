/* ============================================================================
 * MDT DO 服务器门户 - 通用工具（无依赖，无外部请求）
 * 所有输出到 DOM 的字符串必须经由 esc() 转义，或由 PortalMarkup 生成安全 HTML。
 * ==========================================================================*/
(function (global) {
  'use strict';

  var ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

  /** HTML 转义：所有动态文本进入 innerHTML 前必须经过它。 */
  function esc(value) {
    if (value === null || value === undefined) return '';
    return String(value).replace(/[&<>"']/g, function (ch) { return ESC_MAP[ch]; });
  }

  /** 数字格式化（千分位）。 */
  function fmtInt(value) {
    var n = Number(value);
    if (!isFinite(n)) return '--';
    return n.toLocaleString('zh-CN');
  }

  /** 字节数 -> 人类可读大小。 */
  function fmtBytes(bytes) {
    var n = Number(bytes);
    if (!isFinite(n) || n < 0) return '--';
    if (n < 1024) return n + ' B';
    var units = ['KB', 'MB', 'GB', 'TB'];
    var i = -1;
    do { n = n / 1024; i++; } while (n >= 1024 && i < units.length - 1);
    return (n >= 100 ? Math.round(n) : Math.round(n * 10) / 10) + ' ' + units[i];
  }

  /** 秒 -> “3天 2小时 5分”；不足 1 分钟显示秒。 */
  function fmtDuration(seconds) {
    var s = Math.max(0, Math.floor(Number(seconds) || 0));
    if (!s) return '0 分钟';
    var d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
    if (d > 0) return d + ' 天 ' + h + ' 小时 ' + m + ' 分';
    if (h > 0) return h + ' 小时 ' + m + ' 分';
    if (m > 0) return m + ' 分钟';
    return s + ' 秒';
  }

  /** 毫秒 -> 小时数（游玩时长等）。 */
  function fmtHoursFromMs(ms) {
    var h = Number(ms) / 3600000;
    if (!isFinite(h) || h < 0) return '--';
    if (h >= 100) return fmtInt(Math.round(h)) + ' h';
    return (Math.round(h * 10) / 10).toLocaleString('zh-CN') + ' h';
  }

  /**
   * 带宽格式化。注意：后端 data.net.avgUpBps / series[].bps 的名字虽叫 bps，
   * 实际单位是**字节/秒（B/s）**（portal-metrics.ps1 用网卡 SentBytes 差分/秒），
   * 因此这里按 B/s → KB/s → MB/s 格式化，不再除以 8。
   */
  function fmtBps(bytesPerSecond) {
    var n = Number(bytesPerSecond);
    if (!isFinite(n) || n < 0) return '--';
    if (n < 1024) return Math.round(n) + ' B/s';
    if (n < 1024 * 1024) {
      var kb = n / 1024;
      return (kb < 10 ? (Math.round(kb * 10) / 10) : Math.round(kb)) + ' KB/s';
    }
    var mb = n / (1024 * 1024);
    return (mb < 10 ? (Math.round(mb * 10) / 10) : Math.round(mb)) + ' MB/s';
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  /** epoch 毫秒 -> “HH:mm:ss”。 */
  function fmtTime(ts) {
    var d = new Date(Number(ts) || 0);
    if (isNaN(d.getTime())) return '--:--:--';
    return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
  }

  /** epoch 毫秒 -> “YYYY-MM-DD”。 */
  function fmtDate(ts) {
    var d = new Date(Number(ts) || 0);
    if (isNaN(d.getTime())) return '--';
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }

  /** epoch 毫秒 -> “YYYY-MM-DD HH:mm:ss”。 */
  function fmtDateTime(ts) {
    return fmtDate(ts) + ' ' + fmtTime(ts);
  }

  /** 相对时间：刚刚 / N 分钟前 / N 小时前 / 日期。 */
  function timeAgo(ts) {
    var t = Number(ts) || 0;
    if (!t) return '--';
    var diff = Date.now() - t;
    if (diff < 0) diff = 0;
    if (diff < 30000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    if (diff < 7 * 86400000) return Math.floor(diff / 86400000) + ' 天前';
    return fmtDate(t);
  }

  /** 倒计时：毫秒 -> “11:59:30”。 */
  function fmtCountdown(ms) {
    var s = Math.max(0, Math.floor(Number(ms) / 1000));
    var h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    return pad2(h) + ':' + pad2(m) + ':' + pad2(sec);
  }

  /** sessionStorage / localStorage 安全封装。 */
  function storageArea(kind) {
    try { return kind === 'local' ? global.localStorage : global.sessionStorage; } catch (e) { return null; }
  }
  function getItem(key, area) {
    var s = storageArea(area);
    if (!s) return null;
    try { return s.getItem(key); } catch (e) { return null; }
  }
  function setItem(key, value, area) {
    var s = storageArea(area);
    if (!s) return false;
    try { s.setItem(key, value); return true; } catch (e) { return false; }
  }
  function removeItem(key, area) {
    var s = storageArea(area);
    if (!s) return;
    try { s.removeItem(key); } catch (e) { /* ignore */ }
  }
  function getJson(key, area) {
    var raw = getItem(key, area);
    if (!raw) return null;
    try { return JSON.parse(raw); } catch (e) { return null; }
  }
  function setJson(key, obj, area) {
    try { return setItem(key, JSON.stringify(obj), area); } catch (e) { return false; }
  }

  /** 查询串工具；同时解析 ?host= / ?port= 之类的覆盖参数。 */
  function query() {
    try { return new URLSearchParams(global.location.search || ''); } catch (e) { return new URLSearchParams(''); }
  }

  function clamp(n, min, max) {
    n = Number(n) || 0;
    return Math.min(max, Math.max(min, n));
  }

  /** 简易防抖。 */
  function debounce(fn, wait) {
    var timer = null;
    return function () {
      var args = arguments, self = this;
      clearTimeout(timer);
      timer = setTimeout(function () { fn.apply(self, args); }, wait);
    };
  }

  function $ (sel, root) { return (root || document).querySelector(sel); }
  function $$ (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /** 复制文本：优先 navigator.clipboard，失败回退 textarea。返回 Promise<boolean>。 */
  function copyText(text) {
    var value = String(text == null ? '' : text);
    if (global.navigator && global.navigator.clipboard && global.navigator.clipboard.writeText) {
      return global.navigator.clipboard.writeText(value).then(function () { return true; }).catch(function () { return fallbackCopy(value); });
    }
    return Promise.resolve(fallbackCopy(value));
  }
  function fallbackCopy(value) {
    try {
      var ta = document.createElement('textarea');
      ta.value = value;
      ta.setAttribute('readonly', 'readonly');
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      ta.style.pointerEvents = 'none';
      document.body.appendChild(ta);
      ta.select();
      var ok = document.execCommand('copy');
      document.body.removeChild(ta);
      return ok;
    } catch (e) { return false; }
  }

  /** 通过元素属性写文本，避免误用 innerHTML。 */
  function setText(el, text) {
    if (el) el.textContent = text == null ? '' : String(text);
  }

  global.PortalUtil = {
    esc: esc,
    fmtInt: fmtInt,
    fmtBytes: fmtBytes,
    fmtDuration: fmtDuration,
    fmtHoursFromMs: fmtHoursFromMs,
    fmtBps: fmtBps,
    fmtTime: fmtTime,
    fmtDate: fmtDate,
    fmtDateTime: fmtDateTime,
    timeAgo: timeAgo,
    fmtCountdown: fmtCountdown,
    storage: { get: getItem, set: setItem, remove: removeItem, getJson: getJson, setJson: setJson },
    query: query,
    clamp: clamp,
    debounce: debounce,
    copyText: copyText,
    setText: setText,
    $: $,
    $$: $$
  };
})(window);
