package kevin.serverapi.network;

import kevin.serverapi.json.Json;
import kevin.serverapi.storage.RedisCache;
import kevin.serverapi.storage.RedisKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * 多服支援：各伺服器定期把自身狀態以心跳寫入 Redis，
 * 任一台伺服器皆可掃描同一 cluster 下的所有狀態並彙整。
 *
 * 鍵名：{namespace}:{cluster}:{server}:status（三冒號寫法，見 {@link RedisKeys}）
 * 心跳帶 TTL，伺服器離線後其鍵會自動過期消失，無需額外清理。
 */
public final class NetworkService {

    private static final String TYPE = "status";

    /** 未指定 server-id 時的顯示名稱（語意同 LuckPerms 的 global）。 */
    public static final String GLOBAL = "global";

    private final RedisCache redis;      // 可為 null（未設定 Redis 則無跨服功能）
    private final RedisKeys keys;
    private final int ttlSeconds;
    private final boolean enabled;

    public NetworkService(RedisCache redis, RedisKeys keys, int ttlSeconds, boolean enabled) {
        this.redis = redis;
        this.keys = keys;
        this.ttlSeconds = Math.max(2, ttlSeconds);
        this.enabled = enabled;
    }

    /** 多服功能是否可用：需同時啟用開關且已設定 Redis。 */
    public boolean isActive() {
        return enabled && redis != null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean hasRedis() {
        return redis != null;
    }

    /** 本服識別的顯示值；未指定時顯示 global。 */
    public String displayServer() {
        return keys.server().isEmpty() ? GLOBAL : keys.server();
    }

    /** 發布本服心跳（於非同步排程呼叫）。 */
    public void publish(List<String> onlineUuids, Integer discord) {
        if (!isActive()) return;
        Json.JsonArray players = Json.arr();
        for (String u : onlineUuids) players.add(u);

        String payload = Json.obj()
                .put("server", displayServer())
                .put("cluster", keys.cluster())
                .put("online", onlineUuids.size())
                .put("players", players)
                .put("discord", discord)
                .put("updatedAt", System.currentTimeMillis())
                .toJson();

        redis.setex(keys.key(TYPE), ttlSeconds, payload);
    }

    /**
     * 彙整同一 cluster 下所有伺服器的狀態。
     * 心跳已過期的伺服器不會出現（Redis 自動移除）。
     */
    public Json.JsonObject aggregate() {
        Json.JsonArray servers = Json.arr();
        int totalOnline = 0;
        int count = 0;

        if (isActive()) {
            for (String key : redis.scanKeys(keys.patternAcrossServers(TYPE))) {
                String raw = redis.get(key);
                if (raw == null) continue;
                servers.add(Json.raw(raw));
                totalOnline += extractOnline(raw);
                count++;
            }
        }

        return Json.obj()
                .put("cluster", keys.cluster())
                .put("thisServer", displayServer())
                .put("serverCount", count)
                .put("totalOnline", totalOnline)
                .put("servers", servers);
    }

    /**
     * 從心跳 JSON 取出 online 數值。
     * 僅需一個整數欄位，故以輕量解析取代引入 JSON 解析器。
     */
    private static int extractOnline(String json) {
        int i = json.indexOf("\"online\":");
        if (i < 0) return 0;
        int start = i + 9;
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)))) end++;
        try {
            return end > start ? Integer.parseInt(json.substring(start, end)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 供 handler 顯示尚未設定 Redis 時的提示。 */
    public List<String> knownServers() {
        List<String> out = new ArrayList<>();
        if (redis == null) return out;
        for (String key : redis.scanKeys(keys.patternAcrossServers(TYPE))) {
            out.add(keys.serverOf(key));
        }
        return out;
    }
}
