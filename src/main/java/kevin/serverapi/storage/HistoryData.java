package kevin.serverapi.storage;

import java.util.List;

/**
 * 一段時間內的歷史取樣資料，<b>依時間由新到舊</b>：索引 0 是最新的一筆。
 *
 * 這個順序讓索引直接對應「幾個取樣週期以前」，
 * 第 i 筆的時間就是 {@code last - i * 取樣間隔}，取前 N 筆也不必先知道總長度。
 *
 * @param last    最新一筆取樣的時間戳（epoch millis），即索引 0 的時間；無資料時為 0
 * @param online  各取樣點的線上人數
 * @param discord 各取樣點的 Discord 成員數（無 DiscordSRV 時該點為 null）
 */
public record HistoryData(long last, List<Integer> online, List<Integer> discord) {}
