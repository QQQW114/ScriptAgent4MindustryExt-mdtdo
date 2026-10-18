/* ============================================================================
 * MDT DO 服务器门户 - 管理后台
 *  - 登录 / 登出（token 存 sessionStorage，支持同账号多 IP 登录）
 *  - 待审列表：通过（DP 可选热重载）/ 拒绝（必填理由）
 *  - 现有 DP / 地图列表：大小 / 作者 / 更新时间 / 移除
 *  - 最近处理记录；401 自动回到登录表单
 * ==========================================================================*/
(function (global) {
  'use strict';

  var U = global.PortalUtil;
  var Api = global.PortalApi;
  var UI = global.PortalUI;
  var SITE = global.PORTAL_SITE || {};
  var LAST_USER_KEY = 'mdt_portal_last_user';

  var LOG_KIND_LABELS = {
    'game-log': '游戏日志',
    'console': '控制台日志',
    'last': '上次启动日志',
    'restart-supervisor': '重启监督',
    'other': '其它'
  };

  var state = {
    reviewKind: '',
    review: null,
    catalog: { dp: [], maps: [] },
    catalogTab: 'dp',
    loading: false,
    loginSubmitting: false,
    tokenTimer: null,
    dashboardReady: false,
    logs: {
      items: [],
      available: false,
      requireLogin: true,
      maxFileBytes: 50 * 1024 * 1024,
      loaded: false,
      loading: false,
      updatedAt: ''
    },
    consoleInfo: null,
    consoleNoticeUntil: 0
  };
  var el = {};

  function cacheEls() {
    ['loginView', 'dashboardView', 'loginForm', 'loginUser', 'loginPass', 'loginBtn', 'loginError',
      'loginFormError', 'mockLoginHint', 'adminName', 'tokenLine', 'adminRefreshBtn', 'logoutBtn',
      'adminError', 'reviewCountPill', 'reviewKindSeg', 'reviewRefreshBtn', 'reviewQueueHead',
      'reviewQueueBar', 'reviewQueueSplit', 'reviewList', 'catalogSeg', 'catalogRefreshBtn',
      'catalogBody', 'historyCount', 'historyList',
      'logsPill', 'logsRefreshBtn', 'logsUpdatedAt', 'logsError', 'logsList', 'logsMockTools',
      'mockLogsBusyBtn', 'mockLogsRateBtn',
      'consolePill', 'consoleWarning', 'consoleWarningText', 'mockConsoleToggle'].forEach(function (id) {
      el[id] = document.getElementById(id);
    });
  }

  function isMapCatalog() { return state.catalogTab === 'maps'; }

  /* -------------------------------- 视图切换 -------------------------------- */
  function showLogin(message) {
    if (el.dashboardView) el.dashboardView.hidden = true;
    if (el.loginView) el.loginView.hidden = false;
    stopTokenTimer();
    state.dashboardReady = false;
    var last = U.storage.get(LAST_USER_KEY);
    if (el.loginUser) {
      if (!el.loginUser.value && last) el.loginUser.value = last;
      if (el.loginUser.focus && !el.loginPass.value) el.loginUser.focus();
    }
    if (message) UI.toast(message, 'warn');
  }

  function showDashboard() {
    if (el.loginView) el.loginView.hidden = true;
    if (el.dashboardView) el.dashboardView.hidden = false;
    U.setText(el.adminName, Session().username() || 'admin');
    startTokenTimer();
    state.dashboardReady = true;
  }

  function Session() { return Api.Session; }

  function startTokenTimer() {
    stopTokenTimer();
    updateTokenLine();
    state.tokenTimer = setInterval(function () {
      if (!Session().hasToken()) { stopTokenTimer(); return; }
      if (Session().isExpired()) {
        forceLogout('登录已过期，请重新登录');
        return;
      }
      updateTokenLine();
    }, 20000);
  }
  function stopTokenTimer() {
    if (state.tokenTimer) { clearInterval(state.tokenTimer); state.tokenTimer = null; }
  }
  function updateTokenLine() {
    if (!el.tokenLine) return;
    if (!Session().hasToken()) { el.tokenLine.textContent = ''; return; }
    var exp = Session().expiresAt();
    if (!exp) { el.tokenLine.textContent = '登录有效期：--'; return; }
    var remain = exp - Date.now();
    if (remain <= 0) { el.tokenLine.textContent = '登录已过期'; return; }
    el.tokenLine.textContent = '登录有效期剩余 ' + U.fmtCountdown(remain) + '（自动退出）';
  }

  /* --------------------------------- 登录 --------------------------------- */
  function handleLogin(e) {
    if (e) e.preventDefault();
    if (state.loginSubmitting) return;
    var username = el.loginUser ? el.loginUser.value.trim() : '';
    var password = el.loginPass ? el.loginPass.value : '';
    var formError = el.loginFormError;
    if (formError) formError.textContent = '';
    if (!username) { if (formError) formError.textContent = '请填写管理员账号'; el.loginUser.focus(); return; }
    if (!password) { if (formError) formError.textContent = '请填写密码'; el.loginPass.focus(); return; }

    state.loginSubmitting = true;
    UI.setBusy(el.loginBtn, true, '登录中…');
    Api.ready().then(function () {
      return Api.login(username, password);
    }).then(function (data) {
      Session().save(data);
      U.storage.set(LAST_USER_KEY, username, 'session');
      state.loginSubmitting = false;
      UI.setBusy(el.loginBtn, false);
      if (el.loginPass) el.loginPass.value = '';
      UI.clearNotice(el.loginError);
      showDashboard();
      UI.toast('登录成功，欢迎回来', 'success');
      loadAll();
    }).catch(function (err) {
      state.loginSubmitting = false;
      UI.setBusy(el.loginBtn, false);
      var info = Api.errorInfo(err);
      UI.showError(el.loginError, err);
      if (formError && err && err.code === 'RATE_LIMITED') formError.textContent = '登录失败次数过多，请稍后再试';
      UI.toast(info.title, 'error');
    });
  }

  function confirmLogout() {
    UI.confirm({
      title: '退出登录',
      message: '确定要退出管理员登录吗？退出后需要重新输入账号密码。',
      confirmText: '退出登录'
    }).then(function (res) {
      if (!res || !res.ok) return;
      doLogout(true);
    });
  }

  function doLogout(silent) {
    var finish = function () {
      Session().clear();
      showLogin();
      if (!silent) UI.toast('已退出登录', 'success');
    };
    if (Session().hasToken()) {
      Api.logout().then(finish, finish);
    } else {
      finish();
    }
  }

  function forceLogout(message) {
    Session().clear();
    showLogin(message || '登录已失效，请重新登录');
  }

  /* ------------------------------- 数据加载 ------------------------------- */
  function handleAdminError(err) {
    var info = Api.errorInfo(err);
    if (err && (err.code === 'UNAUTHORIZED' || err.code === 'SESSION_EXPIRED')) {
      forceLogout(info.hint || info.title);
      return;
    }
    UI.showError(el.adminError, err, loadAll);
    UI.toast(info.title, 'error');
  }

  function loadAll() {
    if (!Session().hasToken() || Session().isExpired()) { forceLogout('登录已过期，请重新登录'); return Promise.resolve(); }
    if (state.loading) return Promise.resolve();
    state.loading = true;
    UI.clearNotice(el.adminError);
    UI.setBusy(el.adminRefreshBtn, true, '刷新中…');
    var jobs = [loadReview(), loadCatalog(), loadLogs(), loadHealth()];
    return Promise.all(jobs.map(function (p) {
      return p.catch(function () { /* 各自已处理 */ });
    })).then(function () {
      state.loading = false;
      UI.setBusy(el.adminRefreshBtn, false);
    });
  }

  /**
   * 后端 /api/review/list 的 kind 默认 dp（必须显式指定），
   * 因此这里始终并行拉取 dp + map，再在前端按筛选合并展示。
   */
  function loadReview() {
    UI.clearNotice(el.adminError);
    return Promise.all([Api.reviewList('dp'), Api.reviewList('map')]).then(function (results) {
      var dp = results[0] || { items: [], history: [], queue: null };
      var map = results[1] || { items: [], history: [], queue: null };
      var dpQueue = queueParts(dp.queue) || { limit: 10, pending: 0 };
      var mapQueue = queueParts(map.queue) || { limit: 10, pending: 0 };
      var merged = {
        items: mergeByTime(dp.items || [], map.items || [], 'createdAt'),
        history: mergeByTime(dp.history || [], map.history || [], 'reviewedAt'),
        queue: {
          limit: (dpQueue.limit || 10),
          pending: (dpQueue.pending || 0) + (mapQueue.pending || 0),
          byKind: { dp: dpQueue.pending || 0, map: mapQueue.pending || 0 },
          limits: { dp: dpQueue.limit || 10, map: mapQueue.limit || 10 }
        }
      };
      state.review = merged;
      renderReviewQueue(merged.queue);
      renderReview(merged.items.filter(function (it) { return !state.reviewKind || it.kind === state.reviewKind; }));
      renderHistory(merged.history.filter(function (it) { return !state.reviewKind || it.kind === state.reviewKind; }));
    }).catch(handleAdminError);
  }

  function mergeByTime(a, b, timeField) {
    return a.concat(b).sort(function (x, y) {
      return (Number(y && y[timeField]) || 0) - (Number(x && x[timeField]) || 0);
    });
  }

  function loadCatalog() {
    return Api.catalog().then(function (cat) {
      state.catalog = { dp: cat.dp || [], maps: cat.maps || [] };
      renderCatalog();
    }).catch(handleAdminError);
  }

  /* ------------------------------- 待审渲染 ------------------------------- */
  function queueParts(queue) {
    if (!queue) return null;
    var limit = Number(queue.limit) || 10;
    var pendingRaw = queue.pending;
    var pending = null;
    var byKind = queue.byKind || null;
    if (pendingRaw && typeof pendingRaw === 'object') {
      byKind = pendingRaw;
      pending = Object.keys(byKind).reduce(function (sum, k) { return sum + (Number(byKind[k]) || 0); }, 0);
    } else if (pendingRaw != null) {
      pending = Number(pendingRaw);
    }
    if (pending == null) return null;
    return { limit: limit, pending: pending, byKind: byKind };
  }

  function renderReviewQueue(queue) {
    var parts = queueParts(queue);
    var items = state.review && Array.isArray(state.review.items) ? state.review.items : [];
    if (el.reviewCountPill) {
      el.reviewCountPill.textContent = '当前显示 ' + items.length + ' 件待审';
      el.reviewCountPill.className = 'pill ' + (items.length ? 'warn' : 'on');
    }
    if (!el.reviewQueueHead) return;
    if (!parts) {
      el.reviewQueueHead.textContent = '后端未返回队列计数，仅显示待审条目。';
      if (el.reviewQueueBar) el.reviewQueueBar.style.width = '0%';
      if (el.reviewQueueSplit) el.reviewQueueSplit.textContent = '';
      return;
    }
    var byKind = parts.byKind || null;
    var limits = (queue && queue.limits) || { dp: parts.limit, map: parts.limit };
    var totalLimit = (limits.dp || parts.limit) + (limits.map || parts.limit);
    var full = parts.pending >= totalLimit;
    el.reviewQueueHead.innerHTML = '待审队列 <b>' + U.fmtInt(parts.pending) + '</b> / <b>' + U.fmtInt(totalLimit) + '</b> 件（DP 与地图各自上限）' +
      (full ? ' <span class="pill off" style="padding:2px 8px">已满 · 新投稿会被拒绝</span>' : '');
    if (el.reviewQueueBar) {
      var pct = U.clamp(parts.pending / Math.max(1, totalLimit) * 100, 0, 100);
      el.reviewQueueBar.style.width = pct.toFixed(1) + '%';
      el.reviewQueueBar.style.background = full ? 'var(--red)' : '';
    }
    if (el.reviewQueueSplit) {
      if (byKind) {
        el.reviewQueueSplit.innerHTML = ['dp', 'map'].map(function (k) {
          var label = k === 'dp' ? 'DP' : '地图';
          return '<span>' + label + '：' + U.fmtInt(byKind[k] || 0) + ' / ' + U.fmtInt(limits[k] || parts.limit) + ' 件</span>';
        }).join('');
      } else {
        el.reviewQueueSplit.textContent = '队列已满时请先处理现有投稿。';
      }
    }
  }

  function metaChip(text, cls) {
    var span = document.createElement('span');
    span.className = cls || 'dim small';
    span.textContent = text;
    return span;
  }

  function buildReviewItem(item) {
    var wrap = document.createElement('div');
    wrap.className = 'review-item';
    wrap.setAttribute('data-id', String(item.id));

    var head = document.createElement('div');
    head.className = 'ri-head';
    var kindBadge = document.createElement('span');
    kindBadge.className = 'badge-kind ' + (item.kind === 'map' ? 'map' : 'dp');
    kindBadge.textContent = item.kind === 'map' ? '地图' : 'DP';
    var title = document.createElement('span');
    title.className = 'ri-title';
    title.textContent = item.title || '（无标题）';
    var status = document.createElement('span');
    status.className = 'badge-status pending';
    status.textContent = '待审核';
    head.appendChild(kindBadge);
    head.appendChild(title);
    head.appendChild(status);
    wrap.appendChild(head);

    var meta = document.createElement('div');
    meta.className = 'ri-meta';
    var createdText = item.createdAtText || (item.createdAt ? U.fmtDateTime(item.createdAt) : '—');
    meta.appendChild(metaChip('作者：' + (item.author || '—')));
    meta.appendChild(metaChip('文件：' + (item.fileName || '—')));
    meta.appendChild(metaChip('大小：' + (item.size != null ? U.fmtBytes(item.size) : '—')));
    meta.appendChild(metaChip('提交：' + createdText));
    if (item.replaceOf) meta.appendChild(metaChip('替换：' + item.replaceOf, 'pill purple small'));
    wrap.appendChild(meta);

    if (item.desc) {
      var desc = document.createElement('div');
      desc.className = 'ri-desc';
      desc.textContent = item.desc;
      wrap.appendChild(desc);
    }

    var actions = document.createElement('div');
    actions.className = 'ri-actions';
    var approve = document.createElement('button');
    approve.type = 'button';
    approve.className = 'btn small success';
    approve.textContent = '✓ 通过';
    var reject = document.createElement('button');
    reject.type = 'button';
    reject.className = 'btn small danger';
    reject.textContent = '✕ 拒绝';
    actions.appendChild(approve);
    actions.appendChild(reject);
    wrap.appendChild(actions);

    approve.addEventListener('click', function () { doApprove(item, approve, reject); });
    reject.addEventListener('click', function () { doReject(item, reject, approve); });
    return wrap;
  }

  function renderReview(items) {
    if (!el.reviewList) return;
    el.reviewList.innerHTML = '';
    if (!items || !items.length) {
      var empty = document.createElement('div');
      empty.className = 'table-empty';
      empty.innerHTML = '<div style="font-size:26px;margin-bottom:8px">🎉</div>暂无待审投稿' +
        (state.reviewKind ? '（当前筛选：' + (state.reviewKind === 'dp' ? 'DP' : '地图') + '）' : '') +
        '<div class="dim small" style="margin-top:6px">玩家投稿后会出现在这里</div>';
      el.reviewList.appendChild(empty);
      return;
    }
    items.forEach(function (item) { el.reviewList.appendChild(buildReviewItem(item)); });
  }

  function doApprove(item, approveBtn, rejectBtn) {
    var isDp = item.kind !== 'map';
    UI.confirm({
      title: '通过审核',
      message: '确定通过「' + (item.title || '') + '」的投稿吗？通过后会写入服务器列表。',
      detail: item.replaceOf ? '将替换现有条目：' + item.replaceOf : '作为新条目加入列表。',
      confirmText: '通过并入库',
      checkbox: isDp ? { label: '通过后触发 DP 热重载（调用游戏控制台 externalcp load）', checked: false } : null
    }).then(function (res) {
      if (!res || !res.ok) return;
      UI.setBusy(approveBtn, true, '处理中…');
      UI.setBusy(rejectBtn, true);
      Api.approve(item.id, !!res.checked).then(function (data) {
        UI.setBusy(approveBtn, false);
        UI.setBusy(rejectBtn, false);
        var warningList = (data && Array.isArray(data.warnings)) ? data.warnings.filter(Boolean) : [];
        var consoleWarning = data && (data.consoleWarning || data.reloadHint || (warningList.length ? warningList.join('；') : ''));
        var msg = '已通过：' + ((data && data.finalName) || item.title);
        if (data && data.replaced) msg += '，已替换旧文件 ' + data.replaced;
        UI.toast(msg, 'success');
        if (consoleWarning) {
          // 审核默认成功、文件已就位；控制台未开启只是信息提示（重启游戏服后生效），不是失败
          showApproveConsoleWarning(consoleWarning, data && data.finalName);
        } else {
          var reloadInfo = data && data.reload;
          if (reloadInfo && reloadInfo.dispatched) {
            UI.toast(item.kind === 'map' ? '已触发地图列表刷新' : '已触发 DP 热重载', 'success');
          } else if (reloadInfo && reloadInfo.note && !/不可达|未开启|未送达/.test(reloadInfo.note)) {
            UI.toast(reloadInfo.note, 'info');
          } else if (reloadInfo && reloadInfo.note) {
            showApproveConsoleWarning(reloadInfo.note, data && data.finalName);
          }
        }
        refreshAfterAction();
        loadHealth().catch(function () { /* 控制台状态非关键 */ });
      }).catch(function (err) {
        UI.setBusy(approveBtn, false);
        UI.setBusy(rejectBtn, false);
        var info = Api.errorInfo(err);
        UI.toast(info.title, 'error');
        handleAdminError(err);
        refreshAfterAction();
      });
    });
  }

  function doReject(item, rejectBtn, approveBtn) {
    UI.prompt({
      title: '拒绝投稿',
      message: '正在拒绝「' + (item.title || '') + '」。拒绝理由会记录在历史中。',
      label: '拒绝理由（必填）',
      placeholder: '例如：文件无法在服务器加载 / 内容不符合服务器规则 / 缺少必要介绍',
      required: true,
      maxLength: 500,
      confirmText: '确认拒绝'
    }).then(function (res) {
      if (!res || !res.ok) return;
      UI.setBusy(rejectBtn, true, '处理中…');
      UI.setBusy(approveBtn, true);
      Api.reject(item.id, res.value).then(function () {
        UI.setBusy(rejectBtn, false);
        UI.setBusy(approveBtn, false);
        UI.toast('已拒绝该投稿', 'success');
        refreshAfterAction();
      }).catch(function (err) {
        UI.setBusy(rejectBtn, false);
        UI.setBusy(approveBtn, false);
        var info = Api.errorInfo(err);
        UI.toast(info.title, 'error');
        handleAdminError(err);
        refreshAfterAction();
      });
    });
  }

  function refreshAfterAction() {
    loadReview().catch(function () { /* handled */ });
    loadCatalog().catch(function () { /* handled */ });
  }

  /* ------------------------------- 目录渲染 ------------------------------- */
  function renderCatalog() {
    if (!el.catalogBody) return;
    var key = state.catalogTab;
    var list = state.catalog[key] || [];
    U.$$('#catalogSeg button').forEach(function (btn) {
      var active = btn.getAttribute('data-cat') === key;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    el.catalogBody.innerHTML = '';
    if (!list.length) {
      var empty = document.createElement('div');
      empty.className = 'table-empty';
      empty.textContent = key === 'maps' ? '服务器地图目录为空' : 'DP 列表暂无条目';
      el.catalogBody.appendChild(empty);
      return;
    }
    var wrap = document.createElement('div');
    wrap.className = 'table-wrap';
    var table = document.createElement('table');
    table.className = 'data';
    var thead = document.createElement('thead');
    thead.innerHTML = '<tr><th>名称</th><th style="text-align:right">大小</th><th>作者</th><th>更新时间</th><th style="text-align:right">操作</th></tr>';
    table.appendChild(thead);
    var tbody = document.createElement('tbody');
    list.forEach(function (item) {
      var tr = document.createElement('tr');
      var nameTd = document.createElement('td');
      var nameDiv = document.createElement('div');
      nameDiv.style.color = 'var(--text)';
      nameDiv.textContent = item.name || '--';
      nameTd.appendChild(nameDiv);
      if (item.file && item.file !== item.name) {
        var fileDiv = document.createElement('div');
        fileDiv.className = 'dim small';
        fileDiv.textContent = item.file;
        nameTd.appendChild(fileDiv);
      }
      if (item.author || item.desc) {
        var descDiv = document.createElement('div');
        descDiv.className = 'dim small';
        // 第二期：介绍截断上限 80 → 160（2×），并加 title 全文（玩家名/介绍不被静默截断）
        var descText = item.desc ? String(item.desc) : '';
        descDiv.textContent = [item.author ? '作者：' + item.author : '', descText ? (descText.length > 160 ? descText.slice(0, 160) + '…' : descText) : ''].filter(Boolean).join(' · ');
        if (descText.length > 160) descDiv.title = descText;
        nameTd.appendChild(descDiv);
      }
      var sizeTd = document.createElement('td');
      sizeTd.className = 'num';
      sizeTd.textContent = item.size != null ? U.fmtBytes(item.size) : '--';
      var authorTd = document.createElement('td');
      authorTd.className = 'col-author';
      authorTd.textContent = item.author || '—';
      var timeTd = document.createElement('td');
      timeTd.className = 'dim small nowrap';
      timeTd.textContent = item.updatedAt || '--';
      var actionTd = document.createElement('td');
      actionTd.className = 'num';
      var opWrap = document.createElement('div');
      opWrap.className = 'actions';
      var remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'btn small danger';
      remove.textContent = '移除';
      remove.addEventListener('click', function () { doRemove(item, key, remove); });
      opWrap.appendChild(remove);
      actionTd.appendChild(opWrap);
      tr.appendChild(nameTd);
      tr.appendChild(sizeTd);
      tr.appendChild(authorTd);
      tr.appendChild(timeTd);
      tr.appendChild(actionTd);
      tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
    el.catalogBody.appendChild(wrap);
  }

  function doRemove(item, catKey, btn) {
    var kind = catKey === 'maps' ? 'map' : 'dp';
    var display = item.file || item.name || '';
    UI.confirm({
      title: '移除条目',
      message: '确定要从服务器移除「' + (item.name || display) + '」吗？',
      detail: '文件会从服务器对应目录删除，该操作不可撤销。',
      danger: true,
      confirmText: '确认移除',
      checkbox: kind === 'dp' ? { label: '同时尝试从游戏内卸载（调用控制台，可能失败但不影响删除）', checked: false } : null
    }).then(function (res) {
      if (!res || !res.ok) return;
      UI.setBusy(btn, true, '移除中…');
      Api.removeItem(kind, display, !!res.checked).then(function () {
        UI.setBusy(btn, false);
        UI.toast('已移除：' + (item.name || display), 'success');
        loadCatalog().catch(function () { /* handled */ });
      }).catch(function (err) {
        UI.setBusy(btn, false);
        var info = Api.errorInfo(err);
        UI.showError(el.adminError, err);
        UI.toast(info.title, 'error');
        loadCatalog().catch(function () { /* handled */ });
      });
    });
  }

  /* ------------------------------- 服务器日志 ------------------------------- */
  function logsKindLabel(kind) {
    return LOG_KIND_LABELS[kind] || (kind ? String(kind) : '其它');
  }

  function loadLogs() {
    if (!el.logsList) return Promise.resolve();
    if (state.logs.loading) return Promise.resolve();
    state.logs.loading = true;
    UI.clearNotice(el.logsError);
    UI.setBusy(el.logsRefreshBtn, true, '读取中…');
    return Api.logs().then(function (data) {
      state.logs.loading = false;
      UI.setBusy(el.logsRefreshBtn, false);
      state.logs.available = data.available;
      state.logs.requireLogin = data.requireLogin;
      state.logs.maxFileBytes = data.maxFileBytes;
      state.logs.items = data.items || [];
      state.logs.loaded = true;
      state.logs.updatedAt = new Date().toLocaleTimeString('zh-CN', { hour12: false });
      renderLogs();
    }).catch(function (err) {
      state.logs.loading = false;
      UI.setBusy(el.logsRefreshBtn, false);
      var info = Api.errorInfo(err);
      if (err && (err.code === 'UNAUTHORIZED' || err.code === 'SESSION_EXPIRED')) {
        // 401：默认需管理员登录；由统一 401 处理回登录页，这里再提示一次
        UI.showError(el.logsError, err, { hideRaw: true });
        UI.toast('日志接口需要管理员登录（公开模式除外）', 'warn');
        handleAdminError(err);
        return;
      }
      if (!state.logs.loaded) {
        el.logsList.innerHTML = '';
        var box = document.createElement('div');
        box.className = 'table-empty';
        box.textContent = '日志列表读取失败：' + info.title;
        el.logsList.appendChild(box);
      }
      UI.showError(el.logsError, err, { retry: loadLogs, hideRaw: true });
      UI.toast(info.title, 'error');
    });
  }

  function renderLogs() {
    if (!el.logsList) return;
    var items = state.logs.items || [];
    if (el.logsPill) {
      el.logsPill.textContent = state.logs.available
        ? (items.length + ' 个文件')
        : '不可用';
      el.logsPill.className = 'pill ' + (state.logs.available ? (items.length ? 'on' : '') : 'off');
    }
    U.setText(el.logsUpdatedAt, state.logs.updatedAt ? '读取于 ' + state.logs.updatedAt : '');
    el.logsList.innerHTML = '';
    if (!state.logs.available) {
      var empty = document.createElement('div');
      empty.className = 'table-empty';
      empty.textContent = '日志目录不存在或日志功能未启用（后端 logs.enabled / logs.dir 配置）。';
      el.logsList.appendChild(empty);
      return;
    }
    if (!items.length) {
      var empty2 = document.createElement('div');
      empty2.className = 'table-empty';
      empty2.textContent = '日志目录中暂无符合白名单的文件（log-N.txt / console-*.log / last.log / restart-supervisor.log）。';
      el.logsList.appendChild(empty2);
      return;
    }
    var list = document.createElement('div');
    list.className = 'log-list';
    items.forEach(function (item) {
      list.appendChild(buildLogRow(item));
    });
    el.logsList.appendChild(list);
    var tip = document.createElement('div');
    tip.className = 'form-hint';
    tip.style.marginTop = '10px';
    tip.textContent = '提示：下载按钮使用直链（URL 携带会话 token，同源、仅点击时使用，请勿分享链接）；预览只取尾部纯文本并已做 HTML 转义。' +
      '单文件上限 ' + U.fmtBytes(state.logs.maxFileBytes) + '，超出时接口返回 FILE_TOO_LARGE。';
    el.logsList.appendChild(tip);
  }

  function buildLogRow(item) {
    var row = document.createElement('div');
    row.className = 'log-row';
    row.setAttribute('data-name', item.name);

    var info = document.createElement('div');
    info.className = 'log-info';
    var nameLine = document.createElement('div');
    nameLine.className = 'log-name-line';
    var name = document.createElement('code');
    name.className = 'log-name';
    name.textContent = item.name;
    name.title = item.name;
    var kind = document.createElement('span');
    kind.className = 'badge-kind log-kind';
    kind.textContent = logsKindLabel(item.kind);
    kind.title = 'kind: ' + (item.kind || 'other');
    nameLine.appendChild(name);
    nameLine.appendChild(kind);
    info.appendChild(nameLine);
    var meta = document.createElement('div');
    meta.className = 'log-meta';
    meta.textContent = (item.size != null ? U.fmtBytes(item.size) : '--') + ' · ' +
      (item.modifiedAt || '--') +
      (item.size != null && state.logs.maxFileBytes && item.size > state.logs.maxFileBytes ? ' · 超过单文件上限' : '');
    info.appendChild(meta);
    row.appendChild(info);

    var actions = document.createElement('div');
    actions.className = 'log-actions';
    var preview = document.createElement('button');
    preview.type = 'button';
    preview.className = 'btn small ghost';
    preview.textContent = '尾部预览';
    preview.title = '读取该日志最后 ' + ((SITE.logs && SITE.logs.previewLines) || 200) + ' 行（纯文本，已转义）';
    var download = document.createElement('button');
    download.type = 'button';
    download.className = 'btn small primary';
    download.textContent = '下载';
    download.title = '直链下载（不把整个文件读进页面内存）';
    actions.appendChild(preview);
    actions.appendChild(download);
    row.appendChild(actions);

    var panel = document.createElement('div');
    panel.className = 'log-panel';
    panel.hidden = true;
    row.appendChild(panel);

    preview.addEventListener('click', function () { toggleLogPreview(item, panel, preview); });
    download.addEventListener('click', function () { doDownloadLog(item, download); });
    return row;
  }

  function toggleLogPreview(item, panel, btn) {
    if (!panel.hidden) { panel.hidden = true; btn.textContent = '尾部预览'; return; }
    panel.hidden = false;
    btn.textContent = '收起预览';
    if (panel.getAttribute('data-loaded') === '1') return;
    panel.innerHTML = '<div class="loading-block"><span class="spinner"></span>正在读取日志尾部…</div>';
    var lines = (SITE.logs && SITE.logs.previewLines) || 200;
    Api.logsTail(item.name, lines).then(function (data) {
      panel.innerHTML = '';
      var head = document.createElement('div');
      head.className = 'log-panel-head';
      head.textContent = '尾部 ' + data.lines.length + ' 行' + (data.truncated ? '（文件较大，仅显示末尾部分）' : '') +
        (data.size != null ? ' · 文件 ' + U.fmtBytes(data.size) : '');
      panel.appendChild(head);
      var pre = document.createElement('pre');
      pre.className = 'log-preview';
      pre.textContent = data.lines.length ? data.lines.join('\n') : '（空文件）';
      panel.appendChild(pre);
      panel.setAttribute('data-loaded', '1');
    }).catch(function (err) {
      var infoLogs = Api.errorInfo(err);
      panel.innerHTML = '';
      var head = document.createElement('div');
      head.className = 'log-panel-head is-error';
      head.textContent = '⚠ ' + infoLogs.title;
      panel.appendChild(head);
      var hint = document.createElement('div');
      hint.className = 'notice-hint';
      hint.textContent = infoLogs.hint + (err && err.retryAfter ? '（约 ' + err.retryAfter + ' 秒后可重试）' : '');
      panel.appendChild(hint);
      UI.toast(infoLogs.title, 'error');
    });
  }

  function triggerBlobDownload(blob, fileName) {
    try {
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = fileName || 'download.txt';
      a.rel = 'noopener';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      setTimeout(function () { try { URL.revokeObjectURL(url); } catch (e) { /* ignore */ } }, 3000);
    } catch (e) {
      UI.toast('浏览器不支持下载该文件', 'error');
    }
  }

  function doDownloadLog(item, btn) {
    var cfg = (SITE.logs || {});
    var blobMax = Number(cfg.blobDownloadMaxBytes) || (8 * 1024 * 1024);
    var size = item.size != null ? Number(item.size) : 0;
    var direct = cfg.directDownloadWithToken !== false;

    if (Api.isMock()) {
      UI.setBusy(btn, true, '下载中…');
      Api.logsDownloadBlob(item.name).then(function (res) {
        UI.setBusy(btn, false);
        triggerBlobDownload(res.blob, res.fileName || item.name);
        UI.toast('演示模式：已生成模拟日志文件（' + item.name + '）', 'success');
      }).catch(function (err) {
        UI.setBusy(btn, false);
        var info = Api.errorInfo(err);
        UI.showError(el.logsError, err, { hideRaw: true });
        UI.toast(info.title, 'error');
      });
      return;
    }

    // 真实模式：优先直链下载（任务要求：不整读内存）。仅当配置关闭直链且文件较小时用 fetch→Blob 兜底。
    if (direct || size > blobMax) {
      var url = Api.logsDownloadUrl(item.name);
      var a = document.createElement('a');
      a.href = url;
      a.download = item.name;
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      UI.toast('已开始下载 ' + item.name + '（直链，不占页面内存；若提示未登录请刷新页面重新登录）', 'info');
      return;
    }

    UI.setBusy(btn, true, '下载中…');
    Api.logsDownloadBlob(item.name).then(function (res) {
      UI.setBusy(btn, false);
      triggerBlobDownload(res.blob, res.fileName || item.name);
    }).catch(function (err) {
      UI.setBusy(btn, false);
      var info = Api.errorInfo(err);
      UI.showError(el.logsError, err, { hideRaw: true });
      UI.toast(info.title, 'error');
    });
  }

  function setupMockLogsTools() {
    if (!el.logsMockTools || !Api.isMock || !Api.isMock()) return;
    el.logsMockTools.hidden = false;
    if (el.mockLogsBusyBtn) {
      el.mockLogsBusyBtn.addEventListener('click', function () {
        if (global.__PORTAL_MOCK_DEBUG__ && global.__PORTAL_MOCK_DEBUG__.logsFail) global.__PORTAL_MOCK_DEBUG__.logsFail('busy');
        UI.toast('已设置：下一次日志请求返回 BUSY（并发已满）', 'warn');
        loadLogs();
      });
    }
    if (el.mockLogsRateBtn) {
      el.mockLogsRateBtn.addEventListener('click', function () {
        if (global.__PORTAL_MOCK_DEBUG__ && global.__PORTAL_MOCK_DEBUG__.logsFail) global.__PORTAL_MOCK_DEBUG__.logsFail('rate');
        UI.toast('已设置：下一次日志请求返回 RATE_LIMITED', 'warn');
        loadLogs();
      });
    }
  }

  /* --------------------------- 游戏控制台可达性 --------------------------- */
  function loadHealth() {
    return Api.health().then(function (h) {
      state.consoleInfo = h.console;
      renderConsoleStatus();
    }).catch(function () {
      state.consoleInfo = null;
      renderConsoleStatus();
    });
  }

  /**
   * 生产服通常**没有开启**调试控制台端口（socketInput），这是正常/可选状态，不是错误：
   *  - 未开启：中性提示「未开启（可选）」，审核仍会成功、文件正常入库，重启游戏服后生效；
   *  - 已开启：可即时热重载 / 刷新地图列表。
   */
  function renderConsoleStatus() {
    var c = state.consoleInfo;
    if (el.consolePill) {
      if (!c) {
        el.consolePill.textContent = '控制台：未知';
        el.consolePill.className = 'pill';
        el.consolePill.title = '后端 /api/health 未返回 console 字段（旧后端）或请求失败；不影响审核。';
      } else if (!c.enabled) {
        el.consolePill.textContent = '控制台：未开启（可选）';
        el.consolePill.className = 'pill';
        el.consolePill.title = '后端 web-config.json 的 console.enabled=false。审核通过仍会成功、文件正常入库；游戏侧热重载/地图刷新需重启游戏服或手动执行命令。';
      } else if (!c.reachable) {
        el.consolePill.textContent = '控制台：未开启（可选）';
        el.consolePill.className = 'pill';
        el.consolePill.title = '游戏服未开启调试控制台端口 ' + (c.host || '?') + ':' + (c.port != null ? c.port : '?') +
          '（socketInput，生产服常态）。不影响审核：文件会正常入库，重启游戏服后生效；如需即时热重载，可在 server.properties 开启 socketInput 后重试。' +
          (c.lastError ? '（探测信息：' + c.lastError + '）' : '');
      } else {
        el.consolePill.textContent = '控制台：已开启';
        el.consolePill.className = 'pill on';
        el.consolePill.title = '游戏服控制台 ' + (c.host || '?') + ':' + (c.port != null ? c.port : '?') + ' 已开启，审核通过可即时触发 DP 热重载 / 地图列表刷新。';
      }
    }
    if (!el.consoleWarning) return;
    // 控制台未开启属于正常可选状态：不显示常驻警告横幅（只有审核后的信息提示才显示）
    if (Date.now() < (state.consoleNoticeUntil || 0)) return; // 保留刚展示的审核信息横幅
    el.consoleWarning.hidden = true;
  }

  /**
   * 审核通过后的附加提示（consoleWarning / reloadHint）：审核默认成功、文件已就位；
   * 控制台未开启时只是提示「重启游戏服后生效」，用**信息样式**，不当作失败。
   */
  function showApproveConsoleWarning(warning, finalName) {
    state.consoleNoticeUntil = Date.now() + 20000;
    var text;
    if (typeof warning === 'string') text = warning;
    else if (warning && typeof warning === 'object') text = String(warning.message || warning.note || warning.hint || warning.lastError || '');
    else text = '';
    var detail = '文件已就位，游戏控制台未开启（可选）→ 重启游戏服后生效';
    if (finalName) detail += '；也可在游戏控制台手动执行 externalcp load "' + String(finalName).replace(/"/g, '') + '"（DP）或 maps（地图）。';
    // 后端 hint 若与标准说明重复则不重复拼接（只补充额外信息）
    if (text && !/文件已就位|未开启|重启|可选/.test(text)) detail += '（' + text + '）';
    if (el.consoleWarning) {
      el.consoleWarning.hidden = false;
      el.consoleWarning.className = 'notice notice-info';
      el.consoleWarning.innerHTML = '';
      var title = document.createElement('div');
      title.className = 'notice-title';
      title.textContent = 'ℹ 审核已通过：文件已就位，游戏控制台未开启';
      var hint = document.createElement('div');
      hint.className = 'notice-hint';
      hint.textContent = detail;
      el.consoleWarning.appendChild(title);
      el.consoleWarning.appendChild(hint);
    }
    UI.toast(detail, 'info', 9000);
  }

  function setupMockConsoleTools() {
    if (!el.mockConsoleToggle || !Api.isMock || !Api.isMock()) return;
    el.mockConsoleToggle.hidden = false;
    updateMockConsoleBtn();
    el.mockConsoleToggle.addEventListener('click', function () {
      var dbg = global.__PORTAL_MOCK_DEBUG__;
      var cur = (dbg && dbg.consoleReachable) ? dbg.consoleReachable() : false;
      if (dbg && dbg.setConsoleReachable) dbg.setConsoleReachable(!cur);
      updateMockConsoleBtn();
      loadHealth();
      UI.toast(!cur ? '已模拟：游戏控制台已开启（可即时热重载）' : '已模拟：游戏控制台未开启（生产常态，审核通过后重启游戏服生效）', 'info');
    });
  }

  function updateMockConsoleBtn() {
    if (!el.mockConsoleToggle) return;
    var dbg = global.__PORTAL_MOCK_DEBUG__;
    var reachable = (dbg && dbg.consoleReachable) ? dbg.consoleReachable() : false;
    el.mockConsoleToggle.textContent = reachable ? '模拟控制台未开启' : '模拟控制台已开启';
  }

  /* ------------------------------- 历史渲染 ------------------------------- */
  function renderHistory(history) {
    if (!el.historyList) return;
    el.historyList.innerHTML = '';
    U.setText(el.historyCount, history && history.length ? '最近 ' + history.length + ' 条' : '');
    if (!history || !history.length) {
      var empty = document.createElement('div');
      empty.className = 'table-empty';
      empty.textContent = '暂无处理记录';
      el.historyList.appendChild(empty);
      return;
    }
    history.slice(0, 30).forEach(function (item) {
      var line = document.createElement('div');
      line.className = 'history-line';
      var status = document.createElement('span');
      var approved = item.status === 'approved';
      status.className = 'badge-status ' + (approved ? 'approved' : 'rejected');
      status.textContent = approved ? '已通过' : '已拒绝';
      var kind = document.createElement('span');
      kind.className = 'badge-kind ' + (item.kind === 'map' ? 'map' : 'dp');
      kind.textContent = item.kind === 'map' ? '地图' : 'DP';
      var title = document.createElement('span');
      title.className = 'hl-title';
      title.textContent = item.title || '（无标题）';
      var right = document.createElement('span');
      right.className = 'hl-right';
      var reviewedText = item.reviewedAtText || (item.reviewedAt ? U.fmtDateTime(item.reviewedAt) : '');
      right.textContent = reviewedText + (item.author ? ' · ' + item.author : '') + (item.finalName ? ' → ' + item.finalName : '');
      line.appendChild(status);
      line.appendChild(kind);
      line.appendChild(title);
      if (!approved && item.rejectReason) {
        var reason = document.createElement('span');
        reason.className = 'hl-reason';
        reason.textContent = '拒绝理由：' + item.rejectReason;
        line.appendChild(reason);
      }
      line.appendChild(right);
      el.historyList.appendChild(line);
    });
  }

  /* ------------------------------- 事件绑定 ------------------------------- */
  function bind() {
    if (el.loginForm) el.loginForm.addEventListener('submit', handleLogin);
    if (el.logoutBtn) el.logoutBtn.addEventListener('click', confirmLogout);
    if (el.adminRefreshBtn) {
      el.adminRefreshBtn.addEventListener('click', function () {
        loadAll().then(function () { UI.toast('数据已刷新', 'success'); });
      });
    }
    if (el.reviewRefreshBtn) {
      el.reviewRefreshBtn.addEventListener('click', function () {
        UI.setBusy(el.reviewRefreshBtn, true, '刷新中…');
        loadReview().then(function () { UI.setBusy(el.reviewRefreshBtn, false); });
      });
    }
    if (el.catalogRefreshBtn) {
      el.catalogRefreshBtn.addEventListener('click', function () {
        UI.setBusy(el.catalogRefreshBtn, true, '刷新中…');
        loadCatalog().then(function () { UI.setBusy(el.catalogRefreshBtn, false); });
      });
    }
    if (el.reviewKindSeg) {
      el.reviewKindSeg.addEventListener('click', function (e) {
        var btn = e.target.closest ? e.target.closest('button[data-kind]') : null;
        if (!btn) return;
        state.reviewKind = btn.getAttribute('data-kind') || '';
        U.$$('#reviewKindSeg button').forEach(function (b) {
          b.classList.toggle('active', (b.getAttribute('data-kind') || '') === state.reviewKind);
        });
        loadReview();
      });
    }
    if (el.catalogSeg) {
      el.catalogSeg.addEventListener('click', function (e) {
        var btn = e.target.closest ? e.target.closest('button[data-cat]') : null;
        if (!btn) return;
        state.catalogTab = btn.getAttribute('data-cat') === 'maps' ? 'maps' : 'dp';
        renderCatalog();
      });
    }
    if (el.logsRefreshBtn) {
      el.logsRefreshBtn.addEventListener('click', function () {
        loadLogs().then(function () { UI.toast('日志列表已刷新', 'success'); });
      });
    }
    global.addEventListener('portal:unauthorized', function () {
      if (!Session().hasToken() && !state.dashboardReady) return;
      forceLogout('登录已失效，请重新登录');
    });
  }

  /* ------------------------------- 启动 ------------------------------- */
  function boot() {
    cacheEls();
    bind();
    setupMockLogsTools();
    setupMockConsoleTools();
    if (el.mockLoginHint && Api.isMock && Api.isMock()) el.mockLoginHint.hidden = false;
    Api.ready().then(function () {
      if (Session().hasToken() && !Session().isExpired()) {
        showDashboard();
        loadAll();
      } else {
        if (Session().hasToken()) Session().clear();
        showLogin();
      }
    }).catch(function (err) {
      UI.toast('初始化失败：' + (err && err.message ? err.message : err), 'error');
      showLogin();
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})(window);
