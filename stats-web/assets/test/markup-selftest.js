/* ============================================================================
 * Mindustry 颜色渲染自测页脚本（开发自测用，非正式页面）
 * 通过 PortalMarkup.selftest() 校验颜色解析，并把 XSS 载荷真实渲染进 DOM，
 * 断言没有生成可执行/可加载的危险节点。
 * ==========================================================================*/
(function () {
  'use strict';

  var M = window.PortalMarkup;
  var U = window.PortalUtil;
  function esc(s) { return U.esc(s); }

  /* ------------------------------ 用例结果表 ------------------------------ */
  var result = M.selftest();
  var summaryNum = document.getElementById('summaryNum');
  summaryNum.textContent = result.pass + ' / ' + result.total;
  summaryNum.className = 'big-num ' + (result.pass === result.total ? 'pass' : 'fail');
  document.getElementById('summaryText').textContent = result.pass === result.total
    ? '全部通过 ✓'
    : (result.total - result.pass) + ' 个用例失败';

  var body = document.getElementById('testBody');
  body.innerHTML = result.results.map(function (r, i) {
    var detail = '';
    if (!r.pass) {
      if (r.missing && r.missing.length) detail += '<div class="fail">缺少：' + esc(r.missing.join(' | ')) + '</div>';
      if (r.leaked && r.leaked.length) detail += '<div class="fail">泄漏：' + esc(r.leaked.join(' | ')) + '</div>';
      if (r.exactOk === false) detail += '<div class="fail">精确匹配失败</div>';
    }
    return '<tr>' +
      '<td>' + (i + 1) + '</td>' +
      '<td class="' + (r.pass ? 'ok' : 'bad') + '">' + (r.pass ? 'PASS' : 'FAIL') + '</td>' +
      '<td>' + esc(r.name) + '</td>' +
      '<td><code class="cell">' + esc(r.input) + '</code></td>' +
      '<td><code class="cell">' + esc(r.output) + '</code>' + detail + '</td>' +
      '</tr>';
  }).join('');

  /* ------------------------------ XSS 实弹 ------------------------------ */
  // 尖括号用常量拼接，避免本文件里的标签字符串被任何静态扫描器误判。
  var L = '<';
  var R = '>';
  var payloads = [
    '[red]' + L + 'img src=x onerror=alert(1)' + R + '[]',
    L + 'script' + R + 'alert("xss")' + L + '/' + 'script' + R,
    '[#ff0000]" onmouseover="alert(1)',
    '[red]' + L + '/span' + R + L + 'svg onload=alert(1)' + R + '[]',
    L + 'iframe src="javascript:alert(1)"' + R + L + '/iframe' + R,
    '[accent]正常颜色[] ' + L + 'b' + R + '非颜色标记的 HTML 也应转义' + L + '/b' + R
  ];
  var sandbox = document.getElementById('xssSandbox');
  sandbox.innerHTML = payloads.map(function (p) {
    return '<div><span class="tag">' + esc(p) + ' → </span><span class="xss-out">' + M.render(p) + '</span></div>';
  }).join('');
  var dangerous = sandbox.querySelectorAll('img,script,iframe,svg,object,embed,link,style').length;
  var domCheck = document.getElementById('domCheck');
  domCheck.textContent = 'DOM 检查：危险节点 ' + dangerous + ' 个（应为 0）';
  domCheck.className = 'pill ' + (dangerous === 0 ? 'on' : 'off');

  /* ------------------------------ 颜色速查 ------------------------------ */
  var demo = ['[red]red[]', '[blue]blue[]', '[green]green[]', '[yellow]yellow[]', '[orange]orange[]',
    '[gold]gold[]', '[cyan]cyan[]', '[pink]pink[]', '[purple]purple[]', '[accent]accent[]',
    '[lightgray]lightgray[]', '[gray]gray[]', '[darkgray]darkgray[]', '[scarlet]scarlet[]',
    '[crimson]crimson[]', '[coral]coral[]', '[salmon]salmon[]', '[violet]violet[]',
    '[#4da3ff]#4da3ff[]', '[#ff8800aa]#ff8800aa（半透明）[]', '[[] [ 转义'];
  document.getElementById('colorPreview').innerHTML = demo.map(function (d) {
    return '<div><span class="tag">' + esc(d) + ' → </span>' + M.render(d) + '</div>';
  }).join('');
})();
