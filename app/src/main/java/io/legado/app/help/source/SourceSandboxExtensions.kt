package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.externalCache
import splitties.init.appCtx
import java.io.File

/**
 * 书源文件沙箱接入层（P0-S1，对齐 NG JsExtensions 私有助手独立化，可单测）
 *
 * 沙箱根 = externalCache/source/{ns}（ns = BookSourceStorageScope.namespace(sourceUrl)）。
 * 生效前提（同时满足）：开关 bookSourceFileSandbox 开 + 上下文为 BookSource。
 * RssSource / 纯 JS 加密任务（cryptoScope）不沙箱，走旧 externalCache 根零行为变化。
 */
internal object SourceSandboxExtensions {

    /**
     * 沙箱开关（实时读 SP，无设置页时改 SP 即时生效）
     */
    fun sandboxEnabled(): Boolean = AppConfig.bookSourceFileSandbox

    /**
     * 解析当前书源的沙箱根；非 BookSource 或开关关闭返回 null（调用方回退旧路径）
     */
    fun bookSourceFileRoot(source: BaseSource?): File? {
        if (!sandboxEnabled()) {
            return null
        }
        val bookSource = source as? BookSource ?: return null
        return BookSourceFileAccessPolicy.resolveSourceRoot(appCtx.externalCache, bookSource.bookSourceUrl)
    }

    /**
     * 在当前书源沙箱内解析路径；无沙箱上下文返回 null，越界抛 SecurityException 不回退
     */
    fun resolveBookSourceFile(source: BaseSource?, path: String): BookSourceFileTarget? =
        bookSourceFileRoot(source)?.let { BookSourceFileAccessPolicy.resolvePath(it, path) }

    /**
     * 校验文件及其子树位于当前书源沙箱内；无沙箱上下文时 no-op（与现状行为一致）
     */
    fun requireContainedTree(source: BaseSource?, file: File) {
        bookSourceFileRoot(source)?.let { BookSourceFileAccessPolicy.requireContainedTree(it, file) }
    }

    /**
     * 日志用源标识：ns 短码（前 8 位 hex），不记录源名称/URL（logging_rules 脱敏铁律）
     */
    fun nsShortLabel(source: BaseSource?): String =
        (source as? BookSource)
            ?.let { BookSourceStorageScope.namespace(it.bookSourceUrl).take(8) }
            ?: "-"
}
