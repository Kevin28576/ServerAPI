package kevin.serverapi.storage;

/**
 * Redis 鍵名規則：
 *
 * <pre>
 *   {namespace}:{cluster}:{server}:{type}[:{id}]
 *
 *   預設（cluster / server 皆留空）：
 *     serverapi:::history
 *     serverapi:::player_stat:40094f2f-ca8f-4826-8b31-11e0f4c449df
 *
 *   多服環境（cluster=main, server=survival）：
 *     serverapi:main:survival:history
 *     serverapi:main:survival:player_stat:{uuid}
 * </pre>
 *
 * 多台伺服器共用同一個 Redis 時，請為各服設定不同的 server-id 以避免互相覆蓋。
 */
public final class RedisKeys {

    private final String namespace;
    private final String cluster;
    private final String server;

    public RedisKeys(String namespace, String cluster, String server) {
        this.namespace = blankIfNull(namespace).isEmpty() ? "serverapi" : blankIfNull(namespace);
        this.cluster = blankIfNull(cluster);
        this.server = blankIfNull(server);
    }

    /** {namespace}:{cluster}:{server}:{type} */
    public String key(String type) {
        return namespace + ":" + cluster + ":" + server + ":" + type;
    }

    /** {namespace}:{cluster}:{server}:{type}:{id} */
    public String key(String type, String id) {
        return key(type) + ":" + id;
    }

    /**
     * 跨伺服器比對用樣式：{namespace}:{cluster}:*:{type}
     * 用於掃描同一 cluster 下所有伺服器的資料。
     */
    public String patternAcrossServers(String type) {
        return namespace + ":" + cluster + ":*:" + type;
    }

    /**
     * 本 namespace 底下所有鍵的樣式，用於清理舊版遺留鍵。
     * 僅涵蓋自身 namespace，不會掃到其他應用程式的資料。
     */
    public String namespacePattern() {
        return namespace + ":*";
    }

    /** 從完整鍵名取回 server 段（掃描結果解析用）。 */
    public String serverOf(String fullKey) {
        String[] parts = fullKey.split(":", 5);
        return parts.length >= 3 ? parts[2] : "";
    }

    public String cluster() {
        return cluster;
    }

    public String server() {
        return server;
    }

    private static String blankIfNull(String s) {
        return s == null ? "" : s.trim();
    }
}
