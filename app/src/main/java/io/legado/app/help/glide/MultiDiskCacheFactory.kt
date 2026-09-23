package io.legado.app.help.glide

import android.content.Context
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.cache.DiskCache
import com.bumptech.glide.load.engine.cache.DiskCacheAdapter
import com.bumptech.glide.load.engine.cache.DiskLruCacheFactory
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.signature.ObjectKey
import io.legado.app.constant.AppLog
import java.io.File

/**
 * R19（B4）「封面」磁盘缓存标记。
 *
 * 用途：把**封面图**路由到持久区、其余图片留在临时区（见 [MultiDiskCacheFactory]）。
 *
 * 识别方式：请求侧给封面加 [SIGNATURE]（Glide 会把它编进缓存键），缓存侧用
 * [isCover] 判键。之所以用签名而不是「按 URL 猜」：封面 URL 来自各源、形态不一，
 * 猜错会把普通图当封面长期驻留（或反之）；签名是**显式声明**，不存在猜测。
 *
 * 已知上限：判据是键字符串包含标记（非结构化字段）—— Glide 未暴露「取签名」的公共 API；
 * 标记串含 `:` 且足够特异，撞键概率可忽略。升级路径：Glide 若提供签名读取 API 则改为精确比较。
 */
object CoverDiskCacheMarker {

    const val MARKER = "legado:cover"

    val SIGNATURE: Key = ObjectKey(MARKER)

    fun isCover(key: Key?): Boolean = key != null && key.toString().contains(MARKER)
}

/**
 * R19（B4）封面双区磁盘缓存工厂。
 *
 * 分区与生命周期（**本项的核心语义**）：
 * | 区 | 落点 | 淘汰 | 清缓存（缓存管理页） |
 * |----|------|------|--------------------|
 * | 封面持久区 | `filesDir/cover_disk_cache` | 仅按自身容量上限 | **不受影响** ⇒ 断网仍显示封面 |
 * | 普通临时区 | `cacheDir/common_disk_cache` | 按容量上限 | 随缓存目录一并清掉 |
 *
 * 为什么封面要放 `filesDir`：应用的「缓存管理」是清 `cacheDir`（部分是整目录删除），
 * 若封面也放 `cacheDir`，则「清缓存 ⇒ 封面重下」——这正是本项要消除的体验问题
 * （书源封面多来自外站，重下既慢又易失败）。
 *
 * 容量（[COVER_MAX_BYTES] / [COMMON_MAX_BYTES]）：沿用改造前单区 1000MB 的总量口径拆分
 * （改造前 `InternalCacheDiskCacheFactory(context, 1000MB)`），总量不增。
 */
class MultiDiskCacheFactory(private val context: Context) : DiskCache.Factory {

    override fun build(): DiskCache {
        val coverFactory = DiskLruCacheFactory(
            DiskLruCacheFactory.CacheDirectoryGetter {
                File(context.filesDir, COVER_DIR_NAME).apply { mkdirs() }
            },
            COVER_MAX_BYTES
        )
        val commonFactory = InternalCacheDiskCacheFactory(context, COMMON_DIR_NAME, COMMON_MAX_BYTES)
        // Glide 的 Factory.build() 可返回 null（目录不可写等）⇒ 用空实现兜底，避免引擎侧 NPE；
        // 单区失败时另一区仍可用（封面区失败 ⇒ 退化为全部落临时区，功能不中断）。
        val coverCache = coverFactory.build() ?: DiskCacheAdapter()
        val commonCache = commonFactory.build() ?: DiskCacheAdapter()
        // 正式诊断日志（保留）：双区落点是"清缓存后封面是否还在"的唯一可观测证据，
        // 真机排查「封面重下」类问题时需要它（AppLog 统一通道，非临时排查日志）。
        AppLog.putDebugWithTag(
            TAG,
            "双区磁盘缓存就绪: cover=${File(context.filesDir, COVER_DIR_NAME).absolutePath}" +
                "(${COVER_MAX_BYTES / 1024 / 1024}MB) common=${File(context.cacheDir, COMMON_DIR_NAME).absolutePath}" +
                "(${COMMON_MAX_BYTES / 1024 / 1024}MB)",
            null,
            AppLog.Level.INFO
        )
        return MultiDiskCache(coverCache, commonCache).also {
            // 一次性清理改造前的单区遗留目录：它已不被任何代码读取（内容不可达），留着只占空间。
            // 幂等且只针对该固定目录名；失败不影响功能（仅告警）。
            runCatching {
                val legacy = File(context.cacheDir, LEGACY_DIR_NAME)
                if (legacy.exists()) {
                    legacy.deleteRecursively()
                    AppLog.putDebugWithTag(TAG, "已清理旧单区缓存目录 $LEGACY_DIR_NAME", null, AppLog.Level.INFO)
                }
            }.onFailure {
                AppLog.putDebugWithTag(TAG, "清理旧单区缓存失败: ${it.localizedMessage}", it, AppLog.Level.WARN)
            }
        }
    }

    companion object {
        /** 诊断日志 tag（正式日志，勿删） */
        const val TAG = "CoverDiskCache"
        /** 封面持久区目录名（`filesDir` 下） */
        const val COVER_DIR_NAME = "cover_disk_cache"

        /** 普通临时区目录名（`cacheDir` 下） */
        const val COMMON_DIR_NAME = "common_disk_cache"

        /** 封面区上限（改造前总量 1000MB 的 1/4） */
        const val COVER_MAX_BYTES: Long = 256L * 1024 * 1024

        /** 临时区上限（其余 3/4） */
        const val COMMON_MAX_BYTES: Long = 768L * 1024 * 1024

        /**
         * 改造前的单区目录名（`cacheDir` 下）—— 仅用于**一次性清理遗留**，
         * 新代码不从这里读任何东西（Glide 默认名，勿改为其它用途）。
         */
        const val LEGACY_DIR_NAME = "image_manager_disk_cache"
    }
}

/**
 * R19（B4）双区磁盘缓存路由（实现 Glide [DiskCache]）。
 *
 * 路由规则：键命中 [CoverDiskCacheMarker] ⇒ 封面持久区，否则临时区。
 * 生命周期语义：`clear()` **只清临时区**（封面持久区不参与）—— 这是
 * 「清缓存后断网仍显示封面」的实现点；需要全清时由 [clearAll] 显式调用。
 *
 * 绕过语义（X4 回归）：本类不参与 `DiskCacheStrategy` / `skipMemoryCache` / `bypassFailCache`
 * 的决策（那些在 Glide 引擎层），只决定「写哪里/读哪里」⇒ 既有绕过行为不变。
 */
class MultiDiskCache(
    private val coverCache: DiskCache,
    private val commonCache: DiskCache
) : DiskCache {

    private fun pick(key: Key?): DiskCache =
        if (CoverDiskCacheMarker.isCover(key)) coverCache else commonCache

    override fun get(key: Key): File? = pick(key).get(key)

    override fun put(key: Key, writer: DiskCache.Writer) {
        pick(key).put(key, writer)
    }

    override fun delete(key: Key) {
        pick(key).delete(key)
    }

    /** 清缓存：**只清临时区**（封面持久区保留，见类注释） */
    override fun clear() {
        commonCache.clear()
    }

    /** 全量清理（含封面持久区）：仅供显式「清空全部图片缓存」场景使用 */
    fun clearAll() {
        commonCache.clear()
        coverCache.clear()
    }

    /** 仅供诊断/单测：当前键会落到哪个区 */
    internal fun isCoverKey(key: Key?): Boolean = CoverDiskCacheMarker.isCover(key)
}