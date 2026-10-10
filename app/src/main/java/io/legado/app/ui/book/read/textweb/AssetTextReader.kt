package io.legado.app.ui.book.read.textweb

import android.content.Context
import io.legado.app.constant.AppLog

/**
 * assets 文本读取器（带缓存）。
 *
 * 为什么需要缓存：富渲染运行时资产体量大（`mermaid.min.js` ≈ 2.4MB），而每次章节渲染都要读取；
 * 不缓存会造成"翻一页读一次 2.4MB"的明显卡顿。
 *
 * 读失败返回 null（调用方按"资产缺失"降级，不抛异常打断阅读）——与 [io.legado.app.help.md.MdRichRenderInjector]
 * 的 `assets.xxxJs == null ⇒ 不注入该运行时` 契约一致。
 */
internal class AssetTextReader(private val context: Context) {

    private val cache = HashMap<String, String?>()

    fun read(assetPath: String): String? {
        if (cache.containsKey(assetPath)) return cache[assetPath]
        val text = runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            AppLog.putDebug("asset read failed: $assetPath, ${error.localizedMessage}")
            null
        }
        cache[assetPath] = text
        return text
    }
}