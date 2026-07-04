package io.legado.app.help

import java.util.concurrent.ConcurrentHashMap

object IntentData {

    private val bigData: ConcurrentHashMap<String, Any> = ConcurrentHashMap()

    /** 追踪 Activity 级别的数据，用于自动清理防泄漏 */
    private val activityDataKeys: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

    @Synchronized
    fun put(key: String, data: Any?): String {
        data?.let {
            bigData[key] = data
        }
        return key
    }

    @Synchronized
    fun put(data: Any?): String {
        val key = System.currentTimeMillis().toString() + "_${Thread.currentThread().id}"
        data?.let {
            bigData[key] = data
        }
        return key
    }

    /**
     * 存入数据并关联到 Activity 生命周期，Activity 销毁时自动清理
     * 防止 Activity 对象因 IntentData 强引用而泄漏
     */
    fun putWithLifecycle(activityKey: String, data: Any?): String {
        val key = System.currentTimeMillis().toString() + "_${Thread.currentThread().id}"
        data?.let {
            bigData[key] = data
            activityDataKeys.getOrPut(activityKey) { mutableListOf() }.add(key)
        }
        return key
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String?): T? {
        if (key == null) return null
        val data = bigData.remove(key)
        return data as? T
    }

    /**
     * 清理指定 Activity 关联的所有数据
     * 应在 Activity.onDestroy 中调用
     */
    fun cleanup(activityKey: String) {
        activityDataKeys.remove(activityKey)?.forEach { key ->
            bigData.remove(key)
        }
    }

    /**
     * 清理超过 30 分钟未读取的数据，防止 Activity 异常退出时数据驻留
     */
    fun cleanupStaleData() {
        val now = System.currentTimeMillis()
        val staleKeys = mutableListOf<String>()
        bigData.forEach { (key, _) ->
            // 提取时间戳部分
            val timestamp = key.substringBefore("_").toLongOrNull() ?: return@forEach
            if (now - timestamp > 30 * 60 * 1000) {
                staleKeys.add(key)
            }
        }
        staleKeys.forEach { bigData.remove(it) }
    }
}