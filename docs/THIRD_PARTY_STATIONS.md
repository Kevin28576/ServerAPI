# 第三方站點串接

讓你的插件把自己的資料，接到 **ServerAPI** 上一起對外，網址是 `/api/v1/custom/<名稱>`。

這份文件給**寫插件的人**看。伺服器管理員那側的設定，見文末〈伺服器管理員會看到什麼〉。

---

## 一件要先講清楚的事：註冊 ≠ 對外開放

你只能「申請」。要不要真的開、要不要金鑰、藏哪些欄位，**全部是伺服器管理員說了算**。

插件第一次申請時，ServerAPI 只會：

1. 在 `plugins/ServerAPI/custom/<名稱>.yml` 放一個**關著的**設定檔；
2. 在後台提醒伺服器管理員有新站點待確認。

伺服器管理員把 `enabled` 改成 `true` 並重載之前，你的站點一律回 **404**。這是刻意設計的。
沒有這道關卡，任何插件都能擅自把資料塞上別人的公開 API。

---

## 最少要寫什麼

在你插件的 `onEnable()` 裡，拿到 ServerAPI 的註冊入口，給它一個站點名稱和一個回傳資料的函式：

```java
var rsp = getServer().getServicesManager().getRegistration(ServerApiRegistry.class);
if (rsp == null) return;                 // 沒裝 ServerAPI，就當作沒這回事
ServerApiRegistry api = rsp.getProvider();

api.register(CustomStation.builder("myplugin_stats", this)
        .supplier(() -> Map.of("kills", killCount, "wins", winCount))
        .build());
```

就這樣。等伺服器管理員開啟後，資料會出現在 `/api/v1/custom/myplugin_stats`，你回傳的 `Map` 會自動變成 JSON。

`getRegistration(...)` 回傳 `null` 代表伺服器沒裝 ServerAPI。**一定要**做 null 檢查，
讓你的插件在沒有 ServerAPI 時照常運作。

---

## 要放玩家或世界的資料？加一行 `.cached()`

上面那種寫法，你的函式是在**處理 HTTP 請求的執行緒**上跑的，這條執行緒**不能碰 Bukkit 的東西**，
因為玩家、世界、實體這些只有主執行緒讀才安全。

如果你要放的正是這類資料，加一行 `.cached(秒數)`。ServerAPI 會改成在主執行緒上、每隔幾秒幫你抓一次
存起來，之後有人來讀就給快取：

```java
api.register(CustomStation.builder("arena", this)
        .cached(5)                       // 每 5 秒在主執行緒抓一次
        .supplier(() -> {
            World w = Bukkit.getWorld("arena");
            return Map.of("players", w.getPlayers().size(), "time", w.getTime());
        })
        .build());
```

怎麼選：

- 資料本來就在記憶體、算得快，什麼都不用加，預設就是即時。
- 要讀玩家、世界、實體，才加 `.cached(秒)`。

那個秒數只是建議值，伺服器管理員能在設定檔自己改。`.async()` 也可以明寫，但既然是預設，通常不必寫。

> **async 的兩個坑**：① 你的函式會被多條請求**同時**呼叫，讀寫共用狀態請自行同步；
> ② 絕對不要在裡面碰 Bukkit API。這兩種情況都改用 `.cached()`。

---

## 回傳什麼

回傳**純 Java** 就好，ServerAPI 會遞迴轉成 JSON：

| 你回傳 | 變成 |
|--------|------|
| `Map<?, ?>` | 物件 `{}`（鍵一律轉字串） |
| `List` / 陣列 / `Iterable` | 陣列 `[]` |
| `Number`、`Boolean` | 數字、`true`/`false` |
| `String` | 字串 |
| `null` | `null` |
| 其他型別 | 退回 `toString()`（不會壞，但八成不是你要的） |

回傳值會被包進 ServerAPI 統一的信封：

```json
{
  "ok": true,
  "data": {
    "kills": 12,
    "kdr": 2.4,
    "ranked": true,
    "topPlayer": "Alice",
    "recentPlayers": ["Alice", "Bob"],
    "lastScores": [10, 8, 5],
    "arena": { "name": "sky", "round": 3 },
    "matchId": "3f2a1b7c-…",
    "nextEvent": null
  },
  "meta": { "version": "v1", "server": "...", "updatedAt": 1784300000000 }
}
```

上面 `data` 裡每個欄位對應到前面表格的一種型別：整數、小數、布林、字串、
List、`int[]`、巢狀 Map、UUID（其他型別轉字串）、`null`。

`cached` 模式會多一個 `meta.cachedAt`，是快照實際擷取的時間。

---

## 完整範例

一支只做這件事的小插件：

```java
public class MyPlugin extends JavaPlugin {

    private int kills = 0;
    private final UUID matchId = UUID.randomUUID();

    @Override
    public void onEnable() {
        var rsp = getServer().getServicesManager().getRegistration(ServerApiRegistry.class);
        if (rsp == null) {
            getLogger().info("沒裝 ServerAPI，略過資料串接。");
            return;
        }
        rsp.getProvider().register(CustomStation.builder("myplugin_stats", this)
                .description("我的插件統計")     // 選填，會顯示在 /api/v1 索引上
                .supplier(this::snapshot)
                .build());
    }

    // 各種型別都能直接回傳，ServerAPI 會遞迴轉成 JSON。
    // 要放 null 得用 LinkedHashMap，因為 Map.of 不接受 null。
    private Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kills", kills);                             // 數字（整數）
        data.put("kdr", 2.4);                                 // 數字（小數）
        data.put("ranked", true);                             // 布林
        data.put("topPlayer", "Alice");                       // 字串
        data.put("recentPlayers", List.of("Alice", "Bob"));   // 陣列（List）
        data.put("lastScores", new int[]{10, 8, 5});          // 陣列（int[] 也行）
        data.put("arena", Map.of("name", "sky", "round", 3)); // 巢狀物件（Map）
        data.put("matchId", matchId);                         // 其他型別（UUID）自動轉字串
        data.put("nextEvent", null);                          // null
        return data;
    }

    @Override
    public void onDisable() {
        var rsp = getServer().getServicesManager().getRegistration(ServerApiRegistry.class);
        if (rsp != null) rsp.getProvider().unregister("myplugin_stats");
    }
}
```

`unregister(...)` 會停止對外，但會**保留**伺服器管理員的設定檔，下次開插件不必重設。

### 站點名稱規則

- 只接受小寫英數與 `_` `-`，開頭須為英數，上限 32 字。
- 不能跟內建端點撞名（`status`、`player`、`server`…）。
- 不能用別的插件已註冊的名稱。

違反時 `register(...)` 會拋 `IllegalArgumentException`，後台也會說明原因。

---

## 怎麼把 ServerAPI 導入你的專案

你需要在編譯時看得到 `kevin.serverapi.api` 底下的兩個類別：`ServerApiRegistry` 與 `CustomStation`。
下面幾種做法擇一。**不論哪種，都不要把 ServerAPI 打包（shade）進你的 jar**，
它執行期由伺服器上的插件提供，你只是編譯期需要它的型別。

### 做法一：JitPack（作者只要在 GitHub 推一個 tag）

作者把專案掛上 JitPack 後（repo 裡放好 `jitpack.yml`，在 GitHub 推一個版本 tag），
你只要加 JitPack 的 repository 和 `com.github` 座標，第一次編譯時 JitPack 會自動幫你把它建好：

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.Kevin28576</groupId>
        <artifactId>ServerAPI</artifactId>
        <version>v1.1.0</version>
        <scope>provided</scope>
        <exclusions>
            <exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>
        </exclusions>
    </dependency>
</dependencies>
```

`version` 填 GitHub 上的 tag 名（例如 `v1.1.0`），也可以填某個 commit 的短雜湊。
JitPack 給的是整包插件，你只要那兩個介面，所以用 `exclusions` 把它自己的相依全排掉，最乾淨。

### 做法二：直接複製兩個介面（最省事，永遠可行）

不想碰 Maven，就把 `ServerApiRegistry.java` 與 `CustomStation.java` 兩個檔複製進你的專案。
它們只依賴 Bukkit 的 `Plugin`，沒有其他相依，複製過去就能編。缺點是 ServerAPI 改了介面你要自己同步。

---

## 宣告軟依賴（建議）

在你的 `plugin.yml` 或 `paper-plugin.yml` 把 ServerAPI 宣告為軟依賴，讓它先載入；未安裝時你的插件仍照常跑。

`plugin.yml`：

```yaml
softdepend: [ServerAPI]
```

`paper-plugin.yml`：

```yaml
dependencies:
  server:
    ServerAPI:
      load: BEFORE
      required: false
```

---

## 伺服器管理員會看到什麼

以下這段可以轉給伺服器管理員看。

插件一申請，後台就會提示，並產生一個設定檔（一個站點一個檔，檔名就是站點名）：

```yaml
# plugins/ServerAPI/custom/myplugin_stats.yml
# 有個插件想透過 ServerAPI 對外提供資料，所以自動建立了這個檔案。
# 預設是關的：看過內容覺得沒問題，就把 enabled 改成 true、再執行 /serverapi reload。
plugin: CoolPlugin
path: /api/v1/custom/myplugin_stats
mode: async
registered-at: 2026-07-23T10:00:00

enabled: false           # false＝這個網址打不開（回 404）；改成 true 才會真的對外
require-key: true        # true＝要帶 API 金鑰才讀得到；false＝誰都能讀
protected-fields: []     # 沒帶金鑰的人看不到這些欄位，例如 [balance]；留空就是全公開
```

`cached` 模式的站點會多一行 `snapshot-seconds`，用來調它多久更新一次；`async` 模式即時讀取，沒有這一行。

要開啟：`enabled` 改成 `true`，跑一次 `/serverapi reload`。開了以後這個站點會出現在 `/api/v1` 索引，
並套用伺服器管理員原本的三層存取控管（金鑰、隱藏欄位、未授權回 404），跟內建端點一模一樣。

- **唯讀欄位**（`plugin` / `path` / `mode` / `registered-at`）：插件每次註冊都會蓋回，改了沒用。
- **能改的**：`enabled`、`require-key`、`protected-fields`（`cached` 站點還有 `snapshot-seconds`）。

---

## 常見問題

**站點一直是 404。**
確認設定檔 `enabled: true` 且已 `/serverapi reload`。後台在插件註冊時會印出設定檔路徑。

**我要即時資料又要碰 Bukkit API？**
用 `.cached()` 並把間隔調小（例如 `.cached(1)`）。純即時（async）碰 Bukkit API 不安全，這是硬限制。

**改了供應者回傳的內容要重啟嗎？**
不用。供應者是你的程式碼，每次被呼叫都重跑；重啟只在改設定檔或重載時才需要。

**兩個插件想用同一個站點名？**
先到先得，第二個會被拒絕。各自取不同名字就好（例如加上你插件的前綴）。

**ServerAPI 沒裝，我的插件會壞嗎？**
不會，只要你有做 `getRegistration(...) == null` 的檢查。那段直接 return，其餘功能照常。
