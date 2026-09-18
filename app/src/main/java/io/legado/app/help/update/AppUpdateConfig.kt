package io.legado.app.help.update

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 更新加速配置（更新域偏好的唯一读写入口）
 *
 * - 通道已收敛为 GitHub 单通道，故此处只保留代理相关配置
 * - 代理模板列表：未配置（pref 无值）= 返回内置默认；用户显式清空（序列化 `[]`）= 尊重空列表
 * - 选中索引：0 起；-1 = 不使用代理
 */
object AppUpdateConfig {

    private const val URL_PLACEHOLDER = "\${url}"
    private const val URL_PLACEHOLDER_ALT = "{url}"

    /**
     * 内置公共加速模板（2026-09-18 逐个实测选定）
     *
     * 三个均通过"支持 Range 分片（206 且字节精确）+ 可代理 GitHub API（返回完整 JSON）"双门槛，
     * 下载速度 gh-proxy.com > tvv.tw > cors.isteed.cc。选型数据与淘汰清单见
     * `docs/specs/app-update-github-channel/design.md` §8 —— 公共公益站生命周期短，
     * 失效属预期，用户可在"更新加速管理"内自行增删改。
     */
    val DEFAULT_GITHUB_PROXY_TEMPLATES = listOf(
        "https://gh-proxy.com/$URL_PLACEHOLDER",
        "https://tvv.tw/$URL_PLACEHOLDER",
        "https://cors.isteed.cc/$URL_PLACEHOLDER"
    )

    /** 代理模板列表：未配置返回内置默认（不落盘）；写入侧统一 trim/去空/去重 */
    var githubProxyTemplates: List<String>
        get() {
            val raw = appCtx.getPrefString(PreferKey.updateGithubProxyTemplates)
                ?: return DEFAULT_GITHUB_PROXY_TEMPLATES
            return GSON.fromJsonArray<String>(raw)
                .getOrDefault(emptyList())
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }
        set(value) {
            val normalized = value
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            appCtx.putPrefString(PreferKey.updateGithubProxyTemplates, GSON.toJson(normalized))
            // 模板数变少时选中项可能越界，重置为"不使用代理"而非静默指向别的模板
            if (githubProxyIndex >= normalized.size) {
                githubProxyIndex = -1
            }
        }

    /** 当前选中的代理索引（-1=不使用代理）；默认 0 = 启用首个内置模板 */
    var githubProxyIndex: Int
        get() = appCtx.getPrefInt(PreferKey.updateGithubProxyIndex, 0)
        set(value) = appCtx.putPrefInt(PreferKey.updateGithubProxyIndex, value)

    /** 当前选中的代理模板；未选中/越界/列表为空时为 null（表示不使用代理） */
    val selectedGithubProxyTemplate: String?
        get() {
            val index = githubProxyIndex
            if (index < 0) return null
            return githubProxyTemplates.getOrNull(index)
        }

    /**
     * 按当前选中的代理模板改写地址（下载路径的唯一改写入口）
     * 模板含 `${url}` 或 `{url}` 时替换占位符，否则按"模板/原始地址"前缀拼接
     */
    fun applyGithubProxy(url: String): String {
        val template = selectedGithubProxyTemplate?.trim().orEmpty()
        return if (template.isBlank()) url else applyTemplate(template, url)
    }

    /** 用指定模板改写地址（查询候选序列需逐个模板改写，故独立暴露） */
    fun applyTemplate(template: String, url: String): String {
        val trimmed = template.trim()
        return when {
            trimmed.contains(URL_PLACEHOLDER) -> trimmed.replace(URL_PLACEHOLDER, url)
            trimmed.contains(URL_PLACEHOLDER_ALT) -> trimmed.replace(URL_PLACEHOLDER_ALT, url)
            else -> trimmed.trimEnd('/') + "/" + url
        }
    }

    /** 关于页入口摘要（代理只展示主机名，避免长模板撑爆设置行） */
    fun summary(context: Context): String {
        val template = selectedGithubProxyTemplate
        return if (template.isNullOrBlank()) {
            context.getString(R.string.update_accel_summary_none)
        } else {
            context.getString(R.string.update_accel_summary_proxy, proxyLabel(template))
        }
    }

    /** 模板主机名（用于摘要展示） */
    fun proxyLabel(template: String): String {
        return template
            .substringAfter("://")
            .substringBefore('/')
            .ifBlank { template }
    }
}