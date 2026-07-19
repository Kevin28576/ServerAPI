package kevin.serverapi.http.handlers;

import com.sun.net.httpserver.HttpExchange;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.integration.PlaceholderBridge;
import kevin.serverapi.json.Json;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /api/placeholders?uuid={uuid}&p=%player_name%,%vault_eco_balance%
 *      → 解析指定玩家的 placeholder，回傳 JSON。
 * /api/placeholders?list=true
 *      → 回傳 HTML 預覽頁，列出所有已註冊的 expansion 與其 placeholder。
 *
 * 注意：placeholder 解析必須在主執行緒進行（多數 expansion 會存取 Bukkit API），
 * 因此本站點是唯一會派工到主執行緒的端點，並以逾時上限保護。
 */
public class PlaceholderHandler extends ApiHandler {

    public PlaceholderHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) throws Exception {
        if (!PlaceholderBridge.isAvailable()) {
            throw new ApiException(503, plugin.lang().get("api.error.placeholder-missing"));
        }

        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

        if ("true".equalsIgnoreCase(query.get("list"))) {
            // 關閉時視為「沒有這個參數」；回報已停用等於確認這頁存在。
            if (!plugin.isPlaceholdersListEnabled()) {
                throw new NotFoundException(plugin.lang().get("api.error.not-found",
                        exchange.getRequestURI().getPath()));
            }
            return new HtmlResponse(buildPreviewPage());
        }

        String uuidParam = query.get("uuid");
        if (uuidParam == null || uuidParam.isBlank()) {
            throw new ApiException(400, plugin.lang().get("api.error.placeholder-no-uuid"));
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(uuidParam);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, plugin.lang().get("api.error.invalid-uuid", uuidParam));
        }

        String p = query.get("p");
        if (p == null || p.isBlank()) {
            throw new ApiException(400, plugin.lang().get("api.error.placeholder-no-p"));
        }

        List<String> tokens = new ArrayList<>();
        for (String raw : p.split(",")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            tokens.add(t.startsWith("%") ? t : "%" + t + "%");
        }
        if (tokens.isEmpty()) {
            throw new ApiException(400, plugin.lang().get("api.error.placeholder-empty"));
        }

        long timeoutMs = Math.max(200L, plugin.getConfig().getLong("integrations.placeholderapi.resolve-timeout-ms", 2000L));
        boolean stripColor = plugin.getConfig().getBoolean("integrations.placeholderapi.strip-color", true);
        try {
            return plugin.callSync(() -> {
                OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                Json.JsonObject values = Json.obj();
                for (String token : tokens) {
                    String resolved = PlaceholderBridge.resolve(player, token);
                    values.put(token, stripColor ? stripColor(resolved) : resolved);
                }
                return Json.obj()
                        .put("uuid", uuid.toString())
                        .put("online", player.isOnline())
                        .put("values", values);
            }, timeoutMs);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new ApiException(504, plugin.lang().get("api.error.placeholder-timeout"));
        }
    }

    /** 移除 legacy 顏色代碼（§a、§7…，含 §x 十六進位格式），讓官網端可直接使用。 */
    private static String stripColor(String s) {
        if (s == null) return null;
        return s.replaceAll("(?i)§[0-9A-FK-ORX]", "");
    }

    /** 解析 query string（保留原始編碼再解碼，讓 %25 能正確還原為 %）。 */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                map.put(decode(pair), "");
            } else {
                map.put(decode(pair.substring(0, idx)), decode(pair.substring(idx + 1)));
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    // ---- HTML 預覽頁 ----

    private String buildPreviewPage() {
        List<PlaceholderBridge.ExpansionInfo> expansions = PlaceholderBridge.expansions();
        int placeholderCount = expansions.stream().mapToInt(e -> e.placeholders().size()).sum();
        var l = plugin.lang();

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html lang=\"")
                .append(l.code().startsWith("zh") ? "zh-Hant" : "en")
                .append("\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(l.get("web.placeholders.title"))).append("</title><style>")
                .append(STYLE)
                .append("</style></head><body><div class=\"wrap\">")
                .append("<h1>").append(esc(l.get("web.placeholders.title"))).append("</h1>")
                .append("<div class=\"sub\">").append(esc(l.get("web.placeholders.subtitle"))).append("</div>");

        sb.append("<div class=\"stats\"><div class=\"stat\"><b>").append(expansions.size())
                .append("</b><span>").append(esc(l.get("web.placeholders.expansions")))
                .append("</span></div><div class=\"stat\"><b>").append(placeholderCount)
                .append("</b><span>").append(esc(l.get("web.placeholders.placeholders")))
                .append("</span></div></div>");

        if (expansions.isEmpty()) {
            sb.append("<div class=\"exp\"><div class=\"none\">")
                    .append(esc(l.get("web.placeholders.empty"))).append("</div></div>");
        }

        for (PlaceholderBridge.ExpansionInfo e : expansions) {
            sb.append("<div class=\"exp\"><h2>").append(esc(e.identifier()));
            sb.append("<small>v").append(esc(e.version()));
            if (e.author() != null) sb.append(" \u00b7 by ").append(esc(e.author()));
            sb.append("</small></h2>");
            if (e.placeholders().isEmpty()) {
                sb.append("<div class=\"none\">")
                        .append(esc(l.get("web.placeholders.none-declared", e.identifier())))
                        .append("</div>");
            } else {
                sb.append("<div class=\"ph\">");
                for (String ph : e.placeholders()) {
                    sb.append("<code>").append(esc(ph)).append("</code>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
        }

        sb.append("<footer>")
                .append(esc(l.get("web.placeholders.usage",
                        "/api/v1/placeholders?uuid={uuid}&p=%25player_name%25", "%", "%25")))
                .append("</footer></div></body></html>");
        return sb.toString();
    }

    private static final String STYLE = """
            :root{color-scheme:light dark;--bg:#0f1115;--card:#171a21;--fg:#e6e8ee;--mut:#9aa3b2;--acc:#4cc2ff;--bd:#262b36}
            @media(prefers-color-scheme:light){:root{--bg:#f6f7f9;--card:#fff;--fg:#16181d;--mut:#5b6472;--acc:#0969da;--bd:#e2e5ea}}
            *{box-sizing:border-box}body{margin:0;padding:2rem 1rem;background:var(--bg);color:var(--fg);
            font:15px/1.6 ui-sans-serif,system-ui,"Segoe UI","Noto Sans TC",sans-serif}
            .wrap{max-width:1000px;margin:0 auto}h1{font-size:1.5rem;margin:0 0 .25rem}
            .sub{color:var(--mut);margin-bottom:1.5rem}
            .stats{display:flex;gap:1rem;flex-wrap:wrap;margin-bottom:1.5rem}
            .stat{background:var(--card);border:1px solid var(--bd);border-radius:10px;padding:.75rem 1rem;flex:1;min-width:140px}
            .stat b{display:block;font-size:1.5rem;color:var(--acc)}
            .stat span{color:var(--mut);font-size:.85rem}
            .exp{background:var(--card);border:1px solid var(--bd);border-radius:10px;margin-bottom:1rem;overflow:hidden}
            .exp h2{margin:0;padding:.75rem 1rem;font-size:1rem;border-bottom:1px solid var(--bd);
            display:flex;gap:.5rem;align-items:baseline;flex-wrap:wrap}
            .exp h2 small{color:var(--mut);font-weight:400;font-size:.8rem}
            .ph{padding:.5rem 1rem 1rem;display:flex;flex-wrap:wrap;gap:.4rem}
            code{background:rgba(127,127,127,.15);border:1px solid var(--bd);border-radius:6px;
            padding:.2rem .5rem;font:13px ui-monospace,Consolas,monospace;color:var(--acc)}
            .none{color:var(--mut);padding:.5rem 1rem 1rem;font-size:.9rem}
            footer{color:var(--mut);font-size:.85rem;margin-top:2rem;text-align:center}
            """;

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
