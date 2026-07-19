package kevin.serverapi.lang;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 多語言訊息。
 *
 * 語言檔存於 plugins/ServerAPI/lang/，首次啟動時依主機語系自動挑選；
 * 之後可用 /serverapi lang &lt;code&gt; 切換，設定會寫回 config 並於下次啟動沿用。
 *
 * 只有「插件產生的文字」會被翻譯 —— 玩家輸入的內容（封鎖原因、暱稱等）
 * 一律原樣保留，不做任何轉換。
 */
public final class Lang {

    public static final String DEFAULT = "en_US";
    /** 內建的語言檔，會在啟動時釋出到 lang/ 目錄。 */
    public static final List<String> BUNDLED = List.of("en_US", "zh_TW");

    private final Plugin plugin;
    private final Map<String, String> messages = new LinkedHashMap<>();
    private String code = DEFAULT;

    public Lang(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 依主機語系猜測語言。
     * 繁體中文地區（台灣／香港／澳門）回傳 zh_TW，其餘一律 en_US。
     */
    public static String detectFromSystem() {
        Locale locale = Locale.getDefault();
        String lang = locale.getLanguage();
        String country = locale.getCountry();
        if ("zh".equalsIgnoreCase(lang)) {
            if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country)
                    || "MO".equalsIgnoreCase(country)) {
                return "zh_TW";
            }
            // 簡體中文暫無語言檔，退回英文而非給出無法閱讀的介面
            return DEFAULT;
        }
        return DEFAULT;
    }

    public String code() {
        return code;
    }

    /** 釋出內建語言檔並載入指定語言。找不到時退回英文。 */
    public void load(String requested) {
        saveBundledFiles();

        String target = (requested == null || requested.isBlank()) ? DEFAULT : requested.trim();
        messages.clear();

        // 先以英文為底，缺漏的鍵才不會顯示成原始鍵名
        readInto(DEFAULT, messages);
        if (!DEFAULT.equals(target)) {
            Map<String, String> override = new LinkedHashMap<>();
            if (readInto(target, override)) {
                messages.putAll(override);
                code = target;
            } else {
                // 英文底層已於上一步載入，因此這則警告仍可走語言檔
                plugin.getLogger().warning(get("console.warn.lang-missing", target, DEFAULT));
                code = DEFAULT;
            }
        } else {
            code = DEFAULT;
        }
    }

    /** 把 jar 內的語言檔複製到 lang/（已存在則不覆蓋，保留使用者的修改）。 */
    private void saveBundledFiles() {
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists() && !dir.mkdirs()) return;
        for (String name : BUNDLED) {
            File target = new File(dir, name + ".yml");
            if (target.exists()) continue;
            try {
                plugin.saveResource("lang/" + name + ".yml", false);
            } catch (Exception e) {
                // 這裡尚未載入任何語言檔（正是這個步驟失敗），只能以英文輸出
                plugin.getLogger().warning("Failed to write language file " + name + ": " + e);
            }
        }
    }

    /**
     * 讀取語言檔：先鋪 jar 內建作為底層，再以使用者 lang/ 目錄的內容覆蓋。
     *
     * 兩層是必要的。使用者檔案一旦存在就不會被覆寫（保留其修改），
     * 若只讀那一份，改版新增的鍵在舊檔裡找不到，就會原封不動印出鍵名。
     * 鋪底之後，新鍵永遠有值，使用者改過的字句也照樣生效。
     */
    private boolean readInto(String name, Map<String, String> out) {
        boolean found = false;
        try (InputStream in = plugin.getResource("lang/" + name + ".yml")) {
            if (in != null) {
                flatten(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)), out);
                found = true;
            }
        } catch (Exception ignored) {
            // 內建檔讀不到就只靠使用者檔案
        }
        File file = new File(new File(plugin.getDataFolder(), "lang"), name + ".yml");
        if (file.exists()) {
            flatten(YamlConfiguration.loadConfiguration(file), out);
            found = true;
        }
        return found;
    }

    private static void flatten(YamlConfiguration yaml, Map<String, String> out) {
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key)) continue;
            Object value = yaml.get(key);
            if (value != null) out.put(key, String.valueOf(value));
        }
    }

    /**
     * 取得訊息，並以 {0}、{1}… 依序帶入參數。
     * 找不到鍵時回傳鍵名本身，方便在測試時立即看出缺漏。
     */
    public String get(String key, Object... args) {
        String value = messages.getOrDefault(key, key);
        if (args == null || args.length == 0) return value;
        for (int i = 0; i < args.length; i++) {
            value = value.replace("{" + i + "}", args[i] == null ? "" : args[i].toString());
        }
        return value;
    }

    /** 語言檔是否存在（供指令驗證輸入）。 */
    public boolean exists(String name) {
        if (name == null || name.isBlank()) return false;
        File file = new File(new File(plugin.getDataFolder(), "lang"), name + ".yml");
        if (file.exists()) return true;
        try (InputStream in = plugin.getResource("lang/" + name + ".yml")) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 目前可用的語言代碼（內建 + 使用者自行新增的檔案）。 */
    public List<String> available() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(BUNDLED);
        File dir = new File(plugin.getDataFolder(), "lang");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File f : files) out.add(f.getName().substring(0, f.getName().length() - 4));
        }
        return List.copyOf(out);
    }
}
