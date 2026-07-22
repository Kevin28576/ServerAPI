package kevin.serverapi.api;

/**
 * ServerAPI 對第三方插件公開的註冊介面。
 *
 * <p>ServerAPI 會在啟用時把此服務註冊進 Bukkit 的 {@code ServicesManager}。
 * 第三方插件在自己的 {@code onEnable()}（會晚於 ServerAPI）取得後即可註冊站點：
 *
 * <pre>{@code
 * var rsp = getServer().getServicesManager().getRegistration(ServerApiRegistry.class);
 * if (rsp != null) {
 *     ServerApiRegistry api = rsp.getProvider();
 *     api.register(CustomStation.builder("myplugin_stats", this)
 *             .description("我的插件統計")
 *             .cached(10)                       // 需存取 Bukkit API → 主執行緒快照
 *             .supplier(() -> Map.of("kills", killCount, "wins", winCount))
 *             .build());
 * }
 * }</pre>
 *
 * <p>取不到服務（{@code getRegistration} 回傳 null）代表伺服器沒裝 ServerAPI，
 * 或它尚未啟用；請把整段包在 null 檢查裡，讓你的插件在沒有 ServerAPI 時照常運作。
 *
 * <p><b>註冊 ≠ 對外開放。</b>首次註冊只會在 {@code plugins/ServerAPI/custom/}
 * 產生一份<b>預設停用</b>的設定檔，並在控制台提示伺服器管理員。是否開放、是否需要金鑰、
 * 隱藏哪些欄位，全部由伺服器管理員在該檔決定。用 {@link #isServed(String)} 可查詢目前狀態。
 */
public interface ServerApiRegistry {

    /**
     * 註冊（或以相同名稱重新註冊）一個資料站點。
     *
     * <p>名稱不合法、與內建站點撞名，或已被<b>其他</b>插件註冊時，會拋出
     * {@link IllegalArgumentException}。同一插件以相同名稱重複呼叫則視為更新供應者。
     *
     * @param station 由 {@link CustomStation#builder(String, org.bukkit.plugin.Plugin)} 建立的站點
     */
    void register(CustomStation station);

    /**
     * 取消註冊。會停止對外提供並移除路徑，但<b>不會</b>刪除設定檔，
     * 伺服器管理員的開關與安全設定得以保留到下次註冊。名稱不存在時為無操作。
     */
    void unregister(String name);

    /**
     * 此站點目前是否正對外提供（已註冊、設定檔為 enabled，且路徑已掛上）。
     * 第三方可藉此得知伺服器管理員開了沒，例如據以調整自己的行為或提示。
     */
    boolean isServed(String name);
}
