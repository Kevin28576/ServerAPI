package kevin.serverapi.api;

import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.plugin.Plugin;

/**
 * 第三方插件要透過 ServerAPI 對外提供的一個資料站點。
 *
 * <p>用 {@link #builder(String, Plugin)} 建立，交給
 * {@link ServerApiRegistry#register(CustomStation)} 註冊。註冊只代表「請求曝光」。
 * 站點是否真的對外開放、要不要金鑰，一律由 ServerAPI 的伺服器管理員在
 * {@code plugins/ServerAPI/custom/<name>.yml} 決定，且首次註冊預設為<b>停用</b>。
 *
 * <h2>執行緒模式（{@link Mode}）</h2>
 * <ul>
 *   <li>{@link #async()}：{@code supplier} 會在 HTTP 執行緒上被呼叫，
 *       可能同時有多條請求併發呼叫。你必須保證它<b>不阻塞、且執行緒安全</b>，
 *       尤其<b>不可</b>碰只有主執行緒才安全的 Bukkit API（World / Entity / Player 等）。</li>
 *   <li>{@link #cached(int)}：{@code supplier} 由 ServerAPI 在<b>主執行緒</b>上定期呼叫，
 *       結果快取後供 HTTP 請求讀取。適合需要存取 Bukkit API 的資料。
 *       快照間隔的預設值由你建議，實際值由伺服器管理員在設定檔覆寫。</li>
 * </ul>
 *
 * <h2>回傳值</h2>
 * {@code supplier} 回傳純 Java 即可：{@link java.util.Map}、{@link java.util.List}、
 * 陣列、{@link Number}、{@link Boolean}、{@link String} 或 {@code null}，
 * ServerAPI 會遞迴轉成 JSON。Map 的鍵一律以字串輸出。
 */
public final class CustomStation {

    /** 資料供應者的執行緒模式。 */
    public enum Mode {
        /** 於 HTTP 執行緒即時呼叫；供應者須非阻塞且執行緒安全。 */
        ASYNC,
        /** 於主執行緒定期快照；供應者可安全存取 Bukkit API。 */
        CACHED
    }

    private final String name;
    private final Plugin owner;
    private final String description;
    private final Mode mode;
    private final int suggestedSeconds;
    private final Supplier<Object> supplier;

    private CustomStation(Builder b) {
        this.name = b.name;
        this.owner = b.owner;
        this.description = b.description;
        this.mode = b.mode;
        this.suggestedSeconds = b.suggestedSeconds;
        this.supplier = b.supplier;
    }

    /**
     * 開始建立一個站點。
     *
     * @param name  站點名稱，作為路徑 {@code /api/v1/custom/<name>} 與設定檔名。
     *              僅接受小寫英數與 {@code _ -}，開頭須為英數，長度上限 32。
     * @param owner 註冊此站點的插件（用於控制台訊息與設定檔中的來源標示）。
     */
    public static Builder builder(String name, Plugin owner) {
        return new Builder(name, owner);
    }

    public String name() { return name; }
    public Plugin owner() { return owner; }
    public String description() { return description; }
    public Mode mode() { return mode; }
    public int suggestedSeconds() { return suggestedSeconds; }
    public Supplier<Object> supplier() { return supplier; }

    public static final class Builder {
        private final String name;
        private final Plugin owner;
        private String description = "";
        private Mode mode = Mode.ASYNC;
        private int suggestedSeconds = 5;
        private Supplier<Object> supplier;

        private Builder(String name, Plugin owner) {
            this.name = name;
            this.owner = owner;
        }

        /** 顯示於 {@code /api/v1} 索引與文件頁的一句話說明。 */
        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        /** 即時模式：供應者在 HTTP 執行緒上呼叫（須非阻塞、執行緒安全）。 */
        public Builder async() {
            this.mode = Mode.ASYNC;
            return this;
        }

        /**
         * 快照模式：供應者由主執行緒每 {@code seconds} 秒呼叫一次並快取。
         *
         * @param seconds 建議的快照間隔（秒），伺服器管理員可於設定檔覆寫；下限 1 秒。
         */
        public Builder cached(int seconds) {
            this.mode = Mode.CACHED;
            this.suggestedSeconds = Math.max(1, seconds);
            return this;
        }

        /** 資料供應者：回傳純 Java（Map/List/陣列/數字/布林/字串/null）。 */
        public Builder supplier(Supplier<Object> supplier) {
            this.supplier = supplier;
            return this;
        }

        public CustomStation build() {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(supplier, "supplier 未設定");
            return new CustomStation(this);
        }
    }
}
