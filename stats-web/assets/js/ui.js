/* ============================================================================
 * MDT DO 服务器门户 - 共享 UI：toast / 确认框 / 输入框 / 粒子背景 / mock 角标
 * ==========================================================================*/
(function (global) {
  'use strict';

  var U = global.PortalUtil;
  var UI = {};

  /* ------------------------------- toast ------------------------------- */
  function toastRoot() {
    var root = document.getElementById('toastRoot');
    if (!root) {
      root = document.createElement('div');
      root.id = 'toastRoot';
      root.className = 'toast-root';
      root.setAttribute('aria-live', 'polite');
      document.body.appendChild(root);
    }
    return root;
  }

  /**
   * @param {string} message
   * @param {'info'|'success'|'warn'|'error'} [type]
   * @param {number} [timeout]
   */
  function toast(message, type, timeout) {
    var root = toastRoot();
    var el = document.createElement('div');
    el.className = 'toast toast-' + (type || 'info');
    el.setAttribute('role', type === 'error' ? 'alert' : 'status');
    var icon = document.createElement('span');
    icon.className = 'toast-icon';
    icon.textContent = type === 'success' ? '✓' : type === 'error' ? '✕' : type === 'warn' ? '!' : 'i';
    var text = document.createElement('span');
    text.className = 'toast-text';
    text.textContent = message == null ? '' : String(message);
    el.appendChild(icon);
    el.appendChild(text);
    root.appendChild(el);
    var ms = timeout || (type === 'error' ? 6000 : 3600);
    var removed = false;
    function remove() {
      if (removed) return;
      removed = true;
      el.classList.add('out');
      setTimeout(function () { if (el.parentNode) el.parentNode.removeChild(el); }, 220);
    }
    el.addEventListener('click', remove);
    setTimeout(remove, ms);
    return remove;
  }

  /* ------------------------------- modal ------------------------------- */
  var activeModal = null;

  function openModal(opts) {
    if (activeModal) closeModal();
    var overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'true');

    var box = document.createElement('div');
    box.className = 'modal' + (opts.danger ? ' modal-danger' : '');
    var head = document.createElement('h3');
    head.className = 'modal-title';
    head.textContent = opts.title || '确认操作';
    box.appendChild(head);

    if (opts.message) {
      var p = document.createElement('p');
      p.className = 'modal-message';
      p.textContent = opts.message;
      box.appendChild(p);
    }
    if (opts.detail) {
      var d = document.createElement('p');
      d.className = 'modal-detail';
      d.textContent = opts.detail;
      box.appendChild(d);
    }

    var checkbox = null;
    if (opts.checkbox) {
      var label = document.createElement('label');
      label.className = 'modal-check';
      checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = !!opts.checkbox.checked;
      var span = document.createElement('span');
      span.textContent = opts.checkbox.label || '';
      label.appendChild(checkbox);
      label.appendChild(span);
      box.appendChild(label);
    }

    var input = null;
    if (opts.input) {
      var wrap = document.createElement('label');
      wrap.className = 'modal-field';
      var lab = document.createElement('span');
      lab.className = 'modal-field-label';
      lab.textContent = opts.input.label || '请填写';
      input = document.createElement('textarea');
      input.className = 'input textarea';
      input.rows = opts.input.rows || 3;
      input.maxLength = opts.input.maxLength || 500;
      input.placeholder = opts.input.placeholder || '';
      input.value = opts.input.value || '';
      wrap.appendChild(lab);
      wrap.appendChild(input);
      var err = document.createElement('span');
      err.className = 'modal-field-error';
      err.textContent = opts.input.required ? '此项为必填' : '';
      wrap.appendChild(err);
      box.appendChild(wrap);
    }

    var actions = document.createElement('div');
    actions.className = 'modal-actions';
    var cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'btn ghost';
    cancel.textContent = opts.cancelText || '取消';
    var confirm = document.createElement('button');
    confirm.type = 'button';
    confirm.className = 'btn ' + (opts.danger ? 'danger' : 'primary');
    confirm.textContent = opts.confirmText || '确定';
    actions.appendChild(cancel);
    actions.appendChild(confirm);
    box.appendChild(actions);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    activeModal = overlay;
    document.documentElement.classList.add('modal-open');

    return new Promise(function (resolve) {
      function finish(result) {
        closeModal();
        resolve(result);
      }
      function tryConfirm() {
        if (input && opts.input && opts.input.required) {
          var value = input.value.trim();
          if (!value) {
            input.classList.add('invalid');
            var errEl = box.querySelector('.modal-field-error');
            if (errEl) errEl.textContent = opts.input.requiredMessage || '此项为必填';
            input.focus();
            return;
          }
        }
        finish({
          ok: true,
          value: input ? input.value.trim() : '',
          checked: checkbox ? checkbox.checked : false
        });
      }
      cancel.addEventListener('click', function () { finish({ ok: false, value: '', checked: false }); });
      confirm.addEventListener('click', tryConfirm);
      overlay.addEventListener('click', function (e) {
        if (e.target === overlay) finish({ ok: false, value: '', checked: false });
      });
      overlay.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') { e.preventDefault(); finish({ ok: false, value: '', checked: false }); }
        if (e.key === 'Enter' && (!input || e.ctrlKey || e.metaKey)) { e.preventDefault(); tryConfirm(); }
      });
      setTimeout(function () {
        if (input) input.focus();
        else confirm.focus();
      }, 30);
    });
  }

  function closeModal() {
    if (!activeModal) return;
    var node = activeModal;
    activeModal = null;
    document.documentElement.classList.remove('modal-open');
    if (node.parentNode) node.parentNode.removeChild(node);
  }

  /** 确认框：Promise<{ok, checked}> */
  function confirm(opts) {
    opts = opts || {};
    return openModal({
      title: opts.title || '确认操作',
      message: opts.message,
      detail: opts.detail,
      danger: !!opts.danger,
      confirmText: opts.confirmText || '确定',
      cancelText: opts.cancelText || '取消',
      checkbox: opts.checkbox
    });
  }

  /** 输入框：Promise<{ok, value}> */
  function prompt(opts) {
    opts = opts || {};
    return openModal({
      title: opts.title || '请输入',
      message: opts.message,
      confirmText: opts.confirmText || '提交',
      cancelText: opts.cancelText || '取消',
      input: {
        label: opts.label || '内容',
        placeholder: opts.placeholder || '',
        required: opts.required !== false,
        rows: opts.rows || 3,
        maxLength: opts.maxLength || 500,
        value: opts.value || '',
        requiredMessage: opts.requiredMessage || '此项为必填'
      }
    });
  }

  /* ------------------------------ 其它工具 ------------------------------ */
  function setBadge(el, text, cls) {
    if (!el) return;
    el.textContent = text == null ? '' : String(text);
    el.className = 'pill ' + (cls || '');
  }

  function setBusy(button, busy, busyText) {
    if (!button) return;
    if (busy) {
      if (!button.dataset.originalHtml) button.dataset.originalHtml = button.innerHTML;
      button.disabled = true;
      button.classList.add('is-busy');
      button.innerHTML = '<span class="spinner"></span>' + PortalUtil.esc(busyText || '处理中…');
    } else {
      button.disabled = false;
      button.classList.remove('is-busy');
      if (button.dataset.originalHtml) {
        button.innerHTML = button.dataset.originalHtml;
        delete button.dataset.originalHtml;
      }
    }
  }

  /**
   * 在容器内渲染错误信息 + 可选“重试”按钮。
   * @param {HTMLElement} container
   * @param {Error} err
   * @param {Function|object} [retryOrOpts] 函数=重试回调；对象={retry, hideRaw}
   */
  function showError(container, err, retryOrOpts) {
    if (!container) return;
    var opts = typeof retryOrOpts === 'function' ? { retry: retryOrOpts } : (retryOrOpts || {});
    var info = global.PortalApi ? global.PortalApi.errorInfo(err) : { title: String(err && err.message || err), hint: '' };
    container.hidden = false;
    container.classList.add('notice', 'notice-error');
    container.innerHTML = '';
    var title = document.createElement('div');
    title.className = 'notice-title';
    title.textContent = '⚠ ' + info.title;
    container.appendChild(title);
    if (info.hint) {
      var hint = document.createElement('div');
      hint.className = 'notice-hint';
      hint.textContent = info.hint;
      container.appendChild(hint);
    }
    var raw = err && err.message ? String(err.message) : '';
    // hideRaw：不展示原始 message（例如后端 500 的异常文本/堆栈），只给友好提示
    if (raw && raw !== info.title && !opts.hideRaw) {
      var detail = document.createElement('div');
      detail.className = 'notice-detail';
      detail.textContent = '详细信息：' + raw.split('\n').slice(0, 2).join(' ').slice(0, 240);
      container.appendChild(detail);
    }
    if (opts.retry) {
      var retry = document.createElement('button');
      retry.type = 'button';
      retry.className = 'btn small ghost';
      retry.textContent = '重试';
      retry.addEventListener('click', opts.retry);
      container.appendChild(retry);
    }
  }

  function clearNotice(container) {
    if (!container) return;
    container.hidden = true;
    container.innerHTML = '';
    container.classList.remove('notice', 'notice-error');
  }

  /* --------------------------- 玩家名（长名友好） --------------------------- */
  /**
   * 构建玩家名元素：彩色标记安全渲染、不二次截断（上限见 site-config.display.playerNameMaxChars，
   * 默认 512 = 上游事件流 maxPlayerChars 256 的 2 倍）、title 显示完整纯文本名、点击可查看完整原名。
   * @param {string} raw 原始玩家名（可能含 Mindustry 颜色标记）
   * @param {object} [opts] {className, playerId, showDetail}
   * @returns {HTMLElement}
   */
  function buildPlayerName(raw, opts) {
    opts = opts || {};
    var site = global.PORTAL_SITE || {};
    var display = site.display || {};
    var maxChars = Number(display.playerNameMaxChars) || 512;
    var markup = global.PortalMarkup;
    var span = document.createElement('span');
    span.className = 'player-name ' + (opts.className || '');
    var info;
    if (markup && typeof markup.nameInfo === 'function') {
      info = markup.nameInfo(raw, maxChars);
    } else {
      info = { html: escText(raw), plain: String(raw == null ? '' : raw), raw: String(raw == null ? '' : raw), repaired: false, truncated: false, chars: 0 };
    }
    span.innerHTML = info.html; // 安全：nameInfo 内部走 render（先转义再包颜色）
    var fullTitle = info.plain + (info.charactersHint || '');
    if (info.repaired) fullTitle += '（原记录末尾有残缺颜色标记，已自动收尾）';
    if (info.truncated) fullTitle += '（超过前端 ' + maxChars + ' 字符显示上限）';
    span.title = fullTitle;
    span.setAttribute('data-full-name', info.raw);
    span.setAttribute('data-display-name', info.plain);
    if ((display.playerNameClickDetail !== false) && global.PortalUI) {
      span.classList.add('clickable');
      span.setAttribute('role', 'button');
      span.setAttribute('tabindex', '0');
      var showDetail = function (e) {
        if (e) { e.preventDefault(); e.stopPropagation(); }
        showPlayerDetail(info, opts);
      };
      span.addEventListener('click', showDetail);
      span.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') showDetail(e);
      });
    }
    return span;
  }

  function escText(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  /** 玩家名详情弹窗：完整原名（纯文本）+ 颜色标记原文 + 记录信息。 */
  function showPlayerDetail(info, opts) {
    if (activeModal) closeModal();
    var overlay = document.createElement('div');
    overlay.className = 'modal-overlay';
    overlay.setAttribute('role', 'dialog');
    overlay.setAttribute('aria-modal', 'true');
    var box = document.createElement('div');
    box.className = 'modal modal-wide';
    var title = document.createElement('h3');
    title.className = 'modal-title';
    title.textContent = '玩家名';
    box.appendChild(title);

    var list = document.createElement('div');
    list.className = 'detail-list';
    function row(label, valueNode) {
      var wrap = document.createElement('div');
      wrap.className = 'detail-row';
      var k = document.createElement('span');
      k.className = 'detail-k';
      k.textContent = label;
      var v = document.createElement('span');
      v.className = 'detail-v';
      v.appendChild(valueNode);
      wrap.appendChild(k);
      wrap.appendChild(v);
      list.appendChild(wrap);
    }
    var plainNode = document.createElement('div');
    plainNode.className = 'detail-name';
    plainNode.textContent = info.plain || '（空）';
    row('显示名（纯文本）', plainNode);

    var rawNode = document.createElement('code');
    rawNode.className = 'detail-raw';
    rawNode.textContent = info.raw || '（空）';
    row('原始记录（含颜色标记）', rawNode);

    if (info.chars) {
      var lenNode = document.createElement('span');
      lenNode.textContent = info.chars + ' 字符（前端显示上限 ' + ((global.PORTAL_SITE || {}).display || {}).playerNameMaxChars + '）';
      row('长度', lenNode);
    }
    if (opts && opts.playerId) {
      var idNode = document.createElement('code');
      idNode.className = 'detail-raw';
      idNode.textContent = String(opts.playerId);
      row('playerId', idNode);
    }
    if (info.repaired) {
      var note = document.createElement('div');
      note.className = 'notice-hint';
      note.textContent = '上游事件记录在此处截断，末尾残缺的颜色标记已自动收尾（实际名字可能更长；游戏侧 liveEventFeed.maxPlayerChars 可调大）。';
      row('截断说明', note);
    }
    box.appendChild(list);

    var actions = document.createElement('div');
    actions.className = 'modal-actions';
    var copy = document.createElement('button');
    copy.type = 'button';
    copy.className = 'btn ghost';
    copy.textContent = '复制显示名';
    copy.addEventListener('click', function () {
      global.PortalUtil.copyText(info.plain).then(function (ok) { toast(ok ? '已复制玩家名' : '复制失败', ok ? 'success' : 'warn'); });
    });
    var close = document.createElement('button');
    close.type = 'button';
    close.className = 'btn primary';
    close.textContent = '关闭';
    close.addEventListener('click', function () { closeModal(); });
    actions.appendChild(copy);
    actions.appendChild(close);
    box.appendChild(actions);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    activeModal = overlay;
    document.documentElement.classList.add('modal-open');
    overlay.addEventListener('click', function (e) { if (e.target === overlay) closeModal(); });
    overlay.addEventListener('keydown', function (e) { if (e.key === 'Escape') closeModal(); });
    setTimeout(function () { close.focus(); }, 30);
  }

  /* --------------------------- 背景粒子（CSS 动画） --------------------------- */
  function initParticles() {
    var host = document.getElementById('particles');
    if (!host) return;
    if (global.matchMedia && global.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      host.classList.add('static');
      return;
    }
    var coarse = global.matchMedia && global.matchMedia('(pointer: coarse)').matches;
    var count = coarse ? 14 : 26;
    var frag = document.createDocumentFragment();
    for (var i = 0; i < count; i++) {
      var p = document.createElement('span');
      p.className = 'particle';
      var size = 2 + Math.random() * 4;
      var left = Math.random() * 100;
      var delay = -Math.random() * 22;
      var duration = 14 + Math.random() * 16;
      var hue = Math.random() < 0.5 ? 'var(--accent)' : 'var(--accent2)';
      p.style.cssText = 'left:' + left.toFixed(2) + '%;width:' + size.toFixed(1) + 'px;height:' + size.toFixed(1) +
        'px;background:' + hue + ';animation-delay:' + delay.toFixed(1) + 's;animation-duration:' + duration.toFixed(1) + 's;opacity:' + (0.25 + Math.random() * 0.5).toFixed(2);
      frag.appendChild(p);
    }
    host.appendChild(frag);
  }

  /* ------------------------------ mock 角标 ------------------------------ */
  function mountMockBadge() {
    if (!global.PortalApi || !global.PortalApi.isMock || !global.PortalApi.isMock()) return;
    if (document.getElementById('mockBadge')) return;
    var el = document.createElement('button');
    el.type = 'button';
    el.id = 'mockBadge';
    el.className = 'mock-badge';
    el.title = '当前为演示数据模式（不连接真实后端）。点击可重置演示数据或退出演示模式。';
    el.textContent = '演示数据 · mock';
    el.addEventListener('click', function () {
      confirm({
        title: '演示数据模式',
        message: '当前页面使用浏览器内的假数据展示（不会请求真实服务器）。',
        detail: '可以重置演示数据后继续体验完整流程；也可以关闭演示模式（本机未启动后端时会加载失败）。',
        confirmText: '重置演示数据',
        cancelText: '退出演示模式',
        checkbox: null
      }).then(function (res) {
        if (res && res.ok) {
          if (global.__PORTAL_MOCK_DEBUG__ && global.__PORTAL_MOCK_DEBUG__.reset) global.__PORTAL_MOCK_DEBUG__.reset();
          global.location.reload();
        } else {
          U.storage.remove('mdt_portal_mock');
          var url = new URL(global.location.href);
          url.searchParams.delete('mock');
          url.searchParams.set('mock', '0');
          global.location.href = url.toString();
        }
      });
    });
    document.body.appendChild(el);
  }

  function init() {
    initParticles();
    mountMockBadge();
  }

  UI.toast = toast;
  UI.confirm = confirm;
  UI.prompt = prompt;
  UI.setBadge = setBadge;
  UI.setBusy = setBusy;
  UI.showError = showError;
  UI.clearNotice = clearNotice;
  UI.buildPlayerName = buildPlayerName;
  UI.showPlayerDetail = showPlayerDetail;
  UI.closeModal = closeModal;
  UI.initParticles = initParticles;
  UI.mountMockBadge = mountMockBadge;
  UI.init = init;
  global.PortalUI = UI;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})(window);
