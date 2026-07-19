package kevin.serverapi.http.handlers;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.sun.net.httpserver.HttpExchange;
import io.papermc.paper.ban.BanListType;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.integration.CmiPunishmentBridge;
import kevin.serverapi.json.Json;
import kevin.serverapi.storage.SqlPunishmentLog;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * /api/punishments            — 全服處罰紀錄（封鎖／IP 封鎖／警告／禁言／監禁）
 * /api/punishments/{uuid}     — 單一玩家的處罰紀錄
 * /api/punishments?view=html  — 供管理員參考的展示頁 demo
 *
 * 資料來源：
 *   Bukkit 封鎖名單（含臨時封鎖）＋ CMI（警告／禁言／監禁）
 *   ＋ 本插件自建的處罰日誌（踢出 —— Minecraft 與 CMI 皆不保存此歷史）
 */
public class PunishmentsHandler extends ApiHandler {

    private static final int LOG_LIMIT = 500;

    /** 自建的處罰日誌（踢出紀錄）；未啟用資料庫時為 null。 */
    private final SqlPunishmentLog log;

    public PunishmentsHandler(ServerAPIPlugin plugin, SqlPunishmentLog log) {
        super(plugin);
        this.log = log;
    }

    /** 單筆處罰紀錄的中間表示，便於排序與轉為 JSON／HTML。 */
    private record Record(String type, UUID uuid, String name, String ip, String reason,
                          String by, Long at, Long expires, Long liftedAt, Boolean active, String extra) {
        boolean permanent() {
            return expires == null || expires <= 0;
        }
    }

    @Override
    protected Object build(HttpExchange exchange) {
        String remainder = remainderOf(exchange);
        String query = exchange.getRequestURI().getQuery();
        boolean html = query != null && java.util.Arrays.asList(query.split("&")).contains("view=html");

        // 關閉時視為「沒有這個參數」而非回報已停用；
        // 回「已停用」等於告訴對方這頁存在，只是現在關著。
        if (html && !plugin.isPunishmentsHtmlEnabled()) {
            throw new NotFoundException(plugin.lang().get("api.error.not-found",
                    exchange.getRequestURI().getPath()));
        }

        if (!remainder.isEmpty()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(remainder);
            } catch (IllegalArgumentException e) {
                throw new NotFoundException(plugin.lang().get("api.error.invalid-uuid", remainder));
            }
            return playerJson(uuid);
        }

        List<Record> records = collectAll();
        Json.JsonObject payload = listJson(records);
        return html ? new HtmlResponse(renderPage(records, payload)) : payload;
    }

    // ---- 資料收集 ----

    private List<Record> collectAll() {
        List<Record> out = new ArrayList<>();
        long now = System.currentTimeMillis();

        // 持久化日誌：封鎖／IP 封鎖／禁言／監禁／踢出，含已解除與已到期者
        if (log != null) {
            try {
                for (SqlPunishmentLog.Entry e : log.recent(LOG_LIMIT)) {
                    out.add(fromLog(e, now));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning(plugin.lang().get("console.warn.punishment-read-failed", ex));
            }
        }

        // 警告：CMI 本身即保存歷史，直接讀取
        for (CmiPunishmentBridge.Warning w : CmiPunishmentBridge.allWarnings()) {
            out.add(new Record("warning", w.uuid(), nameOf(w.uuid()), null, strip(w.reason()),
                    strip(w.givenBy()), w.givenAt(), null, null, true, w.category()));
        }

        out.sort(Comparator.comparing((Record r) -> r.at() == null ? Long.MIN_VALUE : r.at()).reversed());
        return out;
    }

    /** 日誌列轉為輸出用紀錄；IP 封鎖的對象存於 target 而非 uuid。 */
    private static Record fromLog(SqlPunishmentLog.Entry e, long now) {
        boolean isIp = "ipban".equals(e.type());
        UUID uuid = null;
        if (!isIp && e.target() != null) {
            try {
                uuid = UUID.fromString(e.target());
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 格式，保持 null
            }
        }
        return new Record(e.type(), uuid, e.name(), isIp ? e.target() : null,
                e.reason(), e.issuedBy(), e.at(), e.expires(), e.liftedAt(),
                e.active(now), e.cause());
    }

    /** 移除 § 顏色代碼，讓官網端可直接顯示（CMI 的封鎖原因常帶色碼）。 */
    private static String strip(String s) {
        return s == null ? null : s.replaceAll("(?i)§[0-9A-FK-ORX]", "");
    }

    private static Long time(Date d) {
        return d == null ? null : d.getTime();
    }

    private static String nameOf(UUID uuid) {
        if (uuid == null) return null;
        return Bukkit.getOfflinePlayer(uuid).getName();
    }

    // ---- JSON ----

    private Json.JsonObject listJson(List<Record> records) {
        Json.JsonArray arr = Json.arr();
        int bans = 0, ipBans = 0, warnings = 0, mutes = 0, jails = 0, kicks = 0;
        for (Record r : records) {
            arr.add(toJson(r));
            switch (r.type()) {
                case "ban" -> bans++;
                case "ipban" -> ipBans++;
                case "warning" -> warnings++;
                case "mute" -> mutes++;
                case "jail" -> jails++;
                case "kick" -> kicks++;
                default -> { }
            }
        }
        // summary 直接由資料庫統計，涵蓋全部歷史而非僅本次回傳的 500 筆
        Json.JsonObject summary = Json.obj();
        int distinct = 0;
        if (log != null) {
            try {
                var counts = log.countByType();
                summary.put("total", counts.values().stream().mapToInt(Integer::intValue).sum() + warnings)
                        .put("bans", counts.getOrDefault("ban", 0))
                        .put("ipBans", counts.getOrDefault("ipban", 0))
                        .put("mutes", counts.getOrDefault("mute", 0))
                        .put("jails", counts.getOrDefault("jail", 0))
                        .put("kicks", counts.getOrDefault("kick", 0))
                        .put("warnings", warnings);
                distinct = log.distinctTargets();
            } catch (Exception ex) {
                plugin.getLogger().warning(plugin.lang().get("console.warn.punishment-stats-failed", ex));
            }
        }
        if (summary.isEmpty()) {
            summary.put("total", records.size()).put("bans", bans).put("ipBans", ipBans)
                    .put("warnings", warnings).put("mutes", mutes).put("jails", jails).put("kicks", kicks);
        }
        summary.put("punishedPlayers", distinct)
               .put("activeNow", (int) records.stream()
                       .filter(r -> isDurational(r.type()) && Boolean.TRUE.equals(r.active()))
                       .count());

        return Json.obj()
                .put("summary", summary)
                .put("cmiAvailable", CmiPunishmentBridge.isAvailable())
                .put("returned", records.size())
                .put("limit", LOG_LIMIT)
                .put("records", arr);
    }

    /** 是否為具持續時間的處罰（可被解除／到期），相對於踢出、警告這類一次性事件。 */
    private static boolean isDurational(String type) {
        return switch (type) {
            case "ban", "ipban", "mute", "jail" -> true;
            default -> false;
        };
    }

    private static Json.JsonObject toJson(Record r) {
        // 只有具持續時間的處罰才有「永久與否」的概念；
        // 踢出與警告是一次性事件，permanent 應為 null 而非 true。
        boolean durational = isDurational(r.type());
        return Json.obj()
                .put("type", r.type())
                .put("uuid", r.uuid() == null ? null : r.uuid().toString())
                .put("name", r.name())
                .put("ip", r.ip())
                .put("reason", r.reason())
                .put("by", r.by())
                .put("at", r.at())
                .put("expires", r.expires())
                .put("liftedAt", r.liftedAt())
                // permanent 與 active 都只適用於「有持續時間」的處罰。
                // 踢出、警告是一次性事件，發生即結束，回 null 表示不適用，
                // 否則呼叫端會看到永遠「生效中」的踢出紀錄。
                .put("permanent", durational ? r.permanent() : null)
                .put("active", durational ? r.active() : null)
                .put("extra", r.extra());
    }

    private Json.JsonObject playerJson(UUID uuid) {
        var offline = Bukkit.getOfflinePlayer(uuid);

        Json.JsonArray warnings = Json.arr();
        for (CmiPunishmentBridge.Warning w : CmiPunishmentBridge.warningsOf(uuid)) {
            warnings.add(Json.obj()
                    .put("reason", strip(w.reason()))
                    .put("by", strip(w.givenBy()))
                    .put("at", w.givenAt())
                    .put("category", w.category()));
        }

        Json.JsonObject ban = null;
        BanList<PlayerProfile> profileBans = Bukkit.getBanList(BanListType.PROFILE);
        for (BanEntry<? super PlayerProfile> e : profileBans.getEntries()) {
            if (e.getBanTarget() instanceof PlayerProfile p && uuid.equals(p.getId())) {
                Long exp = time(e.getExpiration());
                ban = Json.obj()
                        .put("reason", strip(e.getReason()))
                        .put("by", strip(e.getSource()))
                        .put("at", time(e.getCreated()))
                        .put("expires", exp)
                        .put("permanent", exp == null);
                break;
            }
        }

        var mute = CmiPunishmentBridge.muteOf(uuid);
        var jail = CmiPunishmentBridge.jailOf(uuid);

        Json.JsonArray history = Json.arr();
        long now = System.currentTimeMillis();
        int active = 0;
        if (log != null) {
            try {
                for (SqlPunishmentLog.Entry e : log.forTarget(uuid.toString(), 200)) {
                    if (e.active(now)) active++;
                    history.add(Json.obj()
                            .put("type", e.type())
                            .put("reason", e.reason())
                            .put("by", e.issuedBy())
                            .put("cause", e.cause())
                            .put("at", e.at())
                            .put("expires", e.expires())
                            .put("liftedAt", e.liftedAt())
                            .put("active", e.active(now)));
                }
            } catch (Exception ex) {
                plugin.getLogger().warning(plugin.lang().get("console.warn.punishment-read-failed", ex));
            }
        }

        return Json.obj()
                .put("uuid", uuid.toString())
                .put("name", offline.getName())
                .put("banned", offline.isBanned())
                .put("ban", ban)
                .put("warnings", warnings)
                .put("warningCount", warnings.size())
                .put("warningPoints", CmiPunishmentBridge.warningPointsOf(uuid))
                .put("mute", mute == null ? null : Json.obj()
                        .put("reason", strip(mute.reason()))
                        .put("expires", mute.until())
                        .put("shadow", mute.shadow()))
                .put("jail", jail == null ? null : Json.obj()
                        .put("reason", strip(jail.reason()))
                        .put("expires", jail.until())
                        .put("by", jail.by()))
                .put("history", history)
                .put("historyCount", history.size())
                .put("activeCount", active);
    }

    // ---- HTML demo 展示頁 ----

    private String renderPage(List<Record> records, Json.JsonObject payload) {
        Json.JsonArray data = Json.arr();
        for (Record r : records) data.add(toJson(r));

        var l = plugin.lang();
        // 頁面文字全部由語言檔提供，前端只負責排版
        Json.JsonObject t = Json.obj();
        for (String key : new String[]{"title", "subtitle", "total", "punished-players", "active-now",
                "col-type", "col-status", "col-target", "col-reason", "col-by", "col-detail",
                "col-time", "col-expiry", "type-ban", "type-ipban", "type-kick", "type-warning",
                "type-mute", "type-jail", "status-active", "status-expired", "status-lifted",
                "status-ended", "filter-all", "permanent", "lifted-by-staff", "no-reason", "empty",
                "page", "prev", "next", "footer-1", "footer-2",
                "ago-minutes", "ago-hours", "ago-days"}) {
            t.put(key, l.get("web.punishments." + key));
        }

        return PAGE
                .replace("/*__DATA__*/[]", data.toJson())
                .replace("/*__SUMMARY__*/{}", summaryOf(payload))
                .replace("/*__T__*/{}", t.toJson())
                .replace("__LANG__", l.code().startsWith("zh") ? "zh-Hant" : "en");
    }

    private static final String PAGE = """
            <!doctype html><html lang="__LANG__"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>ServerAPI</title><style>
            :root{color-scheme:light dark;--bg:#0f1115;--card:#171a21;--fg:#e6e8ee;--mut:#9aa3b2;
                  --bd:#262b36;--acc:#4cc2ff}
            @media(prefers-color-scheme:light){:root{--bg:#f6f7f9;--card:#fff;--fg:#16181d;--mut:#5b6472;
                  --bd:#e2e5ea;--acc:#0969da}}
            *{box-sizing:border-box}body{margin:0;padding:2rem 1rem;background:var(--bg);color:var(--fg);
              font:15px/1.6 ui-sans-serif,system-ui,"Segoe UI","Noto Sans TC",sans-serif}
            .wrap{max-width:1180px;margin:0 auto}
            h1{font-size:1.5rem;margin:0 0 .25rem}.sub{color:var(--mut);margin-bottom:1.5rem}
            .stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(120px,1fr));gap:.75rem;margin-bottom:1.5rem}
            .stat{background:var(--card);border:1px solid var(--bd);border-radius:10px;padding:.75rem 1rem}
            .stat b{display:block;font-size:1.6rem}.stat span{color:var(--mut);font-size:.85rem}
            .bar{display:flex;gap:.5rem;flex-wrap:wrap;margin-bottom:1rem;align-items:center}
            .bar button{background:var(--card);border:1px solid var(--bd);color:var(--fg);border-radius:20px;
              padding:.35rem .9rem;cursor:pointer;font:inherit;font-size:.85rem}
            .bar button.on{background:var(--acc);border-color:var(--acc);color:#fff}
            .bar input{background:var(--card);border:1px solid var(--bd);color:var(--fg);border-radius:20px;
              padding:.35rem .9rem;font:inherit;font-size:.85rem;min-width:180px;margin-left:auto}
            .tbl{background:var(--card);border:1px solid var(--bd);border-radius:10px;overflow-x:auto}
            table{width:100%;border-collapse:collapse;font-size:.9rem}
            th,td{padding:.6rem .8rem;text-align:left;border-bottom:1px solid var(--bd);white-space:nowrap}
            th{color:var(--mut);font-weight:500;font-size:.8rem;text-transform:uppercase;letter-spacing:.03em}
            tr:last-child td{border-bottom:none}
            td.reason{white-space:normal;min-width:180px}
            .tag{display:inline-block;padding:.15rem .55rem;border-radius:6px;font-size:.75rem;font-weight:600}
            .ban{background:#c0392b22;color:#e74c3c}.ipban{background:#8e44ad22;color:#9b59b6}
            .warning{background:#f39c1222;color:#f39c12}.mute{background:#2980b922;color:#3498db}
            .jail{background:#7f8c8d22;color:#95a5a6}.kick{background:#e67e2222;color:#e67e22}
            .exp{color:var(--mut);font-size:.85rem}
            .st{display:inline-block;padding:.1rem .5rem;border-radius:6px;font-size:.72rem;font-weight:600}
            .on{background:#e74c3c22;color:#e74c3c}.off{background:#7f8c8d22;color:#95a5a6}
            .perm{color:#e74c3c;font-weight:600;font-size:.85rem}
            .none{padding:2.5rem;text-align:center;color:var(--mut)}
            .pager{display:flex;gap:.5rem;align-items:center;justify-content:center;margin-top:1rem;color:var(--mut);font-size:.9rem}
            .pager button{background:var(--card);border:1px solid var(--bd);color:var(--fg);border-radius:8px;
              padding:.35rem .9rem;cursor:pointer;font:inherit;font-size:.85rem}
            .pager button:disabled{opacity:.4;cursor:default}
            footer{color:var(--mut);font-size:.85rem;margin-top:1.5rem;text-align:center;line-height:1.8}
            code{background:rgba(127,127,127,.15);padding:.15rem .4rem;border-radius:4px;font-size:.85em}
            </style></head><body><div class="wrap">
            <h1 id="title"></h1><div class="sub" id="sub"></div>
            <div class="stats" id="stats"></div>
            <div class="bar" id="bar"></div>
            <div class="tbl"><table><thead><tr id="head"></tr></thead><tbody id="rows"></tbody></table>
            <div class="none" id="empty" style="display:none"></div></div>
            <div class="pager" id="pager"></div>
            <footer id="foot"></footer>
            </div><script>
            const DATA = /*__DATA__*/[];
            const SUMMARY = /*__SUMMARY__*/{};
            const T = /*__T__*/{};
            const PER_PAGE = 50;
            const LABEL = {ban:T['type-ban'],ipban:T['type-ipban'],kick:T['type-kick'],
                           warning:T['type-warning'],mute:T['type-mute'],jail:T['type-jail']};
            let filter='all', page=1, search='';

            const fmt = t => t ? new Date(t).toLocaleString() : '\u2014';
            const rel = t => { if(!t) return '';
              const d=(Date.now()-t)/1000;
              if(d<3600) return T['ago-minutes'].replace('{0}',Math.max(1,Math.floor(d/60)));
              if(d<86400) return T['ago-hours'].replace('{0}',Math.floor(d/3600));
              return T['ago-days'].replace('{0}',Math.floor(d/86400)); };
            const esc = s => { const d=document.createElement('div'); d.textContent=s; return d.innerHTML; };

            function status(r){
              if(r.permanent===null) return '<span class="st off">'+T['status-ended']+'</span>';
              if(r.liftedAt) return '<span class="st off">'+T['status-lifted']+'</span>';
              if(r.active) return '<span class="st on">'+T['status-active']+'</span>';
              return '<span class="st off">'+T['status-expired']+'</span>';
            }
            function expiry(r){
              if(r.liftedAt) return fmt(r.liftedAt)+'<div class="exp">'+T['lifted-by-staff']+'</div>';
              if(r.permanent===null) return '<span class="exp">\u2014</span>';
              if(r.permanent) return '<span class="perm">'+T['permanent']+'</span>';
              return fmt(r.expires);
            }
            function detail(r){
              if(!r.extra) return '<span class="exp">\u2014</span>';
              return '<code>'+esc(r.extra)+'</code>';
            }
            function visible(){
              return DATA.filter(r=>{
                if(filter!=='all' && r.type!==filter) return false;
                if(!search) return true;
                const hay=[r.name,r.ip,r.reason,r.by,r.uuid].filter(Boolean).join(' ').toLowerCase();
                return hay.includes(search);
              });
            }
            function renderHead(){
              document.getElementById('title').textContent=T.title;
              document.title='ServerAPI \u2014 '+T.title;
              document.getElementById('sub').textContent=T.subtitle.replace('{0}','/api/v1/punishments');
              document.getElementById('head').innerHTML=
                ['col-type','col-status','col-target','col-reason','col-by','col-detail','col-time','col-expiry']
                .map(k=>'<th>'+esc(T[k])+'</th>').join('');
              document.getElementById('foot').innerHTML=esc(T['footer-1'])+'<br>'+esc(T['footer-2']);
            }
            function renderStats(){
              const s=SUMMARY;
              document.getElementById('stats').innerHTML =
                [[T.total,s.total],[T['punished-players'],s.punishedPlayers],[T['active-now'],s.activeNow],
                 [LABEL.ban,s.bans],[LABEL.kick,s.kicks],[LABEL.warning,s.warnings],[LABEL.mute,s.mutes]]
                .map(([n,v])=>'<div class="stat"><b>'+(v??0)+'</b><span>'+esc(n)+'</span></div>').join('');
            }
            function renderBar(){
              const c={all:DATA.length};
              DATA.forEach(r=>c[r.type]=(c[r.type]||0)+1);
              const types=['all',...Object.keys(LABEL).filter(k=>c[k])];
              document.getElementById('bar').innerHTML =
                types.map(t=>'<button data-t="'+t+'" class="'+(t===filter?'on':'')+'">'
                  +esc(t==='all'?T['filter-all']:LABEL[t])+' ('+(c[t]||0)+')</button>').join('')
                + '<input id="q" type="search" placeholder="'+esc(T['col-target'])+' / '+esc(T['col-reason'])+'">';
              document.querySelectorAll('#bar button').forEach(b=>
                b.onclick=()=>{filter=b.dataset.t;page=1;renderBar();renderRows();});
              const q=document.getElementById('q');
              q.value=search;
              q.oninput=()=>{search=q.value.trim().toLowerCase();page=1;renderRows();};
            }
            function renderRows(){
              const all=visible();
              const pages=Math.max(1,Math.ceil(all.length/PER_PAGE));
              if(page>pages) page=pages;
              const list=all.slice((page-1)*PER_PAGE, page*PER_PAGE);
              const empty=document.getElementById('empty');
              empty.textContent=T.empty;
              empty.style.display=all.length?'none':'block';
              document.getElementById('rows').innerHTML = list.map(r=>'<tr>'
                +'<td><span class="tag '+r.type+'">'+esc(LABEL[r.type]||r.type)+'</span></td>'
                +'<td>'+status(r)+'</td>'
                +'<td>'+esc(r.name || r.ip || (r.uuid?r.uuid.slice(0,8)+'\u2026':'\u2014'))+'</td>'
                +'<td class="reason">'+(r.reason?esc(r.reason):'<span class="exp">'+T['no-reason']+'</span>')+'</td>'
                +'<td>'+(r.by?esc(r.by):'\u2014')+'</td>'
                +'<td>'+detail(r)+'</td>'
                +'<td>'+fmt(r.at)+'<div class="exp">'+rel(r.at)+'</div></td>'
                +'<td>'+expiry(r)+'</td></tr>').join('');
              renderPager(pages, all.length);
            }
            function renderPager(pages, total){
              const el=document.getElementById('pager');
              if(total<=PER_PAGE){ el.innerHTML=''; return; }
              el.innerHTML='<button id="prev"'+(page<=1?' disabled':'')+'>'+esc(T.prev)+'</button>'
                +'<span>'+esc(T.page.replace('{0}',page).replace('{1}',pages))+'</span>'
                +'<button id="next"'+(page>=pages?' disabled':'')+'>'+esc(T.next)+'</button>';
              const p=document.getElementById('prev'), n=document.getElementById('next');
              if(p) p.onclick=()=>{page--;renderRows();window.scrollTo(0,0);};
              if(n) n.onclick=()=>{page++;renderRows();window.scrollTo(0,0);};
            }
            renderHead();renderStats();renderBar();renderRows();
            </script></body></html>
            """;

    /** 從回應中取出 summary 區塊的 JSON，供展示頁的統計卡片使用。 */
    private static String summaryOf(Json.JsonObject payload) {
        String json = payload.toJson();
        int i = json.indexOf("\"summary\":");
        if (i < 0) return "{}";
        int start = json.indexOf('{', i);
        if (start < 0) return "{}";
        int depth = 0;
        for (int p = start; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(start, p + 1);
        }
        return "{}";
    }
}
