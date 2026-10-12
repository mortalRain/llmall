/* 临时诊断脚本：注入到 _probe.html，测量布局 + 检查 CSS 是否生效 + 记录接口错误 */
setTimeout(function () {
  function box(el) {
    var r = el.getBoundingClientRect();
    return { x: Math.round(r.left), y: Math.round(r.top + window.scrollY), w: Math.round(r.width), h: Math.round(r.height) };
  }
  var out = { hash: location.hash, docH: document.documentElement.scrollHeight, vw: window.innerWidth, sections: [], css: [], apiErrors: window.__apiErrors || [], flags: [] };

  // 1) CSS 是否生效：逐个选择器看 display / grid-template-columns / padding
  var probes = ['.item-top', '.two-col', '.pkg-grid', '.rev-grid', '.mall-grid.dense', '.cat-tiles', '.hero-stats', '.buy-card', '.pkg', '.side-item', '.sort-bar', '.cat-tile', '.item-cover'];
  probes.forEach(function (s) {
    var el = document.querySelector(s);
    if (!el) { out.css.push({ sel: s, exists: false }); return; }
    var cs = getComputedStyle(el);
    out.css.push({
      sel: s, exists: true,
      display: cs.display,
      cols: cs.gridTemplateColumns,
      pad: cs.padding,
      cls: el.className.toString().slice(0, 30)
    });
  });

  // 2) 样式表解析情况
  var sheets = [];
  for (var i = 0; i < document.styleSheets.length; i++) {
    var ss = document.styleSheets[i];
    var n = -1, err = '';
    try { n = ss.cssRules ? ss.cssRules.length : -1; } catch (e) { err = String(e).slice(0, 60); }
    sheets.push({ href: (ss.href || 'inline').split('/').pop(), rules: n, err: err });
  }
  out.sheets = sheets;

  // 3) 关键区块盒子
  ['.hero', '.hero-stats', '.cat-tiles', '.sort-bar', '.layout', '.aside', '.mall-grid', '.pkg-grid', '.pkg',
   '.item-top', '.item-cover', '.buy-card', '.two-col', '.rev-grid', '.side-item', '.icard', '.panel',
   '.topbar-in', '.nav a', '.crow', '.ocard', '.detail-head'].forEach(function (s) {
    var list = document.querySelectorAll(s);
    if (!list.length) return;
    var o = { sel: s, count: list.length, box: box(list[0]) };
    if (list.length > 1) { var last = box(list[list.length - 1]); o.lastBox = last; }
    out.sections.push(o);
  });

  // 4) 留白检测
  var wasted = [];
  document.querySelectorAll('.item, .crow, .ocard, .pkg, .side-item, .card, .icard').forEach(function (el) {
    var r = el.getBoundingClientRect();
    var kids = Array.prototype.slice.call(el.children);
    if (!kids.length) return;
    var right = Math.max.apply(null, kids.map(function (k) { return k.getBoundingClientRect().right; }));
    var left = Math.min.apply(null, kids.map(function (k) { return k.getBoundingClientRect().left; }));
    var ratio = r.width > 0 ? (right - left) / r.width : 1;
    if (ratio < 0.75) wasted.push({ cls: el.className.toString().slice(0, 24), w: Math.round(r.width), used: Math.round(right - left), ratio: Math.round(ratio * 100) + '%' });
  });
  out.wasted = wasted.slice(0, 8);

  // 5) 溢出与裁切
  if (document.documentElement.scrollWidth > window.innerWidth + 2) out.flags.push('横向溢出 ' + document.documentElement.scrollWidth);
  var clipped = 0;
  document.querySelectorAll('h3,h4,h5,.nm,.si b,.t,.pi h4').forEach(function (el) {
    if (el.clientWidth > 0 && el.scrollWidth > el.clientWidth + 2) clipped++;
  });
  out.clipped = clipped;

  var pre = document.createElement('pre');
  pre.id = '__METRICS';
  pre.textContent = 'METRICS_JSON:' + JSON.stringify(out);
  document.body.appendChild(pre);
}, 9000);
