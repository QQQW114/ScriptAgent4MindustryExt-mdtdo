/* ============================================================================
 * MDT DO 服务器门户 - API 客户端 / 会话 / 错误码人话化 / mock 开关
 * ----------------------------------------------------------------------------
 * 后端契约（stats-web/server）：
 *   成功 {ok:true,data:...}；失败 {ok:false,error:{code,message}}（HTTP 状态码同步反映）。
 * Auth：Authorization: Bearer <token>（token 存 sessionStorage，同一账号允许多 IP 登录）
 * mock：URL 带 ?mock=1 或 window.__MOCK__===true 时启用（持久化到 sessionStorage，
 *       ?mock=0 关闭），由 assets/mock/mock-data.js 提供假后端，不产生任何真实请求。
 * ==========================================================================*/
(function (global) {
  'use strict';

  var U = global.PortalUtil;
  var SITE = global.PORTAL_SITE || {};
  var MOCK_FLAG = 'mdt_portal_mock';
  var AUTH_KEY = 'mdt_portal_auth';
  var MOCK_SCRIPT = 'assets/mock/mock-data.js';

  /* ------------------------------- 错误类型 ------------------------------- */
  function ApiError(code, message, status, payload) {
    this.name = 'ApiError';
    this.code = code || 'UNKNOWN';
    this.message = message || '请求失败';
    this.status = status || 0;
    this.payload = payload || null;
  }
  ApiError.prototype = Object.create(Error.prototype);
  ApiError.prototype.constructor = ApiError;

  function defaultMessage(code) {
    switch (code) {
      case 'BAD_CREDENTIALS': return '用户名或密码错误';
      case 'UNAUTHORIZED': return '未登录或登录已失效';
      case 'SESSION_EXPIRED': return '登录已过期';
      case 'FORBIDDEN': return '没有权限';
      case 'QUEUE_FULL': return '待审队列已满';
      case 'FILE_TOO_LARGE': return '文件超过大小上限';
      case 'BAD_TYPE': return '文件类型不支持';
      case 'BAD_REQUEST': return '提交内容不完整或格式不正确';
      case 'NAME_CONFLICT': return '名称已存在';
      case 'RATE_LIMITED': return '操作太频繁';
      case 'BUSY': return '服务器繁忙（并发已满）';
      case 'READ_FAILED': return '读取文件失败';
      case 'NOT_FOUND': return '接口或资源不存在';
      case 'ALREADY_REVIEWED': return '该投稿已被处理';
      case 'PAYLOAD_MISSING': return '上传文件已丢失';
      case 'CONFIG_ERROR': return '后端目录配置错误';
      case 'TARGET_DIR_ERROR': return '服务器目标目录不可用';
      case 'MOVE_FAILED': return '文件写入服务器目录失败';
      case 'UPLOAD_SAVE_FAILED': return '后端保存上传文件失败';
      case 'DELETE_FAILED': return '删除文件失败';
      case 'SERVER_ERROR': return '服务器内部错误';
      case 'NETWORK_ERROR': return '无法连接服务器后端';
      default: return '请求失败';
    }
  }

  function codeFromStatus(status) {
    switch (status) {
      case 400: return 'BAD_REQUEST';
      case 401: return 'UNAUTHORIZED';
      case 403: return 'FORBIDDEN';
      case 404: return 'NOT_FOUND';
      case 409: return 'CONFLICT';
      case 410: return 'PAYLOAD_MISSING';
      case 413: return 'FILE_TOO_LARGE';
      case 429: return 'RATE_LIMITED';
      default: return status >= 500 ? 'SERVER_ERROR' : 'UNKNOWN';
    }
  }

  function envelopeError(status, parsed) {
    var code = codeFromStatus(status);
    var message = defaultMessage(code);
    if (parsed && parsed.error) {
      if (parsed.error.code) code = String(parsed.error.code);
      if (parsed.error.message) message = String(parsed.error.message);
      else if (code !== 'UNKNOWN') message = defaultMessage(code);
    }
    var err = new ApiError(code, message, status, parsed);
    if (parsed && parsed.error && parsed.error.retryAfter != null) {
      var ra = Number(parsed.error.retryAfter);
      if (isFinite(ra) && ra > 0) err.retryAfter = ra;
    }
    return err;
  }

  /**
   * 错误码 -> 人话（UI 统一入口）。
   * @returns {{code:string,title:string,hint:string,raw:string,status:number}}
   */
  function errorInfo(err) {
    var code = (err && err.code) || 'UNKNOWN';
    var info = { code: code, title: defaultMessage(code), hint: '', raw: (err && err.message) || '', status: (err && err.status) || 0 };
    switch (code) {
      case 'BAD_CREDENTIALS':
        info.title = '用户名或密码错误';
        info.hint = '管理员账号/密码在 stats-web/web-config.json 中配置，修改后需重启门户服务。';
        break;
      case 'UNAUTHORIZED':
      case 'SESSION_EXPIRED':
        info.title = code === 'SESSION_EXPIRED' ? '登录已过期' : '需要先登录';
        info.hint = '请重新登录管理员账号。同一账号支持多 IP 同时登录。';
        break;
      case 'QUEUE_FULL':
        info.title = '待审队列已满';
        info.hint = '每种类型的待审上限为 10 件，请等待管理员审核后再提交。';
        break;
      case 'FILE_TOO_LARGE':
        if (/日志/.test(info.raw)) {
          info.title = '日志文件过大，无法通过页面访问';
          info.hint = '后端日志单文件上限为 50MB（web-config.json 的 logs.maxFileBytes），请直接在服务器上查看。';
        } else {
          info.title = '文件超过 10MB 上限';
          info.hint = '请先裁剪/压缩文件，或拆分后分批提交。';
        }
        break;
      case 'BAD_TYPE':
        info.title = '文件类型不支持';
        info.hint = 'DP 支持 .zip / .json / .hjson / .json5；地图支持 .msav。';
        break;
      case 'BAD_REQUEST':
        info.title = '提交内容不完整或格式不正确';
        info.hint = '请检查标题、作者、介绍与文件是否已填写，标题不超过 200 字。';
        break;
      case 'NAME_CONFLICT':
        info.title = '名称已存在';
        info.hint = '请更换标题；如是对现有条目的更新，请选择“替换现有条目”。';
        break;
      case 'ALREADY_REVIEWED':
        info.title = '该投稿已被其他管理员处理';
        info.hint = '列表已自动刷新，请查看最新状态。';
        break;
      case 'PAYLOAD_MISSING':
        info.title = '上传文件已丢失，无法继续操作';
        info.hint = '可能是暂存文件被清理。请拒绝该投稿并让作者重新提交。';
        break;
      case 'CONFIG_ERROR':
      case 'TARGET_DIR_ERROR':
      case 'MOVE_FAILED':
      case 'UPLOAD_SAVE_FAILED':
      case 'DELETE_FAILED':
        info.title = defaultMessage(code);
        info.hint = '请检查后端 web-config.json 的目录配置与磁盘权限，并查看 stats-web/web-data/portal.log。';
        break;
      case 'RATE_LIMITED':
        info.title = '操作太频繁，请稍后再试';
        info.hint = '上传限制：10 分钟内最多 5 次；日志下载/预览每 IP 5 次/5 分钟；历史消息每分钟 60 次；登录失败过多也会被短暂限制。';
        if (err && err.retryAfter) info.hint += '（约 ' + err.retryAfter + ' 秒后可重试）';
        break;
      case 'BUSY':
        info.title = '服务器繁忙：日志下载并发已满';
        info.hint = '同时下载数已达上限（默认 2 个），请等待其他下载完成后再试。' +
          (err && err.retryAfter ? '（约 ' + err.retryAfter + ' 秒后重试）' : '');
        break;
      case 'READ_FAILED':
        info.title = '读取日志文件失败';
        info.hint = '日志文件可能正被游戏服写入或已被轮转，请刷新列表后重试。';
        break;
      case 'NOT_FOUND':
      case 'SERVER_ERROR':
        info.title = '服务器内部错误';
        info.hint = '请查看门户服务控制台输出，或 stats-web/web-data/portal.log。';
        break;
      case 'NETWORK_ERROR':
        info.title = '无法连接服务器门户后端';
        info.hint = mockEnabled()
          ? '当前为演示数据模式，理论上不会出现网络错误。'
          : '请确认 stats-web/start-web.ps1 正在运行，并检查页面地址与端口（默认 8090）。';
        break;
      default:
        info.title = info.raw || defaultMessage(code);
        info.hint = '';
    }
    return info;
  }

  /* ------------------------------- mock 开关 ------------------------------- */
  function mockEnabled() {
    var q = U.query();
    if (q.get('mock') === '0') { U.storage.remove(MOCK_FLAG); return false; }
    if (q.get('mock') === '1' || q.get('mock') === '') {
      U.storage.set(MOCK_FLAG, '1');
      return true;
    }
    if (global.__MOCK__ === true) return true;
    return U.storage.get(MOCK_FLAG) === '1';
  }

  var mockHandler = null;
  var mockLoading = null;

  function loadScriptOnce(src) {
    return new Promise(function (resolve, reject) {
      var existing = document.querySelector('script[data-portal-mock="1"]');
      if (existing && mockHandler) { resolve(); return; }
      var el = document.createElement('script');
      el.src = src;
      el.async = true;
      el.setAttribute('data-portal-mock', '1');
      el.onload = function () { resolve(); };
      el.onerror = function () { reject(new Error('演示数据脚本加载失败：' + src)); };
      document.head.appendChild(el);
    });
  }

  function ensureMock() {
    if (!mockEnabled()) return Promise.resolve(null);
    if (mockHandler) return Promise.resolve(mockHandler);
    if (mockLoading) return mockLoading;
    mockLoading = loadScriptOnce(MOCK_SCRIPT).then(function () {
      mockHandler = global.__PORTAL_MOCK_HANDLER__ || null;
      if (!mockHandler) throw new Error('演示数据处理器未注册');
      return mockHandler;
    });
    return mockLoading;
  }

  function mockRequest(method, path, query, body) {
    return ensureMock().then(function (handler) {
      return handler({ method: method, path: path, query: query || {}, body: body, token: Session.token() });
    }).then(function (res) {
      res = res || {};
      var parsed = res.body || {};
      if (res.status >= 200 && res.status < 300 && parsed.ok !== false) {
        return parsed.data !== undefined ? parsed.data : parsed;
      }
      throw envelopeError(res.status || 500, parsed);
    });
  }

  /* --------------------------------- 会话 --------------------------------- */
  var Session = {
    get: function () { return U.storage.getJson(AUTH_KEY) || null; },
    save: function (data) {
      U.storage.setJson(AUTH_KEY, {
        token: data.token || '',
        expiresAt: Number(data.expiresAt) || 0,
        username: data.username || ''
      });
    },
    token: function () { var s = Session.get(); return s && s.token ? s.token : null; },
    username: function () { var s = Session.get(); return s && s.username ? s.username : ''; },
    expiresAt: function () { var s = Session.get(); return s && s.expiresAt ? s.expiresAt : 0; },
    hasToken: function () { return !!Session.token(); },
    isExpired: function () {
      var s = Session.get();
      if (!s || !s.token) return true;
      if (s.expiresAt && s.expiresAt <= Date.now() + 5000) return true;
      return false;
    },
    clear: function () { U.storage.remove(AUTH_KEY); }
  };

  /* ------------------------------- HTTP 请求 ------------------------------- */
  function buildQuery(query) {
    if (!query) return '';
    var parts = [];
    Object.keys(query).forEach(function (key) {
      var value = query[key];
      if (value === undefined || value === null || value === '') return;
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
    });
    return parts.join('&');
  }

  function request(method, path, options) {
    options = options || {};
    var query = options.query || null;
    if (mockEnabled()) return mockRequest(method, path, query, options.body);

    var qs = buildQuery(query);
    var url = path + (qs ? '?' + qs : '');
    var headers = { 'Accept': 'application/json' };
    var body;
    if (options.form) {
      body = options.form; // multipart：交给浏览器设置 Content-Type/boundary
    } else if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json; charset=utf-8';
      body = JSON.stringify(options.body);
    }
    if (options.auth) {
      var token = Session.token();
      if (token) headers['Authorization'] = 'Bearer ' + token;
    }

    var controller = null;
    var timer = null;
    var timeoutMs = options.timeout || 15000;
    var fetchOpts = { method: method, headers: headers, body: body, cache: 'no-store', credentials: 'same-origin' };
    if (global.AbortController && timeoutMs > 0) {
      controller = new global.AbortController();
      fetchOpts.signal = controller.signal;
      timer = setTimeout(function () { try { controller.abort(); } catch (e) { /* ignore */ } }, timeoutMs);
    }

    return fetch(url, fetchOpts).then(function (res) {
      return res.text().then(function (text) {
        var parsed = null;
        if (text) { try { parsed = JSON.parse(text); } catch (e) { parsed = null; } }
        if (res.ok && parsed && parsed.ok !== false) {
          if (parsed && parsed.ok === true) return parsed.data !== undefined ? parsed.data : null;
          return parsed; // 宽容：老接口直接返回裸 JSON
        }
        var err = envelopeError(res.status, parsed);
        try {
          var retryAfter = parseInt(res.headers.get('Retry-After'), 10) || 0;
          if (retryAfter > 0 && !err.retryAfter) err.retryAfter = retryAfter;
        } catch (e3) { /* ignore */ }
        if (res.status === 401 && options.auth) {
          if (err.code === 'SESSION_EXPIRED') Session.clear();
          try { global.dispatchEvent(new CustomEvent('portal:unauthorized', { detail: { code: err.code } })); } catch (e2) { /* ignore */ }
        }
        throw err;
      });
    }).catch(function (err) {
      if (err instanceof ApiError) throw err;
      if (err && err.name === 'AbortError') {
        throw new ApiError('NETWORK_ERROR', '请求超时（' + Math.round(timeoutMs / 1000) + ' 秒）', 0, null);
      }
      throw new ApiError('NETWORK_ERROR', '网络请求失败：' + (err && err.message ? err.message : '未知错误'), 0, null);
    }).then(function (value) {
      if (timer) clearTimeout(timer);
      return value;
    }, function (err) {
      if (timer) clearTimeout(timer);
      throw err;
    });
  }

  /* --------------------------- 状态数据规范化 --------------------------- */
  function numOrNull(value) {
    if (value === null || value === undefined || value === '') return null;
    var n = Number(value);
    return isFinite(n) ? n : null;
  }

  /** 事件对象规范化（/api/events 与 /api/events/history 共用）。 */
  function normalizeEvent(ev) {
    if (!ev || typeof ev !== 'object') return null;
    return {
      ts: numOrNull(ev.ts) || 0,
      type: String(ev.type || 'system'),
      text: ev.text == null ? '' : String(ev.text),
      player: ev.player == null ? '' : String(ev.player),
      playerId: ev.playerId == null ? '' : String(ev.playerId),
      seq: ev.seq
    };
  }

  /** 宿主上行带宽快照规范化（注意 bps 实际单位是字节/秒）。 */
  function normalizeNet(raw) {
    if (!raw || typeof raw !== 'object') {
      return { available: false, avgUpBps: null, windowMinutes: null, intervalSeconds: null, sampledAt: null, sampledAtText: '', interface: '', series: [], note: '', error: '' };
    }
    var series = (Array.isArray(raw.series) ? raw.series : []).map(function (p) {
      if (!p || typeof p !== 'object') return null;
      var ts = numOrNull(p.ts);
      var bps = numOrNull(p.bps);
      if (!ts || bps == null) return null;
      return { ts: ts, bps: bps };
    }).filter(Boolean);
    // 后端最终契约：bps 实际单位是字节/秒；同时提供 unit="bytes_per_second" 与 avgUpBytesPerSec 别名
    var unit = raw.unit ? String(raw.unit) : 'bytes_per_second';
    var avg = numOrNull(raw.avgUpBytesPerSec);
    if (avg == null) avg = numOrNull(raw.avgUpBps);
    return {
      available: raw.available === true,
      unit: unit,
      avgUpBps: avg,
      windowMinutes: numOrNull(raw.windowMinutes),
      intervalSeconds: numOrNull(raw.intervalSeconds),
      sampledAt: numOrNull(raw.sampledAt),
      sampledAtText: raw.sampledAtText ? String(raw.sampledAtText) : '',
      interface: raw.interface ? String(raw.interface) : '',
      series: series,
      note: raw.note ? String(raw.note) : '',
      error: raw.error ? String(raw.error) : ''
    };
  }

  /** 把 /api/status 的 data（或旧 server-status.json）规范化为前端统一结构。 */
  function normalizeStatus(raw) {
    if (!raw || typeof raw !== 'object') return { available: false };
    if (raw.available === false) {
      return { available: false, enabled: raw.enabled !== false, updatedAt: raw.updatedAt || '', stats: raw.stats || null };
    }
    var isApi = !!(raw.stats && typeof raw.stats === 'object');
    var stats = isApi ? raw.stats : raw;
    var info = stats.serverInfo || {};
    return {
      available: true,
      source: isApi ? 'api' : 'legacy',
      enabled: raw.enabled !== undefined ? raw.enabled !== false : stats.enabled !== false,
      updatedAt: raw.updatedAt || stats.updatedAt || '',
      serverName: raw.serverName || stats.serverName || '',
      motd: raw.motd || stats.motd || '',
      port: numOrNull(raw.port),
      online: numOrNull(isApi && raw.online !== undefined ? raw.online : info.onlinePlayers),
      maxPlayers: numOrNull(isApi ? raw.maxPlayers : info.playerLimit),
      map: raw.map || info.map || '',
      mode: raw.mode || info.mode || '',
      wave: numOrNull(isApi && raw.wave !== undefined ? raw.wave : info.wave),
      tps: numOrNull(isApi && raw.tps !== undefined ? raw.tps : info.tps),
      uptimeSeconds: numOrNull(isApi && raw.uptimeSeconds !== undefined ? raw.uptimeSeconds : info.uptimeSeconds),
      onlineByRank: (isApi ? raw.onlineByRank : info.onlineByRank) || null,
      net: normalizeNet(isApi ? raw.net : (raw.net || null)),
      stats: stats
    };
  }

  /** 旧服务兜底：直接读 /server-status.json（新门户也通常保留该路由）。 */
  function legacyStatus() {
    return fetch('server-status.json?t=' + Date.now(), { cache: 'no-store' }).then(function (res) {
      if (!res.ok) throw new ApiError('NOT_FOUND', '旧版状态文件不可用（HTTP ' + res.status + '）', res.status, null);
      return res.json().then(function (raw) { return normalizeStatus(raw); });
    }, function () {
      throw new ApiError('NETWORK_ERROR', '状态接口与旧版 JSON 均不可用', 0, null);
    });
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function nowText() {
    var d = new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
      pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  /* --------------------------------- API --------------------------------- */
  var Api = {
    ready: function () { return ensureMock(); },
    isMock: mockEnabled,
    request: request,

    /** 服务器状态：优先新 API，失败回退旧 /server-status.json。 */
    status: function () {
      return request('GET', '/api/status', { timeout: 10000 }).then(function (data) {
        return normalizeStatus(data);
      }).catch(function (err) {
        if (err.code === 'NOT_FOUND' || err.code === 'NETWORK_ERROR' || err.code === 'UNAUTHORIZED' || err.code === 'FORBIDDEN') {
          return legacyStatus();
        }
        throw err;
      });
    },

    /** 事件流。afterTs>0 时增量拉取；不传时后端返回最近 limit 条。 */
    events: function (afterTs, limit) {
      var query = { limit: limit || 100 };
      if (afterTs && afterTs > 0) query.afterTs = afterTs;
      return request('GET', '/api/events', { query: query, timeout: 10000 }).then(function (data) {
        var list = (data && Array.isArray(data.events)) ? data.events : [];
        var events = list.map(normalizeEvent).filter(function (ev) { return ev && ev.ts > 0; });
        return {
          events: events,
          lastTs: numOrNull(data && data.lastTs) || (events.length ? Math.max.apply(null, events.map(function (e) { return e.ts; })) : 0),
          available: !(data && data.available === false)
        };
      });
    },

    /**
     * 历史事件（跨当前 + 旋转历史文件，最新在前，cursor 分页）。
     * @param {object} options {fromTs,toTs,type,keyword,limit,cursor}
     */
    eventsHistory: function (options) {
      options = options || {};
      var query = {
        limit: options.limit || 100,
        cursor: options.cursor || 0,
        fromTs: options.fromTs || 0,
        toTs: options.toTs || 0,
        type: options.type || '',
        keyword: options.keyword || ''
      };
      return request('GET', '/api/events/history', { query: query, timeout: 20000 }).then(function (data) {
        var d = data || {};
        var items = (Array.isArray(d.items) ? d.items : []).map(normalizeEvent)
          .filter(function (ev) { return ev && ev.ts > 0; })
          .sort(function (a, b) { return b.ts - a.ts; }); // 后端已倒序，这里再保证一次
        return {
          available: d.available !== false,
          items: items,
          hasMore: d.hasMore === true,
          nextCursor: numOrNull(d.nextCursor) || 0,
          limit: numOrNull(d.limit) || query.limit,
          scannedFiles: numOrNull(d.scannedFiles),
          scannedLines: numOrNull(d.scannedLines)
        };
      });
    },

    /** 服务器日志文件列表（默认需管理员登录）。 */
    logs: function (limit) {
      return request('GET', '/api/logs', { query: { limit: limit || 100 }, auth: true, timeout: 12000 }).then(function (data) {
        var d = data || {};
        var rawItems = Array.isArray(d.items) ? d.items : (Array.isArray(d.files) ? d.files : []);
        var items = rawItems.map(function (it) {
          return {
            name: it && it.name != null ? String(it.name) : '',
            size: numOrNull(it && it.size),
            modifiedAt: it && it.modifiedAt != null ? String(it.modifiedAt) : '',
            kind: it && it.kind != null ? String(it.kind) : 'other'
          };
        }).filter(function (it) { return !!it.name; });
        return {
          available: d.available !== false,
          dir: d.dir ? String(d.dir) : '',
          requireLogin: d.requireLogin !== false,
          maxFileBytes: numOrNull(d.maxFileBytes) || 50 * 1024 * 1024,
          items: items,
          count: numOrNull(d.count) || items.length
        };
      });
    },

    /** 日志尾部预览（纯文本行数组，默认 200 行）。 */
    logsTail: function (name, lines) {
      return request('GET', '/api/logs/tail', {
        query: { name: name, lines: lines || 200 }, auth: true, timeout: 20000
      }).then(function (data) {
        var d = data || {};
        return {
          name: d.name ? String(d.name) : String(name || ''),
          kind: d.kind ? String(d.kind) : '',
          size: numOrNull(d.size),
          lines: (Array.isArray(d.lines) ? d.lines : []).map(function (x) { return x == null ? '' : String(x); }),
          truncated: d.truncated === true
        };
      });
    },

    /**
     * 日志**直链下载 URL**（携带会话 token 查询参数，不把文件读进页面内存）。
     * 需要后端支持 ?token= 查询参数鉴权；否则请用 logsDownloadBlob（小文件兼容模式）。
     */
    logsDownloadUrl: function (name) {
      var token = Session.token() || '';
      return '/api/logs/download?name=' + encodeURIComponent(String(name || '')) +
        (token ? '&token=' + encodeURIComponent(token) : '');
    },

    /**
     * 兼容下载：Authorization 头鉴权 fetch → Blob。
     * 仅建议用于小文件（大文件会占用浏览器内存）；mock 模式返回假内容。
     * @returns {Promise<{blob:Blob, fileName:string}>}
     */
    logsDownloadBlob: function (name) {
      if (mockEnabled()) {
        return mockRequest('GET', '/api/logs/download', { name: name }, null).then(function (data) {
          var d = data || {};
          var text = d.content != null ? String(d.content) : '';
          return { blob: new Blob([text], { type: 'text/plain;charset=utf-8' }), fileName: d.fileName || String(name || 'log.txt') };
        });
      }
      var token = Session.token();
      var headers = { 'Accept': '*/*' };
      if (token) headers['Authorization'] = 'Bearer ' + token;
      return fetch('/api/logs/download?name=' + encodeURIComponent(String(name || '')), {
        headers: headers, cache: 'no-store'
      }).then(function (res) {
        if (!res.ok) {
          return res.text().then(function (text) {
            var parsed = null;
            try { parsed = JSON.parse(text); } catch (e) { parsed = null; }
            var err = envelopeError(res.status, parsed);
            if (res.status === 401) {
              try { global.dispatchEvent(new CustomEvent('portal:unauthorized', { detail: { code: err.code } })); } catch (e2) { /* ignore */ }
            }
            throw err;
          });
        }
        return res.blob().then(function (blob) { return { blob: blob, fileName: String(name || 'log.txt') }; });
      }, function () {
        throw new ApiError('NETWORK_ERROR', '下载日志时网络中断', 0, null);
      });
    },

    /** DP / 地图目录。 */
    catalog: function () {
      return request('GET', '/api/catalog', { timeout: 12000 }).then(function (data) {
        var d = data || {};
        function normalizeItems(items) {
          return (Array.isArray(items) ? items : []).map(function (it) {
            return {
              name: it && it.name != null ? String(it.name) : '',
              file: it && it.file != null ? String(it.file) : '',
              size: numOrNull(it && it.size),
              author: it && it.author != null ? String(it.author) : '',
              desc: it && it.desc != null ? String(it.desc) : '',
              updatedAt: it && it.updatedAt != null ? String(it.updatedAt) : ''
            };
          });
        }
        return { dp: normalizeItems(d.dp), maps: normalizeItems(d.maps) };
      });
    },

    /**
     * 公开的待审队列信息。
     * 后端公开接口为 GET /api/health（含 pendingDp/pendingMap/queueLimit）；
     * 若后端版本较旧没有这些字段，则返回 null（上传页降级为只显示上限）。
     */
    queueInfo: function () {
      return request('GET', '/api/health', { timeout: 8000 }).then(function (d) {
        if (!d || (d.pendingDp == null && d.pendingMap == null)) return null;
        var limit = Number(d.queueLimit) || 10;
        var byKind = { dp: Number(d.pendingDp) || 0, map: Number(d.pendingMap) || 0 };
        return { limit: limit, pending: byKind.dp + byKind.map, byKind: byKind, source: 'health' };
      }).catch(function () { return null; });
    },

    /**
     * 运维健康信息（管理后台用）：重点是游戏控制台 socketInput 可达性。
     * 后端 /api/health 新字段：console={enabled,reachable,host,port,lastError}。
     */
    health: function () {
      return request('GET', '/api/health', { timeout: 8000 }).then(function (d) {
        d = d || {};
        var consoleInfo = (d.console && typeof d.console === 'object') ? d.console : null;
        return {
          serverTime: d.serverTime ? String(d.serverTime) : '',
          eventsAvailable: d.eventsAvailable === true,
          statsAvailable: d.statsAvailable === true,
          console: consoleInfo ? {
            enabled: consoleInfo.enabled !== false,
            reachable: consoleInfo.reachable === true,
            host: consoleInfo.host ? String(consoleInfo.host) : '',
            port: numOrNull(consoleInfo.port),
            lastError: consoleInfo.lastError ? String(consoleInfo.lastError) : ''
          } : null,
          raw: d
        };
      });
    },

    login: function (username, password) {
      return request('POST', '/api/login', { body: { username: username, password: password }, timeout: 15000 }).then(function (data) {
        if (!data || !data.token) throw new ApiError('BAD_CREDENTIALS', '登录响应缺少 token', 401, data);
        return data;
      });
    },

    logout: function () {
      return request('POST', '/api/logout', { auth: true, timeout: 10000 });
    },

    reviewList: function (kind) {
      return request('GET', '/api/review/list', { query: { kind: kind }, auth: true, timeout: 12000 }).then(function (data) {
        var d = data || {};
        return {
          items: Array.isArray(d.items) ? d.items : [],
          history: Array.isArray(d.history) ? d.history : [],
          queue: d.queue || null
        };
      });
    },

    approve: function (id, reload) {
      var body = { id: id };
      if (reload) body.reload = true;
      return request('POST', '/api/review/approve', { body: body, auth: true, timeout: 60000 });
    },

    reject: function (id, reason) {
      return request('POST', '/api/review/reject', { body: { id: id, reason: reason }, auth: true, timeout: 20000 });
    },

    removeItem: function (kind, name, unload) {
      var body = { kind: kind, name: name };
      if (unload) body.unload = true;
      return request('DELETE', '/api/item', { body: body, auth: true, timeout: 60000 });
    },

    /**
     * multipart 上传（公开，无需登录）。使用 XHR 以获得上传进度。
     * @param {FormData} formData
     * @param {(ratio:number)=>void} onProgress 0..1
     */
    upload: function (formData, onProgress) {
      if (mockEnabled()) {
        return ensureMock().then(function () {
          return new Promise(function (resolve, reject) {
            var steps = [0.12, 0.3, 0.55, 0.78, 0.93];
            var idx = 0;
            var timer = setInterval(function () {
              if (idx < steps.length) {
                if (onProgress) onProgress(steps[idx]);
                idx++;
                return;
              }
              clearInterval(timer);
              mockRequest('POST', '/api/upload', null, formData).then(function (data) {
                if (onProgress) onProgress(1);
                resolve(data);
              }, reject);
            }, 180);
          });
        });
      }
      return new Promise(function (resolve, reject) {
        var xhr = new XMLHttpRequest();
        xhr.open('POST', '/api/upload', true);
        xhr.responseType = 'text';
        xhr.timeout = 120000;
        xhr.upload.onprogress = function (e) {
          if (e.lengthComputable && e.total > 0 && onProgress) onProgress(e.loaded / e.total);
        };
        xhr.onload = function () {
          var parsed = null;
          try { parsed = xhr.responseText ? JSON.parse(xhr.responseText) : null; } catch (e) { parsed = null; }
          if (xhr.status >= 200 && xhr.status < 300 && parsed && parsed.ok !== false) {
            resolve(parsed && parsed.ok === true ? (parsed.data !== undefined ? parsed.data : null) : parsed);
            return;
          }
          reject(envelopeError(xhr.status, parsed));
        };
        xhr.onerror = function () { reject(new ApiError('NETWORK_ERROR', '上传时网络中断', 0, null)); };
        xhr.ontimeout = function () { reject(new ApiError('NETWORK_ERROR', '上传超时（120 秒）', 0, null)); };
        xhr.send(formData);
      });
    },

    Session: Session,
    ApiError: ApiError,
    errorInfo: errorInfo,
    normalizeStatus: normalizeStatus,
    mockEnabled: mockEnabled,
    site: SITE
  };

  global.PortalApi = Api;
})(window);
