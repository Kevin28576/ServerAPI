package kevin.serverapi.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 極簡、零依賴的 JSON 建構器（含正確跳脫）。
 * 用法：Json.obj().put("a", 1).put("b", Json.arr().add("x"))
 */
public final class Json {

    private Json() {}

    public static JsonObject obj() {
        return new JsonObject();
    }

    public static JsonArray arr() {
        return new JsonArray();
    }

    /** 將既有的 JSON 字串原樣嵌入（例如來自 Redis 的快取）。呼叫端需確保其為合法 JSON。 */
    public static JsonValue raw(String rawJson) {
        return sb -> sb.append(rawJson == null ? "null" : rawJson);
    }

    /**
     * 深層複製並移除指定名稱的欄位（任意層級皆會移除，含陣列內的物件）。
     *
     * 產生新物件而非就地修改 —— 快照站點回傳的是共用快取物件，
     * 直接刪除欄位會影響其他請求並造成資料競爭。
     *
     * 注意：以 {@link #raw(String)} 嵌入的既有 JSON 字串無法解析，會原樣保留。
     */
    public static Object filter(Object value, Set<String> removeKeys) {
        if (removeKeys == null || removeKeys.isEmpty()) return value;

        if (value instanceof JsonObject obj) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, Object> e : obj.map.entrySet()) {
                if (removeKeys.contains(e.getKey())) continue;
                out.map.put(e.getKey(), filter(e.getValue(), removeKeys));
            }
            return out;
        }
        if (value instanceof JsonArray arr) {
            JsonArray out = new JsonArray();
            for (Object v : arr.list) {
                out.list.add(filter(v, removeKeys));
            }
            return out;
        }
        return value;
    }

    /** 可被序列化的 JSON 節點。 */
    public interface JsonValue {
        void write(StringBuilder sb);
    }

    public static final class JsonObject implements JsonValue {
        private final Map<String, Object> map = new LinkedHashMap<>();

        public JsonObject put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        public boolean isEmpty() {
            return map.isEmpty();
        }

        /**
         * 淺拷貝：用於在「共用快取物件」上附加中繼欄位而不改動原物件。
         * 快照站點的回應會被多條 HTTP 執行緒共用，直接 put 會造成資料競爭。
         */
        public JsonObject copy() {
            JsonObject c = new JsonObject();
            c.map.putAll(this.map);
            return c;
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, e.getKey());
                sb.append(':');
                writeValue(sb, e.getValue());
            }
            sb.append('}');
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            write(sb);
            return sb.toString();
        }

        @Override
        public String toString() {
            return toJson();
        }
    }

    public static final class JsonArray implements JsonValue {
        private final List<Object> list = new ArrayList<>();

        public JsonArray add(Object value) {
            list.add(value);
            return this;
        }

        public int size() {
            return list.size();
        }

        @Override
        public void write(StringBuilder sb) {
            sb.append('[');
            boolean first = true;
            for (Object v : list) {
                if (!first) sb.append(',');
                first = false;
                writeValue(sb, v);
            }
            sb.append(']');
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            write(sb);
            return sb.toString();
        }

        @Override
        public String toString() {
            return toJson();
        }
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof JsonValue jv) {
            jv.write(sb);
        } else if (value instanceof Double || value instanceof Float) {
            // 避免科學記號（例如 1.3E8）——JSON 雖合法，但對呼叫端不友善
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");   // JSON 不支援 NaN / Infinity
            } else {
                sb.append(java.math.BigDecimal.valueOf(d).toPlainString());
            }
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
