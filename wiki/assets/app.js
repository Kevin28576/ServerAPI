(function () {
  var root = document.documentElement;

  /* ── 語言 ── */
  function setLang(code) {
    root.setAttribute('data-lang', code);
    root.setAttribute('lang', code === 'zh' ? 'zh-Hant' : 'en');
    document.querySelectorAll('.langs button').forEach(function (b) {
      b.classList.toggle('on', b.dataset.set === code);
    });
    try { localStorage.setItem('serverapi-wiki-lang', code); } catch (e) {}
  }
  document.querySelectorAll('.langs button').forEach(function (b) {
    b.onclick = function () { setLang(b.dataset.set); render(document.getElementById('q').value); };
  });
  var saved = null;
  try { saved = localStorage.getItem('serverapi-wiki-lang'); } catch (e) {}
  var browserZh = (navigator.language || '').toLowerCase().indexOf('zh') === 0;
  setLang(saved || (browserZh ? 'zh' : 'en'));

  /* 進場淡入：先加上隱藏狀態再於下一幀移除。計時器是保底 ——
     rAF 若不回呼，內容仍會在 400ms 內顯示。 */
  var mainEl = document.querySelector('main');
  function reveal() { mainEl.classList.remove('enter'); }
  mainEl.classList.add('enter');
  requestAnimationFrame(function () { requestAnimationFrame(reveal); });
  setTimeout(reveal, 400);

  /* ── 程式碼上色（無外部相依、離線可用；JS 未跑時退回純文字仍可讀）──
     不分語言的通用掃描器：註解、字串、數字、關鍵字、YAML/JSON 鍵。
     刻意處理兩個易錯處：:// 網址不可誤判成註解、鍵後的 : 不可吃掉 :// 與 ::。 */
  var HL_KW = /^(?:var|new|return|if|else|for|while|do|void|public|private|protected|final|static|abstract|class|interface|enum|import|package|extends|implements|throws|throw|try|catch|finally|this|super|boolean|byte|short|int|long|float|double|char|true|false|null)$/;
  function hlEsc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function hlSpan(cls, s) { return '<span class="hl-' + cls + '">' + hlEsc(s) + '</span>'; }
  function hlTokenize(src) {
    var out = '', i = 0, n = src.length;
    while (i < n) {
      var c = src[i], rest = src.slice(i), m;
      // 註解：# 到行尾，或 // 到行尾（前一字為 : 時是網址，不算註解）
      if (c === '#' || (c === '/' && src[i + 1] === '/' && src[i - 1] !== ':')) {
        var e = src.indexOf('\n', i); if (e < 0) e = n;
        out += hlSpan('c', src.slice(i, e)); i = e; continue;
      }
      // 字串（單/雙引號，支援跳脫）
      if (c === '"' || c === "'") {
        var q = c, j = i + 1;
        while (j < n && src[j] !== q) { if (src[j] === '\\') j++; j++; }
        j = Math.min(j + 1, n);
        out += hlSpan('s', src.slice(i, j)); i = j; continue;
      }
      // 數字
      m = rest.match(/^\d[\d._]*/);
      if (m) { out += hlSpan('n', m[0]); i += m[0].length; continue; }
      // 註記 @Word
      if (c === '@') { m = rest.match(/^@\w+/); if (m) { out += hlSpan('k', m[0]); i += m[0].length; continue; } }
      // 識別字：關鍵字、或後接單一 : 的 YAML/JSON 鍵，其餘原樣
      m = rest.match(/^[A-Za-z_$][\w$-]*/);
      if (m) {
        var w = m[0], after = src.slice(i + w.length);
        if (HL_KW.test(w)) out += hlSpan('k', w);
        else if (/^\s*:(?![:/=])/.test(after)) out += hlSpan('key', w);
        else out += hlEsc(w);
        i += w.length; continue;
      }
      out += hlEsc(c); i++;
    }
    return out;
  }
  document.querySelectorAll('main pre > code').forEach(function (code) {
    code.innerHTML = hlTokenize(code.textContent);
  });

  /* 站內導覽時顯示頂部進度條 */
  var bar = document.getElementById('progress');
  function startBar() {
    bar.classList.remove('on');
    void bar.offsetWidth;      // 重新觸發動畫，連點時才會從頭開始
    bar.classList.add('on');
  }
  document.addEventListener('click', function (e) {
    var a = e.target.closest('a[href]');
    if (!a || a.target || a.hasAttribute('download')) return;
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    var href = a.getAttribute('href');
    if (!href || href.charAt(0) === '#' || /^[a-z]+:/i.test(href)) return;
    startBar();
  });
  // 從上一頁返回時 bfcache 會保留 DOM，進度條可能停在半路
  addEventListener('pageshow', function () {
    bar.classList.remove('on');
    reveal();
  });

  /* 右側「本頁內容」：捲動時標示目前所在標題 */
  var tocLinks = [], tocTargets = [];
  function initToc() {
    var lang = root.getAttribute('data-lang');
    var box = document.querySelector('.toc .' + lang);
    tocLinks = box ? [].slice.call(box.querySelectorAll('a')) : [];
    tocTargets = tocLinks.map(function (a) { return document.getElementById(a.dataset.h); })
                         .filter(Boolean);
    tocSpy();
  }
  function tocSpy() {
    if (!tocTargets.length) return;
    var atBottom = innerHeight + scrollY >= document.documentElement.scrollHeight - 2;
    var cur = atBottom ? tocTargets[tocTargets.length - 1] : tocTargets[0];
    if (!atBottom) {
      tocTargets.forEach(function (h) {
        if (h.getBoundingClientRect().top <= 140) cur = h;
      });
    }
    tocLinks.forEach(function (a) { a.classList.toggle('on', a.dataset.h === cur.id); });
  }
  addEventListener('scroll', tocSpy, { passive: true });
  initToc();
  // 切換語言後右側目錄整份換掉，要重新綁定
  new MutationObserver(initToc).observe(root, { attributes: true, attributeFilter: ['data-lang'] });

  /* 頂欄高度隨換行改變，量測後寫進變數供側欄定位使用 */
  function syncTop() {
    root.style.setProperty('--top-h', document.querySelector('.top').offsetHeight + 'px');
  }
  addEventListener('resize', syncTop);
  syncTop();

  /* ── 側欄（窄螢幕為抽屜） ── */
  var side = document.getElementById('side'), scrim = document.getElementById('scrim');
  function drawer(open) {
    side.classList.toggle('open', open);
    scrim.classList.toggle('on', open);
  }
  document.getElementById('menu').onclick = function () { drawer(!side.classList.contains('open')); };
  scrim.onclick = function () { drawer(false); };

  /* ── 搜尋：整站索引在建置時產生，純用戶端比對，無需後端 ── */
  var BASE = root.dataset.base || '';
  var INDEX = [], q = document.getElementById('q'), box = document.getElementById('results');
  fetch(BASE + 'assets/search.json').then(function (r) { return r.json(); })
    .then(function (d) { INDEX = d; })
    .catch(function () { /* 以 file:// 開啟時 fetch 會被擋，搜尋停用，其餘功能不受影響 */ });

  /* 沒有可見文字的元件（搜尋框提示、選單鈕的輔助標籤）改由這裡依語言指定 */
  function labels() {
    var zh = root.getAttribute('data-lang') === 'zh';
    q.placeholder = zh ? '搜尋文件…' : 'Search docs…';
    var menuBtn = document.getElementById('menu');
    menuBtn.setAttribute('aria-label', zh ? '選單' : 'Menu');
    menuBtn.setAttribute('title', zh ? '選單' : 'Menu');
  }
  var langObserver = new MutationObserver(labels);
  langObserver.observe(root, { attributes: true, attributeFilter: ['data-lang'] });
  labels();

  function esc(s) { var d = document.createElement('div'); d.textContent = s; return d.innerHTML; }

  function render(term) {
    term = (term || '').trim().toLowerCase();
    if (!term) { box.classList.remove('on'); box.innerHTML = ''; return; }
    var lang = root.getAttribute('data-lang');
    var hits = [];
    INDEX.forEach(function (p) {
      var t = p[lang].title, body = p[lang].text;
      var i = body.toLowerCase().indexOf(term);
      var inTitle = t.toLowerCase().indexOf(term) >= 0;
      if (i < 0 && !inTitle) return;
      var snip = i < 0 ? body.slice(0, 90)
                       : body.slice(Math.max(0, i - 35), Math.max(0, i - 35) + 110);
      hits.push({ url: p.url, title: t, snip: (i > 35 ? '…' : '') + snip + '…', top: inTitle });
    });
    hits.sort(function (a, b) { return (b.top ? 1 : 0) - (a.top ? 1 : 0); });
    if (!hits.length) {
      box.innerHTML = '<div class="none">' +
        (lang === 'zh' ? '找不到符合的內容' : 'No matches') + '</div>';
    } else {
      box.innerHTML = hits.slice(0, 8).map(function (h) {
        return '<a href="' + BASE + h.url + '"><b>' + esc(h.title) + '</b><small>' +
               esc(h.snip) + '</small></a>';
      }).join('');
    }
    box.classList.add('on');
  }
  q.oninput = function () { render(q.value); };
  q.onfocus = function () { if (q.value.trim()) render(q.value); };

  /* 鍵盤：/ 聚焦、Esc 收起、上下鍵與 Enter 選取 */
  var sel = -1;
  addEventListener('keydown', function (e) {
    if (e.key === '/' && document.activeElement !== q) { e.preventDefault(); q.focus(); return; }
    if (!box.classList.contains('on')) return;
    var items = [].slice.call(box.querySelectorAll('a'));
    if (e.key === 'Escape') { box.classList.remove('on'); q.blur(); sel = -1; }
    else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (!items.length) return;
      e.preventDefault();
      sel = (sel + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
      items.forEach(function (a, i) { a.classList.toggle('sel', i === sel); });
      items[sel].scrollIntoView({ block: 'nearest' });
    } else if (e.key === 'Enter' && sel >= 0 && items[sel]) { items[sel].click(); }
  });
  document.addEventListener('click', function (e) {
    if (!e.target.closest('.search')) { box.classList.remove('on'); sel = -1; }
  });
})();
