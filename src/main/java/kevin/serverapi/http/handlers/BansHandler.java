package kevin.serverapi.http.handlers;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.sun.net.httpserver.HttpExchange;
import io.papermc.paper.ban.BanListType;
import kevin.serverapi.ServerAPIPlugin;
import kevin.serverapi.http.ApiHandler;
import kevin.serverapi.json.Json;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;

import java.net.InetAddress;
import java.util.Date;

/**
 * /api/bans — 封鎖名單（玩家與 IP）。
 *
 * 使用 Paper 的 BanListType（取代已棄用的 BanList.Type），
 * 並以 getEntries() / getBanTarget() 等非棄用 API 取值。
 * 玩家一律以 UUID 表示，與其他站點慣例一致。
 */
public class BansHandler extends ApiHandler {

    public BansHandler(ServerAPIPlugin plugin) {
        super(plugin);
    }

    @Override
    protected Object build(HttpExchange exchange) {
        // getEntries() 的型別為 Set<BanEntry<? super T>>，故以萬用字元接收並檢查目標型別
        Json.JsonArray players = Json.arr();
        BanList<PlayerProfile> profileBans = Bukkit.getBanList(BanListType.PROFILE);
        for (BanEntry<? super PlayerProfile> entry : profileBans.getEntries()) {
            if (entry.getBanTarget() instanceof PlayerProfile profile) {
                players.add(base(entry)
                        .put("uuid", profile.getId() == null ? null : profile.getId().toString())
                        .put("name", profile.getName()));
            }
        }

        Json.JsonArray ips = Json.arr();
        BanList<InetAddress> ipBans = Bukkit.getBanList(BanListType.IP);
        for (BanEntry<? super InetAddress> entry : ipBans.getEntries()) {
            if (entry.getBanTarget() instanceof InetAddress address) {
                ips.add(base(entry).put("ip", address.getHostAddress()));
            }
        }

        return Json.obj()
                .put("players", Json.obj()
                        .put("count", players.size())
                        .put("entries", players))
                .put("ips", Json.obj()
                        .put("count", ips.size())
                        .put("entries", ips));
    }

    /** 各類封鎖共通的欄位；expires 為 null 表示永久封鎖。 */
    private static Json.JsonObject base(BanEntry<?> entry) {
        Date created = entry.getCreated();
        Date expires = entry.getExpiration();
        return Json.obj()
                .put("reason", entry.getReason())
                .put("source", entry.getSource())
                .put("created", created == null ? null : created.getTime())
                .put("expires", expires == null ? null : expires.getTime())
                .put("permanent", expires == null);
    }
}
