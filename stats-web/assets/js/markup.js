/* ============================================================================
 * MDT DO 服务器门户 - Mindustry 彩色文字渲染
 * ----------------------------------------------------------------------------
 * 规则对齐 Arc/MindustryX `arc.util.Strings#parseColorMarkup` 与 `Colors` 命名色：
 *   - `[red]` / `[LIGHT_GRAY]` 等命名色（大小写不敏感，兼容 snake_case）；
 *   - `[#RRGGBB]` 与 `[#RRGGBBAA]` 十六进制色（AA 可选，00-FF 透明度）；
 *   - `[]` 出栈：恢复到上一个颜色（颜色栈，与游戏一致）；
 *   - `[[` 为左方括号转义（`[` 按字面显示，后一个 `[` 仍按标记解析，同游戏）；
 *   - 未知标记原样按文本显示；`[[:`/未闭合标记也只是文本。
 *
 * 安全策略（XSS）：**先对文本做 HTML 转义，再包颜色 span**。
 *   - 文本片段经 esc() 转义后进入 innerHTML；
 *   - 颜色值只可能是本文件内置常量，或由 `[#hex]` 的数字解析生成（不拼接任意字符串），
 *     因此 style 属性不存在注入面；
 *   - 输出中的 `<span style="color:...">` 是本模块唯一会产生的标签。
 * ==========================================================================*/
(function (global) {
  'use strict';

  var ESC_MAP = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) { return ESC_MAP[ch]; });
  }

  /* ------------------------- 命名色（Arc Colors + Mindustry UI） ------------------------- */
  // [小写无下划线名, CSS 颜色, 大写/下划线别名]
  var NAMED = [
    ['clear', 'transparent', 'CLEAR'],
    ['black', '#000000', 'BLACK'],
    ['white', '#ffffff', 'WHITE'],
    ['lightgray', '#bfbfbf', 'LIGHT_GRAY'], ['lightgrey', '#bfbfbf', 'LIGHT_GREY'],
    ['gray', '#7f7f7f', 'GRAY'], ['grey', '#7f7f7f', 'GREY'],
    ['darkgray', '#3f3f3f', 'DARK_GRAY'], ['darkgrey', '#3f3f3f', 'DARK_GREY'],
    ['blue', '#4169e1', 'BLUE'], ['navy', '#000080', 'NAVY'], ['royal', '#4169e1', 'ROYAL'],
    ['slate', '#708090', 'SLATE'], ['sky', '#87ceeb', 'SKY'],
    ['cyan', '#00ffff', 'CYAN'], ['teal', '#008080', 'TEAL'],
    ['green', '#38d667', 'GREEN'], ['acid', '#7fff00', 'ACID'], ['lime', '#32cd32', 'LIME'],
    ['forest', '#228b22', 'FOREST'], ['olive', '#6b8e23', 'OLIVE'],
    ['yellow', '#ffff00', 'YELLOW'], ['gold', '#ffd700', 'GOLD'],
    ['goldenrod', '#daa520', 'GOLDENROD'], ['orange', '#ffa500', 'ORANGE'],
    ['brown', '#8b4513', 'BROWN'], ['tan', '#d2b48c', 'TAN'], ['brick', '#b22222', 'BRICK'],
    ['red', '#e55454', 'RED'], ['scarlet', '#ff341c', 'SCARLET'], ['crimson', '#dc143c', 'CRIMSON'],
    ['coral', '#ff7f50', 'CORAL'], ['salmon', '#fa8072', 'SALMON'],
    ['pink', '#ff69b4', 'PINK'], ['magenta', '#ff00ff', 'MAGENTA'],
    ['purple', '#a020f0', 'PURPLE'], ['violet', '#ee82ee', 'VIOLET'], ['maroon', '#b03060', 'MAROON'],
    // Mindustry UI 额外注册（UI.init 中 Colors.put）：accent/unlaunched/highlight/stat/negstat
    ['accent', '#ffd37f', 'ACCENT'],
    ['highlight', '#ffe0a5', 'HIGHLIGHT'],
    ['unlaunched', '#8982ed', 'UNLAUNCHED'],
    ['stat', '#ffd37f', 'STAT'],
    ['negstat', '#e55454', 'NEGSTAT']
  ];

  var NAMED_MAP = Object.create(null);
  NAMED.forEach(function (row) {
    var lower = row[0], css = row[1], upper = row[2];
    NAMED_MAP[lower] = css;            // lightgray
    NAMED_MAP[upper] = css;            // LIGHT_GRAY
    NAMED_MAP[upper.toLowerCase()] = css; // light_gray
  });

  /** 命名色查找：大小写不敏感，忽略下划线；未知返回 null。 */
  function lookupNamedColor(name) {
    if (!name) return null;
    if (NAMED_MAP[name]) return NAMED_MAP[name];
    var lower = name.toLowerCase();
    if (NAMED_MAP[lower]) return NAMED_MAP[lower];
    var compact = lower.replace(/_/g, '');
    if (NAMED_MAP[compact]) return NAMED_MAP[compact];
    var upper = name.toUpperCase();
    if (NAMED_MAP[upper]) return NAMED_MAP[upper];
    return null;
  }

  /** `#RRGGBB` / `#RRGGBBAA` -> CSS 颜色；非法返回 null。 */
  function hexToCss(digits) {
    if (digits.length === 6) return '#' + digits.toLowerCase();
    if (digits.length === 8) {
      var r = parseInt(digits.slice(0, 2), 16);
      var g = parseInt(digits.slice(2, 4), 16);
      var b = parseInt(digits.slice(4, 6), 16);
      var a = parseInt(digits.slice(6, 8), 16) / 255;
      a = Math.round(a * 1000) / 1000;
      return 'rgba(' + r + ', ' + g + ', ' + b + ', ' + a + ')';
    }
    return null;
  }

  /**
   * 从 src[start]（已知是 '['）解析一个颜色标记。
   * @returns {{end:number, css:string}|{end:number, pop:true}|null}
   */
  function parseTag(src, start) {
    var close = src.indexOf(']', start + 1);
    if (close < 0) return null; // 未闭合，按文本处理
    var inner = src.slice(start + 1, close);
    if (inner === '') return { end: close + 1, pop: true };
    if (inner.charAt(0) === '#') {
      var digits = inner.slice(1);
      if (!/^[0-9a-fA-F]+$/.test(digits)) return null;
      var css = hexToCss(digits);
      return css ? { end: close + 1, css: css } : null;
    }
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(inner)) return null;
    var named = lookupNamedColor(inner);
    return named ? { end: close + 1, css: named } : null;
  }

  var MAX_INPUT = 4000;

  /**
   * 把 Mindustry 颜色标记文本渲染为安全 HTML。
   * @param {*} text 原始文本（可能含颜色标记）
   * @returns {string} 可安全写入 innerHTML 的 HTML
   */
  function render(text) {
    var src = text == null ? '' : String(text);
    if (src.length > MAX_INPUT) src = src.slice(0, MAX_INPUT) + '…';
    var out = [];
    var stack = [];
    var current = null; // 当前生效的 CSS 颜色（null = 默认）
    var buf = '';

    function flush() {
      if (!buf) return;
      out.push(current
        ? '<span style="color:' + current + '">' + esc(buf) + '</span>'
        : esc(buf));
      buf = '';
    }

    var i = 0;
    while (i < src.length) {
      var ch = src.charAt(i);
      if (ch === '[' && src.charAt(i + 1) === '[') {
        // 转义左方括号：输出一个字面 '['，只前进 1 格（同 Arc stripColors）
        buf += '[';
        i += 1;
        continue;
      }
      if (ch === '[') {
        var tag = parseTag(src, i);
        if (tag) {
          flush();
          if (tag.pop) stack.pop();
          else stack.push(tag.css);
          current = stack.length ? stack[stack.length - 1] : null;
          i = tag.end;
          continue;
        }
      }
      buf += ch;
      i += 1;
    }
    flush();
    return out.join('');
  }

  /**
   * 去掉已知颜色标记，保留未知标记与普通文本（用于标题/摘要等纯文本场景）。
   */
  function plain(text) {
    var src = text == null ? '' : String(text);
    var out = '';
    var i = 0;
    while (i < src.length) {
      var ch = src.charAt(i);
      if (ch === '[' && src.charAt(i + 1) === '[') {
        out += '[';
        i += 1;
        continue;
      }
      if (ch === '[') {
        var tag = parseTag(src, i);
        if (tag) { i = tag.end; continue; }
      }
      out += ch;
      i += 1;
    }
    return out;
  }

  /** 统计颜色标记数量（自测/调试用）。 */
  function countTags(text) {
    var src = text == null ? '' : String(text);
    var n = 0, i = 0;
    while (i < src.length) {
      if (src.charAt(i) === '[' && src.charAt(i + 1) === '[') { i += 1; continue; }
      if (src.charAt(i) === '[') {
        var tag = parseTag(src, i);
        if (tag) { n++; i = tag.end; continue; }
      }
      i += 1;
    }
    return n;
  }

  /**
   * 修复「上游截断可能造成的残缺颜色标记」。
   * 上游 liveEventFeed.kts 会按字符数截断 player/text（默认 64/500 字符，颜色标记也计入），
   * 可能把 `[white]` 切成 `[whi`。这里把末尾未闭合的 `[...` 片段去掉，避免页面直接显示
   * `[whi` 之类残渣（只影响显示，不改变「按字符记录」的事实）。
   * @param {*} text
   * @returns {{text:string, repaired:boolean, dangling:string}}
   */
  function repairTruncated(text) {
    var src = text == null ? '' : String(text);
    var i = 0;
    var lastUnclosed = -1;
    while (i < src.length) {
      var ch = src.charAt(i);
      if (ch === '[') {
        if (src.charAt(i + 1) === '[') { i += 2; continue; } // 转义左括号，整体跳过
        var close = src.indexOf(']', i + 1);
        if (close < 0) { lastUnclosed = i; break; }
        if (src.slice(i + 1, close).indexOf('[') >= 0) { i += 1; continue; } // 括号内再有 '['，按普通文本
        i = close + 1;
        continue;
      }
      i += 1;
    }
    if (lastUnclosed >= 0) {
      return { text: src.slice(0, lastUnclosed), repaired: true, dangling: src.slice(lastUnclosed) };
    }
    return { text: src, repaired: false, dangling: '' };
  }

  /**
   * 玩家名渲染信息（供 UI 层统一使用，保证「不二次截断 + 可看完整名」）。
   * @param {*} raw 原始玩家名（可能带颜色标记、可能被上游截断）
   * @param {number} [maxChars] 允许显示的最大字符数（默认 512 = 上游 maxPlayerChars 256 的 2 倍）
   * @returns {{raw:string, text:string, html:string, plain:string, repaired:boolean, truncated:boolean, chars:number}}
   */
  function nameInfo(raw, maxChars) {
    var limit = Math.max(16, Number(maxChars) || 512);
    var original = raw == null ? '' : String(raw);
    var repaired = repairTruncated(original);
    var text = repaired.text;
    var truncated = false;
    if (text.length > limit) {
      text = text.slice(0, limit);
      truncated = true;
      // 截断后若又切出半个颜色标记，再次收尾
      text = repairTruncated(text).text;
    }
    return {
      raw: original,
      text: text,
      html: render(text),
      plain: plain(text),
      repaired: repaired.repaired,
      truncated: truncated,
      chars: original.length
    };
  }

  /* ----------------------------- 内置自测（XSS / 颜色） ----------------------------- */
  function makeCases() {
    function hasAll(name, input, includes, excludes, exact) {
      return { name: name, input: input, includes: includes || [], excludes: excludes || [], exact: exact };
    }
    return [
      hasAll('基础命名色', '[red]红[]白', [], [], '<span style="color:#e55454">红</span>白'),
      hasAll('十六进制 6 位', '[#00ff00]绿', [], [], '<span style="color:#00ff00">绿</span>'),
      hasAll('十六进制 8 位带透明', '[#ff8800aa]橙',
        ['rgba(255, 136, 0, 0.667)']),
      hasAll('嵌套 + 出栈恢复', '[red]a[blue]b[]c[]d', [], [],
        '<span style="color:#e55454">a</span><span style="color:#4169e1">b</span><span style="color:#e55454">c</span>d'),
      hasAll('accent 色', '[accent]高亮[]普通', ['color:#ffd37f']),
      hasAll('LIGHT_GRAY 与大写下划线', '[LIGHT_GRAY]灰', ['color:#bfbfbf']),
      hasAll('XSS 裸标签', '<img src=x onerror=alert(1)>',
        ['&lt;img src=x onerror=alert(1)&gt;'], ['<img']),
      hasAll('XSS 在颜色内', '[red]<script>alert(1)</script>[]',
        ['<span style="color:#e55454">', '&lt;script&gt;alert(1)&lt;/script&gt;'], ['<script']),
      hasAll('XSS 属性注入', '[red]" onmouseover="alert(1)',
        ['&quot; onmouseover=&quot;alert(1)'], [' onmouseover="']),
      hasAll('XSS 闭合 span', '[red]</span><img src=x>',
        ['&lt;/span&gt;&lt;img src=x&gt;'], ['<img']),
      hasAll('未知标记按文本', '[foo]bar', [], [], '[foo]bar'),
      hasAll('未知十六进制长度按文本', '[#12]x', [], [], '[#12]x'),
      hasAll('双左括号转义', '[[red]x', [], [], '[<span style="color:#e55454">x</span>'),
      hasAll('未闭合颜色包裹到结尾', '[red]未闭合', ['<span style="color:#e55454">未闭合</span>']),
      hasAll('空栈出栈无害', '[]abc', [], [], 'abc'),
      hasAll('文本中的 & 转义', '[red]A&B', ['<span style="color:#e55454">A&amp;B</span>']),
      hasAll('换行保留', 'a\nb', [], [], 'a\nb'),
      hasAll('纯色透明与游戏一致', '[clear]x', ['color:transparent'], ['<script']),
      // ---- 上游截断修复 / 玩家名长度保护（自定义检查）----
      { name: '截断修复：残缺颜色标记被收尾', input: '[pink]猫娘[white][whi',
        custom: function (r) { var x = repairTruncated(r); return x.repaired && x.text === '[pink]猫娘[white]'; } },
      { name: '截断修复：完整文本原样保留', input: '[pink]猫娘[white]',
        custom: function (r) { var x = repairTruncated(r); return !x.repaired && x.text === r; } },
      { name: '玩家名：默认上限 512（≥2×256）生效', input: new Array(601).join('x'),
        custom: function (r) { var info = nameInfo(r); return info.truncated && info.text.length <= 512; } },
      { name: '玩家名：未超 512 的长名不截断', input: new Array(61).join('名'),
        custom: function (r) { var info = nameInfo(r); return !info.truncated && info.plain.length === 60; } },
      { name: '玩家名：渲染仍做 XSS 转义', input: '[red]<img src=x onerror=alert(1)>',
        custom: function (r) { var info = nameInfo(r); return info.html.indexOf('<img') < 0 && info.html.indexOf('&lt;img') >= 0; } }
    ];
  }

  /** 在浏览器中运行自测，返回 {pass, total, results:[...]}。 */
  function selftest() {
    var results = makeCases().map(function (c) {
      if (typeof c.custom === 'function') {
        var customOk = false;
        try { customOk = !!c.custom(c.input); } catch (e) { customOk = false; }
        return { name: c.name, input: c.input, output: '(自定义检查)', pass: customOk, missing: [], leaked: [], exactOk: customOk };
      }
      var html;
      try { html = render(c.input); } catch (e) { html = ''; }
      var missing = c.includes.filter(function (s) { return html.indexOf(s) < 0; });
      var leaked = c.excludes.filter(function (s) { return html.indexOf(s) >= 0; });
      var exactOk = typeof c.exact === 'undefined' || html === c.exact;
      return {
        name: c.name,
        input: c.input,
        output: html,
        pass: missing.length === 0 && leaked.length === 0 && exactOk,
        missing: missing,
        leaked: leaked,
        exactOk: exactOk
      };
    });
    var pass = results.filter(function (r) { return r.pass; }).length;
    return { pass: pass, total: results.length, results: results };
  }

  global.PortalMarkup = {
    render: render,
    plain: plain,
    countTags: countTags,
    repairTruncated: repairTruncated,
    nameInfo: nameInfo,
    selftest: selftest,
    colors: NAMED_MAP
  };
})(window);
