package io.legado.app.help.video.engine

import android.webkit.CookieManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.video.SniffCandidate
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleDataInterface

/**
 * HeaderResolver 统一头收口（video-sniff-403-and-rss-classic-fix Phase 3 / 4.3 / AD-02）
 *
 * 结构性根源：头组装在播放链与下载链 5 处分叉（VideoPlay 内部重复点、VideoUrlExtractor headerMap、
 * ExoPlayerHelper.buildAntiLeechHeaders、ChunkDownloader.resolveHeaders、DownloadService.parseHeaders），
 * 语义同一实现各异——任何一处补 Cookie/补 Referer 都无法覆盖其余四处，"能播不能下"即由此而来。
 *
 * 本对象是"为资源构造访问头"的唯一出口，merge 策略固定三层（design §3.2）：
 * 1. 嗅探上下文优先：SniffCandidate.headers 是资源实际可访问时刻的实测有效环境（真实 Cookie/Referer/UA
 *    刚在页面侧访问成功），头冲突时嗅探值覆盖源配置；
 * 2. 源配置兜底：baseHeaders（AnalyzeUrl.headerMap 语义，声明式猜测）；
 * 3. CookieManager 兜底：CookieManager.getCookie(targetUrl) 按域过滤追加（不跨域泄露）。
 *
 * Referer 兜底顺序：嗅探头 > 源配置 > ruleData 页面链接。
 */
object HeaderResolver {

    private val gson = Gson()

    /**
     * 核心 merge（调用方已持有 base headerMap 时使用——VideoPlay 各播放分支、下载链）。
     *
     * @param candidate 嗅探候选（headers 为命中现场上下文；静态解析路径为空 map=零破坏退化）
     * @param baseHeaders 源配置头（AnalyzeUrl.headerMap；可为空）
     * @param refererFallback Referer 最终兜底（ruleData 页面链接；无则不注入）
     * @param targetUrl 目标资源 URL（CookieManager 按此域读 Cookie）
     */
    fun merge(
        candidate: SniffCandidate?,
        baseHeaders: Map<String, String>,
        refererFallback: String?,
        targetUrl: String
    ): Map<String, String> {
        val merged = linkedMapOf<String, String>()
        // 层 2：源配置兜底打底
        merged.putAll(baseHeaders)
        // 层 1：嗅探上下文覆盖（实测有效环境优先于声明式猜测）
        candidate?.headers?.forEach { (k, v) ->
            if (v.isNotBlank()) merged[k] = v
        }
        // Referer 兜底链：嗅探头 > 源配置 > 页面链接
        if (!merged.keys.any { it.equals("Referer", ignoreCase = true) }) {
            refererFallback?.takeIf { it.isNotBlank() }?.let { merged["Referer"] = it }
        }
        // 层 3：CookieManager 域内兜底（仅在目标域确有 Cookie 时追加，无 Cookie 输出与现状一致）
        if (!merged.keys.any { it.equals("Cookie", ignoreCase = true) }) {
            val cookie = runCatching {
                CookieManager.getInstance().getCookie(targetUrl)
            }.getOrNull()
            if (!cookie.isNullOrBlank()) merged["Cookie"] = cookie
        }
        return merged
    }

    /**
     * design §3.2 签名收口：内部构造 AnalyzeUrl 取源配置 headerMap 作 base。
     * 供未持有 base headerMap 的调用方（下载链按需嗅探等）使用。
     */
    fun buildHeaders(
        candidate: SniffCandidate?,
        source: BaseSource?,
        ruleData: RuleDataInterface?,
        targetUrl: String,
        refererFallback: String? = null
    ): Map<String, String> {
        val baseHeaders = runCatching {
            AnalyzeUrl(targetUrl, source = source, ruleData = ruleData).headerMap
        }.getOrNull() ?: emptyMap()
        return merge(candidate, baseHeaders, refererFallback, targetUrl)
    }

    // ==================== headersJson 持久化协议（下载链续传根治） ====================

    /**
     * 序列化完整头为 headersJson（下载任务入库时调用）。
     * 根治"恢复场景二次丢失"：DownloadService 恢复/续传时 parseHeaders 直接还原完整头，
     * 不再依赖"播放现场遗留头"（currentPlayHeaders 跨会话必然丢失）。
     */
    fun toJsonHeaders(headers: Map<String, String>): String {
        if (headers.isEmpty()) return ""
        return runCatching { gson.toJson(headers) }.getOrDefault("")
    }

    /**
     * 反序列化 headersJson（DownloadService 恢复/续传时调用）。
     * 旧任务 headersJson 缺失/非法时返回空 map，调用方降级 UA-only 现状行为（零破坏迁移）。
     */
    fun fromJsonHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson<Map<String, String>>(json, type).orEmpty()
        }.onFailure {
            AppLog.put("HeaderResolver: headersJson parse failed (${it.message}), fallback empty, len=${json.length}")
        }.getOrDefault(emptyMap())
    }

    /**
     * 脱敏日志输出（AppLog 规范：头内容不落明文，仅输出键名与总长度）。
     */
    fun summarize(headers: Map<String, String>): String {
        if (headers.isEmpty()) return "empty"
        return "keys=${headers.keys.joinToString(",")}, totalLen=${headers.values.sumOf { it.length }}"
    }
}
