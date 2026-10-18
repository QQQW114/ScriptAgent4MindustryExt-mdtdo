/* ============================================================================
 * MDT DO 服务器门户 - 原生 SVG 折线图（无依赖）
 * ----------------------------------------------------------------------------
 * 由原 stats-web/index.html 的内联图表逻辑重构而来，保留 hover 提示能力，
 * 并修复原实现把数据拼进 innerHTML 的做法：提示内容全部用 textContent 构建，
 * 坐标/刻度只来自数值计算，标签进入 SVG 前做 HTML 转义。
 * ==========================================================================*/
(function (global) {
  'use strict';

  var U = global.PortalUtil;
  var COLOR_RE = /^(#[0-9a-fA-F]{3,8}|rgba?\([0-9.,\s]+\))$/;

  function safeColor(color, fallback) {
    return typeof color === 'string' && COLOR_RE.test(color) ? color : (fallback || '#4da3ff');
  }

  function niceStep(max) {
    var raw = max / 4;
    var mag = Math.pow(10, Math.floor(Math.log10(Math.max(1, raw))));
    var norm = raw / mag;
    var mult = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
    return Math.max(1, mult * mag);
  }

  var registry = [];

  function findInstance(el) {
    for (var i = 0; i < registry.length; i++) if (registry[i].el === el) return registry[i];
    return null;
  }

  function ensureTooltip(container) {
    var tip = container.querySelector('.chart-tip');
    if (!tip) {
      tip = document.createElement('div');
      tip.className = 'chart-tip';
      tip.hidden = true;
      container.appendChild(tip);
    }
    return tip;
  }

  function renderTooltip(tip, state, index) {
    if (!tip || index < 0 || index >= state.labels.length) { if (tip) tip.hidden = true; return; }
    tip.innerHTML = '';
    var head = document.createElement('div');
    head.className = 'chart-tip-head';
    head.textContent = state.labels[index];
    tip.appendChild(head);
    state.series.forEach(function (s) {
      var row = document.createElement('div');
      row.className = 'chart-tip-row';
      var dot = document.createElement('i');
      dot.style.background = s.color;
      var name = document.createElement('span');
      name.textContent = s.name;
      var val = document.createElement('b');
      val.textContent = U.fmtInt(s.values[index]) + (state.unit || '');
      row.appendChild(dot);
      row.appendChild(name);
      row.appendChild(val);
      tip.appendChild(row);
    });
    tip.hidden = false;
  }

  /**
   * 渲染折线图。
   * @param {HTMLElement} el 容器
   * @param {object} opts {labels:string[], series:[{name,color,values,area}], height, unit, emptyText}
   */
  function line(el, opts) {
    if (!el) return;
    opts = opts || {};
    var old = findInstance(el);
    if (old && old.cleanup) old.cleanup();

    var width = Math.max(320, Math.min(1000, Math.round(el.clientWidth || el.parentNode && el.parentNode.clientWidth || 1000)));
    var height = opts.height || 260;
    var pad = { l: 46, r: 16, t: 16, b: 30 };
    var labels = Array.isArray(opts.labels) ? opts.labels.map(function (x) { return x == null ? '' : String(x); }) : [];
    var series = (Array.isArray(opts.series) ? opts.series : []).filter(function (s) {
      return s && Array.isArray(s.values) && s.values.length;
    }).map(function (s) {
      return {
        name: String(s.name || ''),
        color: safeColor(s.color),
        area: !!s.area,
        values: s.values.map(function (v) { var n = Number(v); return isFinite(n) ? n : 0; })
      };
    });
    var n = labels.length;
    series.forEach(function (s) { n = Math.max(n, s.values.length); });
    if (!n || !series.length) {
      el.innerHTML = '<div class="chart-empty">' + U.esc(opts.emptyText || '暂无趋势数据') + '</div>';
      registry.push({ el: el, cleanup: function () {} });
      return;
    }

    var max = 0;
    series.forEach(function (s) { s.values.forEach(function (v) { if (v > max) max = v; }); });
    var step = niceStep(Math.max(1, max));
    var top = Math.ceil(max / step) * step;
    if (top <= 0) top = 1;

    var plotW = width - pad.l - pad.r;
    var plotH = height - pad.t - pad.b;
    function x(i) { return pad.l + (n === 1 ? plotW / 2 : i * plotW / (n - 1)); }
    function y(v) { return pad.t + plotH - (v / top) * plotH; }

    var parts = [];
    // 横向网格 + y 轴刻度
    for (var g = 0; g <= top + 1e-9; g += step) {
      var gy = y(g).toFixed(1);
      parts.push('<line class="gl" x1="' + pad.l + '" y1="' + gy + '" x2="' + (width - pad.r) + '" y2="' + gy + '"></line>');
      parts.push('<text class="ax" x="' + (pad.l - 8) + '" y="' + (Number(gy) + 3.5).toFixed(1) + '" text-anchor="end">' + U.esc(String(Math.round(g))) + '</text>');
    }
    // x 轴标签（自动抽稀，避免拥挤）
    var labelEvery = Math.max(1, Math.ceil(n / Math.max(2, Math.floor(plotW / 58))));
    for (var i = 0; i < n; i++) {
      if (i % labelEvery !== 0 && i !== n - 1) continue;
      parts.push('<text class="ax" x="' + x(i).toFixed(1) + '" y="' + (height - 8) + '" text-anchor="middle">' + U.esc(labels[i] != null ? labels[i] : '') + '</text>');
    }
    // 折线 / 面积
    series.forEach(function (s) {
      var pts = s.values.map(function (v, i) { return x(i).toFixed(1) + ',' + y(v).toFixed(1); });
      var d = 'M' + pts.join(' L');
      if (s.area) {
        parts.push('<path class="ar" fill="' + s.color + '" d="' + d + ' L' + x(Math.min(n, s.values.length) - 1).toFixed(1) + ',' + (height - pad.b) + ' L' + x(0).toFixed(1) + ',' + (height - pad.b) + ' Z"></path>');
      }
      parts.push('<path class="ln" stroke="' + s.color + '" d="' + d + '"></path>');
      if (n <= 32) {
        s.values.forEach(function (v, i) {
          parts.push('<circle class="pt" fill="' + s.color + '" cx="' + x(i).toFixed(1) + '" cy="' + y(v).toFixed(1) + '"></circle>');
        });
      }
    });
    // 悬停热区
    var colW = plotW / Math.max(1, n - 1);
    for (var k = 0; k < n; k++) {
      parts.push('<rect class="hover" x="' + (x(k) - colW / 2).toFixed(1) + '" y="' + pad.t + '" width="' + colW.toFixed(1) +
        '" height="' + plotH + '" data-index="' + k + '"></rect>');
    }

    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'chart');
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', opts.ariaLabel || '数据折线图');
    svg.style.height = height + 'px';
    svg.innerHTML = parts.join('');
    el.innerHTML = '';
    el.appendChild(svg);
    var tip = ensureTooltip(el);
    tip.hidden = true;

    var state = { el: el, labels: labels, series: series, unit: opts.unit || '', width: width, height: height, pad: pad,
      svg: svg, tip: tip, activeIndex: -1 };

    function indexFromEvent(e) {
      var rect = svg.getBoundingClientRect();
      if (!rect.width) return -1;
      var sx = ((e.clientX - rect.left) / rect.width) * width;
      if (sx < pad.l - 6 || sx > width - pad.r + 6) return -1;
      var idx = Math.round((sx - pad.l) / (plotW / Math.max(1, n - 1)));
      return Math.max(0, Math.min(n - 1, idx));
    }
    function onMove(e) {
      var idx = indexFromEvent(e);
      state.activeIndex = idx;
      if (idx < 0) { tip.hidden = true; return; }
      // 高亮当前位置的圆点
      var circles = svg.querySelectorAll('circle.pt');
      circles.forEach(function (c) { c.classList.remove('on'); });
      renderTooltip(tip, state, idx);
      var rect = svg.getBoundingClientRect();
      var px = rect.left + (x(idx) / width) * rect.width;
      var py = rect.top + pad.t;
      var tw = tip.offsetWidth || 170;
      tip.style.left = Math.min(global.innerWidth - tw - 10, Math.max(8, px + 14)) + 'px';
      tip.style.top = Math.max(8, py - 10) + 'px';
    }
    function onLeave() { tip.hidden = true; }
    el.addEventListener('mousemove', onMove);
    el.addEventListener('mouseleave', onLeave);
    el.addEventListener('touchstart', function (e) {
      if (e.touches && e.touches.length) { var t = e.touches[0]; onMove({ clientX: t.clientX }); }
    }, { passive: true });

    var cleanup = function () {
      el.removeEventListener('mousemove', onMove);
      el.removeEventListener('mouseleave', onLeave);
    };
    registry.push({ el: el, cleanup: cleanup, state: state, svg: svg });
  }

  /**
   * 迷你趋势图（sparkline）：无坐标轴/hover，仅展示形状；用于上行带宽等小指标。
   * @param {HTMLElement} el 容器
   * @param {object} opts {values:number[], color, height, area, title, emptyText}
   */
  function sparkline(el, opts) {
    if (!el) return;
    opts = opts || {};
    var values = (Array.isArray(opts.values) ? opts.values : []).map(function (v) {
      var n = Number(v);
      return isFinite(n) && n >= 0 ? n : 0;
    });
    var height = opts.height || 36;
    var width = Math.max(120, Math.round(el.clientWidth || 240));
    if (!values.length) {
      el.innerHTML = '<div class="chart-empty small">' + U.esc(opts.emptyText || '暂无数值') + '</div>';
      return;
    }
    var color = safeColor(opts.color, '#4da3ff');
    var max = Math.max.apply(null, values);
    var top = max > 0 ? max : 1;
    var pad = 3;
    var n = values.length;
    function x(i) { return pad + (n === 1 ? (width - pad * 2) / 2 : i * (width - pad * 2) / (n - 1)); }
    function y(v) { return height - pad - (top > 0 ? v / top : 0) * (height - pad * 2); }
    var pts = values.map(function (v, i) { return x(i).toFixed(1) + ',' + y(v).toFixed(1); });
    var d = 'M' + pts.join(' L');
    var parts = [];
    if (opts.area !== false) {
      parts.push('<path class="spark-area" fill="' + color + '" d="' + d + ' L' + x(n - 1).toFixed(1) + ',' + (height - pad) + ' L' + x(0).toFixed(1) + ',' + (height - pad) + ' Z"></path>');
    }
    parts.push('<path class="spark-line" stroke="' + color + '" d="' + d + '"></path>');
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'sparkline');
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
    svg.setAttribute('preserveAspectRatio', 'none');
    svg.setAttribute('role', 'img');
    svg.setAttribute('aria-label', opts.title || '迷你趋势图');
    svg.style.height = height + 'px';
    svg.innerHTML = parts.join('');
    el.innerHTML = '';
    el.appendChild(svg);
  }

  function clear(el) {
    var inst = findInstance(el);
    if (inst && inst.cleanup) inst.cleanup();
    if (el) el.innerHTML = '';
  }

  global.PortalChart = { line: line, sparkline: sparkline, clear: clear };
})(window);
