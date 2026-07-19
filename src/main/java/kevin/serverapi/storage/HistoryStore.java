package kevin.serverapi.storage;

/**
 * 歷史資料持久化後端（SQLite / MySQL / MariaDB）。
 * 所有方法皆預期在非同步執行緒呼叫。
 */
public interface HistoryStore extends AutoCloseable {

    /** 建立資料表（若不存在）。 */
    void init() throws Exception;

    /** 新增一筆取樣。 */
    void append(long ts, int online, Integer discord) throws Exception;

    /** 刪除早於指定時間戳的資料。 */
    void trim(long olderThanTs) throws Exception;

    /** 讀取時間戳 >= sinceTs 的資料（由舊到新）。 */
    HistoryData read(long sinceTs) throws Exception;

    @Override
    void close();
}
