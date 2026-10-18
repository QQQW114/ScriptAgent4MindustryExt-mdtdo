/* ============================================================================
 * MDT DO 服务器门户 - 投稿页
 *  - 类型（DP / 地图）、标题、作者、介绍、可选替换条目、文件
 *  - 前端校验：必填 + 扩展名 + 单文件 10MB（与后端 web-config.json 默认值一致）
 *  - 上传进度（XHR）、后端错误码人话化、待审队列进度、现有列表
 * ==========================================================================*/
(function (global) {
  'use strict';

  var U = global.PortalUtil;
  var Api = global.PortalApi;
  var UI = global.PortalUI;
  var SITE = global.PORTAL_SITE || {};
  var UPLOAD = SITE.upload || {};

  var MAX_BYTES = Number(UPLOAD.maxFileBytes) || 10 * 1024 * 1024;
  var QUEUE_LIMIT = Number(UPLOAD.queueLimit) || 10;

  var state = {
    kind: 'dp',
    file: null,
    catalog: { dp: [], maps: [] },
    queue: null,
    submitting: false,
    catalogTab: 'dp'
  };

  var el = {};
  function cacheEls() {
    ['uploadForm', 'uploadError', 'kindSeg', 'kindHint', 'titleInput', 'authorInput', 'descInput',
      'titleCount', 'authorCount', 'descCount', 'titleError', 'authorError', 'descError',
      'replaceSelect', 'replaceHint', 'dropzone', 'fileInput', 'dropHint', 'fileChipWrap', 'fileError',
      'uploadProgressRow', 'uploadProgressBar', 'uploadProgressText', 'submitBtn', 'resetBtn',
      'successPanel', 'successId', 'successQueueText', 'successTips', 'againBtn', 'viewCatalogBtn',
      'queueRefreshBtn', 'queueBox', 'queueBar', 'queueSplit', 'mockTools', 'mockFillBtn', 'mockResetBtn',
      'catalogSeg', 'catalogBody', 'catalogSection'].forEach(function (id) {
      el[id] = document.getElementById(id);
    });
  }

  function allowedExtensions() {
    var list = state.kind === 'map' ? UPLOAD.allowedMapExtensions : UPLOAD.allowedDpExtensions;
    return (Array.isArray(list) && list.length ? list : state.kind === 'map' ? ['.msav'] : ['.zip', '.json', '.hjson', '.json5']);
  }
  function kindLabel() { return state.kind === 'map' ? '地图' : 'DP'; }
  function catalogKey() { return state.kind === 'map' ? 'maps' : 'dp'; }
  function extOf(name) {
    var idx = String(name == null ? '' : name).lastIndexOf('.');
    return idx < 0 ? '' : String(name).slice(idx).toLowerCase();
  }

  /* ------------------------------- 错误展示 ------------------------------- */
  function clearUploadError() { UI.clearNotice(el.uploadError); }
  function showUploadError(err) {
    UI.showError(el.uploadError, err);
    if (el.uploadError && el.uploadError.scrollIntoView) {
      el.uploadError.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }

  /* ------------------------------- 类型切换 ------------------------------- */
  function setKind(kind) {
    state.kind = kind === 'map' ? 'map' : 'dp';
    var isMap = state.kind === 'map';
    U.$$('#kindSeg button').forEach(function (btn) {
      var active = btn.getAttribute('data-kind') === state.kind;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    if (el.kindHint) el.kindHint.innerHTML = isMap
      ? '地图会被放入服务器 <code>config/maps</code> 目录，支持 .msav（Mindustry 存档地图）'
      : 'DP 会被放入服务器 <code>external-cp</code> 列表，支持 .zip / .json / .hjson / .json5';
    var allowed = allowedExtensions();
    if (el.fileInput) el.fileInput.setAttribute('accept', allowed.join(','));
    if (el.dropHint) el.dropHint.textContent = '支持 ' + allowed.join(' / ') + '，单文件不超过 10MB';
    el.replaceSelect.value = '';
    rebuildReplaceOptions();
    // 已选文件类型与新类型不匹配时清空
    if (state.file) {
      var msg = validateFile(state.file);
      if (msg) { clearFile(); el.fileError.textContent = msg; }
    }
  }

  /* ------------------------------- 文件处理 ------------------------------- */
  function validateFile(file) {
    if (!file) return '请选择要上传的文件';
    var allowed = allowedExtensions();
    var ext = extOf(file.name);
    if (allowed.indexOf(ext) < 0) {
      return '不支持的文件类型「' + (ext || '无扩展名') + '」；' + kindLabel() + '支持：' + allowed.join(' / ');
    }
    if (file.size > MAX_BYTES) {
      return '文件大小 ' + U.fmtBytes(file.size) + ' 超过 ' + U.fmtBytes(MAX_BYTES) + ' 上限';
    }
    if (!file.size) return '文件内容为空，请重新选择';
    return '';
  }

  function clearFile() {
    state.file = null;
    if (el.fileInput) el.fileInput.value = '';
    if (el.fileChipWrap) el.fileChipWrap.innerHTML = '';
    if (el.fileError) el.fileError.textContent = '';
    if (el.dropzone) el.dropzone.classList.remove('invalid');
  }

  function showFileChip(file) {
    if (!el.fileChipWrap) return;
    el.fileChipWrap.innerHTML = '';
    var chip = document.createElement('div');
    chip.className = 'file-chip';
    var icon = document.createElement('span');
    icon.className = 'fi';
    icon.textContent = '📦';
    var name = document.createElement('span');
    name.className = 'fname';
    name.textContent = file.name;
    name.title = file.name;
    var size = document.createElement('span');
    size.className = 'fsize';
    size.textContent = U.fmtBytes(file.size);
    var remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'fx';
    remove.title = '移除文件';
    remove.setAttribute('aria-label', '移除文件');
    remove.textContent = '✕';
    remove.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      clearFile();
    });
    chip.appendChild(icon);
    chip.appendChild(name);
    chip.appendChild(size);
    chip.appendChild(remove);
    el.fileChipWrap.appendChild(chip);
  }

  function acceptFile(file) {
    var msg = validateFile(file);
    if (el.fileError) el.fileError.textContent = msg;
    if (el.dropzone) el.dropzone.classList.toggle('invalid', !!msg);
    if (msg) { state.file = null; showFileChip({ name: file.name, size: file.size }); return false; }
    state.file = file;
    showFileChip(file);
    return true;
  }

  /* ------------------------------- 表单校验 ------------------------------- */
  function setFieldError(id, message) {
    var target = el[id];
    var input = { titleError: el.titleInput, authorError: el.authorInput, descError: el.descInput, fileError: el.fileInput }[id];
    if (target) target.textContent = message || '';
    if (input && input.classList) input.classList.toggle('invalid', !!message);
  }

  function validateForm() {
    var ok = true;
    var title = el.titleInput.value.trim();
    var author = el.authorInput.value.trim();
    var desc = el.descInput.value;

    if (!title) { setFieldError('titleError', '请填写标题'); ok = false; }
    else if (title.length > 200) { setFieldError('titleError', '标题不能超过 200 字'); ok = false; }
    else setFieldError('titleError', '');

    if (!author) { setFieldError('authorError', '请填写作者'); ok = false; }
    else if (author.length > 100) { setFieldError('authorError', '作者不能超过 100 字'); ok = false; }
    else setFieldError('authorError', '');

    if (desc.length > 4000) { setFieldError('descError', '介绍不能超过 4000 字'); ok = false; }
    else setFieldError('descError', '');

    var fileMsg = validateFile(state.file);
    setFieldError('fileError', fileMsg);
    if (fileMsg) ok = false;
    if (el.dropzone) el.dropzone.classList.toggle('invalid', !!fileMsg);
    return ok;
  }

  /* ------------------------------- 队列展示 ------------------------------- */
  function extractQueue(data) {
    if (!data || typeof data !== 'object') return null;
    var q = data.queue && typeof data.queue === 'object' ? data.queue : data;
    var limit = Number(q.limit) || QUEUE_LIMIT;
    var pendingRaw = q.pending;
    var pending = null;
    var byKind = null;
    if (pendingRaw && typeof pendingRaw === 'object') {
      byKind = pendingRaw;
      pending = Object.keys(byKind).reduce(function (sum, k) { return sum + (Number(byKind[k]) || 0); }, 0);
    } else if (pendingRaw != null) {
      pending = Number(pendingRaw);
    }
    if (byKind == null && q.byKind && typeof q.byKind === 'object') {
      byKind = q.byKind;
      if (pending == null) pending = Object.keys(byKind).reduce(function (sum, k) { return sum + (Number(byKind[k]) || 0); }, 0);
    }
    if (pending == null || !isFinite(pending)) return null;
    return { limit: limit, pending: pending, byKind: byKind };
  }

  function loadQueue() {
    if (!el.queueBox) return Promise.resolve();
    return Api.ready().then(function () { return Api.queueInfo(); }).then(function (data) {
      state.queue = extractQueue(data);
      renderQueue();
    }).catch(function () {
      state.queue = null;
      renderQueue();
    });
  }

  function renderQueue() {
    if (!el.queueBox) return;
    var q = state.queue;
    if (!q) {
      el.queueBox.innerHTML = '';
      var head = document.createElement('div');
      head.className = 'queue-head';
      head.innerHTML = '后端未提供公开队列计数接口，仅显示上限：<b>' + QUEUE_LIMIT + '</b> 件 / 类型';
      el.queueBox.appendChild(head);
      var barWrap = document.createElement('div');
      barWrap.className = 'progress';
      barWrap.innerHTML = '<i style="width:0%"></i>';
      el.queueBox.appendChild(barWrap);
      var tip = document.createElement('div');
      tip.className = 'queue-split';
      tip.textContent = '提交成功或队列满时，后端会在响应中返回最新队列进度。';
      el.queueBox.appendChild(tip);
      return;
    }
    var pct = U.clamp(q.pending / Math.max(1, q.limit) * 100, 0, 100);
    var full = q.pending >= q.limit;
    el.queueBox.innerHTML = '';
    var head2 = document.createElement('div');
    head2.className = 'queue-head';
    head2.innerHTML = '当前待审 <b>' + U.fmtInt(q.pending) + '</b> / <b>' + U.fmtInt(q.limit) + '</b> 件' +
      (full ? ' <span class="pill off" style="padding:2px 8px">已满</span>' : '');
    el.queueBox.appendChild(head2);
    var bar = document.createElement('div');
    bar.className = 'progress';
    var fill = document.createElement('i');
    fill.style.width = pct.toFixed(1) + '%';
    if (full) fill.style.background = 'var(--red)';
    bar.appendChild(fill);
    el.queueBox.appendChild(bar);
    if (q.byKind) {
      var split = document.createElement('div');
      split.className = 'queue-split';
      split.innerHTML = Object.keys(q.byKind).map(function (k) {
        var label = k === 'dp' ? 'DP' : k === 'map' ? '地图' : k;
        return '<span>' + U.esc(label) + '：' + U.fmtInt(q.byKind[k]) + ' 件</span>';
      }).join('');
      el.queueBox.appendChild(split);
    }
  }

  /* ------------------------------- 现有列表 ------------------------------- */
  function loadCatalog() {
    if (!el.catalogBody) return Promise.resolve();
    var hasContent = !!el.catalogBody.querySelector('table, .table-empty, .notice');
    if (!hasContent) el.catalogBody.innerHTML = '<div class="loading-block"><span class="spinner"></span>正在读取列表…</div>';
    return Api.ready().then(function () { return Api.catalog(); }).then(function (cat) {
      state.catalog = { dp: cat.dp || [], maps: cat.maps || [] };
      rebuildReplaceOptions();
      renderCatalog();
    }).catch(function (err) {
      var info = Api.errorInfo(err);
      if (hasContent) { UI.toast(info.title, 'error'); return; }
      el.catalogBody.innerHTML = '';
      var box = document.createElement('div');
      box.className = 'notice notice-error';
      box.innerHTML = '<div class="notice-title">⚠ ' + U.esc(info.title) + '</div><div class="notice-hint">' + U.esc(info.hint) + '</div>';
      el.catalogBody.appendChild(box);
    });
  }

  function rebuildReplaceOptions() {
    if (!el.replaceSelect) return;
    var items = state.catalog[catalogKey()] || [];
    var html = '<option value="">— 不替换，作为新条目提交 —</option>';
    items.forEach(function (it) {
      var name = it.name || it.file || '';
      var file = it.file || it.name || '';
      var label = name + (file && file !== name ? '（' + file + '）' : '') + (it.size != null ? ' · ' + U.fmtBytes(it.size) : '');
      html += '<option value="' + U.esc(file || name) + '">' + U.esc(label) + '</option>';
    });
    el.replaceSelect.innerHTML = html;
  }

  function renderCatalog() {
    if (!el.catalogBody) return;
    var key = state.catalogTab;
    var list = state.catalog[key] || [];
    U.$$('#catalogSeg button').forEach(function (btn) {
      var active = btn.getAttribute('data-cat') === key;
      btn.classList.toggle('active', active);
      btn.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    if (!list.length) {
      el.catalogBody.innerHTML = '<div class="table-empty">' + (key === 'maps' ? '服务器地图目录为空' : 'DP 列表暂无条目') + '</div>';
      return;
    }
    var rows = list.map(function (it) {
      var desc = it.desc ? String(it.desc) : '';
      // 第二期：介绍截断上限 64 → 128（2×），并加 title 全文
      var shortDesc = desc.length > 128 ? desc.slice(0, 128) + '…' : desc;
      return '<tr>' +
        '<td><div style="color:var(--text)">' + U.esc(it.name || '--') + '</div>' +
        (it.file && it.file !== it.name ? '<div class="dim small">' + U.esc(it.file) + '</div>' : '') + '</td>' +
        '<td class="num">' + U.esc(it.size != null ? U.fmtBytes(it.size) : '--') + '</td>' +
        '<td>' + U.esc(it.author || '—') + '</td>' +
        '<td style="max-width:340px"><span title="' + U.esc(desc ? '介绍全文：' + desc : '') + '">' + (shortDesc ? U.esc(shortDesc) : '<span class="dim">—</span>') + '</span></td>' +
        '<td class="dim small nowrap">' + U.esc(it.updatedAt || '--') + '</td>' +
        '</tr>';
    }).join('');
    el.catalogBody.innerHTML = '<div class="table-wrap"><table class="data"><thead><tr>' +
      '<th>名称</th><th style="text-align:right">大小</th><th>作者</th><th>介绍</th><th>更新时间</th>' +
      '</tr></thead><tbody>' + rows + '</tbody></table></div>';
  }

  /* ------------------------------- 提交 ------------------------------- */
  function setProgress(ratio, text) {
    if (el.uploadProgressBar) el.uploadProgressBar.style.width = U.clamp(ratio * 100, 0, 100).toFixed(1) + '%';
    if (el.uploadProgressText) el.uploadProgressText.textContent = text;
  }

  function finishSubmit() {
    state.submitting = false;
    UI.setBusy(el.submitBtn, false);
    if (el.uploadProgressRow) el.uploadProgressRow.hidden = true;
    setProgress(0, '准备上传…');
  }

  function handleSubmit(e) {
    if (e) e.preventDefault();
    if (state.submitting) return;
    clearUploadError();
    if (!validateForm()) {
      UI.toast('请先修正表单中标红的内容', 'warn');
      return;
    }
    var title = el.titleInput.value.trim();
    var author = el.authorInput.value.trim();
    var desc = el.descInput.value.trim();
    var replaceOf = el.replaceSelect.value || '';
    var formData = new FormData();
    formData.set('kind', state.kind);
    formData.set('title', title);
    formData.set('author', author);
    formData.set('desc', desc);
    if (replaceOf) formData.set('replaceOf', replaceOf);
    formData.set('file', state.file, state.file.name);

    state.submitting = true;
    UI.setBusy(el.submitBtn, true, '上传中…');
    if (el.uploadProgressRow) el.uploadProgressRow.hidden = false;
    setProgress(0, '上传中 0%');

    Api.ready().then(function () {
      return Api.upload(formData, function (ratio) {
        var pct = Math.round(U.clamp(ratio, 0, 1) * 100);
        setProgress(ratio, ratio >= 1 ? '上传完成，等待服务器处理…' : '上传中 ' + pct + '%');
      });
    }).then(function (data) {
      finishSubmit();
      var queue = extractQueue(data && data.queue ? { queue: data.queue } : null);
      if (queue) state.queue = queue;
      showSuccess(data || {});
      loadQueue();
      loadCatalog();
      UI.toast('投稿已受理，等待管理员审核', 'success');
      formDirty = false;
    }).catch(function (err) {
      finishSubmit();
      var info = Api.errorInfo(err);
      showUploadError(err);
      UI.toast(info.title, 'error');
      // 后端在 QUEUE_FULL 等错误里会返回最新队列状态（error.queue）
      var errQueue = err && err.payload && err.payload.error && err.payload.error.queue;
      if (errQueue) {
        var parsedQueue = extractQueue({ queue: errQueue });
        if (parsedQueue) { state.queue = parsedQueue; renderQueue(); }
      } else if (err && (err.code === 'QUEUE_FULL' || err.code === 'FILE_TOO_LARGE' || err.code === 'BAD_TYPE' || err.code === 'RATE_LIMITED')) {
        loadQueue();
      }
    });
  }

  function showSuccess(data) {
    if (!el.uploadForm) return;
    el.uploadForm.hidden = true;
    if (el.successPanel) el.successPanel.hidden = false;
    U.setText(el.successId, data.id != null ? String(data.id) : '--');
    var q = extractQueue(data.queue ? { queue: data.queue } : null) || state.queue;
    U.setText(el.successQueueText, q ? '（提交后待审 ' + U.fmtInt(q.pending) + '/' + U.fmtInt(q.limit) + ' 件）' : '');
    if (el.successPanel.scrollIntoView) el.successPanel.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  function showForm() {
    if (el.successPanel) el.successPanel.hidden = true;
    if (el.uploadForm) el.uploadForm.hidden = false;
    resetForm();
    if (el.titleInput && el.titleInput.focus) el.titleInput.focus();
  }

  function resetForm() {
    if (!el.uploadForm) return;
    el.uploadForm.reset();
    setKind(state.kind);
    clearFile();
    updateCounters();
    ['titleError', 'authorError', 'descError', 'fileError'].forEach(function (id) { setFieldError(id, ''); });
    clearUploadError();
  }

  function updateCounters() {
    if (el.titleCount) el.titleCount.textContent = String(el.titleInput.value.length);
    if (el.authorCount) el.authorCount.textContent = String(el.authorInput.value.length);
    if (el.descCount) el.descCount.textContent = String(el.descInput.value.length);
  }

  var formDirty = false;

  /* ------------------------------- 绑定事件 ------------------------------- */
  function bind() {
    if (el.kindSeg) {
      el.kindSeg.addEventListener('click', function (e) {
        var btn = e.target.closest ? e.target.closest('button[data-kind]') : null;
        if (btn) setKind(btn.getAttribute('data-kind'));
      });
    }
    [el.titleInput, el.authorInput, el.descInput].forEach(function (input) {
      if (!input) return;
      input.addEventListener('input', function () {
        formDirty = true;
        updateCounters();
        if (input === el.titleInput) setFieldError('titleError', '');
        if (input === el.authorInput) setFieldError('authorError', '');
        if (input === el.descInput) setFieldError('descError', '');
      });
    });
    if (el.replaceSelect) {
      el.replaceSelect.addEventListener('change', function () {
        var opt = el.replaceSelect.options[el.replaceSelect.selectedIndex];
        if (!opt || !el.replaceSelect.value) {
          if (el.replaceHint) el.replaceHint.textContent = '审核通过时将用新文件替换所选条目（保持原文件在服务器上的名称）。';
        } else if (el.replaceHint) {
          el.replaceHint.textContent = '将替换：' + opt.textContent + '（审核通过后用新文件覆盖）';
        }
      });
    }
    if (el.fileInput) {
      el.fileInput.addEventListener('change', function () {
        if (el.fileInput.files && el.fileInput.files.length) acceptFile(el.fileInput.files[0]);
        else clearFile();
      });
    }
    if (el.dropzone) {
      ['dragenter', 'dragover'].forEach(function (type) {
        el.dropzone.addEventListener(type, function (e) {
          e.preventDefault();
          e.stopPropagation();
          el.dropzone.classList.add('dragover');
        });
      });
      ['dragleave', 'dragend'].forEach(function (type) {
        el.dropzone.addEventListener(type, function (e) {
          e.preventDefault();
          el.dropzone.classList.remove('dragover');
        });
      });
      el.dropzone.addEventListener('drop', function (e) {
        e.preventDefault();
        e.stopPropagation();
        el.dropzone.classList.remove('dragover');
        if (e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files.length) acceptFile(e.dataTransfer.files[0]);
      });
    }
    if (el.uploadForm) el.uploadForm.addEventListener('submit', handleSubmit);
    if (el.resetBtn) {
      el.resetBtn.addEventListener('click', function () {
        if (!formDirty && !state.file) { resetForm(); return; }
        UI.confirm({ title: '清空表单', message: '确定要清空已填写的内容与已选文件吗？', danger: true, confirmText: '清空' }).then(function (res) {
          if (res && res.ok) { resetForm(); formDirty = false; }
        });
      });
    }
    if (el.againBtn) el.againBtn.addEventListener('click', showForm);
    if (el.viewCatalogBtn) {
      el.viewCatalogBtn.addEventListener('click', function () {
        if (el.catalogSection) el.catalogSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    }
    if (el.queueRefreshBtn) {
      el.queueRefreshBtn.addEventListener('click', function () {
        UI.setBusy(el.queueRefreshBtn, true, '刷新中…');
        loadQueue().then(function () {
          UI.setBusy(el.queueRefreshBtn, false);
          UI.toast('队列信息已刷新', 'success');
        });
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
    if (el.mockTools) {
      if (Api.isMock && Api.isMock()) {
        el.mockTools.hidden = false;
        if (el.mockFillBtn) {
          el.mockFillBtn.textContent = '填满队列（演示）';
          el.mockFillBtn.title = '把当前类型的待审队列填满 10 件，用于测试 QUEUE_FULL 错误提示';
          el.mockFillBtn.addEventListener('click', function () {
            if (global.__PORTAL_MOCK_DEBUG__ && global.__PORTAL_MOCK_DEBUG__.fillQueue) {
              global.__PORTAL_MOCK_DEBUG__.fillQueue(state.kind);
              loadQueue().then(function () { UI.toast('已填满 ' + kindLabel() + ' 待审队列（演示用），现在提交会收到 QUEUE_FULL', 'warn'); });
            }
          });
        }
        if (el.mockResetBtn) {
          el.mockResetBtn.addEventListener('click', function () {
            UI.confirm({ title: '重置演示数据', message: '将清空浏览器内的演示投稿、审核记录与目录改动，并刷新页面。', confirmText: '重置并刷新' }).then(function (res) {
              if (res && res.ok && global.__PORTAL_MOCK_DEBUG__ && global.__PORTAL_MOCK_DEBUG__.reset) {
                global.__PORTAL_MOCK_DEBUG__.reset();
                global.location.reload();
              }
            });
          });
        }
      }
    }
  }

  function boot() {
    cacheEls();
    if (!el.uploadForm) return;
    bind();
    setKind('dp');
    updateCounters();
    Api.ready().then(function () {
      loadCatalog();
      loadQueue();
      if (Api.isMock && Api.isMock()) {
        UI.toast('演示数据模式：可用左下角「演示数据 · mock」重置；管理员账号 admin / demo123', 'warn', 7000);
      }
    }).catch(function (err) {
      UI.toast('初始化失败：' + (err && err.message ? err.message : err), 'error');
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})(window);
