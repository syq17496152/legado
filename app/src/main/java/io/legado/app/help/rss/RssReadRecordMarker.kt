package io.legado.app.help.rss

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

/**
 * W4 / REQ-18：订阅**一键全标已读**的唯一编排入口（纯数据层，UI 只负责确认与回执）。
 *
 * 为什么需要编排而非一句 SQL（实证根因）：
 * 文章列表的未读判定是 `left join rssReadRecords` + `ifNull(read, 0)`
 * ⇒ ① 已有记录置 `read = 1` 只能覆盖「读过但标为未读」的行；
 * ② **没有记录的文章**必须**补一条**记录才会变已读。
 * 两步顺序固定：先补缺记录，再统一置读（`insert` 的 `read` 已为 1，第二次 update 只作幂等兜底）。
 *
 * 范围统一以 **origin 集合** 表达（全部 / 当前源 / 当前分组 三种语义都由调用方换算成集合），
 * 避免在数据层再分叉出三套 SQL。
 */
object RssReadRecordMarker {

    /**
     * 把 [origins] 范围内所有订阅文章标为已读。
     *
     * @return 实际改动行数（新增记录数 + 置读行数），供 UI 回执；`0` 表示范围内已全部已读
     */
    suspend fun markRead(origins: List<String>): Int = withContext(IO) {
        if (origins.isEmpty()) return@withContext 0
        val now = System.currentTimeMillis()
        // 新增行数：Room 的 INSERT…SELECT 无法回传 rowid ⇒ 用记录总数前后差值统计（同一 IO 线程内无并发插入）
        val before = appDb.rssReadRecordDao.countRecords
        appDb.rssReadRecordDao.insertMissingAsReadByOrigins(origins, now)
        val inserted = appDb.rssReadRecordDao.countRecords - before
        val updated = appDb.rssReadRecordDao.markAllReadByOrigins(origins)
        val total = inserted + updated
        // 诊断日志（日志子规范：只记技术字段 —— 源数量/改动行数，不落源名与文章标题）
        AppLog.putInfo(
            "RssMarkRead: origins=${origins.size}, inserted=$inserted, updated=$updated"
        )
        total
    }

    /** 全部订阅源（「全部标为已读」范围）。 */
    suspend fun allOrigins(): List<String> = withContext(IO) {
        appDb.rssSourceDao.all.map { it.sourceUrl }
    }

    /** 当前分组范围：按「源分组的扁平标签集合」筛选（分组为逗号扁平串，无层级语义）。 */
    suspend fun originsOfGroup(group: String): List<String> = withContext(IO) {
        appDb.rssSourceDao.all.filter { it.hasGroup(group) }.map { it.sourceUrl }
    }
}