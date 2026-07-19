package kevin.serverapi.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;

import java.util.Map;

/**
 * /api/docs — 互動式 API 文件。
 *
 * 列出所有已啟用的端點與參數，並可直接在頁面上送出請求看到真實回應，
 * 讓串接者不必另外準備工具就能理解每個端點的輸出。
 *
 * 端點清單由實際註冊結果產生，因此關閉的站點不會出現在文件中。
 */
public class DocsHandler extends ApiHandler {

    private final Map<String, String> stations;
    private final java.util.Set<String> protectedPaths;
    private final String basePath;

    public DocsHandler(ServerAPIPlugin plugin, Map<String, String> stations,
                       java.util.Set<String> protectedPaths, String basePath) {
        super(plugin);
        this.stations = stations;
        this.protectedPaths = protectedPaths;
        this.basePath = basePath;
    }

    @Override
    protected Object build(HttpExchange exchange) {
        if (!remainderOf(exchange).isEmpty()) {
            throw new NotFoundException(plugin.lang().get("api.error.not-found",
                    exchange.getRequestURI().getPath()));
        }

        var l = plugin.lang();
        // require-key 需搭配 auth.enabled 才會真正擋下請求
        boolean authOn = plugin.isAuthEnabled();

        Json.JsonArray endpoints = Json.arr();
        for (Map.Entry<String, String> e : stations.entrySet()) {
            String path = e.getKey();
            String station = path.substring(path.lastIndexOf('/') + 1);
            endpoints.add(Json.obj()
                    .put("path", path)
                    .put("station", station)
                    .put("description", e.getValue())
                    .put("requiresKey", authOn && protectedPaths.contains(path))
                    .put("params", paramsFor(station)));
        }

        Json.JsonObject t = Json.obj();
        for (String key : new String[]{"title", "subtitle", "base-url",
                "api-key", "api-key-placeholder", "api-key-hint", "auth-disabled", "key-required-hint",
                "quick-start", "quick-start-hint", "essentials", "response-format",
                "success-example", "error-example", "auth", "rate-limit", "rate-limit-hint",
                "endpoints", "search", "no-match", "chip-auth-on", "chip-auth-off", "chip-endpoints",
                "run", "copy", "copied", "params", "requires-key", "public", "loading", "failed"}) {
            t.put(key, l.get("web.docs." + key));
        }

        return new HtmlResponse(PAGE
                .replace("/*__ENDPOINTS__*/[]", endpoints.toJson())
                .replace("/*__T__*/{}", t.toJson())
                .replace("__BASE__", basePath + "/" + API_VERSION)
                .replace("__VERSION__", plugin.getPluginMeta().getVersion())
                .replace("__AUTHON__", String.valueOf(authOn))
                .replace("__LANG__", l.code().startsWith("zh") ? "zh-Hant" : "en"));
    }

    /**
     * 各站點支援的查詢參數，供文件顯示與試用表單使用。
     * 兩個 HTML 展示頁若已關閉就不列出——文件不該宣傳一個會回 404 的參數。
     */
    private Json.JsonArray paramsFor(String station) {
        Json.JsonArray out = Json.arr();
        switch (station) {
            case "status" -> out.add(param("history", "true", "Append a time series of online / discord counts"));
            case "player" -> {
                out.add(param("{uuid}", "path", "Player UUID (path segment)"));
                out.add(param("detailed", "true", "Expand per-block / item / entity statistics"));
            }
            case "worlds" -> out.add(param("{name}", "path", "World name (path segment)"));
            case "punishments" -> {
                out.add(param("{uuid}", "path", "Single player history (path segment)"));
                if (plugin.isPunishmentsHtmlEnabled()) {
                    out.add(param("view", "html", "Render the demo page instead of JSON"));
                }
            }
            case "placeholders" -> {
                out.add(param("uuid", "<uuid>", "Player to resolve placeholders for"));
                out.add(param("p", "%player_name%", "Comma separated placeholders (%25 encoded)"));
                if (plugin.isPlaceholdersListEnabled()) {
                    out.add(param("list", "true", "Browse all registered expansions as HTML"));
                }
            }
            default -> { }
        }
        return out;
    }

    private static Json.JsonObject param(String name, String value, String desc) {
        return Json.obj().put("name", name).put("value", value).put("description", desc);
    }

    private static final String PAGE = """
            <!doctype html><html lang="__LANG__"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>ServerAPI</title><style>
            :root{color-scheme:light dark;--bg:#0f1115;--card:#171a21;--fg:#e6e8ee;--mut:#9aa3b2;
                  --bd:#262b36;--acc:#4cc2ff;--ok:#2ecc71;--warn:#f39c12;--bar:#12151bd9}
            @media(prefers-color-scheme:light){:root{--bg:#f6f7f9;--card:#fff;--fg:#16181d;--mut:#5b6472;
                  --bd:#e2e5ea;--acc:#0969da;--bar:#ffffffd9}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);
                 font:15px/1.65 ui-sans-serif,system-ui,"Segoe UI","Noto Sans TC",sans-serif}
            pre{background:rgba(127,127,127,.1);border:1px solid var(--bd);border-radius:8px;padding:.75rem;
                overflow-x:auto;margin:0;font:12.5px/1.55 ui-monospace,Consolas,monospace}
            code{font:12.5px ui-monospace,Consolas,monospace}
            button{border:0;border-radius:7px;padding:.4rem 1rem;cursor:pointer;font:inherit;font-size:.85rem;
                   background:var(--acc);color:#fff}
            button.ghost{background:transparent;border:1px solid var(--bd);color:var(--mut)}
            button.ghost:hover{color:var(--fg)}
            button.sm{padding:.2rem .5rem;font-size:.75rem}

            /* Sticky toolbar: the key stays reachable from any endpoint, no scrolling back up */
            .top{position:sticky;top:0;z-index:30;background:var(--bar);
                 -webkit-backdrop-filter:blur(12px);backdrop-filter:blur(12px);border-bottom:1px solid var(--bd)}
            .top-in{max-width:1280px;margin:0 auto;padding:.55rem 1rem;display:flex;gap:.75rem;
                    align-items:center;flex-wrap:wrap}
            .brand{font-weight:700;display:flex;gap:.45rem;align-items:baseline}
            .brand .ver{font:11.5px ui-monospace,Consolas,monospace;color:var(--mut);font-weight:400}
            .tools{display:flex;gap:.5rem;align-items:stretch;flex-wrap:wrap;margin-left:auto}
            .field{display:flex;align-items:center;gap:.45rem;background:var(--card);border:1px solid var(--bd);
                   border-radius:8px;padding:.22rem .3rem .22rem .6rem}
            .field>span{font-size:.68rem;color:var(--mut);text-transform:uppercase;letter-spacing:.05em;
                        white-space:nowrap}
            .field code{color:var(--acc)}
            .field input{background:transparent;border:0;color:var(--fg);outline:none;padding:.2rem;
                         font:12.5px ui-monospace,Consolas,monospace;width:190px}
            .field input::placeholder{color:var(--mut);opacity:.7}
            .field.on{border-color:var(--acc)}

            .layout{display:grid;grid-template-columns:230px 1fr;gap:2.5rem;
                    max-width:1280px;margin:0 auto;padding:1.75rem 1rem 0}
            nav{position:sticky;top:4.4rem;align-self:start;max-height:calc(100vh - 6rem);
                overflow:auto;padding-bottom:1rem}
            nav input{width:100%;background:var(--card);border:1px solid var(--bd);color:var(--fg);
                      border-radius:8px;padding:.4rem .6rem;font:inherit;font-size:.85rem;
                      margin-bottom:.7rem;outline:none}
            nav input:focus{border-color:var(--acc)}
            nav a{display:block;padding:.28rem .55rem;border-radius:6px;color:var(--mut);
                  text-decoration:none;font-size:.87rem;border-left:2px solid transparent}
            nav a:hover{color:var(--fg);background:var(--card)}
            nav a.on{color:var(--acc);border-left-color:var(--acc);background:var(--card)}
            nav a.ep-link{font:12.5px ui-monospace,Consolas,monospace}
            nav .grp{font-size:.68rem;text-transform:uppercase;letter-spacing:.06em;color:var(--mut);
                     margin:.9rem 0 .3rem;padding-left:.55rem;opacity:.75}

            h1{font-size:1.85rem;margin:0 0 .3rem;letter-spacing:-.01em}
            .sub{color:var(--mut);margin:0 0 .9rem}
            .chips{display:flex;gap:.4rem;flex-wrap:wrap;margin-bottom:1.6rem}
            .chip{font-size:.75rem;padding:.2rem .6rem;border-radius:999px;border:1px solid var(--bd);
                  color:var(--mut);background:var(--card)}
            .chip.lock{color:var(--warn);border-color:#f39c1244;background:#f39c1214}
            .chip.open{color:var(--ok);border-color:#2ecc7144;background:#2ecc7114}

            section{scroll-margin-top:5rem}
            h2.sec{font-size:1.12rem;margin:0 0 .2rem;letter-spacing:-.01em}
            .lead{color:var(--mut);font-size:.87rem;margin:0 0 .8rem}
            .card{background:var(--card);border:1px solid var(--bd);border-radius:11px;padding:1rem}
            .stack{display:flex;flex-direction:column;gap:2.2rem;padding-bottom:3rem}

            .tabs{display:flex;gap:.3rem;margin-bottom:.7rem;flex-wrap:wrap}
            .tab{background:transparent;border:1px solid transparent;color:var(--mut);
                 padding:.25rem .7rem;font-size:.8rem;border-radius:7px}
            .tab:hover{color:var(--fg)}
            .tab.on{background:rgba(127,127,127,.12);border-color:var(--bd);color:var(--fg)}

            /* Response format carries far more than the other two; three equal columns
               would leave a large gap under the right-hand pair. */
            .grid{display:grid;grid-template-columns:1.35fr 1fr;gap:.9rem;align-items:start}
            .grid .col{display:flex;flex-direction:column;gap:.9rem}
            .grid .card{display:flex;flex-direction:column;gap:.5rem}
            .grid h3{font-size:.92rem;margin:0}
            .hint{color:var(--mut);font-size:.79rem;margin:0}

            .ep{background:var(--card);border:1px solid var(--bd);border-radius:11px;
                margin-bottom:.6rem;overflow:hidden}
            .ep summary{display:flex;gap:.6rem;align-items:center;padding:.7rem 1rem;cursor:pointer;
                        flex-wrap:wrap;list-style:none}
            .ep summary::-webkit-details-marker{display:none}
            .ep summary:hover{background:rgba(127,127,127,.06)}
            .ep[open] summary{border-bottom:1px solid var(--bd)}
            .m{background:#2ecc7122;color:var(--ok);padding:.1rem .45rem;border-radius:5px;
               font-size:.68rem;font-weight:700;letter-spacing:.03em}
            .p{font:12.5px ui-monospace,Consolas,monospace;color:var(--acc)}
            .d{color:var(--mut);font-size:.83rem;margin-left:auto;text-align:right}
            .lock{background:#f39c1222;color:var(--warn);padding:.1rem .45rem;border-radius:5px;font-size:.68rem}
            .open{background:#7f8c8d22;color:#95a5a6;padding:.1rem .45rem;border-radius:5px;font-size:.68rem}
            .ep .body{padding:.9rem 1rem 1rem}
            table{width:100%;border-collapse:collapse;font-size:.83rem;margin:0 0 .8rem}
            th,td{text-align:left;padding:.35rem .5rem;border-bottom:1px solid var(--bd)}
            th{color:var(--mut);font-weight:500;font-size:.7rem;text-transform:uppercase;letter-spacing:.04em}
            td code{background:rgba(127,127,127,.15);padding:.1rem .35rem;border-radius:4px;color:var(--acc)}
            .row{display:flex;gap:.5rem;align-items:center;flex-wrap:wrap}
            .row input{flex:1;min-width:220px;background:var(--bg);border:1px solid var(--bd);color:var(--fg);
                       border-radius:7px;padding:.42rem .6rem;font:12.5px ui-monospace,Consolas,monospace;
                       outline:none}
            .row input:focus{border-color:var(--acc)}
            .out{max-height:330px;overflow:auto;margin-top:.6rem;display:none}
            .out.on{display:block}
            .warn{color:var(--warn);font-size:.79rem;margin-top:.5rem}
            .notice{background:#f39c1214;border:1px solid #f39c1240;border-radius:9px;
                    padding:.65rem .85rem;font-size:.85rem;margin-bottom:1.5rem}
            .empty{color:var(--mut);text-align:center;padding:2rem;font-size:.9rem}
            footer{color:var(--mut);font-size:.82rem;text-align:center;padding:1.5rem 1rem 2.5rem;
                   border-top:1px solid var(--bd);max-width:1280px;margin:0 auto}
            @media(max-width:1100px){.grid{grid-template-columns:1fr}}
            @media(max-width:900px){
              .layout{grid-template-columns:1fr;gap:1.5rem}
              nav{position:static;max-height:none}
              .tools{width:100%;margin-left:0}
              .field input{width:100%}
              .field{flex:1}
              .d{margin-left:0;width:100%;text-align:left}
            }
            </style></head><body>

            <div class="top"><div class="top-in">
              <div class="brand">ServerAPI<span class="ver">v__VERSION__</span></div>
              <div class="tools">
                <div class="field"><span id="l-base"></span><code id="baseUrl"></code>
                  <button class="ghost sm" id="baseCopy"></button></div>
                <div class="field" id="keyField"><span id="l-key"></span>
                  <input id="apiKey" type="password" autocomplete="off" spellcheck="false">
                  <button class="ghost sm" id="keyClear">×</button></div>
              </div>
            </div></div>

            <div class="layout">
              <nav><input id="filter"><div id="toc"></div></nav>
              <main class="stack">
                <div>
                  <h1 id="title"></h1>
                  <p class="sub" id="sub"></p>
                  <div class="chips" id="chips"></div>
                  <div class="notice" id="authNotice" style="display:none"></div>
                </div>

                <section id="s-quick">
                  <h2 class="sec" id="h-quick"></h2>
                  <p class="lead" id="quickHint"></p>
                  <div class="card">
                    <div class="tabs" id="qsTabs"></div>
                    <pre id="qsCode"></pre>
                  </div>
                </section>

                <section id="s-essentials">
                  <h2 class="sec" id="h-ess"></h2>
                  <div class="grid">
                    <div class="card">
                      <h3 id="e-fmt"></h3>
                      <div class="tabs" id="fmtTabs"></div>
                      <pre id="fmtCode"></pre>
                    </div>
                    <div class="col">
                      <div class="card">
                        <h3 id="e-auth"></h3>
                        <pre id="authCode"></pre>
                        <p class="hint" id="keyHint"></p>
                      </div>
                      <div class="card">
                        <h3 id="e-rate"></h3>
                        <pre id="rateCode"></pre>
                        <p class="hint" id="rateHint"></p>
                      </div>
                    </div>
                  </div>
                </section>

                <section id="s-endpoints">
                  <h2 class="sec" id="h-eps"></h2>
                  <div id="eps"></div>
                  <div class="empty" id="noMatch" style="display:none"></div>
                </section>
              </main>
            </div>
            <footer>ServerAPI v__VERSION__</footer>

            <script>
            const EP = /*__ENDPOINTS__*/[];
            const T = /*__T__*/{};
            const BASE = '__BASE__';
            const AUTH_ON = __AUTHON__;
            const URL_BASE = location.origin + BASE;
            const esc = s => { const d = document.createElement('div'); d.textContent = s; return d.innerHTML; };
            const set = (id, v) => { document.getElementById(id).textContent = v; };

            /* Tab strip driven by a [label, code] list; used by Quick start and Response format. */
            const buildTabs = (tabsId, codeId, list) => {
              const tabs = document.getElementById(tabsId), code = document.getElementById(codeId);
              list.forEach(([label, body], i) => {
                const b = document.createElement('button');
                b.className = 'tab' + (i ? '' : ' on');
                b.textContent = label;
                b.onclick = () => {
                  tabs.querySelectorAll('.tab').forEach(x => x.classList.remove('on'));
                  b.classList.add('on');
                  code.textContent = body;
                };
                tabs.appendChild(b);
              });
              code.textContent = list[0][1];
            };

            /* ---------- Heading and status ---------- */
            document.title = T.title;
            set('title', T.title);
            set('sub', T.subtitle.replace('{0}', '__VERSION__'));
            set('l-base', T['base-url']);
            set('l-key', T['api-key']);
            set('baseUrl', URL_BASE);
            set('baseCopy', T.copy);
            set('h-quick', T['quick-start']);
            set('quickHint', T['quick-start-hint']);
            set('h-ess', T.essentials);
            set('e-fmt', T['response-format']);
            set('e-auth', T.auth);
            set('e-rate', T['rate-limit']);
            set('keyHint', T['api-key-hint']);
            set('rateHint', T['rate-limit-hint']);
            set('h-eps', T.endpoints);
            set('noMatch', T['no-match']);

            const chips = document.getElementById('chips');
            [[AUTH_ON ? T['chip-auth-on'] : T['chip-auth-off'], AUTH_ON ? 'lock' : 'open'],
             [T['chip-endpoints'].replace('{0}', EP.length), '']]
              .forEach(([text, cls]) => {
                const s = document.createElement('span');
                s.className = 'chip ' + cls; s.textContent = text; chips.appendChild(s);
              });
            if (!AUTH_ON) {
              const n = document.getElementById('authNotice');
              n.textContent = T['auth-disabled'];
              n.style.display = 'block';
            }

            /* ---------- Key: stays in this browser, sent as a header only when you press Run ---------- */
            const keyInput = document.getElementById('apiKey');
            const keyField = document.getElementById('keyField');
            const markKey = () => keyField.classList.toggle('on', !!keyInput.value.trim());
            keyInput.placeholder = T['api-key-placeholder'];
            keyInput.value = localStorage.getItem('serverapi-key') || '';
            keyInput.oninput = () => { localStorage.setItem('serverapi-key', keyInput.value.trim()); markKey(); };
            document.getElementById('keyClear').onclick = () => {
              keyInput.value = ''; localStorage.removeItem('serverapi-key'); markKey();
            };
            markKey();

            const copied = (btn, label) => {
              btn.textContent = T.copied;
              setTimeout(() => { btn.textContent = label; }, 1200);
            };
            document.getElementById('baseCopy').onclick = async ev => {
              await navigator.clipboard.writeText(URL_BASE);
              copied(ev.target, T.copy);
            };

            /* ---------- Quick start ---------- */
            const H = AUTH_ON;
            const SAMPLES = [
              ['cURL', H ? 'curl -H "X-API-Key: <key>" ' + URL_BASE + '/status'
                         : 'curl ' + URL_BASE + '/status'],
              ['JavaScript',
                "const res = await fetch('" + URL_BASE + "/status'"
                + (H ? ", {\\n  headers: { 'X-API-Key': '<key>' }\\n}" : "") + ");\\n"
                + "const { ok, data } = await res.json();\\n"
                + "console.log(data.online);"],
              ['Python',
                "import requests\\n\\n"
                + "res = requests.get(\\n    '" + URL_BASE + "/status'"
                + (H ? ",\\n    headers={'X-API-Key': '<key>'}" : "") + "\\n)\\n"
                + "print(res.json()['data'])"]
            ];
            buildTabs('qsTabs', 'qsCode', SAMPLES);

            /* ---------- Essentials ---------- */
            buildTabs('fmtTabs', 'fmtCode', [
              [T['success-example'], JSON.stringify({ok: true, data: {'...': 'endpoint payload'},
                 meta: {version: 'v1', server: 'global',
                 updatedAt: 1784300000000, cachedAt: 1784299700000, authenticated: false}}, null, 2)],
              [T['error-example'], JSON.stringify({ok: false,
                 error: {status: 404, message: '...', path: BASE + '/x'},
                 meta: {version: 'v1', server: 'global'}}, null, 2)]
            ]);
            set('authCode', 'X-API-Key: <key>\\n' + BASE + '/plugins?key=<key>');
            set('rateCode', 'HTTP 429\\nRetry-After: <seconds>');

            /* ---------- Contents ---------- */
            const toc = document.getElementById('toc');
            const navLink = (href, text, cls) => {
              const a = document.createElement('a');
              a.href = href; a.textContent = text; if (cls) a.className = cls;
              toc.appendChild(a); return a;
            };
            navLink('#s-quick', T['quick-start']);
            navLink('#s-essentials', T.essentials);
            navLink('#s-endpoints', T.endpoints);
            const grp = document.createElement('div');
            grp.className = 'grp'; grp.textContent = T.endpoints;
            toc.appendChild(grp);
            EP.forEach(e => { navLink('#ep-' + e.station, '/' + e.station, 'ep-link').id = 'nav-' + e.station; });

            /* ---------- Endpoints ---------- */
            const eps = document.getElementById('eps');
            EP.forEach(e => {
              const el = document.createElement('details');
              el.className = 'ep'; el.id = 'ep-' + e.station;
              const params = e.params.length
                ? '<table><tr><th>' + esc(T.params) + '</th><th></th><th></th></tr>'
                  + e.params.map(p => '<tr><td><code>' + esc(p.name) + '</code></td><td><code>'
                      + esc(p.value) + '</code></td><td>' + esc(p.description) + '</td></tr>').join('')
                  + '</table>'
                : '';
              el.innerHTML =
                '<summary><span class="m">GET</span><span class="p">' + esc(e.path) + '</span>'
                + (e.requiresKey ? '<span class="lock">' + esc(T['requires-key']) + '</span>'
                                 : '<span class="open">' + esc(T.public) + '</span>')
                + '<span class="d">' + esc(e.description) + '</span></summary>'
                + '<div class="body">' + params
                + '<div class="row"><input value="' + esc(e.path) + '" spellcheck="false">'
                + '<button class="run">' + esc(T.run) + '</button>'
                + '<button class="ghost copy">' + esc(T.copy) + '</button></div>'
                + (e.requiresKey ? '<div class="warn">' + esc(T['key-required-hint']) + '</div>' : '')
                + '<pre class="out"></pre></div>';
              eps.appendChild(el);

              const input = el.querySelector('input'), out = el.querySelector('pre.out');
              el.querySelector('.run').onclick = async () => {
                out.classList.add('on');
                out.textContent = T.loading;
                try {
                  const key = keyInput.value.trim();
                  const res = await fetch(input.value, key ? {headers: {'X-API-Key': key}} : undefined);
                  const ct = res.headers.get('content-type') || '';
                  out.textContent = ct.includes('json')
                    ? JSON.stringify(await res.json(), null, 2)
                    : 'HTTP ' + res.status + ' ' + ct;
                } catch (err) {
                  out.textContent = T.failed + ': ' + err.message;
                }
              };
              el.querySelector('.copy').onclick = async ev => {
                await navigator.clipboard.writeText(location.origin + input.value);
                copied(ev.target, T.copy);
              };
            });

            /* ---------- Filter: applies to both the list and the contents ---------- */
            const filter = document.getElementById('filter');
            const noMatch = document.getElementById('noMatch');
            filter.placeholder = T.search;
            filter.oninput = () => {
              const q = filter.value.trim().toLowerCase();
              let hits = 0;
              EP.forEach(e => {
                const hit = !q || e.path.toLowerCase().includes(q)
                         || (e.description || '').toLowerCase().includes(q);
                document.getElementById('ep-' + e.station).style.display = hit ? '' : 'none';
                const link = document.getElementById('nav-' + e.station);
                if (link) link.style.display = hit ? '' : 'none';
                if (hit) hits++;
              });
              noMatch.style.display = hits ? 'none' : 'block';
            };

            /* ---------- Highlight the section currently in view ---------- */
            const sections = [...document.querySelectorAll('main section')];
            const sectionLinks = [...toc.querySelectorAll('a:not(.ep-link)')];
            const spy = () => {
              // At the bottom, take the last section outright: it may never reach the
              // trigger line, so on a short page it would never light up.
              const atBottom = innerHeight + scrollY >= document.documentElement.scrollHeight - 2;
              let current = sections[sections.length - 1];
              if (!atBottom) {
                current = sections[0];
                for (const s of sections) {
                  if (s.getBoundingClientRect().top <= 120) current = s;
                }
              }
              sectionLinks.forEach(a =>
                a.classList.toggle('on', a.getAttribute('href') === '#' + current.id));
            };
            addEventListener('scroll', spy, {passive: true});
            spy();
            </script></body></html>
            """;
}
