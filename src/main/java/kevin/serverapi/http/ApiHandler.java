package kevin.serverapi.http;

import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 所有站點 handler 的共用基底：
 *  - 僅允許 GET
 *  - 選用 X-API-Key 驗證
 *  - build() 一律在 HTTP 執行緒上執行，「絕不」阻塞主執行緒
 *  - 需要主執行緒才安全的資料改由 {@link CachedApiHandler} 定期快照
 *  - 統一 JSON 輸出與錯誤處理
 */
public abstract class ApiHandler implements HttpHandler {

    /** 目前的 API 版本。站點同時掛在 /api/v1/… 與 /api/…（別名，指向最新版）。 */
    public static final String API_VERSION = "v1";

    protected final ServerAPIPlugin plugin;

    /** 站點名稱（設定檔中的鍵），於註冊時填入。 */
    private String station = "";
    /** 本站點是否需要 X-API-Key 才能存取。 */
    private boolean requiresKey;
    /** 未帶有效 key 時要隱藏的敏感欄位（任意層級同名皆隱藏）。 */
    private Set<String> protectedFields = Set.of();

    protected ApiHandler(ServerAPIPlugin plugin) {
        this.plugin = plugin;
    }

    /** 由插件於註冊站點時設定權限規則。 */
    public void configureAuth(String station, boolean requiresKey, Set<String> protectedFields) {
        this.station = station;
        this.requiresKey = requiresKey;
        this.protectedFields = protectedFields == null ? Set.of() : protectedFields;
    }

    public String getStation() {
        return station;
    }

    /** 此請求是否已通過驗證（驗證關閉時一律視為已通過）。 */
    protected boolean isAuthenticated(HttpExchange exchange) {
        return !plugin.isAuthEnabled() || hasValidKey(exchange);
    }

    /** 由子類別實作：在 HTTP 執行緒上執行並回傳可序列化的 JSON 節點（不得阻塞主執行緒）。 */
    protected abstract Object build(HttpExchange exchange) throws Exception;

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // CORS：允許瀏覽器端讀取
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, plugin.lang().get("api.error.method-not-allowed"));
                return;
            }

            // 兩層權限：站點層級（是否放行）與欄位層級（是否顯示敏感欄位）
            boolean authEnabled = plugin.isAuthEnabled();
            boolean hasKey = hasValidKey(exchange);
            // 驗證關閉時視同已授權：設定檔明訂「所有端點與欄位皆公開」
            boolean authenticated = !authEnabled || hasKey;

            // 速率限制。放在驗證之後才能讓已授權的呼叫端豁免，
            // 但仍在實際處理之前，未授權的洪水請求不會進到資料收集階段。
            //
            // 豁免只認「驗證已開啟且確實帶了有效金鑰」。若沿用上面的 authenticated，
            // auth.enabled 一關就等於所有人都豁免，限流會在預設設定下完全失效。
            boolean rateLimitExempt = authEnabled && hasKey && plugin.isRateLimitExemptAuthenticated();
            RateLimiter limiter = plugin.getRateLimiter();
            if (limiter != null && !rateLimitExempt) {
                long retryAfter = limiter.check(clientIp(exchange));
                if (retryAfter > 0) {
                    exchange.getResponseHeaders().set("Retry-After", String.valueOf(retryAfter));
                    sendError(exchange, 429, plugin.lang().get("api.error.rate-limited", retryAfter));
                    return;
                }
            }
            if (authEnabled && requiresKey && !authenticated) {
                if (plugin.isHideProtected()) {
                    // 回應與「端點不存在」完全一致，不透露此路徑存在或受保護。
                    // 否則等同告訴攻擊者哪些端點有值得保護的資料。
                    sendError(exchange, 404, plugin.lang().get("api.error.not-found", exchange.getRequestURI().getPath()));
                } else {
                    sendError(exchange, 401, plugin.lang().get("api.error.unauthorized"));
                }
                return;
            }

            Object payload;
            try {
                payload = build(exchange);
            } catch (ApiException ae) {
                sendError(exchange, ae.getStatus(), ae.getMessage());
                return;
            } catch (Exception ex) {
                plugin.getLogger().warning(plugin.lang().get("console.warn.request-failed", ex));
                sendError(exchange, 500, plugin.lang().get("api.error.internal"));
                return;
            }

            String body;
            if (payload instanceof HtmlResponse html) {
                body = html.html();
                sendHtml(exchange, 200, body);
            } else {
                // 欄位層級權限：未通過驗證時移除敏感欄位（產生新物件，不動到共用快取）
                Object data = payload;
                boolean filtered = false;
                if (!authenticated && !protectedFields.isEmpty()) {
                    data = Json.filter(payload, protectedFields);
                    filtered = true;
                }

                Json.JsonObject meta = meta(exchange);
                meta.put("authenticated", authenticated);
                if (filtered) {
                    // 明確告知哪些欄位因未驗證而被隱藏，避免呼叫端誤以為資料缺失
                    Json.JsonArray hidden = Json.arr();
                    for (String key : protectedFields) hidden.add(key);
                    meta.put("hiddenFields", hidden);
                }

                // 統一成功包裝：{ok, data, meta}
                body = Json.obj()
                        .put("ok", true)
                        .put("data", data)
                        .put("meta", meta)
                        .toJson();
                sendJson(exchange, 200, body);
            }

            if (plugin.isDebug()) {
                // 只印 path，不可改為完整 URI —— 查詢字串可能含 ?key=，
                // 而此訊息會推播給有 serverapi.notice 權限的玩家。
                plugin.debugNotify("&f" + exchange.getRequestURI().getPath()
                        + " &7← &f" + clientIp(exchange)
                        + " &8(" + body.getBytes(StandardCharsets.UTF_8).length + " bytes)");
            }
        }
    }

    /**
     * 組出回應的 meta 區塊。所有站點皆有 {@code version}、{@code server} 與
     * {@code updatedAt}（本次回應產生的時間）；快照站點另由 {@link CachedApiHandler}
     * 覆寫加上 {@code cachedAt}（快照實際擷取時間）。
     */
    protected Json.JsonObject meta(HttpExchange exchange) {
        Json.JsonObject meta = Json.obj()
                .put("version", API_VERSION)
                .put("server", plugin.getServerId())
                .put("updatedAt", System.currentTimeMillis());
        decorateMeta(meta);
        return meta;
    }

    /** 子類別可覆寫以附加額外的 meta 欄位。 */
    protected void decorateMeta(Json.JsonObject meta) {
    }

    /**
     * 取得請求路徑中，扣掉本站點 context 之後的剩餘片段。
     * 以實際匹配到的 context 路徑計算，因此同一 handler 可同時掛在
     * /api/worlds 與 /api/v1/worlds 下而不需知道自己被註冊在哪。
     */
    protected static String remainderOf(HttpExchange exchange) {
        String context = exchange.getHttpContext().getPath();
        String path = exchange.getRequestURI().getPath();
        String remainder = path.length() > context.length() ? path.substring(context.length()) : "";
        return remainder.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    /**
     * 來源 IP。
     * 實際解析（Cloudflare／Nginx／Apache 的代理標頭與可信代理判斷）
     * 交由 {@link ClientIpResolver} 處理。
     */
    private String clientIp(HttpExchange exchange) {
        ClientIpResolver resolver = plugin.getClientIpResolver();
        if (resolver != null) return resolver.resolve(exchange);
        return exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "?";
    }

    /**
     * 請求是否帶有正確的金鑰。
     * 優先讀 X-API-Key 標頭；若設定允許，亦接受網址參數 ?key=（方便瀏覽器直接測試）。
     */
    private boolean hasValidKey(HttpExchange exchange) {
        String expected = plugin.getApiKey();
        if (expected == null || expected.isEmpty()) return false;

        String provided = exchange.getRequestHeaders().getFirst("X-API-Key");
        if (provided == null && plugin.isQueryKeyAllowed()) {
            provided = queryParam(exchange, "key");
        }
        return expected.equals(provided);
    }

    /** 取得指定的查詢參數值（已解碼）；不存在則回傳 null。 */
    protected static String queryParam(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return null;
        for (String pair : raw.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) continue;
            if (decode(pair.substring(0, idx)).equals(name)) {
                return decode(pair.substring(idx + 1));
            }
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    protected void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 統一錯誤格式，與成功回應對稱：
     * {@code {ok:false, error:{status, message, path}, meta:{...}}}
     * 呼叫端一律先看 {@code ok} 即可分流。
     */
    protected void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String body = Json.obj()
                .put("ok", false)
                .put("error", Json.obj()
                        .put("status", status)
                        .put("message", message)
                        .put("path", exchange.getRequestURI().getPath()))
                .put("meta", Json.obj()
                        .put("version", API_VERSION)
                        .put("server", plugin.getServerId())
                        .put("updatedAt", System.currentTimeMillis()))
                .toJson();
        sendJson(exchange, status, body);
    }

    protected void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        headers.set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 子類別回傳此型別即以 text/html 輸出（例如 PlaceholderAPI 預覽頁）。 */
    public record HtmlResponse(String html) {}

    /** 子類別可丟出以回傳指定 HTTP 狀態碼與統一錯誤格式。 */
    public static class ApiException extends RuntimeException {
        private final int status;

        public ApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 便利子類別：回傳 404。 */
    public static final class NotFoundException extends ApiException {
        public NotFoundException(String message) {
            super(404, message);
        }
    }
}
