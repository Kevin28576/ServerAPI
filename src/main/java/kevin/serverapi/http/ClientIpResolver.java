package kevin.serverapi.http;

import com.sun.net.httpserver.HttpExchange;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import kevin.serverapi.lang.Lang;

import java.util.logging.Logger;

/**
 * 取得請求的真實來源 IP，支援 Cloudflare／Nginx／Apache 等反向代理。
 *
 * 安全模型：代理標頭可由用戶端任意偽造，因此<b>只有當連線來源本身屬於可信代理時
 * 才採信這些標頭</b>。若直接無條件相信 X-Forwarded-For，任何人只要自己加一個
 * 標頭就能偽裝成別的 IP，速率限制與 IP 豁免會完全失效。
 *
 * 支援的標頭（依設定順序嘗試）：
 *   CF-Connecting-IP  Cloudflare
 *   True-Client-IP    Cloudflare Enterprise / Akamai
 *   X-Real-IP         Nginx 慣例（proxy_set_header X-Real-IP $remote_addr）
 *   X-Forwarded-For   通用標準，Apache／Nginx 皆會附上；為逗號分隔的轉發鏈
 *
 * X-Forwarded-For 會由右往左掃描，略過所有可信代理，取第一個非可信位址 ——
 * 這是唯一不會被偽造前綴欺騙的解析方式。
 */
public final class ClientIpResolver {

    /** Cloudflare 公布的 IP 區段（cloudflare.com/ips），供設定中以 "cloudflare" 代稱展開。 */
    private static final String[] CLOUDFLARE_RANGES = {
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22",
            "141.101.64.0/18", "108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20",
            "197.234.240.0/22", "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32", "2405:b500::/32",
            "2405:8100::/32", "2a06:98c0::/29", "2c0f:f248::/32"
    };

    private final boolean enabled;
    private final List<String> headers;
    private final List<Cidr> trustedProxies;

    public ClientIpResolver(boolean enabled, List<String> headers,
                            List<String> trustedProxies, Logger logger, Lang lang) {
        this.enabled = enabled;
        this.headers = headers == null || headers.isEmpty()
                ? List.of("CF-Connecting-IP", "True-Client-IP", "X-Real-IP", "X-Forwarded-For")
                : headers;
        this.trustedProxies = parseRanges(trustedProxies, logger, lang);
    }

    private static List<Cidr> parseRanges(List<String> raw, Logger logger, Lang lang) {
        List<Cidr> out = new ArrayList<>();
        if (raw == null) return out;
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) continue;
            String value = entry.trim();
            if (value.equalsIgnoreCase("cloudflare")) {
                for (String cf : CLOUDFLARE_RANGES) {
                    Cidr c = Cidr.parse(cf);
                    if (c != null) out.add(c);
                }
                continue;
            }
            Cidr c = Cidr.parse(value);
            if (c == null) {
                logger.warning(lang.get("console.warn.proxy-parse-failed", value));
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 解析真實來源 IP。
     * 連線來源不是可信代理時，直接回傳連線位址並忽略所有標頭。
     */
    public String resolve(HttpExchange exchange) {
        String remote = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : null;
        if (!enabled || remote == null || !isTrustedProxy(remote)) {
            return remote == null ? "?" : remote;
        }

        for (String header : headers) {
            String value = exchange.getRequestHeaders().getFirst(header);
            if (value == null || value.isBlank()) continue;

            String resolved = value.indexOf(',') >= 0
                    ? fromForwardedChain(value)   // X-Forwarded-For 之類的轉發鏈
                    : normalize(value.trim());
            if (resolved != null) return resolved;
        }
        return remote;
    }

    /**
     * 由右往左掃描轉發鏈，取第一個非可信代理的位址。
     * 直接取最左邊會被偽造 —— 用戶端可自行送出
     * "X-Forwarded-For: 1.2.3.4" 讓代理附加後變成 "1.2.3.4, 真實IP"。
     */
    private String fromForwardedChain(String chain) {
        String[] parts = chain.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = normalize(parts[i].trim());
            if (candidate == null) continue;
            if (!isTrustedProxy(candidate)) return candidate;
        }
        return null;
    }

    public boolean isTrustedProxy(String ip) {
        InetAddress addr = parseLiteral(ip);
        if (addr == null) return false;
        for (Cidr c : trustedProxies) {
            if (c.matches(addr)) return true;
        }
        return false;
    }

    /** 去掉可能附帶的埠號並驗證格式；不合法回傳 null。 */
    private static String normalize(String value) {
        String v = value;
        // IPv6 常以 [::1]:1234 表示
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end > 0) v = v.substring(1, end);
        } else if (v.indexOf(':') >= 0 && v.indexOf(':') == v.lastIndexOf(':')) {
            v = v.substring(0, v.indexOf(':'));   // IPv4:port
        }
        return parseLiteral(v) == null ? null : v;
    }

    /**
     * 僅接受 IP 字面值。
     * 標頭內容不可信，若直接丟給 InetAddress.getByName，遇到主機名稱會觸發 DNS 查詢，
     * 等於讓外部輸入能拖慢 HTTP 執行緒。
     */
    private static InetAddress parseLiteral(String s) {
        if (s == null || s.isBlank()) return null;
        boolean looksValid = s.indexOf(':') >= 0
                ? s.matches("[0-9A-Fa-f:]+(:[0-9]{1,3}(\\.[0-9]{1,3}){3})?")
                : s.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        if (!looksValid) return null;
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** CIDR 區段，支援 IPv4 與 IPv6。 */
    private record Cidr(byte[] prefix, int bits) {

        static Cidr parse(String value) {
            String addr = value;
            int bits = -1;
            int slash = value.lastIndexOf('/');
            if (slash > 0) {
                addr = value.substring(0, slash);
                try {
                    bits = Integer.parseInt(value.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            InetAddress parsed = parseLiteral(addr.trim());
            if (parsed == null) return null;
            byte[] bytes = parsed.getAddress();
            if (bits < 0) bits = bytes.length * 8;               // 單一位址視為完整遮罩
            if (bits > bytes.length * 8) return null;
            return new Cidr(bytes, bits);
        }

        boolean matches(InetAddress address) {
            byte[] a = address.getAddress();
            if (a.length != prefix.length) return false;         // v4 與 v6 不互相比對
            int fullBytes = bits / 8;
            int remaining = bits % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (a[i] != prefix[i]) return false;
            }
            if (remaining == 0) return true;
            int mask = 0xFF << (8 - remaining);
            return (a[fullBytes] & mask) == (prefix[fullBytes] & mask);
        }
    }
}
