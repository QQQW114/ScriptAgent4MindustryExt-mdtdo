/* ============================================================================
 * MDT DO 服务器门户 - 首页逻辑
 *  - 欢迎区 + 服务器状态（每 30 秒）
 *  - 近 14 天 / 今日 24 小时趋势图（原生 SVG）
 *  - 服务器实时消息流（每 4 秒轮询，增量 afterTs，彩色文字安全渲染）
 *  - 原状态页数据分析（履历视图 / 运维视图）
 * ==========================================================================*/
(function (global) {
  'use strict';

  var U = global.PortalUtil;
  var Api = global.PortalApi;
  var M = global.PortalMarkup;
  var UI = global.PortalUI;
  var CH = global.PortalChart;
  var SITE = global.PORTAL_SITE || {};

  var $ = U.$;
  var POLL_MS = 4000;
  var STATUS_MS = 30000;
  var MAX_FEED_EVENTS = 300;
  var MAX_FEED_DOM = 360;

  var TYPE_META = {
    chat: { label: '聊天', icon: '💬' },
    join: { label: '加入', icon: '➡️' },
    leave: { label: '离开', icon: '⬅️' },
    vote_start: { label: '投票发起', icon: '🗳️' },
    vote_pass: { label: '投票通过', icon: '✅' },
    vote_fail: { label: '投票失败', icon: '❌' },
    map_change: { label: '地图切换', icon: '🗺️' },
    system: { label: '系统', icon: '⚙️' }
  };
  var TYPE_ORDER = ['chat', 'join', 'leave', 'vote_start', 'vote_pass', 'vote_fail', 'map_change', 'system'];
  var MODE_NAMES = { survival: '生存', sandbox: '沙盒', attack: '进攻', pvp: '对战', editor: '编辑器', waves: '波次' };
  var RANK_ORDER = ['0', '1', '2', '3', '3+'];
  var RANK_NAMES = { '0': '0级 游客', '1': '1级', '2': '2级', '3': '3级', '3+': '3+ 级及以上' };

  var state = {
    status: null,
    statusLoadedAt: 0,
    net: null,
    events: [],
    lastTs: 0,
    seen: Object.create(null),
    seenQueue: [],
    renderedKeys: Object.create(null),
    filters: { chat: true, join: true, leave: true, vote_start: true, vote_pass: true, vote_fail: true, map_change: true, system: true },
    paused: false,
    loading: false,
    failures: 0,
    available: true,
    initialLoaded: false,
    unread: 0,
    autoScroll: true,
    lastRenderedDate: '',
    currentView: 'resume',
    statusTimer: null,
    feedTimer: null,
    history: {
      open: false,
      range: '1h',
      type: '',
      keyword: '',
      items: [],
      cursor: 0,
      hasMore: false,
      loading: false,
      loaded: false
    }
  };

  var el = {};

  function cacheEls() {
    ['motd', 'serverStatePill', 'heroDot', 'onlineNum', 'maxOnline',
      'onlineHint', 'heroMap', 'heroWave', 'heroTps', 'heroUptime', 'heroUpdated', 'heroSource',
      'statusUpdatedAt', 'refreshStatusBtn', 'statOnline', 'statOnlineSub', 'statOnlineBar', 'statMap', 'statMode',
      'statWave', 'statTps', 'statTpsSub', 'statTpsBar', 'statUptime', 'statUptimeSub', 'statEnabled', 'statEnabledSub',
      'statNet', 'statNetSub', 'netSpark',
      'statusError', 'dailyChart', 'hourlyChart', 'dailyRange', 'hourlyDate',
      'feedFilters', 'feedList', 'feedStatePill', 'feedPauseBtn', 'feedJumpBtn', 'feedJumpFab', 'feedCount',
      'feedPollInfo', 'feedRefreshLink', 'feedNotice', 'feedAvailableNote', 'feedDot', 'feedHistoryBtn',
      'historyOverlay', 'historyPill', 'historyCloseBtn', 'historyRangeSeg', 'historyTypeSelect', 'historyKeyword',
      'historySearchBtn', 'historyNotice', 'historyList', 'historyCount', 'historyMoreBtn',
      'viewSeg', 'resumeView', 'opsView', 'resumeKpis', 'resumeKpiHint', 'communityKpis', 'communityHint',
      'rankBarsResume', 'summaryBox', 'sMap', 'sMode', 'sWave', 'sTps', 'sUptime', 'sOnline', 'sPeakToday',
      'sPeakAll', 'obFill', 'obText', 'todayDate', 'tPlayers', 'tFlow', 'tChat', 'tPlay', 'aPlayers', 'aFlow',
      'aChat', 'aPlay', 'aPosts', 'aComments', 'aLikes', 'aRecognitions', 'dailyTable', 'footYear'].forEach(function (id) {
      el[id] = document.getElementById(id);
    });
  }

  /* ------------------------------- 工具函数 ------------------------------- */
  function fmt(value, fallback) {
    if (value === null || value === undefined || value === '') return fallback || '--';
    return U.fmtInt(value);
  }
  function parseLocalTime(text) {
    var m = String(text || '').match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/);
    if (!m) return 0;
    return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6])).getTime();
  }
  function modeName(mode) {
    if (!mode) return '--';
    return MODE_NAMES[String(mode).toLowerCase()] || String(mode);
  }
  function isAtBottom(node) {
    return node.scrollHeight - node.scrollTop - node.clientHeight < 48;
  }

  /** 状态 pill：只接受文本（内部转义），避免任何 innerHTML 注入。 */
  function setPill(target, text, cls) {
    if (!target) return;
    target.className = 'pill ' + (cls || '');
    target.innerHTML = '<span class="pdot"></span>' + U.esc(text == null ? '' : String(text));
  }

  function eventKey(ev) {
    return ev.ts + '|' + ev.type + '|' + (ev.player || '') + '|' + (ev.text || '');
  }

  /* ------------------------------- 介绍 ------------------------------- */
  function setupHero() {
    if (el.footYear) el.footYear.textContent = String(new Date().getFullYear());
  }

  function renderMotd(text) {
    if (!el.motd) return;
    var src = text && String(text).trim() ? String(text) : (SITE.motd || '');
    if (!src) { el.motd.textContent = '欢迎来到 ' + (SITE.serverName || 'MDT DO') + '！'; return; }
    // 安全：render 内部先转义文本再包颜色 span
    el.motd.innerHTML = M.render(src);
  }

  /* ------------------------------- 状态加载 ------------------------------- */
  function loadStatus() {
    return Api.ready().then(function () {
      return Api.status();
    }).then(function (st) {
      state.status = st;
      state.statusLoadedAt = Date.now();
      renderStatus(st);
      renderCharts(st);
      renderDataBoard(st);
      UI.clearNotice(el.statusError);
      return st;
    }).catch(function (err) {
      UI.setBadge(el.serverStatePill, '连接失败', 'off');
      UI.showError(el.statusError, err, loadStatus);
      UI.toast(Api.errorInfo(err).title, 'error');
      throw err;
    });
  }

  function renderStatus(st) {
    st = st || { available: false };
    var online = st.online;
    var max = st.maxPlayers != null ? st.maxPlayers : (SITE.maxPlayersFallback || 16);
    var enabled = st.enabled !== false;
    var available = st.available !== false;
    // 地图名可能带 Mindustry 颜色标记（如 [stat]烬区），展示时去掉标记
    var mapText = M.plain(st.map) || '--';

    // 顶部状态 pill
    if (!available) {
      setPill(el.serverStatePill, '统计文件缺失', 'off');
      if (el.heroDot) el.heroDot.className = 'dot';
    } else if (!enabled) {
      setPill(el.serverStatePill, '统计已关闭（展示停止前数据）', 'warn');
      if (el.heroDot) el.heroDot.className = 'dot yellow';
    } else if (st.tps != null && Number(st.tps) < 30) {
      setPill(el.serverStatePill, '运行中 · 负载较高', 'warn');
      if (el.heroDot) el.heroDot.className = 'dot yellow';
    } else {
      setPill(el.serverStatePill, '运行中', 'on');
      if (el.heroDot) el.heroDot.className = 'dot green';
    }

    // hero 面板
    U.setText(el.onlineNum, online != null ? U.fmtInt(online) : '--');
    U.setText(el.maxOnline, max != null ? U.fmtInt(max) : '--');
    U.setText(el.onlineHint, online == null
      ? '在线人数未知'
      : (online === 0 ? '当前无人在线，欢迎进服开荒' : '当前有 ' + U.fmtInt(online) + ' 名玩家在线'));
    U.setText(el.heroMap, mapText);
    if (el.heroMap) el.heroMap.title = st.map ? M.plain(st.map) : '';
    U.setText(el.heroWave, st.wave != null ? U.fmtInt(st.wave) : '--');
    U.setText(el.heroTps, st.tps != null ? (Math.round(Number(st.tps) * 10) / 10) : '--');
    U.setText(el.heroUptime, st.uptimeSeconds != null ? U.fmtDuration(st.uptimeSeconds) : '--');
    U.setText(el.heroUpdated, st.updatedAt ? '数据更新于 ' + st.updatedAt : '数据更新时间未知');
    if (el.heroSource) {
      if (st.source === 'legacy') {
        el.heroSource.hidden = false;
        el.heroSource.textContent = '旧版 JSON 兼容模式';
        el.heroSource.className = 'pill purple small';
      } else {
        el.heroSource.hidden = true;
      }
    }

    var updatedTs = parseLocalTime(st.updatedAt);
    U.setText(el.statusUpdatedAt, st.updatedAt
      ? '更新于 ' + st.updatedAt + (updatedTs ? '（' + U.timeAgo(updatedTs) + '）' : '')
      : '--');

    // 状态卡
    U.setText(el.statOnline, online != null ? U.fmtInt(online) : '--');
    U.setText(el.statOnlineSub, online != null && max ? '上限 ' + U.fmtInt(max) + ' 人' : '上限 --');
    if (el.statOnlineBar) el.statOnlineBar.style.width = (online != null && max ? U.clamp(online / max * 100, 0, 100) : 0).toFixed(1) + '%';
    U.setText(el.statMap, mapText);
    if (el.statMap) el.statMap.title = st.map ? M.plain(st.map) : '';
    U.setText(el.statMode, '模式 ' + modeName(st.mode));
    U.setText(el.statWave, st.wave != null ? U.fmtInt(st.wave) : '--');

    var tps = st.tps != null ? Number(st.tps) : null;
    if (el.statTps) {
      el.statTps.textContent = tps != null ? (Math.round(tps * 10) / 10) : '--';
      el.statTps.className = 'v ' + (tps == null ? '' : tps >= 55 ? 'green' : tps >= 30 ? 'yellow' : 'red');
    }
    U.setText(el.statTpsSub, tps == null ? '目标 60' : (tps >= 55 ? '流畅 · 目标 60' : tps >= 30 ? '有波动 · 目标 60' : '卡顿 · 目标 60'));
    if (el.statTpsBar) el.statTpsBar.style.width = (tps != null ? U.clamp(tps / 60 * 100, 0, 100) : 0).toFixed(1) + '%';
    U.setText(el.statUptime, st.uptimeSeconds != null ? U.fmtDuration(st.uptimeSeconds) : '--');
    U.setText(el.statUptimeSub, st.uptimeSeconds != null ? '本次启动以来' : '--');
    U.setText(el.statEnabled, !available ? '无数据' : (enabled ? '统计已启用' : '统计已关闭'));
    if (el.statEnabled) el.statEnabled.className = 'v small ' + (!available ? '' : enabled ? 'green' : 'yellow');
    U.setText(el.statEnabledSub, '数据来源：' + (!available ? '--' : st.source === 'legacy' ? '旧版 JSON' : '门户 API'));

    if (!available) {
      el.statusError.hidden = false;
      el.statusError.className = 'notice notice-warn';
      el.statusError.innerHTML = '';
      var title = document.createElement('div');
      title.className = 'notice-title';
      title.textContent = '⚠ 服务器统计文件尚未生成';
      var hint = document.createElement('div');
      hint.className = 'notice-hint';
      hint.textContent = '服务器统计脚本（serverStats.kts）写出 server-status.json 后，本页会自动显示。若服务器刚启动，请稍等 1 分钟再刷新。';
      el.statusError.appendChild(title);
      el.statusError.appendChild(hint);
    }

    renderNet(st.net);
  }

  /* ------------------------- 上行带宽（第二期） ------------------------- */
  /**
   * 渲染「上行带宽（最近 X 分钟平均）」状态卡 + sparkline。
   * 注意：后端 net.avgUpBps / series[].bps 虽然名字带 bps，实际单位是**字节/秒**。
   */
  function renderNet(net) {
    net = (net && typeof net === 'object') ? net : { available: false };
    state.net = net;
    var available = net.available === true && net.avgUpBps != null;
    var windowMin = net.windowMinutes != null ? net.windowMinutes : null;
    if (el.statNet) {
      el.statNet.textContent = available ? U.fmtBps(net.avgUpBps) : '不可用';
      el.statNet.className = 'v small ' + (available ? 'green' : '');
      var titleParts = ['最近 ' + (windowMin != null ? windowMin : '--') + ' 分钟平均上行带宽'];
      if (available) titleParts.push('平均：' + U.fmtBps(net.avgUpBps));
      if (net.interface) titleParts.push('网卡：' + net.interface);
      if (net.sampledAtText) titleParts.push('最后采样：' + net.sampledAtText);
      titleParts.push('口径：宿主机网卡上行总量（含游戏服 / Web / 系统流量），不等于纯游戏流量。');
      if (net.error) titleParts.push('状态：' + net.error);
      el.statNet.title = titleParts.join('\n');
    }
    if (el.statNetSub) {
      el.statNetSub.textContent = available
        ? '宿主机网卡上行 · 窗口 ' + (windowMin != null ? windowMin : '--') + ' 分钟平均'
        : '宿主机未提供网卡统计';
      el.statNetSub.title = available ? (net.sampledAtText ? '最后采样：' + net.sampledAtText : '') : '后端 data.net.available=false（非 Windows / 采样失败 / 已禁用）';
    }
    if (el.netSpark) {
      if (available && Array.isArray(net.series) && net.series.length >= 2) {
        CH.sparkline(el.netSpark, {
          values: net.series.map(function (p) { return p.bps; }),
          color: '#3ddc84',
          height: 34,
          title: '最近 ' + (windowMin != null ? windowMin : '') + ' 分钟上行带宽走势（字节/秒）'
        });
        el.netSpark.hidden = false;
      } else {
        el.netSpark.innerHTML = '';
        el.netSpark.hidden = true;
      }
    }
  }

  /* ------------------------------- 趋势图 ------------------------------- */
  function renderCharts(st) {
    st = st || state.status || {};
    var stats = st.stats || {};
    var daily = Array.isArray(stats.daily) ? stats.daily : [];
    var hourly = Array.isArray(stats.hourly) ? stats.hourly : [];
    var win = stats.last14Days || {};
    var today = stats.today || {};

    U.setText(el.dailyRange, win.startDate ? win.startDate + ' ~ ' + win.endDate : '--');
    CH.line(el.dailyChart, {
      labels: daily.map(function (d) { return d && d.date != null ? String(d.date) : ''; }),
      series: [
        { name: '每日游玩人数', color: '#4da3ff', area: true, values: daily.map(function (d) { return Number(d && d.players) || 0; }) },
        { name: '每日人流量', color: '#3ddc84', values: daily.map(function (d) { return Number(d && d.flow) || 0; }) }
      ],
      height: 250,
      emptyText: '暂无近 14 天趋势数据（统计启用后逐步填充）',
      ariaLabel: '近 14 天玩家活跃趋势'
    });

    U.setText(el.hourlyDate, today.date ? today.date + '（0-23 时）' : '--');
    CH.line(el.hourlyChart, {
      labels: hourly.map(function (h) {
        if (!h || h.hour == null) return '';
        return String(h.hour).padStart(2, '0') + ':00';
      }),
      series: [
        { name: '每小时人流量', color: '#ffd257', area: true, values: hourly.map(function (h) { return Number(h && h.flow) || 0; }) },
        { name: '每小时峰值在线', color: '#ff7bc8', values: hourly.map(function (h) { return Number(h && h.peakOnline) || 0; }) }
      ],
      height: 250,
      emptyText: '暂无今日 24 小时趋势数据',
      ariaLabel: '今日 24 小时在线趋势'
    });
  }

  /* --------------------------- 运营数据（旧页面能力） --------------------------- */
  function kpi(value, label, cls, sub) {
    return '<div class="kpi"><div class="v ' + (cls || '') + '">' + value + '</div><div class="l">' + U.esc(label) + '</div>' +
      (sub ? '<div class="s">' + U.esc(sub) + '</div>' : '') + '</div>';
  }

  function renderBars(container, data) {
    if (!container) return;
    var values = RANK_ORDER.map(function (k) { return Number((data && data[k]) || 0); });
    var total = values.reduce(function (a, b) { return a + b; }, 0);
    var max = Math.max(1, Math.max.apply(null, values));
    container.innerHTML = RANK_ORDER.map(function (k, i) {
      var v = values[i];
      var pct = Math.round(v / max * 100);
      var share = total > 0 ? Math.round(v / total * 1000) / 10 : 0;
      return '<div class="bar-row"><span class="label">' + U.esc(RANK_NAMES[k]) + '</span>' +
        '<span class="track"><span class="fill" style="width:' + pct + '%"></span></span>' +
        '<span class="num">' + U.fmtInt(v) + '<span class="dim"> · ' + share + '%</span></span></div>';
    }).join('');
  }

  function renderDataBoard(st) {
    if (!st || !st.stats) return;
    var d = st.stats;
    var total = d.total || {}, today = d.today || {}, info = d.serverInfo || {};
    var win = d.last14Days || {};
    var days = Number(win.days || 14);

    // ---- 履历视图 ----
    if (el.resumeKpis) {
      el.resumeKpis.innerHTML = [
        kpi(U.fmtInt(total.players), '累计服务玩家', 'green', '去重主体数'),
        kpi(U.fmtInt(total.flow), '累计人流量', 'yellow', '进入次数'),
        kpi(U.fmtInt(total.chatMessages), '累计聊天条数', '', '玩家发言'),
        kpi(total.peakOnline != null ? U.fmtInt(total.peakOnline) : '--', '历史峰值在线', 'purple', '同时在线上限'),
        kpi(U.fmtHoursFromMs(total.playMillis), '累计游玩时长', 'small', '统计期内'),
        kpi(U.fmtInt(total.forumPosts), '社区帖子', 'pink', '累计发布'),
        kpi(U.fmtInt(total.likes), '收到点赞', 'pink', '玩家互赞'),
        kpi(U.fmtInt(total.recognitions), '玩家认可', '', '互相认可次数')
      ].join('');
    }
    if (el.resumeKpiHint) {
      el.resumeKpiHint.textContent = win.startDate
        ? '近 ' + days + ' 天（' + win.startDate + ' ~ ' + win.endDate + '）：日均 ' + (win.dailyAvgPlayers != null ? win.dailyAvgPlayers : '--') +
          ' 人、活跃 ' + (win.activeDays || 0) + '/' + days + ' 天，人流量 ' + U.fmtInt(win.flow) + '，聊天 ' + U.fmtInt(win.chatMessages) + ' 条。'
        : '';
    }
    if (el.communityKpis) {
      el.communityKpis.innerHTML = [
        kpi(U.fmtInt(win.forumPosts), '新帖子', 'pink'),
        kpi(U.fmtInt(win.forumComments), '新评论', 'pink'),
        kpi(U.fmtInt(win.likes), '新增点赞', 'green'),
        kpi(U.fmtInt(win.recognitions), '新增认可', '')
      ].join('');
    }
    renderBars(el.rankBarsResume, d.totalByRank);
    renderSummary(d);

    // ---- 运维视图 ----
    U.setText(el.sMap, M.plain(info.map) || '--');
    U.setText(el.sMode, modeName(info.mode));
    U.setText(el.sWave, info.wave != null ? String(info.wave) : '--');
    U.setText(el.sTps, info.tps != null ? String(info.tps) : '--');
    U.setText(el.sUptime, info.uptimeSeconds != null ? U.fmtDuration(info.uptimeSeconds) : '--');
    U.setText(el.sOnline, info.onlinePlayers != null ? info.onlinePlayers + ' 人' : '--');
    U.setText(el.sPeakToday, today.peakOnline != null ? today.peakOnline + ' 人' : '--');
    U.setText(el.sPeakAll, total.peakOnline != null ? total.peakOnline + ' 人' : '--');

    var ob = RANK_ORDER.map(function (k) { return Number((info.onlineByRank && info.onlineByRank[k]) || 0); });
    var obTotal = ob.reduce(function (a, b) { return a + b; }, 0);
    var obMax = Math.max.apply(null, ob);
    if (el.obFill) el.obFill.style.width = (obTotal > 0 ? Math.round(obMax * 100 / obTotal) : 0) + '%';
    U.setText(el.obText, obTotal > 0 ? RANK_ORDER.map(function (k, i) { return k + ':' + ob[i]; }).join('  ') : '无人在线');

    U.setText(el.todayDate, today.date ? '（' + today.date + '）' : '');
    U.setText(el.tPlayers, U.fmtInt(today.players));
    U.setText(el.tFlow, U.fmtInt(today.flow));
    U.setText(el.tChat, U.fmtInt(today.chatMessages));
    U.setText(el.tPlay, U.fmtHoursFromMs(today.playMillis));
    U.setText(el.aPlayers, U.fmtInt(total.players));
    U.setText(el.aFlow, U.fmtInt(total.flow));
    U.setText(el.aChat, U.fmtInt(total.chatMessages));
    U.setText(el.aPlay, U.fmtHoursFromMs(total.playMillis));
    U.setText(el.aPosts, U.fmtInt(total.forumPosts));
    U.setText(el.aComments, U.fmtInt(total.forumComments));
    U.setText(el.aLikes, U.fmtInt(total.likes));
    U.setText(el.aRecognitions, U.fmtInt(total.recognitions));
    U.setText(el.communityHint, total.dislikes != null ? '（含收到的踩 ' + U.fmtInt(total.dislikes) + ' 次）' : '');

    renderDailyTable(d.daily);
  }

  function renderSummary(d) {
    if (!el.summaryBox) return;
    var total = d.total || {}, win = d.last14Days || {};
    var days = Number(win.days || 14);
    function line(k, v) { return '<div><span class="dim">' + U.esc(k) + '：</span><b>' + U.esc(v) + '</b></div>'; }
    var html = [
      line('运营的 Mindustry 开源游戏服务器累计服务玩家', U.fmtInt(total.players) + ' 人'),
      line('累计人流量（进入次数）', U.fmtInt(total.flow) + ' 次'),
      line('累计玩家聊天', U.fmtInt(total.chatMessages) + ' 条'),
      line('累计游玩时长', U.fmtHoursFromMs(total.playMillis)),
      line('历史最高同时在线', U.fmtInt(total.peakOnline) + ' 人'),
      line('近 ' + days + ' 天日均活跃', (win.dailyAvgPlayers != null ? win.dailyAvgPlayers : '--') + ' 人（活跃 ' + (win.activeDays || 0) + '/' + days + ' 天）'),
      line('社区累计帖子 / 评论 / 点赞 / 认可', [U.fmtInt(total.forumPosts), U.fmtInt(total.forumComments), U.fmtInt(total.likes), U.fmtInt(total.recognitions)].join(' / '))
    ];
    if (win.startDate) {
      html.push('<div class="dim" style="margin-top:6px">数据统计区间：每日明细自 ' + U.esc(win.startDate) + ' 起；社区累计值含全部历史。</div>');
    }
    el.summaryBox.innerHTML = html.join('');
  }

  function renderDailyTable(daily) {
    if (!el.dailyTable) return;
    if (!Array.isArray(daily) || !daily.length) {
      el.dailyTable.innerHTML = '<div class="table-empty">暂无每日明细数据（统计启用后按天累积）</div>';
      return;
    }
    var rows = daily.slice().reverse().map(function (row) {
      return '<tr><td>' + U.esc(row.date) + '</td>' +
        '<td class="num">' + U.fmtInt(row.players) + '</td>' +
        '<td class="num">' + U.fmtInt(row.flow) + '</td>' +
        '<td class="num">' + U.fmtInt(row.chatMessages) + '</td>' +
        '<td class="num">' + U.fmtHoursFromMs(row.playMillis) + '</td></tr>';
    }).join('');
    el.dailyTable.innerHTML = '<div class="table-wrap"><table class="data"><thead><tr>' +
      ['日期', '游玩人数', '人流量', '聊天条数', '游玩时长'].map(function (h, i) {
        return '<th' + (i ? ' style="text-align:right"' : '') + '>' + h + '</th>';
      }).join('') +
      '</tr></thead><tbody>' + rows + '</tbody></table></div>';
  }

  function switchView(view) {
    state.currentView = view === 'ops' ? 'ops' : 'resume';
    if (el.resumeView) el.resumeView.hidden = state.currentView !== 'resume';
    if (el.opsView) el.opsView.hidden = state.currentView !== 'ops';
    U.$$('#viewSeg button').forEach(function (btn) {
      var active = btn.getAttribute('data-view') === state.currentView;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-selected', active ? 'true' : 'false');
    });
  }

  /* ------------------------------- 消息流 ------------------------------- */
  function feedPill(text, cls) {
    setPill(el.feedStatePill, text, cls);
  }

  function setFeedNotice(kind, title, hint) {
    if (!el.feedNotice) return;
    if (!title) { el.feedNotice.hidden = true; el.feedNotice.innerHTML = ''; return; }
    el.feedNotice.hidden = false;
    el.feedNotice.className = 'notice notice-' + (kind || 'warn');
    el.feedNotice.innerHTML = '';
    var t = document.createElement('div');
    t.className = 'notice-title';
    t.textContent = title;
    el.feedNotice.appendChild(t);
    if (hint) {
      var h = document.createElement('div');
      h.className = 'notice-hint';
      h.textContent = hint;
      el.feedNotice.appendChild(h);
    }
  }

  function buildFilterChips() {
    if (!el.feedFilters) return;
    el.feedFilters.innerHTML = '';
    var allChip = document.createElement('button');
    allChip.type = 'button';
    allChip.className = 'chip';
    allChip.setAttribute('data-type', '__all__');
    allChip.innerHTML = '<span class="ic">✨</span>全部';
    el.feedFilters.appendChild(allChip);
    TYPE_ORDER.forEach(function (type) {
      var meta = TYPE_META[type];
      var chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'chip';
      chip.setAttribute('data-type', type);
      chip.innerHTML = '<span class="ic">' + meta.icon + '</span>' + U.esc(meta.label);
      chip.title = '只显示' + meta.label + '消息';
      el.feedFilters.appendChild(chip);
    });
    el.feedFilters.addEventListener('click', function (e) {
      var chip = e.target.closest ? e.target.closest('.chip') : null;
      if (!chip) return;
      var type = chip.getAttribute('data-type');
      if (type === '__all__') {
        TYPE_ORDER.forEach(function (t) { state.filters[t] = true; });
      } else {
        // 点击类型 = 只看该类型；再次点击同一类型 = 恢复全部
        var onlyThis = TYPE_ORDER.every(function (t) {
          return t === type ? state.filters[t] === true : state.filters[t] === false;
        });
        if (onlyThis) TYPE_ORDER.forEach(function (t) { state.filters[t] = true; });
        else TYPE_ORDER.forEach(function (t) { state.filters[t] = (t === type); });
      }
      syncFilterChips();
      renderFeed(true);
      if (el.feedList) { el.feedList.scrollTop = el.feedList.scrollHeight; state.autoScroll = true; }
      hideJumpFab();
    });
    syncFilterChips();
  }

  function syncFilterChips() {
    if (!el.feedFilters) return;
    var allOn = TYPE_ORDER.every(function (t) { return state.filters[t]; });
    U.$$('#feedFilters .chip').forEach(function (chip) {
      var type = chip.getAttribute('data-type');
      var active = type === '__all__' ? allOn : !!state.filters[type];
      chip.classList.toggle('active', active);
    });
  }

  function eventText(ev) {
    if (ev.text && String(ev.text).trim()) return M.render(ev.text);
    if (ev.type === 'join') return '<span style="color:#8ff2b6">加入了服务器</span>';
    if (ev.type === 'leave') return '<span style="color:#8d99ad">离开了服务器</span>';
    return '<span class="dim">（无内容）</span>';
  }

  function buildFeedItem(ev) {
    var meta = TYPE_META[ev.type] || { label: ev.type, icon: '•' };
    var item = document.createElement('div');
    item.className = 'feed-item t-' + ev.type;
    item.setAttribute('data-key', ev.ts + '|' + ev.type);

    var time = document.createElement('div');
    time.className = 'feed-time';
    time.textContent = U.fmtTime(ev.ts);
    time.title = U.fmtDateTime(ev.ts);

    var icon = document.createElement('div');
    icon.className = 'feed-icon';
    icon.textContent = meta.icon;
    icon.title = meta.label;
    icon.setAttribute('aria-hidden', 'true');

    var main = document.createElement('div');
    main.className = 'feed-main';
    if (ev.player) {
      // 玩家名统一走 UI.buildPlayerName：颜色标记安全渲染、长名不截断/可换行、title+点击看完整名
      main.appendChild(UI.buildPlayerName(ev.player, { className: 'feed-player', playerId: ev.playerId }));
    }
    var text = document.createElement('span');
    text.className = 'feed-text';
    text.innerHTML = eventText(ev); // 安全：eventText 已走 PortalMarkup.render
    main.appendChild(text);

    item.appendChild(time);
    item.appendChild(icon);
    item.appendChild(main);
    return item;
  }

  function buildDateDivider(ts) {
    var div = document.createElement('div');
    div.className = 'feed-divider';
    var span = document.createElement('span');
    span.className = 'feed-date';
    var date = U.fmtDate(ts);
    span.textContent = date === U.fmtDate(Date.now()) ? '今天 · ' + date : date;
    div.appendChild(span);
    return div;
  }

  function filteredEvents() {
    return state.events.filter(function (ev) { return state.filters[ev.type] !== false; });
  }

  function renderFeed(full) {
    if (!el.feedList) return;
    var list = filteredEvents();
    if (full) {
      el.feedList.innerHTML = '';
      state.renderedKeys = Object.create(null);
      state.lastRenderedDate = '';
    }
    U.setText(el.feedCount, '共 ' + U.fmtInt(state.events.length) + ' 条消息（当前显示 ' + U.fmtInt(list.length) + ' 条）');
    if (!list.length) {
      if (!el.feedList.querySelector('.feed-empty')) {
        el.feedList.innerHTML = '<div class="feed-empty"><span class="big">💬</span>暂时没有符合筛选条件的消息' +
          (state.available === false ? '<br>游戏内事件流尚未启用，服务器产生事件后会自动出现。' : '') + '</div>';
      }
      return;
    }
    var placeholder = el.feedList.querySelector('.loading-block, .feed-empty');
    if (placeholder) placeholder.remove();

    var frag = document.createDocumentFragment();
    var appended = 0;
    list.forEach(function (ev) {
      var key = eventKey(ev);
      if (!full && state.renderedKeys[key]) return; // 增量渲染：跳过已渲染事件
      state.renderedKeys[key] = 1;
      var date = U.fmtDate(ev.ts);
      if (date !== state.lastRenderedDate) {
        frag.appendChild(buildDateDivider(ev.ts));
        state.lastRenderedDate = date;
      }
      frag.appendChild(buildFeedItem(ev));
      appended++;
    });
    if (appended) el.feedList.appendChild(frag);

    // DOM 行数上限，避免超长时间运行页面变卡
    var rows = el.feedList.querySelectorAll('.feed-item');
    if (rows.length > MAX_FEED_DOM) {
      var removeCount = rows.length - MAX_FEED_DOM;
      for (var i = 0; i < removeCount; i++) {
        if (rows[i].parentNode) rows[i].parentNode.removeChild(rows[i]);
      }
    }
    if (state.autoScroll) {
      el.feedList.scrollTop = el.feedList.scrollHeight;
      hideJumpFab();
    }
  }

  function ingestEvents(events) {
    var added = 0;
    events.forEach(function (ev) {
      if (!ev || !ev.ts) return;
      var key = eventKey(ev);
      if (state.seen[key]) return;
      state.seen[key] = 1;
      state.seenQueue.push(key);
      if (state.seenQueue.length > 1200) {
        delete state.seen[state.seenQueue.shift()];
      }
      state.events.push(ev);
      if (state.filters[ev.type] !== false) added++;
    });
    if (state.events.length > MAX_FEED_EVENTS) {
      state.events = state.events.slice(-MAX_FEED_EVENTS);
    }
    if (added && !state.autoScroll && state.initialLoaded) {
      state.unread += added;
      showJumpFab();
    }
    if (added || !state.initialLoaded) renderFeed(!state.initialLoaded);
    state.initialLoaded = true;
    if (el.feedList && state.autoScroll) el.feedList.scrollTop = el.feedList.scrollHeight;
  }

  function showJumpFab() {
    if (!el.feedJumpFab) return;
    el.feedJumpFab.hidden = false;
    el.feedJumpFab.textContent = (state.unread > 0 ? state.unread + ' 条新消息' : '有新消息') + ' ↓';
  }
  function hideJumpFab() {
    state.unread = 0;
    if (el.feedJumpFab) el.feedJumpFab.hidden = true;
  }

  function updatePollInfo() {
    if (!el.feedPollInfo) return;
    var base = '每 ' + (POLL_MS / 1000) + ' 秒增量拉取';
    if (state.paused) base = '已暂停轮询';
    else if (state.failures > 0) base = '连接异常，自动重试中（第 ' + state.failures + ' 次）';
    el.feedPollInfo.textContent = base;
  }

  function scheduleFeed() {
    clearTimeout(state.feedTimer);
    if (state.paused) return;
    var delay = state.failures >= 3 ? Math.min(15000, POLL_MS * 2) : POLL_MS;
    state.feedTimer = setTimeout(feedTick, delay);
  }

  function feedTick() {
    if (state.paused || state.loading) return;
    if (document.hidden) { scheduleFeed(); return; }
    state.loading = true;
    Api.events(state.lastTs, 100).then(function (res) {
      state.loading = false;
      state.failures = 0;
      state.available = res.available !== false;
      U.setText(el.feedAvailableNote, state.available ? '' : '实时文件暂缺');
      if (!state.available) {
        // 后端最终契约：主事件文件可能因轮转暂时缺失（available:false），但历史文件仍可用
        var histOk = res.historyAvailable === true;
        setFeedNotice('warn',
          histOk ? '实时事件文件暂缺（可能刚轮转），历史消息仍可查看' : '游戏内实时事件流尚未启用',
          res.hint || (histOk
            ? '新事件产生后实时流会自动恢复；可点上方「🕘 历史」浏览已归档消息。'
            : '服务器脚本 liveEventFeed.kts 写出 config/stats/live-events.jsonl 后，消息会自动出现。'));
        feedPill(histOk ? '实时文件暂缺' : '事件流未启用', 'warn');
      } else {
        setFeedNotice();
        if (!state.paused) feedPill(state.lastTs ? '实时连接中' : '连接中…', 'live');
      }
      if (!res.available) state.lastTs = state.lastTs || 0;
      if (res.events && res.events.length) {
        ingestEvents(res.events);
        var maxTs = res.events.reduce(function (acc, ev) { return Math.max(acc, ev.ts); }, 0);
        if (!state.lastTs || maxTs > state.lastTs) state.lastTs = maxTs;
      } else if (!state.lastTs && res.lastTs) {
        state.lastTs = res.lastTs;
      }
      if (state.available && !state.paused) feedPill('实时连接中', 'live');
      updatePollInfo();
      scheduleFeed();
    }).catch(function (err) {
      state.loading = false;
      state.failures++;
      feedPill('连接异常', 'off');
      updatePollInfo();
      var info = Api.errorInfo(err);
      if (state.failures >= 2) {
        setFeedNotice('error', info.title, info.hint + '（将持续自动重试）');
      }
      scheduleFeed();
    });
  }

  function setupFeedControls() {
    buildFilterChips();
    UI.clearNotice(el.feedNotice);

    if (el.feedPauseBtn) {
      el.feedPauseBtn.addEventListener('click', function () {
        state.paused = !state.paused;
        el.feedPauseBtn.textContent = state.paused ? '▶ 继续' : '⏸ 暂停';
        el.feedPauseBtn.setAttribute('aria-pressed', state.paused ? 'true' : 'false');
        el.feedPauseBtn.classList.toggle('primary', state.paused);
        if (state.paused) {
          feedPill('已暂停', 'paused');
          clearTimeout(state.feedTimer);
        } else {
          feedPill('连接中…', '');
          feedTick();
        }
        updatePollInfo();
      });
    }
    if (el.feedRefreshLink) {
      el.feedRefreshLink.addEventListener('click', function (e) {
        e.preventDefault();
        feedTick();
      });
    }
    [el.feedJumpBtn, el.feedJumpFab].forEach(function (btn) {
      if (!btn) return;
      btn.addEventListener('click', function () {
        if (!el.feedList) return;
        state.autoScroll = true;
        el.feedList.scrollTop = el.feedList.scrollHeight;
        hideJumpFab();
      });
    });
    if (el.feedList) {
      el.feedList.addEventListener('scroll', function () {
        state.autoScroll = isAtBottom(el.feedList);
        if (state.autoScroll) hideJumpFab();
      }, { passive: true });
    }
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && !state.paused) {
        clearTimeout(state.feedTimer);
        feedTick();
      }
    });
  }

  /* ----------------------------- 历史消息浏览（第二期） ----------------------------- */
  function historyTypeOptions() {
    var sel = el.historyTypeSelect;
    if (!sel || sel.getAttribute('data-built') === '1') return;
    TYPE_ORDER.forEach(function (type) {
      var meta = TYPE_META[type];
      var opt = document.createElement('option');
      opt.value = type;
      opt.textContent = meta.icon + ' ' + meta.label;
      sel.appendChild(opt);
    });
    sel.setAttribute('data-built', '1');
  }

  function historyRangeBounds(range) {
    var now = Date.now();
    if (range === '1h') return { fromTs: now - 3600000, toTs: now };
    if (range === '6h') return { fromTs: now - 6 * 3600000, toTs: now };
    if (range === 'today') {
      var d = new Date();
      return { fromTs: new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(), toTs: now };
    }
    return { fromTs: 0, toTs: 0 }; // 全部
  }

  function historyPill(text, cls) {
    setPill(el.historyPill, text, cls);
  }

  function historyNotice(kind, title, hint) {
    var node = el.historyNotice;
    if (!node) return;
    if (!title) { node.hidden = true; node.innerHTML = ''; return; }
    node.hidden = false;
    node.className = 'notice notice-' + (kind || 'error');
    node.innerHTML = '';
    var t = document.createElement('div');
    t.className = 'notice-title';
    t.textContent = title;
    node.appendChild(t);
    if (hint) {
      var h = document.createElement('div');
      h.className = 'notice-hint';
      h.textContent = hint;
      node.appendChild(h);
    }
  }

  function openHistory() {
    if (!el.historyOverlay) return;
    state.history.open = true;
    el.historyOverlay.hidden = false;
    document.documentElement.classList.add('drawer-open');
    historyTypeOptions();
    if (!state.history.loaded) searchHistory(false);
    else historyPill('已加载 ' + U.fmtInt(state.history.items.length) + ' 条', 'purple');
    if (el.historyCloseBtn && el.historyCloseBtn.focus) el.historyCloseBtn.focus();
  }

  function closeHistory() {
    if (!el.historyOverlay) return;
    state.history.open = false;
    el.historyOverlay.hidden = true;
    document.documentElement.classList.remove('drawer-open');
  }

  function historyItemNode(ev) {
    var meta = TYPE_META[ev.type] || { label: ev.type, icon: '•' };
    var row = document.createElement('div');
    row.className = 'history-item t-' + ev.type;
    var time = document.createElement('div');
    time.className = 'history-time';
    time.textContent = U.fmtDateTime(ev.ts);
    var icon = document.createElement('div');
    icon.className = 'history-icon';
    icon.textContent = meta.icon;
    icon.title = meta.label;
    icon.setAttribute('aria-hidden', 'true');
    var main = document.createElement('div');
    main.className = 'history-main';
    if (ev.player) main.appendChild(UI.buildPlayerName(ev.player, { className: 'history-player', playerId: ev.playerId }));
    var text = document.createElement('span');
    text.className = 'history-text';
    text.innerHTML = eventText(ev); // 安全：eventText 内部走 PortalMarkup.render
    main.appendChild(text);
    row.appendChild(time);
    row.appendChild(icon);
    row.appendChild(main);
    return row;
  }

  function renderHistoryItems(items, append) {
    if (!el.historyList) return;
    if (!append) el.historyList.innerHTML = '';
    var placeholder = el.historyList.querySelector('.loading-block, .feed-empty');
    if (placeholder) placeholder.remove();
    var frag = document.createDocumentFragment();
    (items || []).forEach(function (ev) { frag.appendChild(historyItemNode(ev)); });
    el.historyList.appendChild(frag);
    if (!state.history.items.length) {
      el.historyList.innerHTML = '<div class="feed-empty"><span class="big">🗂️</span>没有符合条件的历史消息' +
        '<div class="dim small" style="margin-top:6px">可以放宽时间范围、切换类型或清空关键词再试</div></div>';
    }
    U.setText(el.historyCount, '已加载 ' + U.fmtInt(state.history.items.length) + ' 条' +
      (state.history.hasMore ? '（还有更早的）' : '（已到最早）'));
    if (el.historyMoreBtn) {
      el.historyMoreBtn.hidden = !state.history.hasMore;
      el.historyMoreBtn.disabled = state.history.loading;
    }
  }

  function searchHistory(append) {
    if (!el.historyList) return;
    var bounds = historyRangeBounds(state.history.range);
    var keyword = el.historyKeyword ? el.historyKeyword.value.trim() : '';
    var type = el.historyTypeSelect ? el.historyTypeSelect.value : '';
    var maxKeyword = (SITE.history && SITE.history.maxKeywordChars) || 100;
    if (keyword.length > maxKeyword) {
      historyNotice('warn', '关键词过长', '最多 ' + maxKeyword + ' 个字符，请缩短后重试。');
      return;
    }
    // 请求序号：快速切换筛选时只应用「最后一次」查询结果（避免旧响应覆盖新筛选）
    state.history.requestSeq = (state.history.requestSeq || 0) + 1;
    var seq = state.history.requestSeq;
    state.history.keyword = keyword;
    state.history.type = type;
    state.history.loading = true;
    setPill(el.historyPill, append ? '加载更多…' : '查询中…', 'warn');
    historyNotice();
    if (el.historyMoreBtn) el.historyMoreBtn.disabled = true;
    if (!append) {
      el.historyList.innerHTML = '<div class="loading-block"><span class="spinner"></span>正在读取历史消息…</div>';
      U.setText(el.historyCount, '--');
    }
    Api.eventsHistory({
      fromTs: bounds.fromTs,
      toTs: bounds.toTs,
      type: type,
      keyword: keyword,
      limit: (SITE.history && SITE.history.pageSize) || 100,
      cursor: append ? state.history.cursor : 0
    }).then(function (res) {
      if (seq !== state.history.requestSeq) return; // 已有更新的查询，丢弃旧响应
      state.history.loading = false;
      if (!res.available) {
        setPill(el.historyPill, '历史不可用', 'off');
        state.history.loaded = true;
        renderHistoryItems([], append);
        historyNotice('warn', '历史事件文件不可用', '服务器尚未生成 live-events.jsonl，或历史文件已被轮转清理。');
        return;
      }
      var items = res.items || [];
      state.history.items = append ? state.history.items.concat(items) : items;
      state.history.cursor = res.nextCursor || (items.length ? items[items.length - 1].ts : 0);
      state.history.hasMore = res.hasMore === true;
      state.history.loaded = true;
      renderHistoryItems(items, append);
      setPill(el.historyPill, state.history.hasMore ? '还有更早' : '已到最早', state.history.hasMore ? '' : 'on');
    }).catch(function (err) {
      if (seq !== state.history.requestSeq) return;
      state.history.loading = false;
      var info = Api.errorInfo(err);
      setPill(el.historyPill, '查询失败', 'off');
      if (!append) {
        state.history.items = [];
        renderHistoryItems([], false);
      }
      var hint = info.hint || '';
      if (err && err.retryAfter) hint += '（约 ' + err.retryAfter + ' 秒后可重试）';
      historyNotice('error', info.title, hint);
      UI.toast(info.title, 'error');
    });
  }

  function setupHistory() {
    if (!el.feedHistoryBtn || !el.historyOverlay) return;
    el.feedHistoryBtn.addEventListener('click', openHistory);
    if (el.historyCloseBtn) el.historyCloseBtn.addEventListener('click', closeHistory);
    el.historyOverlay.addEventListener('click', function (e) {
      if (e.target === el.historyOverlay) closeHistory();
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && state.history.open) { e.preventDefault(); closeHistory(); }
    });
    if (el.historyRangeSeg) {
      el.historyRangeSeg.addEventListener('click', function (e) {
        var btn = e.target.closest ? e.target.closest('button[data-range]') : null;
        if (!btn) return;
        state.history.range = btn.getAttribute('data-range') || '1h';
        U.$$('#historyRangeSeg button').forEach(function (b) { b.classList.toggle('active', b === btn); });
        searchHistory(false);
      });
    }
    if (el.historySearchBtn) el.historySearchBtn.addEventListener('click', function () { searchHistory(false); });
    if (el.historyTypeSelect) el.historyTypeSelect.addEventListener('change', function () { searchHistory(false); });
    if (el.historyKeyword) {
      el.historyKeyword.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') { e.preventDefault(); searchHistory(false); }
      });
    }
    if (el.historyMoreBtn) el.historyMoreBtn.addEventListener('click', function () { searchHistory(true); });
  }

  /* ------------------------------- 初始化 ------------------------------- */
  function bindEvents() {
    if (el.refreshStatusBtn) {
      el.refreshStatusBtn.addEventListener('click', function () {
        var btn = el.refreshStatusBtn;
        UI.setBusy(btn, true, '刷新中…');
        loadStatus().then(function () {
          UI.setBusy(btn, false);
          UI.toast('服务器状态已刷新', 'success');
        }).catch(function () {
          UI.setBusy(btn, false);
        });
      });
    }
    if (el.viewSeg) {
      el.viewSeg.addEventListener('click', function (e) {
        var btn = e.target.closest ? e.target.closest('button[data-view]') : null;
        if (btn) switchView(btn.getAttribute('data-view'));
      });
    }
    state.statusTimer = setInterval(function () {
      if (!document.hidden) loadStatus().catch(function () { /* 静默，错误已展示 */ });
    }, STATUS_MS);
    global.addEventListener('resize', U.debounce(function () {
      if (state.status) renderCharts(state.status);
      if (state.net) renderNet(state.net);
    }, 220));
    global.addEventListener('portal:unauthorized', function () { /* 首页不需要登录，忽略 */ });
  }

  function boot() {
    cacheEls();
    setupHero();
    renderMotd(SITE.motd);
    if (el.feedList) {
      el.feedList.innerHTML = '<div class="loading-block"><span class="spinner"></span>正在连接服务器消息流…</div>';
    }
    setupFeedControls();
    setupHistory();
    bindEvents();
    switchView('resume');

    Api.ready().then(function () {
      loadStatus().then(function (st) {
        if (!st.motd) renderMotd(SITE.motd);
      }).catch(function () { /* 已提示 */ });
      feedTick();
    }).catch(function (err) {
      UI.toast('初始化失败：' + (err && err.message ? err.message : err), 'error');
      feedPill('初始化失败', 'off');
      if (el.feedList) {
        el.feedList.innerHTML = '<div class="feed-empty"><span class="big">⚠</span>初始化失败，请刷新页面重试</div>';
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})(window);
