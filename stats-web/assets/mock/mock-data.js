/* ============================================================================
 * MDT DO 服务器门户 - 演示数据（仅 ?mock=1 / window.__MOCK__ 时由 api.js 动态加载）
 * ----------------------------------------------------------------------------
 * 提供与真实后端一致的 {ok,data}/{ok,error} JSON 契约，状态保存在 sessionStorage，
 * 因此「首页 → 上传页 → 管理页」在 mock 模式下可以走完整流程（提交 → 审核 → 入库）。
 * 仅在浏览器执行，不进行任何网络请求。
 * ==========================================================================*/
(function (global) {
  'use strict';

  var STATE_KEY = 'mdt_portal_mock_state_v1';
  var now = function () { return Date.now(); };

  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function fmtDate(ts) { var d = new Date(ts); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  function fmtDateTime(ts) { var d = new Date(ts); return fmtDate(ts) + ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds()); }

  function seeded(seed) {
    return function () {
      seed = (seed * 1103515245 + 12345) & 0x7fffffff;
      return seed / 0x7fffffff;
    };
  }

  function defaultCatalog() {
    var T = now();
    function entry(name, file, size, author, desc, updatedAt) {
      return { name: name, file: file, size: size, author: author || null, desc: desc || null, updatedAt: updatedAt || fmtDateTime(T - 86400000 * 20) };
    }
    return {
      dp: [
        entry('赛克兰特高阶科技', '赛克兰特高阶科技.zip', 440793, '玩家 赛克兰特', '高阶科技 DP：新增 33 种内容与 196 张贴图。', '2026-10-02 18:20:11'),
        entry('更多弹药', '更多弹药.zip', 897691, '玩家 弹药库', '扩充弹药类型，含多级弹药生产线。', '2026-09-18 21:04:37'),
        entry('更多墙体（地雷）', '更多墙体（地雷）.zip', 52734, null, null, '2026-09-11 12:40:02'),
        entry('惊鸿3', '惊鸿3.zip', 66790, null, null, '2026-09-04 09:12:45'),
        entry('仙古2.9.2', '仙古2.9.2.zip', 470836, null, null, '2026-08-29 16:33:19'),
        entry('亚龙组合包 (1)', '亚龙组合包 (1).zip', 456748, null, null, '2026-08-21 11:08:55'),
        entry('饱和火力3.1.6.4棍母dp(不含单位)', '饱和火力3.1.6.4棍母dp(不含单位).zip', 2288438, null, null, '2026-08-15 19:27:41'),
        entry('饱和火力4.0.5dp版无平衡与稳定性保障', '饱和火力4.0.5dp版无平衡与稳定性保障.zip', 6818863, null, null, '2026-08-12 22:15:08'),
        entry('更多反应堆（核弹）', '更多反应堆（核弹）.zip', 254659, null, null, '2026-08-09 14:52:30'),
        entry('区块生存CP', '区块生存CP.hjson', 7353250, null, null, '2026-08-03 08:41:12'),
        entry('奇妙双科cp', '奇妙双科cp.json', 743260, null, null, '2026-07-30 17:06:49'),
        entry('生锈的铜的cp预览版', '生锈的铜的cp预览版.json', 11037, null, null, '2026-07-25 10:19:26'),
        entry('高炮放平', '高炮放平.json', 558, null, null, '2026-07-21 23:02:07'),
        entry('锈铜墙', '锈铜墙.json', 581, null, null, '2026-07-21 23:01:55')
      ],
      maps: [
        entry('S9100', 'S9100.msav', 12066, '服务器存档', '服务器当前存档地图（8 人合作）。', '2026-10-16 20:11:00')
      ]
    };
  }

  function defaultState() {
    var t = now();
    function itemTime(ts) { return { createdAt: ts, createdAtText: fmtDateTime(ts) }; }
    var pending = [
      {
        id: '1001', kind: 'dp', title: '赛克兰特高阶科技 v2 更新', author: '玩家 赛克兰特',
        desc: '更新内容：新增 2 个反应堆与 5 种墙体，修复上一版贴图丢失问题。替换现有「赛克兰特高阶科技」。',
        replaceOf: '赛克兰特高阶科技.zip', fileName: '赛克兰特高阶科技-v2.zip', size: 468213,
        status: 'pending'
      },
      {
        id: '1002', kind: 'map', title: '四路矿图 · 极难版', author: '玩家 星野',
        desc: '8 人合作地图，四条矿路对称，资源紧张，适合老手开荒。',
        replaceOf: '', fileName: 'S9200.msav', size: 983040,
        status: 'pending'
      }
    ];
    pending[0] = Object.assign(pending[0], itemTime(t - 3600000));
    pending[1] = Object.assign(pending[1], itemTime(t - 7200000));
    var history = [
      {
        id: '1000', kind: 'dp', title: '更多弹药 v1.2', author: '玩家 弹药库', replaceOf: '',
        fileName: '更多弹药.zip', size: 897691, status: 'approved',
        createdAt: t - 172800000, createdAtText: fmtDateTime(t - 172800000),
        reviewedAt: t - 169200000, reviewedAtText: fmtDateTime(t - 169200000),
        rejectReason: '', finalName: '更多弹药.zip'
      },
      {
        id: '999', kind: 'map', title: '测试地图', author: '匿名', replaceOf: '',
        fileName: 'test.msav', size: 10240, status: 'rejected',
        createdAt: t - 259200000, createdAtText: fmtDateTime(t - 259200000),
        reviewedAt: t - 252000000, reviewedAtText: fmtDateTime(t - 252000000),
        rejectReason: '地图无法在服务器正常加载（缺少沙盒兼容设置）。', finalName: ''
      }
    ];
    return {
      catalog: defaultCatalog(),
      pending: pending,
      history: history,
      nextId: 1003,
      token: '',
      tokenExpiresAt: 0,
      uploadTimes: [],
      logsFailMode: '',
      consoleReachable: false,
      statusOnline: 3
    };
  }

  function loadState() {
    try {
      var raw = global.sessionStorage.getItem(STATE_KEY);
      if (raw) {
        var parsed = JSON.parse(raw);
        if (parsed && parsed.catalog && Array.isArray(parsed.pending)) return parsed;
      }
    } catch (e) { /* ignore */ }
    var state = defaultState();
    saveState(state);
    return state;
  }
  function saveState(state) {
    try { global.sessionStorage.setItem(STATE_KEY, JSON.stringify(state)); } catch (e) { /* ignore */ }
  }
  function resetState() {
    var state = defaultState();
    saveState(state);
    return state;
  }

  var state = null;
  function S() { if (!state) state = loadState(); return state; }

  /* --------------------------------- 事件流 --------------------------------- */
  var eventState = { builtFor: 0, events: [], nextTs: 0, liveIndex: 0, seq: 0 };

  // 第二期：长玩家名用例。第二个模拟上游 liveEventFeed.kts 默认 maxPlayerChars=64 截断后的真实形态
  // （真实 JSON 里见过 `...[white][whi` 这种被切一半的颜色标记）。
  var LONG_PLAYER_VISIBLE = '[pink][活跃玩家][][white][pink]猫娘爱玩Mindustry超长名字测试员QQQWWEEERRRTTT[]';
  var LONG_PLAYER_TRUNCATED = '[pink][活跃玩家][][white][pink]猫娘爱玩Mindustry超长名字测试员QQQWWEEERRRTTTYYYUUUIIIOOO' + '[whi';

  function ev(ts, type, player, text, playerId) {
    eventState.seq += 1;
    return { seq: eventState.seq, ts: ts, type: type, text: text, player: player || '', playerId: playerId || '' };
  }

  function buildBacklog() {
    var t = now();
    var list = [];
    var minutes = [78, 74, 70, 66, 61, 57, 52, 48, 44, 40, 36, 31, 27, 24, 21, 18, 15, 12, 10, 8, 6, 4, 3, 2, 1];
    var rows = [
      ['system', '', '[accent]服务器公告[]：每周五晚 8 点开荒新图，欢迎带上你的 DP 来投稿。'],
      ['chat', '服主', '[gold]欢迎来到 MDT DO！[]有问题可以在群里问，也可以双击玩家打开信息面板。'],
      ['chat', '小萌新', '[green]萌新求带，硅厂到底怎么拍啊[]'],
      ['chat', '老玩家', '[blue]先去左边打掉坐标，再拍硅厂，别忘了供电[]'],
      ['chat', '安全测试', '[red]<img src=x onerror=alert(1)> XSS 用例：这段应显示为纯文本[]'],
      ['chat', '安全测试', '<script>alert("xss")</script> 这个也必须显示为纯文本'],
      ['chat', '<b>恶意名称测试</b>', '玩家名里的 HTML 标签也必须按文本显示'],
      ['chat', '颜色测试', '[unknownTag]未知标记按文本显示[]；[#ff8800]橙色十六进制[]；[#00ff00aa]半透明绿色[]；[[red] 双左括号转义'],
      ['join', '路过的萌新', '[green]加入了服务器[]'],
      ['chat', '路过的萌新', '这个服务器好热闹啊[accent]！[]'],
      ['chat', '老玩家', '[pink]有人一起打第 40 波吗？[]'],
      ['leave', '路过的萌新', ''],
      ['join', '建筑工头', '[green]加入了服务器[]'],
      ['chat', '建筑工头', '我来铺一下传送带，别拆我的路[orange]谢谢[]'],
      ['vote_start', '老玩家', '发起投票：将玩家「熊孩子」踢出服务器？同意率需达到 70%'],
      ['chat', '熊孩子', '我什么都没干啊[salmon]…[]'],
      ['vote_fail', '', '投票失败：同意率 42% < 70%（熊孩子 留在服务器）'],
      ['chat', '建筑工头', '[cyan]地图加载中，请稍候…[]'],
      ['map_change', '', '地图切换：赛克兰特 → 四路矿图（生存模式）'],
      ['chat', '老玩家', '[red]未闭合的颜色标记（后面文字会一直是红色）'],
      ['system', '', '[yellow]提醒[]：资源区请勿堵路，影响他人游戏体验。'],
      ['chat', '小萌新', '[green]谢谢大佬！[]'],
      ['join', '赛克兰特', '[green]加入了服务器[]'],
      ['chat', '赛克兰特', '[stat]测试一下 stat 颜色[] 和 [negstat]negstat 颜色[]'],
      ['vote_start', '', '发起投票：将地图切换为「四路矿图 极难版」？'],
      ['vote_pass', '', '投票通过：地图即将切换为「四路矿图 极难版」'],
      ['chat', '建筑工头', '收到，准备换图了[accent]！[]'],
      // ---- 第二期：首屏即包含长玩家名用例（含正常长名 + 模拟上游 64 字符截断的残缺标记）----
      ['join', LONG_PLAYER_VISIBLE, '[green]加入了服务器[]'],
      ['chat', LONG_PLAYER_VISIBLE, '[gold]40+ 字符长玩家名：Web 端应完整显示（可换行），悬停/点击可看完整名。[]'],
      ['chat', LONG_PLAYER_TRUNCATED, '[yellow]模拟上游 64 字符截断（末尾残缺 [whi）：Web 端应自动收尾且可查看原始记录。[]']
    ];
    // 分钟偏移不足时自动补齐（保证所有 rows 都会出现在首屏，含第二期长玩家名用例）
    for (var i = 0; i < rows.length; i++) {
      var row = rows[i];
      var minutesAgo = minutes[i] != null ? minutes[i] : (i + 1);
      list.push(ev(t - minutesAgo * 60000 - 137, row[0], row[1], row[2]));
    }
    return list;
  }

  function ensureEventState() {
    if (!eventState.events.length) {
      eventState.events = buildBacklog();
      eventState.nextTs = now();
      eventState.builtFor = now();
    }
  }

  var LIVE = [
    ['chat', '小萌新', '[green]又卡了，TPS 好像掉了[]'],
    ['chat', '老玩家', '[blue]把传送带优化一下，别堆一起[]'],
    ['chat', '建筑工头', '谁能借我 200 铜[orange]…[]'],
    ['chat', '赛克兰特', '[#ffd37f]今日目标：第 50 波！[]'],
    ['chat', '安全测试', '<script>alert("xss")</script> 这个也必须是纯文本'],
    ['chat', '颜色测试', '[pink]粉色[] [violet]紫罗兰[] [coral]珊瑚[] [goldenrod]金菊[]'],
    ['join', '路过的萌新', '[green]加入了服务器[]'],
    ['leave', '路过的萌新', ''],
    ['chat', '小萌新', '[yellow]大佬们，钍在哪挖？[]'],
    ['chat', '老玩家', '[green]右下角矿脉，先清怪再挖[]'],
    ['vote_start', '老玩家', '发起投票：将玩家「捣乱者」踢出服务器？'],
    ['vote_pass', '', '投票通过：捣乱者 已被踢出服务器'],
    ['vote_fail', '', '投票失败：同意率不足，投票未通过'],
    ['map_change', '', '地图切换：赛克兰特 → 区块生存（生存模式）'],
    ['system', '', '[accent]系统提示[]：服务器将在 10 分钟后自动保存。'],
    ['chat', '建筑工头', '[salmon]这波稳了[]'],
    ['chat', '赛克兰特', '我来带节奏[accent]！[]'],
    ['chat', '小萌新', '[lightgray]第一次进服务器，多多关照[]'],
    // ---- 第二期：长玩家名用例（验证 Web 端不二次截断 / 残缺标记收尾 / 完整名查看）----
    ['join', LONG_PLAYER_VISIBLE, '[green]加入了服务器[]'],
    ['chat', LONG_PLAYER_VISIBLE, '[gold]这是 40+ 字符的长玩家名，Web 端应完整显示（可换行），点击或悬停可看完整名。[]'],
    ['chat', LONG_PLAYER_TRUNCATED, '[yellow]这个名字模拟上游 64 字符截断（末尾残缺颜色标记），Web 端应自动收尾且可看原始记录。[]']
  ];

  function appendLiveEvents(count) {
    ensureEventState();
    var t = now();
    for (var i = 0; i < count; i++) {
      var row = LIVE[eventState.liveIndex % LIVE.length];
      eventState.liveIndex++;
      var ts = Math.max(t + i, eventState.nextTs + 1);
      eventState.nextTs = ts;
      eventState.events.push(ev(ts, row[0], row[1], row[2]));
    }
    if (eventState.events.length > 400) eventState.events = eventState.events.slice(-400);
  }

  /* ---------------- 第二期：上行带宽（mock，单位 B/s，与真实后端一致） ---------------- */
  var netState = { series: [], lastTs: 0, available: true };

  function netSnapshot() {
    var t = now();
    if (!netState.series.length) {
      var rand = seeded(20261018);
      var base = 1.6 * 1024 * 1024; // 约 1.6 MB/s
      for (var i = 29; i >= 0; i--) {
        netState.series.push({ ts: t - i * 10000, bps: Math.round(base * (0.55 + rand() * 0.9)) });
      }
      netState.lastTs = t;
    } else {
      while (t - netState.lastTs >= 8000) {
        netState.lastTs += 10000;
        var last = netState.series[netState.series.length - 1].bps;
        var next = last * (0.75 + Math.random() * 0.5);
        next = Math.max(200 * 1024, Math.min(8 * 1024 * 1024, next));
        netState.series.push({ ts: netState.lastTs, bps: Math.round(next) });
        if (netState.series.length > 30) netState.series.shift();
      }
    }
    var sum = 0;
    netState.series.forEach(function (s) { sum += s.bps; });
    var lastSample = netState.series[netState.series.length - 1];
    return {
      available: netState.available !== false,
      avgUpBps: netState.series.length ? Math.round((sum / netState.series.length) * 10) / 10 : null,
      windowMinutes: 5,
      intervalSeconds: 10,
      sampledAt: lastSample ? lastSample.ts : null,
      sampledAtText: lastSample ? fmtDateTime(lastSample.ts) : null,
      interface: '以太网（mock）',
      series: netState.series.slice(-30),
      error: netState.available === false ? 'disabled (mock)' : null,
      note: '宿主机网卡上行总量（含游戏服/Web/系统流量），不等于纯游戏流量；游戏服与 Web 不同机时无意义'
    };
  }

  /* -------------------- 第二期：历史事件（mock 归档 + 倒序分页） -------------------- */
  var historyArchive = null;

  function buildHistoryArchive() {
    var t = now();
    var rand = seeded(20261018);
    var types = ['chat', 'chat', 'chat', 'join', 'leave', 'vote_start', 'vote_pass', 'system', 'map_change'];
    var players = ['小萌新', '老玩家', '建筑工头', '赛克兰特', '路过的萌新', '安全测试', LONG_PLAYER_VISIBLE, LONG_PLAYER_TRUNCATED];
    var textPool = {
      chat: ['[green]萌新求带[]', '[blue]先去打坐标，然后再拍硅厂[]', '[pink]有人一起打第 40 波吗？[]', '[gold]这波稳了[]', '[orange]缺铜，谁有多余的？[]', '[accent]今晚 8 点开荒新图！[]', '[red]别堵路啊[]'],
      join: ['[green]加入了服务器[]'],
      leave: ['离开了服务器'],
      vote_start: ['发起投票：将玩家「捣乱者」踢出服务器？'],
      vote_pass: ['投票通过：捣乱者 已被踢出服务器'],
      vote_fail: ['投票失败：同意率不足，未通过'],
      system: ['[accent]系统提示[]：服务器将在 10 分钟后自动保存。', '[gray]实时事件流已启动[]'],
      map_change: ['地图切换：赛克兰特 → 区块生存（生存模式）']
    };
    var list = [];
    for (var i = 0; i < 320; i++) {
      var type = types[Math.floor(rand() * types.length)];
      var hasPlayer = type === 'chat' || type === 'join' || type === 'leave' || type === 'vote_start';
      var pool = textPool[type] || ['[gray]系统消息[]'];
      list.push({
        seq: i + 1,
        ts: t - Math.floor(rand() * 3 * 86400000) - 1000,
        type: type,
        text: pool[Math.floor(rand() * pool.length)],
        player: hasPlayer ? players[Math.floor(rand() * players.length)] : '',
        playerId: ''
      });
    }
    list.sort(function (a, b) { return b.ts - a.ts; });
    return list;
  }

  function allHistoryEvents() {
    if (!historyArchive) historyArchive = buildHistoryArchive();
    ensureEventState();
    return historyArchive.concat(eventState.events);
  }

  function handleEventsHistory(req) {
    var q = req.query || {};
    var fromTs = Number(q.fromTs) || 0;
    var toTs = Number(q.toTs) || 0;
    var cursor = Number(q.cursor) || 0;
    var limit = Math.min(200, Math.max(1, Number(q.limit) || 100));
    var type = String(q.type || '').toLowerCase();
    var keywordRaw = String(q.keyword || '');
    var keyword = keywordRaw.toLowerCase();
    var all = allHistoryEvents().slice().sort(function (a, b) { return b.ts - a.ts; });
    var filtered = all.filter(function (e) {
      if (cursor > 0 && e.ts >= cursor) return false;   // cursor 语义：只返回 ts < cursor
      if (toTs > 0 && e.ts > toTs) return false;
      if (fromTs > 0 && e.ts < fromTs) return false;
      if (type && e.type !== type) return false;
      if (keyword) {
        var hay = (e.text + ' ' + e.player).toLowerCase();
        if (hay.indexOf(keyword) < 0) return false;
      }
      return true;
    });
    var hasMore = filtered.length > limit;
    var items = filtered.slice(0, limit);
    return ok({
      available: true,
      items: items,
      hasMore: hasMore,
      nextCursor: hasMore && items.length ? items[items.length - 1].ts : 0,
      limit: limit,
      fromTs: fromTs,
      toTs: toTs,
      type: type,
      keyword: keywordRaw,
      files: ['live-events.jsonl'],
      scannedFiles: 1,
      scannedLines: all.length
    });
  }

  /* ------------------------- 第二期：服务器日志（mock） ------------------------- */
  var MOCK_LOG_FILES = [
    { name: 'log-0.txt', size: 2512384, kind: 'game-log', ageMin: 2 },
    { name: 'log-1.txt', size: 1153433, kind: 'game-log', ageMin: 42 },
    { name: 'last.log', size: 655360, kind: 'last', ageMin: 58 },
    { name: 'restart-supervisor.log', size: 8192, kind: 'restart-supervisor', ageMin: 300 },
    { name: 'console-20261018-120000.log', size: 4404019, kind: 'console', ageMin: 600 },
    { name: 'log-12.txt', size: 60 * 1024 * 1024, kind: 'game-log', ageMin: 1440, tooBig: true }
  ];
  var MOCK_LOG_MAX_BYTES = 50 * 1024 * 1024;

  function mockLogFile(name) {
    for (var i = 0; i < MOCK_LOG_FILES.length; i++) if (MOCK_LOG_FILES[i].name === name) return MOCK_LOG_FILES[i];
    return null;
  }

  function mockLogLines(name, maxLines) {
    var file = mockLogFile(name) || { kind: 'game-log' };
    var count = Math.min(maxLines || 200, 400);
    var rand = seeded(20261018);
    var t = now();
    var out = [];
    for (var i = count - 1; i >= 0; i--) {
      var ts = t - i * 1500;
      var lvl = rand() < 0.08 ? 'WARN' : rand() < 0.03 ? 'ERROR' : 'INFO';
      var body = [
        '游戏线程 tick 正常，TPS=' + (58 + Math.floor(rand() * 3)),
        '玩家事件：PlayerJoin name=测试玩家' + Math.floor(rand() * 999),
        '外部CP 资源加载完成：赛克兰特高阶科技.zip',
        '地图保存成功 slot=1 size=' + (Math.floor(rand() * 900) + 100) + 'KB',
        'Web 门户交互：审核通过 id=' + Math.floor(rand() * 9999),
        '玩家聊天：<这是一行很长的日志内容，用于验证日志预览在窄屏/长行时是否正确换行显示而不会撑破页面或出现横向滚动的极端情况，内容重复填充 ' + new Array(12).join('填充') + '>'
      ][i % 6];
      out.push('[' + fmtDate(ts) + ' ' + pad(new Date(ts).getHours()) + ':' + pad(new Date(ts).getMinutes()) + ':' + pad(new Date(ts).getSeconds()) + '] [' + lvl + '] ' + body);
    }
    return out;
  }

  function consumeLogsFail(state) {
    var mode = state.logsFailMode || '';
    if (!mode) return null;
    state.logsFailMode = '';
    saveState(state);
    if (mode === 'busy') return fail(429, 'BUSY', '日志下载并发已满（mock 模拟），请稍后重试', { retryAfter: 15 });
    return fail(429, 'RATE_LIMITED', '日志访问过于频繁（mock 模拟），请稍后再试', { retryAfter: 60 });
  }

  function handleLogs(req) {
    if (!isAuthed(req)) return authFail(req);
    var s = S();
    var failRes = consumeLogsFail(s);
    if (failRes) return failRes;
    var items = MOCK_LOG_FILES.map(function (f) {
      return { name: f.name, size: f.size, kind: f.kind, modifiedAt: fmtDateTime(now() - f.ageMin * 60000) };
    });
    return ok({
      available: true,
      dir: 'mdtserver/config/logs（mock）',
      requireLogin: true,
      maxFileBytes: MOCK_LOG_MAX_BYTES,
      items: items,
      count: items.length,
      serverTime: fmtDateTime(now())
    });
  }

  function handleLogsTail(req) {
    if (!isAuthed(req)) return authFail(req);
    var s = S();
    var failRes = consumeLogsFail(s);
    if (failRes) return failRes;
    var name = String((req.query && req.query.name) || '');
    var file = mockLogFile(name);
    if (!file) return fail(404, 'NOT_FOUND', '日志文件不存在或不允许访问：' + name);
    if (file.tooBig) return fail(413, 'FILE_TOO_LARGE', '日志文件超过大小上限（' + MOCK_LOG_MAX_BYTES + ' 字节）');
    var lines = Math.min(2000, Math.max(1, Number((req.query && req.query.lines) || 200)));
    var all = mockLogLines(name, 400);
    var shown = all.slice(-lines);
    return ok({
      name: name,
      kind: file.kind,
      size: file.size,
      lines: shown,
      truncated: all.length > shown.length,
      serverTime: fmtDateTime(now())
    });
  }

  function handleLogsDownload(req) {
    if (!isAuthed(req)) return authFail(req);
    var s = S();
    var failRes = consumeLogsFail(s);
    if (failRes) return failRes;
    var name = String((req.query && req.query.name) || '');
    var file = mockLogFile(name);
    if (!file) return fail(404, 'NOT_FOUND', '日志文件不存在或不允许访问：' + name);
    if (file.tooBig) return fail(413, 'FILE_TOO_LARGE', '日志文件超过大小上限（' + MOCK_LOG_MAX_BYTES + ' 字节）');
    return ok({ fileName: name, kind: file.kind, size: file.size, content: mockLogLines(name, 400).join('\n') + '\n' });
  }

  /* --------------------------------- 状态 --------------------------------- */
  function buildStats(online) {
    var T = now();
    var rand = seeded(20261017);
    var daily = [];
    var totalPlayers = 0, totalFlow = 0, totalChat = 0;
    for (var i = 13; i >= 0; i--) {
      var ts = T - i * 86400000;
      var players = 1 + Math.floor(rand() * 6);
      var flow = 3 + Math.floor(rand() * 18);
      var chat = 10 + Math.floor(rand() * 90);
      var playMillis = (30 + Math.floor(rand() * 180)) * 60000;
      totalPlayers += players; totalFlow += flow; totalChat += chat;
      daily.push({ date: fmtDate(ts).slice(5), players: players, flow: flow, chatMessages: chat, playMillis: playMillis });
    }
    var hourly = [];
    for (var h = 0; h < 24; h++) {
      hourly.push({ hour: h, flow: h >= 8 && h <= 23 ? Math.floor(rand() * 9) : Math.floor(rand() * 3), peakOnline: 0 });
      if (h <= new Date().getHours()) hourly[h].peakOnline = Math.floor(rand() * 6);
    }
    return {
      schema: 2,
      enabled: true,
      updatedAt: fmtDateTime(T),
      serverInfo: {
        map: '赛克兰特', mode: 'survival', wave: 36 + Math.floor(rand() * 3), tps: 58 + Math.floor(rand() * 3),
        uptimeSeconds: 5 * 3600 + 23 * 60 + Math.floor(rand() * 60), onlinePlayers: online,
        onlineByRank: { '0': 0, '1': 1, '2': online > 1 ? 1 : 0, '3': 0, '3+': online > 2 ? 1 : 0 }
      },
      today: { date: fmtDate(T), players: Math.min(6, totalPlayers % 7 + 1), flow: 20 + Math.floor(rand() * 12), chatMessages: 90 + Math.floor(rand() * 60), playMillis: 7380000, online: online, peakOnline: Math.max(online, 6), peakOnlineHistory: 12 },
      total: { players: 328, flow: 1912, chatMessages: 45781, playMillis: 9036000000, forumPosts: 131, forumComments: 655, likes: 1526, dislikes: 39, recognitions: 92, peakOnline: 12 },
      last14Days: { days: 14, startDate: fmtDate(T - 13 * 86400000), endDate: fmtDate(T), activeDays: 12, players: 57, dailyAvgPlayers: 4.1, flow: totalFlow, chatMessages: totalChat, playMillis: 86000000, forumPosts: 9, forumComments: 41, likes: 96, dislikes: 2, recognitions: 5 },
      daily: daily,
      hourly: hourly,
      totalByRank: { '0': 12, '1': 186, '2': 96, '3': 22, '3+': 12 },
      notes: { dailySeriesDays: 14, hourlySeriesHours: 24, dailyKeepDays: 400, hourlyKeepDays: 14, communityWindowDays: 14, peakOnline: 12, playMillisTotal: 9036000000, communityTotalsAreLifetime: true, dailyHistorySince: '2026-10-04' }
    };
  }

  function statusPayload() {
    var s = S();
    // 在线人数做小幅随机游走，模拟真实服务器波动
    var delta = Math.floor(Math.random() * 3) - 1;
    s.statusOnline = Math.max(0, Math.min(16, (s.statusOnline || 3) + delta));
    saveState(s);
    var stats = buildStats(s.statusOnline);
    return {
      available: true,
      enabled: true,
      updatedAt: stats.updatedAt,
      serverName: 'MDT DO',
      motd: PAPER_MOTD,
      port: 6567,
      online: s.statusOnline,
      maxPlayers: 16,
      map: stats.serverInfo.map,
      mode: stats.serverInfo.mode,
      wave: stats.serverInfo.wave,
      tps: stats.serverInfo.tps,
      uptimeSeconds: stats.serverInfo.uptimeSeconds,
      onlineByRank: stats.serverInfo.onlineByRank,
      net: netSnapshot(),
      stats: stats
    };
  }

  var PAPER_MOTD = '[gold]欢迎来到 MDT DO![]\n[blue]游戏内输入 /post 或 /wiki 以查看最新消息[]\n双击自己或其他玩家，快速打开信息面板！';

  /* --------------------------------- 路由 --------------------------------- */
  function ok(data) { return { status: 200, body: { ok: true, data: data } }; }
  function fail(status, code, message, extra) {
    var error = { code: code, message: message };
    if (extra) Object.keys(extra).forEach(function (k) { error[k] = extra[k]; });
    return { status: status, body: { ok: false, error: error } };
  }

  function isAuthed(req) {
    var s = S();
    if (!s.token) return false;
    if (s.tokenExpiresAt && s.tokenExpiresAt < now()) return false;
    return req.token === s.token;
  }
  function authFail(req) {
    var s = S();
    if (s.token && s.tokenExpiresAt && s.tokenExpiresAt < now()) return fail(401, 'SESSION_EXPIRED', '登录已失效，请重新登录');
    return fail(401, 'UNAUTHORIZED', '需要管理员登录');
  }

  function pendingCount(state, kind) {
    return state.pending.filter(function (i) { return i.kind === kind; }).length;
  }
  function queueState(state, kind) {
    return { limit: 10, pending: pendingCount(state, kind) };
  }
  function queueHealth(state) {
    return {
      pendingDp: pendingCount(state, 'dp'),
      pendingMap: pendingCount(state, 'map'),
      queueLimit: 10
    };
  }

  function parseForm(form, key) {
    try {
      var v = form.get(key);
      return v == null ? '' : String(v);
    } catch (e) { return ''; }
  }
  function getUploadFile(form) {
    try {
      var f = form.get('file');
      if (f && typeof f === 'object' && typeof f.name === 'string') return f;
    } catch (e) { /* ignore */ }
    return null;
  }

  function extOf(name) {
    var idx = String(name).lastIndexOf('.');
    return idx < 0 ? '' : String(name).slice(idx).toLowerCase();
  }

  function handleUpload(req) {
    var form = req.body;
    if (!form || typeof form.get !== 'function') return fail(400, 'BAD_REQUEST', '缺少 multipart 表单数据');
    var kind = parseForm(form, 'kind');
    var title = parseForm(form, 'title').trim();
    var author = parseForm(form, 'author').trim();
    var desc = parseForm(form, 'desc');
    var replaceOf = parseForm(form, 'replaceOf').trim();
    var file = getUploadFile(form);
    var s = S();

    if (kind !== 'dp' && kind !== 'map') return fail(400, 'BAD_REQUEST', 'kind 必须是 dp 或 map');
    if (!title) return fail(400, 'BAD_REQUEST', '标题不能为空');
    if (title.length > 200) return fail(400, 'BAD_REQUEST', '标题不能超过 200 字');
    if (!author) return fail(400, 'BAD_REQUEST', '作者不能为空');
    if (author.length > 100) return fail(400, 'BAD_REQUEST', '作者不能超过 100 字');
    if (desc && desc.length > 4000) return fail(400, 'BAD_REQUEST', '介绍不能超过 4000 字');
    if (!file) return fail(400, 'BAD_REQUEST', '缺少上传文件');
    if (!file.size) return fail(400, 'BAD_REQUEST', '文件为空');
    if (file.size > 10 * 1024 * 1024) return fail(413, 'FILE_TOO_LARGE', '文件超过 10MB 上限');
    var allowed = kind === 'dp' ? ['.zip', '.json', '.hjson', '.json5'] : ['.msav'];
    if (allowed.indexOf(extOf(file.name)) < 0) return fail(400, 'BAD_TYPE', '文件类型不支持：' + extOf(file.name));

    // 限频：10 分钟 5 次
    var cutoff = now() - 10 * 60000;
    s.uploadTimes = (s.uploadTimes || []).filter(function (t) { return t > cutoff; });
    if (s.uploadTimes.length >= 5) return fail(429, 'RATE_LIMITED', '上传过于频繁，请稍后再试');
    // 队列上限：每种类型 10 件
    var queue = queueState(s, kind);
    if (queue.pending >= 10) {
      return fail(409, 'QUEUE_FULL', '待审核队列已满（' + queue.pending + '/' + queue.limit + '），请稍后再试', { queue: queue });
    }
    // 名称冲突（未选择替换时）
    if (!replaceOf) {
      var catalog = s.catalog[kind] || [];
      var conflict = catalog.some(function (it) {
        return String(it.name || '').trim().toLowerCase() === title.toLowerCase();
      });
      if (conflict) return fail(409, 'NAME_CONFLICT', '已存在同名条目，可选择“替换现有条目”');
    }

    var id = String(s.nextId++);
    var tsNow = now();
    var resolvedReplace = '';
    if (replaceOf) {
      var existing = (s.catalog[kind] || []).filter(function (it) {
        return String(it.file || '').toLowerCase() === replaceOf.toLowerCase() ||
          String(it.name || '').toLowerCase() === replaceOf.toLowerCase();
      })[0];
      resolvedReplace = existing ? existing.file : replaceOf; // 与后端一致：尽量归一为现有文件名
    }
    var item = {
      id: id, kind: kind, title: title, author: author, desc: desc, replaceOf: resolvedReplace,
      fileName: String(file.name), size: Number(file.size) || 0,
      createdAt: tsNow, createdAtText: fmtDateTime(tsNow), status: 'pending'
    };
    s.pending.unshift(item);
    s.uploadTimes.push(tsNow);
    saveState(s);
    return ok({
      id: id, kind: kind, status: 'pending', title: title, author: author,
      fileName: item.fileName, size: item.size,
      queue: queueState(s, kind), message: '已受理，等待管理员审核'
    });
  }

  function handleReviewList(req) {
    if (!isAuthed(req)) return authFail(req);
    var kind = String((req.query && req.query.kind) || 'dp').toLowerCase();
    if (kind !== 'dp' && kind !== 'map') return fail(400, 'BAD_REQUEST', 'kind 只支持 dp/map');
    var s = S();
    var items = s.pending.filter(function (i) { return i.kind === kind; })
      .sort(function (a, b) { return (b.createdAt || 0) - (a.createdAt || 0); });
    var history = s.history.filter(function (i) { return i.kind === kind; }).slice(0, 20);
    return ok({ kind: kind, items: items, history: history, queue: queueState(s, kind) });
  }

  function fileBaseName(name) {
    var idx = String(name).lastIndexOf('.');
    return idx < 0 ? String(name) : String(name).slice(0, idx);
  }

  function handleApprove(req) {
    if (!isAuthed(req)) return authFail(req);
    var body = req.body || {};
    var s = S();
    var idx = s.pending.findIndex(function (i) { return String(i.id) === String(body.id); });
    if (idx < 0) return fail(404, 'NOT_FOUND', '提交不存在');
    var item = s.pending[idx];
    s.pending.splice(idx, 1);

    var list = s.catalog[item.kind] || (s.catalog[item.kind] = []);
    var finalFile = item.fileName;
    var replaced = '';
    if (item.replaceOf) {
      var oldIdx = list.findIndex(function (it) {
        return it.file === item.replaceOf || it.name === item.replaceOf;
      });
      if (oldIdx >= 0) {
        replaced = list[oldIdx].file;
        // 同名同格式：原地替换（沿用旧文件名，避免 catalog 出现重复条目）
        finalFile = extOf(replaced) === extOf(item.fileName) ? replaced : uniqueName(list, item.fileName);
        list.splice(oldIdx, 1);
      }
    }
    if (!replaced || finalFile === item.fileName) {
      if (!list.some(function (it) { return it.file === finalFile; })) { /* 直接使用 */ }
      else if (!item.replaceOf) finalFile = uniqueName(list, item.fileName);
    }
    var nowTs = now();
    list.unshift({
      name: fileBaseName(finalFile), file: finalFile, size: item.size,
      author: item.author || null, desc: item.desc || null,
      title: item.title || null, updatedAt: fmtDateTime(nowTs)
    });
    var requested = (body.reload === true || body.reload === 'true') || (item.kind === 'map' && body.reload !== false);
    var consoleReachable = s.consoleReachable !== false;
    var dispatched = consoleReachable && requested;
    var rec = {
      id: item.id, kind: item.kind, title: item.title, author: item.author,
      fileName: item.fileName, status: 'approved',
      reviewedAt: nowTs, reviewedAtText: fmtDateTime(nowTs),
      rejectReason: '', finalName: finalFile
    };
    s.history.unshift(rec);
    s.history = s.history.slice(0, 200);
    saveState(s);
    var result = {
      id: item.id, kind: item.kind, status: 'approved', finalName: finalFile, replaced: replaced,
      reload: {
        dispatched: dispatched,
        requested: requested,
        command: item.kind === 'dp' ? 'externalcp load "' + finalFile + '"' : 'maps',
        note: !consoleReachable
          ? '游戏控制台未开启（可选），未触发游戏侧命令'
          : (requested ? '演示模式：未真正调用游戏控制台热重载' : '')
      }
    };
    if (!consoleReachable) {
      result.reloadHint = '游戏控制台未开启（可选，生产服常态）：文件已就位，重启游戏服后生效；也可在游戏控制台手动执行 externalcp load / maps。';
    }
    return ok(result);
  }

  function uniqueName(list, fileName) {
    var base = fileBaseName(fileName);
    var ext = extOf(fileName);
    var candidate = fileName;
    var i = 2;
    while (list.some(function (it) { return it.file === candidate; })) { candidate = base + ' (' + i + ')' + ext; i++; }
    return candidate;
  }

  function handleReject(req) {
    if (!isAuthed(req)) return authFail(req);
    var body = req.body || {};
    var reason = String(body.reason || '').trim();
    if (!reason) return fail(400, 'BAD_REQUEST', '拒绝原因不能为空');
    var s = S();
    var idx = s.pending.findIndex(function (i) { return String(i.id) === String(body.id); });
    if (idx < 0) return fail(404, 'NOT_FOUND', '提交不存在');
    var item = s.pending.splice(idx, 1)[0];
    var nowTs = now();
    s.history.unshift({
      id: item.id, kind: item.kind, title: item.title, author: item.author,
      fileName: item.fileName, status: 'rejected',
      reviewedAt: nowTs, reviewedAtText: fmtDateTime(nowTs),
      rejectReason: reason, finalName: ''
    });
    s.history = s.history.slice(0, 200);
    saveState(s);
    return ok({ id: item.id, status: 'rejected', reason: reason });
  }

  function handleDelete(req) {
    if (!isAuthed(req)) return authFail(req);
    var body = req.body || {};
    var kind = String(body.kind || '');
    var name = String(body.name || '');
    if (kind !== 'dp' && kind !== 'map') return fail(400, 'BAD_REQUEST', 'kind 必须是 dp 或 map');
    if (!name) return fail(400, 'BAD_REQUEST', '缺少 name');
    var s = S();
    var list = s.catalog[kind] || [];
    var idx = list.findIndex(function (it) { return it.file === name || it.name === name; });
    if (idx < 0) return fail(404, 'NOT_FOUND', '文件不存在：' + name);
    var removed = list.splice(idx, 1)[0];
    saveState(s);
    return ok({
      kind: kind, name: fileBaseName(removed.file || removed.name), file: removed.file || removed.name,
      deleted: true,
      reload: { dispatched: false, requested: !!body.unload, note: body.unload ? '演示模式：未真正调用游戏控制台卸载' : '' }
    });
  }

  /* ------------------------------- 请求入口 ------------------------------- */
  function route(req) {
    var method = String(req.method || 'GET').toUpperCase();
    var path = String(req.path || '');
    if (method === 'GET' && path === '/api/status') return ok(statusPayload());
    if (method === 'GET' && path === '/api/health') {
      var sh = S();
      var consoleReachable = sh.consoleReachable !== false;
      return ok(Object.assign({
        serverTime: fmtDateTime(now()), mode: 'mock',
        dpCount: (sh.catalog.dp || []).length, mapCount: (sh.catalog.maps || []).length,
        logsAvailable: true,
        console: {
          enabled: true,
          reachable: consoleReachable,
          optional: true,
          host: '127.0.0.1',
          port: 6859,
          lastError: ''
        },
        version: 'portal-mock-1.1.0'
      }, queueHealth(sh)));
    }
    if (method === 'GET' && path === '/api/events') {
      ensureEventState();
      var q = req.query || {};
      var after = Number(q.afterTs) || 0;
      var limit = Math.min(300, Math.max(1, Number(q.limit) || 100));
      if (!after) {
        var initial = eventState.events.slice(-limit);
        var lastTs0 = initial.length ? initial[initial.length - 1].ts : 0;
        return ok({ events: initial, lastTs: lastTs0, available: true });
      }
      // 每次增量轮询追加 0~2 条“新”事件，让演示流持续滚动
      var add = Math.random() < 0.75 ? 1 + Math.floor(Math.random() * 2) : 0;
      if (add) appendLiveEvents(add);
      var fresh = eventState.events.filter(function (e) { return e.ts > after; }).slice(-limit);
      var lastTs = eventState.events.length ? eventState.events[eventState.events.length - 1].ts : after;
      return ok({ events: fresh, lastTs: lastTs, available: true });
    }
    if (method === 'GET' && path === '/api/catalog') {
      var s0 = S();
      return ok({ dp: s0.catalog.dp || [], maps: s0.catalog.maps || [] });
    }
    if (method === 'GET' && path === '/api/events/history') return handleEventsHistory(req);
    if (method === 'GET' && path === '/api/logs') return handleLogs(req);
    if (method === 'GET' && path === '/api/logs/tail') return handleLogsTail(req);
    if (method === 'GET' && path === '/api/logs/download') return handleLogsDownload(req);
    if (method === 'GET' && path === '/api/upload/queue') {
      return ok({ queue: queueHealth(S()) });
    }
    if (method === 'POST' && path === '/api/login') {
      var body = req.body || {};
      var username = String(body.username || '');
      var password = String(body.password || '');
      if (username !== 'admin' || password !== 'demo123') {
        return fail(401, 'BAD_CREDENTIALS', '用户名或密码错误（演示模式：admin / demo123）');
      }
      var s1 = S();
      s1.token = 'mock-' + Math.random().toString(16).slice(2, 18);
      s1.tokenExpiresAt = now() + 12 * 3600000;
      saveState(s1);
      return ok({ token: s1.token, expiresAt: s1.tokenExpiresAt, username: 'admin' });
    }
    if (method === 'POST' && path === '/api/logout') {
      var s2 = S();
      s2.token = '';
      s2.tokenExpiresAt = 0;
      saveState(s2);
      return ok({ loggedOut: true });
    }
    if (method === 'GET' && path === '/api/review/list') return handleReviewList(req);
    if (method === 'POST' && path === '/api/upload') return handleUpload(req);
    if (method === 'POST' && path === '/api/review/approve') return handleApprove(req);
    if (method === 'POST' && path === '/api/review/reject') return handleReject(req);
    if (method === 'DELETE' && path === '/api/item') return handleDelete(req);
    return fail(404, 'NOT_FOUND', '演示数据不支持的接口：' + method + ' ' + path);
  }

  global.__PORTAL_MOCK_HANDLER__ = function (req) {
    // 模拟网络延迟，让 loading 状态可见
    var delay = 120 + Math.floor(Math.random() * 220);
    return new Promise(function (resolve) {
      setTimeout(function () { resolve(route(req)); }, delay);
    });
  };

  /* 演示调试工具：上传/管理页在 mock 模式下会显示小工具条调用它。 */
  global.__PORTAL_MOCK_DEBUG__ = {
    reset: function () { state = resetState(); eventState = { builtFor: 0, events: [], nextTs: 0, liveIndex: 0, seq: 0 }; return true; },
    fillQueue: function (kind) {
      var s = S();
      kind = kind === 'map' ? 'map' : 'dp';
      var n = 0;
      while ((s.pending.filter(function (i) { return i.kind === kind; }).length) < 10) {
        n++;
        var ts = now();
        s.pending.push({
          id: String(s.nextId++), kind: kind, title: '演示待审 ' + n, author: '演示作者',
          desc: '用于测试队列已满错误码的假数据。', replaceOf: '',
          fileName: (kind === 'dp' ? 'demo' + n + '.zip' : 'demo' + n + '.msav'),
          size: 1024 * n, createdAt: ts, createdAtText: fmtDateTime(ts), status: 'pending'
        });
      }
      saveState(s);
      return state.pending.length;
    },
    state: function () { return S(); },
    /** 第二期：演示上行带宽不可用状态（true/false） */
    setNetAvailable: function (available) {
      netState.available = available !== false;
      if (netState.available) netState.series = [];
      saveState(S());
      return netState.available;
    },
    /** 第二期：模拟游戏控制台可达性（true/false），影响 /api/health.console 与审核 consoleWarning */
    setConsoleReachable: function (reachable) {
      var s = S();
      s.consoleReachable = reachable !== false;
      saveState(s);
      return s.consoleReachable;
    },
    consoleReachable: function () { return S().consoleReachable !== false; },
    /** 第二期：模拟日志接口错误（'busy' | 'rate' | ''），下一次日志请求生效 */
    logsFail: function (mode) {
      var s = S();
      s.logsFailMode = mode === 'busy' ? 'busy' : mode === 'rate' ? 'rate' : '';
      saveState(s);
      return s.logsFailMode;
    }
  };
})(window);
