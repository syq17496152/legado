package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.ConcurrentException
import io.legado.app.model.analyzeRule.AnalyzeUrl.ConcurrentRecord
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class ConcurrentRateLimiter(source: BaseSource?) {

    companion object {
        val concurrentRecordMap = ConcurrentHashMap<String, ConcurrentRecord>()
        /**
         * 更新并发率
         */
        fun updateConcurrentRate(key: String, concurrentRate: String) {
            concurrentRecordMap.compute(key) { _, record ->
                try {
                    val rateIndex = concurrentRate.indexOf("/")
                    when {
                        rateIndex > 0 -> {
                            val accessLimit = concurrentRate.take(rateIndex).toInt()
                            val interval = concurrentRate.substring(rateIndex + 1).toInt()
                            if (accessLimit <= 0 || interval <= 0) throw NumberFormatException()
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                accessLimit,
                                interval,
                                record?.frequency ?: 0
                            )
                        }
                        concurrentRate.toInt() > 0 -> {
                            ConcurrentRecord(
                                record?.time ?: System.currentTimeMillis(),
                                1,
                                concurrentRate.toInt(),
                                record?.frequency ?: 0
                            )
                        }
                        else -> record
                    }
                } catch (_: NumberFormatException) {
                    record
                }
            }
        }

        /**
         * F-P1-C4 删除指定源的并发限流记录
         * 调用方：SourceHelp.deleteBookSourceInternal / deleteRssSourceInternal（删源时清理，避免内存泄漏）
         */
        fun clearRecord(key: String) {
            concurrentRecordMap.remove(key)
        }

        /**
         * B12 取更严格的并发率（吞吐更小者）
         * 用于缓存限流注入时合并用户配置与书源自身并发率
         */
        fun effectiveRate(rate1: String?, rate2: String?): String? {
            val t1 = throughput(rate1)
            val t2 = throughput(rate2)
            return if (t1 <= t2) rate1 else rate2
        }

        /**
         * B12 校验并发率格式：null/空/纯数字/次数/毫秒 均合法
         */
        fun isValidRate(rate: String?): Boolean {
            if (rate.isNullOrBlank()) return true
            val regex = Regex("""^(\d+)(/(\d+))?$""")
            val match = regex.matchEntire(rate.trim()) ?: return false
            if (match.groupValues[1].toIntOrNull()?.let { it <= 0 } == true) return false
            val interval = match.groupValues[3]
            return interval.isEmpty() || (interval.toIntOrNull()?.let { it > 0 } == true)
        }

        /**
         * B12 计算吞吐（每秒访问次数），越大限制越宽松
         * 纯数字视为间隔毫秒（1/毫秒）；次数/毫秒 计算 次数*1000/毫秒
         */
        private fun throughput(rate: String?): Double {
            if (rate.isNullOrBlank() || rate == "0") return Double.POSITIVE_INFINITY
            return try {
                val rateIndex = rate.indexOf("/")
                if (rateIndex > 0) {
                    val limit = rate.take(rateIndex).toInt()
                    val ms = rate.substring(rateIndex + 1).toInt()
                    if (limit <= 0 || ms <= 0) return Double.POSITIVE_INFINITY
                    limit * 1000.0 / ms
                } else {
                    val ms = rate.toInt()
                    if (ms <= 0) return Double.POSITIVE_INFINITY
                    1000.0 / ms
                }
            } catch (_: NumberFormatException) {
                Double.POSITIVE_INFINITY
            }
        }

        private fun buildRecord(rate: String): ConcurrentRecord {
            val rateIndex = rate.indexOf("/")
            return if (rateIndex > 0) {
                val accessLimit = rate.take(rateIndex).toIntOrNull() ?: 1
                val interval = rate.substring(rateIndex + 1).toIntOrNull() ?: 0
                ConcurrentRecord(System.currentTimeMillis(), accessLimit, interval, 1)
            } else {
                ConcurrentRecord(System.currentTimeMillis(), 1, rate.toIntOrNull() ?: 0, 1)
            }
        }

        private fun recordToRate(record: ConcurrentRecord): String {
            return if (record.accessLimit > 1) {
                "${record.accessLimit}/${record.interval}"
            } else {
                record.interval.toString()
            }
        }
    }

    private val source: BaseSource? = source
    private val key = source?.getKey()
    /**
     * 开始访问,并发判断
     * B12 实时读取 source.concurrentRate（非构造快照），缓存限流注入后可即时生效
     */
    @Throws(ConcurrentException::class)
    private fun fetchStart(): ConcurrentRecord? {
        val sourceRate = source?.concurrentRate
        if (sourceRate.isNullOrEmpty() || sourceRate == "0") {
            return null
        }
        val key = key ?: return null
        var isNewRecord = false
        val fetchRecord = concurrentRecordMap.compute(key) { _, record ->
            if (record == null) {
                isNewRecord = true
                return@compute buildRecord(sourceRate)
            }
            val recordRate = recordToRate(record)
            if (recordRate != sourceRate) {
                // 并发率已变更（如缓存限流注入），取更严格者平滑接管
                val effective = effectiveRate(sourceRate, recordRate)
                if (effective != recordRate) {
                    isNewRecord = true
                    return@compute buildRecord(effective ?: sourceRate)
                }
            }
            record
        } ?: return null
        if (isNewRecord) return fetchRecord
        val waitTime: Long = synchronized(fetchRecord) {
            //并发控制为 次数/毫秒 , 非并发实际为1/毫秒
            val nextTime = fetchRecord.time + fetchRecord.interval.toLong()
            val nowTime = System.currentTimeMillis()
            if (nowTime >= nextTime) {
                //已经过了限制时间,重置开始时间
                fetchRecord.time = nowTime
                fetchRecord.frequency = 1
                return@synchronized 0
            }
            if (fetchRecord.frequency < fetchRecord.accessLimit) {
                fetchRecord.frequency ++
                return@synchronized 0
            } else {
                return@synchronized nextTime - nowTime
            }
        }
        if (waitTime > 0) {
            kotlin.runCatching {
                AppLog.putDebugWithTag(
                    AppLog.TAG_CACHE_CONCURRENT,
                    "限流生效 key=$key 等待=${waitTime}ms",
                    level = AppLog.Level.INFO
                )
            }
            throw ConcurrentException(
                "根据并发率还需等待${waitTime}毫秒才可以访问",
                waitTime = waitTime
            )
        }
        return fetchRecord
    }

    /**
     * 获取并发记录，若处于并发限制状态下则会等待
     */
    suspend fun getConcurrentRecord(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                delay(e.waitTime)
            }
        }
    }

    fun getConcurrentRecordBlocking(): ConcurrentRecord? {
        while (true) {
            try {
                return fetchStart()
            } catch (e: ConcurrentException) {
                Thread.sleep(e.waitTime)
            }
        }
    }

    suspend inline fun <T> withLimit(block: () -> T): T {
        getConcurrentRecord()
        return block()
    }

    inline fun <T> withLimitBlocking(block: () -> T): T {
        getConcurrentRecordBlocking()
        return block()
    }

}
