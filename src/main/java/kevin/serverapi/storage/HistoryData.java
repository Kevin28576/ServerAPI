package kevin.serverapi.storage;

import java.util.List;

/**
 * 一段時間內的歷史取樣資料（依時間由舊到新）。
 *
 * @param last    最新一筆取樣的時間戳（epoch millis），無資料時為 0
 * @param online  各取樣點的線上人數
 * @param discord 各取樣點的 Discord 成員數（無 DiscordSRV 時該點為 null）
 */
public record HistoryData(long last, List<Integer> online, List<Integer> discord) {}
