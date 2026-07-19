package kevin.serverapi.integration;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 顏色推導工具：將 Minecraft 色碼轉為網頁可直接使用的 hex（#RRGGBB）。
 * 支援傳統色碼（§0–§f）與十六進位格式（§x§R§R§G§G§B§B），並容忍 &amp; 形式。
 */
public final class ColorUtil {

    private ColorUtil() {}

    private static final Pattern HEX_CODE = Pattern.compile("(?i)§x(§[0-9a-f]){6}");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)§([0-9a-f])");
    private static final Pattern PLAIN_HEX = Pattern.compile("(?i)^#[0-9a-f]{6}$");

    /** 傳統色碼對應的標準 Minecraft 顏色。 */
    private static final Map<Character, String> LEGACY = Map.ofEntries(
            Map.entry('0', "#000000"), Map.entry('1', "#0000AA"),
            Map.entry('2', "#00AA00"), Map.entry('3', "#00AAAA"),
            Map.entry('4', "#AA0000"), Map.entry('5', "#AA00AA"),
            Map.entry('6', "#FFAA00"), Map.entry('7', "#AAAAAA"),
            Map.entry('8', "#555555"), Map.entry('9', "#5555FF"),
            Map.entry('a', "#55FF55"), Map.entry('b', "#55FFFF"),
            Map.entry('c', "#FF5555"), Map.entry('d', "#FF55FF"),
            Map.entry('e', "#FFFF55"), Map.entry('f', "#FFFFFF"));

    /**
     * 推導字串中第一個出現的顏色，回傳 hex；無顏色時回傳 null。
     * 例如 "§b暱稱" → "#55FFFF"、"§x§F§F§0§0§0§0字" → "#FF0000"。
     */
    public static String firstColor(String input) {
        if (input == null || input.isEmpty()) return null;
        String s = input.replace('&', '§');

        Matcher hex = HEX_CODE.matcher(s);
        if (hex.find()) {
            String g = hex.group();
            StringBuilder out = new StringBuilder("#");
            // §x§R§R§G§G§B§B —— 色值位於索引 3,5,7,9,11,13
            for (int i = 3; i < g.length(); i += 2) out.append(g.charAt(i));
            return out.toString().toUpperCase();
        }

        Matcher legacy = LEGACY_CODE.matcher(s);
        if (legacy.find()) {
            return LEGACY.get(Character.toLowerCase(legacy.group(1).charAt(0)));
        }
        return null;
    }

    /**
     * 正規化外部插件回傳的顏色值：
     * 已是 #RRGGBB 則原樣回傳；是色碼則轉 hex；都不是則回傳原值。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (PLAIN_HEX.matcher(s).matches()) return s.toUpperCase();
        String derived = firstColor(s);
        return derived != null ? derived : s;
    }
}
