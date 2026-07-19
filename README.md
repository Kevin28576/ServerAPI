# ServerAPI

**繁體中文** · [English](README_EN.md)

一套 REST API，讓網站、Discord bot 與監控面板讀取 Minecraft 伺服器的即時資料。

![ServerAPI](banner.png)

[![Paper](https://img.shields.io/badge/Paper-26.1-blue)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://adoptium.net)
[![Version](https://img.shields.io/badge/version-1.0.0-green)](https://github.com/Kevin28576/ServerAPI/releases)

```bash
curl http://localhost:8080/api/v1/status
```
```json
{
  "ok": true,
  "data": { "online": ["40094f2f-…", "8b1e…"], "minecraft": 2, "discord": 3400 },
  "meta": { "version": "v1", "server": "global", "updatedAt": 1784300000000, "cachedAt": 1784299700000 }
}
```

所有玩家欄位一律使用 **UUID**。

---

## 特色

- **不阻塞主執行緒** — 需要主執行緒才安全的資料由主執行緒定期快照，HTTP 請求只讀快取，請求量再大也不影響 TPS
- **每個端點獨立控管** — 逐一決定是否需要金鑰，還能指定「即使端點公開也要藏起來」的欄位
- **處罰紀錄永久保存** — 封鎖／臨時封鎖／踢出／警告／禁言／監禁，解封後仍保留於歷史
- **內建速率限制** — token bucket，正確處理 Cloudflare／Nginx 後方的真實來源 IP
- **零執行期相依** — HTTP 層使用 JDK 內建伺服器，JSON 自行序列化
- **完整雙語** — 繁體中文／英文，涵蓋設定檔、主控台、指令與 API 輸出

## 安裝

1. 下載 `ServerAPI-1.0.0.jar` 放入 `plugins/`
2. 啟動伺服器，產生 `plugins/ServerAPI/config.yml`
3. 確認 `http.port`（**預設 8080，常與其他服務衝突**）
4. `curl http://localhost:8080/api/v1`

需要 Paper 26.1 與 Java 21 以上。首次啟動會由 Paper 的 PluginLoader 自 Maven 下載
資料庫驅動（HikariCP／JDBC／Jedis），因此**第一次啟動需要能連上網路**。

## 端點

索引 `GET /api/v1` 會列出目前啟用的所有端點。

| 端點 | 內容 | 預設需金鑰 |
|------|------|:---:|
| `/status` | 線上玩家 UUID 與人數、DiscordSRV 成員數 | |
| `/status?history=true` | 附上線上人數與 Discord 人數的時間序列 | |
| `/player/{uuid}` | 單一玩家：名稱、rank、暱稱、遊玩時長、AFK、餘額、Discord | |
| `/player/{uuid}?detailed=true` | 展開方塊／物品／實體的細項統計 | |
| `/server` | 版本、MOTD、最大玩家數、遊戲模式、視距 | ✓ |
| `/performance` | TPS、平均 tick 時間、記憶體、JVM／OS | ✓ |
| `/worlds`、`/worlds/{name}` | 世界列表與細節 | ✓ |
| `/gamerules` | 各世界的 game rule | ✓ |
| `/entities` | 各世界實體統計（依類型，排除玩家） | ✓ |
| `/spawnlimits` | 各世界生怪上限與間隔 | ✓ |
| `/punishments`、`/punishments/{uuid}` | 處罰紀錄與統計 | ✓ |
| `/bans` | 封鎖名單（玩家 UUID 與 IP） | ✓ |
| `/operators` | 管理員 (OP) 名單 | ✓ |
| `/whitelist` | 白名單狀態與名單 | ✓ |
| `/plugins` | 已安裝插件列表 | ✓ |
| `/placeholders?uuid=&p=` | 解析 PlaceholderAPI 變數 | ✓ |
| `/network` | 跨伺服器彙整（需多服 + Redis） | ✓ |
| `/constants` | 靜態列舉參考（遊戲模式、難度、環境） | ✓ |

每個端點同時掛在兩個路徑：`/api/v1/{端點}` 是版本化路徑，**對外串接請用這個**；
`/api/{端點}` 是別名，永遠指向最新版本。

實體統計刻意排除玩家，以免間接洩漏線上玩家資訊。

## 回應格式

```json
{ "ok": true,  "data": { … }, "meta": { … } }
{ "ok": false, "error": { "status": 404, "message": "…", "path": "…" }, "meta": { … } }
```

呼叫端先看 `ok` 分流即可，內容永遠在 `data`。

`meta` 內兩個時間戳的差別：

| 欄位 | 出現於 | 意義 |
|------|--------|------|
| `updatedAt` | 所有端點 | 本次回應產生的時間 |
| `cachedAt` | 快照端點 | 資料實際擷取的時間 |

`updatedAt - cachedAt` 就是這份資料的陳舊程度。要「資料真正的時間」請用 `cachedAt`。

## 驗證

金鑰以標頭 `X-API-Key` 帶入，或（若 `auth.allow-query-key` 開啟）用 `?key=`：

```bash
curl -H "X-API-Key: <金鑰>" http://localhost:8080/api/v1/plugins
curl "http://localhost:8080/api/v1/plugins?key=<金鑰>"
```

> 網址會留在瀏覽器歷史與反向代理的存取記錄裡，正式串接請用標頭。

三層控管：

**端點層** — `auth.require-key.<端點>` 逐一決定。未列出的端點預設需要金鑰，新端點不會意外公開。

**欄位層** — `auth.protected-fields.<端點>` 指定「端點公開、但這些欄位要藏起來」。
預設 `/player` 會藏 `op`、`banned`、`discord`：前兩者在 `/operators`、`/bans` 都需要金鑰，
不擋等於留後門 —— 從公開的 `/status` 取得線上 UUID 再逐一查 `/player` 就能還原那兩份名單。
被隱藏的欄位會列在 `meta.hiddenFields`。

**回應偽裝** — `auth.hide-protected` 開啟時，未授權的請求會收到 404 而非 401。
回 401 等於告訴對方「這裡有值得保護的東西」並替他標出目標。

> `auth.enabled` 為 `false` 時整套驗證關閉，上述設定一律不生效。
> 這種狀態下啟動會有警告提醒。

## 速率限制與反向代理

Token bucket：以固定速率補充額度，容許短暫叢發，持續超量回 `429` 並附 `Retry-After`。
預設每個來源 IP 每分鐘 120 次、突發 30 次。

掛在 Cloudflare／Nginx／Apache 後方時，所有訪客的來源都會是代理主機，限流會因此失準。
開啟 `real-ip.enabled` 後改從代理標頭取得真實 IP：

```yaml
real-ip:
  enabled: true
  trusted-proxies:
    - "cloudflare"        # 展開為 Cloudflare 公布的所有區段
    - "127.0.0.1/32"
```

代理標頭可由用戶端任意偽造，因此**只有當連線來源本身屬於 `trusted-proxies` 時才會被採信**。
`X-Forwarded-For` 由右往左掃描，略過可信代理後取第一個非可信位址。

## 處罰紀錄

`/punishments` 彙整封鎖、IP 封鎖、臨時封鎖、踢出、警告、禁言、監禁，
並附上統計（總數、受罰人數、目前生效中）。

紀錄**永久保留** —— 解封或到期的項目仍會列出，只是狀態改為已解除／已到期，
適合做公開的處罰查詢頁。`punishment-log.retention-days: 0` 表示永不清除。

Minecraft 與 CMI 都不保存踢出歷史，由本插件監聽事件自行記錄，
因此只涵蓋插件啟用之後發生的踢出。

資料來源是定期與封鎖名單／CMI 對帳，而非攔截指令，所以不論處罰是誰下的都會被記錄。

## 網頁介面

三個以 HTML 呈現的頁面，**預設全部關閉**：

```yaml
web:
  docs: false           # /api/docs                 互動式 API 文件與試用介面
  punishments: false    # /punishments?view=html    處罰紀錄展示頁
  placeholders: false   # /placeholders?list=true   PlaceholderAPI 預覽頁
```

這些頁面會把內容完整攤開給任何打得開的人，公開環境請斟酌。
關閉時對應路徑與參數一律回 404，JSON 端點不受影響。

`/api/docs` 會列出所有已啟用端點與參數，可直接在頁面上送出請求看到真實回應，
並附金鑰輸入欄（只存在瀏覽器本機）。

## 資料儲存

歷史資料、玩家統計快取與處罰日誌都寫入資料庫，資料庫是唯一真相來源。

```yaml
storage:
  type: sqlite          # sqlite · mysql · mariadb
```

可選用 Redis 加速：查詢優先讀 Redis，更新時先寫資料庫再覆蓋 Redis。
Redis 掛掉會自動降級為資料庫，服務不中斷，恢復後自動回填。

> 單機且 Redis 在遠端時，本地資料庫通常反而更快。跨服彙整才是 Redis 的主要用途。

離線玩家的統計需重讀統計檔（實測約 425ms，線上玩家僅約 4ms），因此只對離線玩家快取。
快取存於 Redis／資料庫，**不佔用伺服器記憶體**。

## 多服

多台伺服器共用同一個 Redis／資料庫，以 `server-id` 隔離資料；
同一 `cluster-id` 的伺服器會互相發現，可由 `/network` 取得全網彙整。

```
鍵名   {namespace}:{cluster-id}:{server-id}:{type}[:{id}]
單機   serverapi:::history
多服   serverapi:main:survival:history
```

`server-id` 預設為 `global`，代表未指定、視為單機。
多台共用同一個 Redis 時**務必各設唯一值**，否則資料會互相覆蓋（啟動時會警告）。

## 多語言

內建繁體中文與英文，涵蓋設定檔註解、主控台訊息、指令回應與 API 輸出。
玩家自行輸入的內容（封鎖理由、暱稱）不會被翻譯。

`language: auto` 會依主機語系判斷，首次啟動後寫入實際結果。用指令切換：

```
/serverapi lang en_US
```

切換時會先備份現有設定檔，再以新語言的範本重建，**你的設定值全數保留**。
語言檔位於 `plugins/ServerAPI/lang/`，可自行修改；改版新增的鍵會自動由內建版本補上。

## 第三方整合

皆為選用軟依賴，未安裝時對應欄位為 `null`，不影響其他功能。

| 插件 | 提供 |
|------|------|
| LuckPerms | `/player` 的 `rank` |
| CMI | `/player` 的暱稱、顏色、遊玩時長、AFK；`/punishments` 的警告、禁言、監禁 |
| Vault | `/player` 的 `balance` |
| DiscordSRV | `/player` 的 `discord`、`/status` 的 Discord 成員數 |
| PlaceholderAPI | `/placeholders` 端點 |

`/placeholders` 是唯一會派工到主執行緒的端點（多數 expansion 需存取 Bukkit API），
已設逾時上限保護，逾時回 `504`。

## 指令與權限

| 指令 | 說明 |
|------|------|
| `/serverapi info` | 查看目前狀態 |
| `/serverapi reload` | 重新載入設定並重啟 HTTP 伺服器 |
| `/serverapi lang <代碼>` | 切換語言 |

| 權限 | 預設 | 說明 |
|------|:---:|------|
| `serverapi.admin` | op | 使用上述指令 |
| `serverapi.notice` | op | `debug` 開啟時接收資料請求通知 |

## 快照間隔

依「變動頻率 × 成本」各自調整（`config.yml` 的 `cache`，單位為 tick）：

| 端點 | 預設 | 理由 |
|------|------|------|
| `status` | 5 秒 | 最即時，成本也最低 |
| `entities` | 20 秒 | 需遍歷所有實體，最耗費 |
| `worlds` / `gamerules` / `spawnlimits` | 10 分鐘 | 變動慢 |

## 建置

編譯需要 **JDK 25**（paper-api 26.1.2 的 class 檔為 Java 25 格式），
產出的 jar 以 Java 21 為目標，因此執行只需 Java 21 以上。

```bash
mvn clean package
```

產物：`target/ServerAPI-1.0.0.jar`

## 授權

僅供非商業用途使用，商業伺服器需另行取得授權。詳見 [LICENSE](LICENSE)。

問題回報：[GitHub Issues](https://github.com/Kevin28576/ServerAPI/issues)
