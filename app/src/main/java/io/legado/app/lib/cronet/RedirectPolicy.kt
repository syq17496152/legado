package io.legado.app.lib.cronet

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 重定向解析与跟随决策（纯函数，无 Cronet / Android 依赖，可 JVM 单测）
 *
 * 背景（缺陷 IF-23 / spec: fix-cronet-redirect-follow）：
 * Cronet 适配层 [AbsCallBack] 原先在 `onRedirectReceived` 内直接 `Request.Builder.url(newLocationUrl)`，
 * 未按 RFC 用 base 解析 Location，且对空值/非法值无兜底；一旦抛异常即逃逸到 Cronet 回调线程，
 * `request.cancel()` 与 `onCanceled` 的「发下一跳」都不执行 ⇒ 请求既不成功也不失败 ⇒ 静默挂死。
 *
 * 本对象把「解析 + 跟随判定」抽成可单测的纯逻辑，[AbsCallBack] 内只做「取参 → 调策略 → 发/错」。
 */
object RedirectPolicy {

    sealed interface Decision {
        /** 可跟随：url 为解析后的**绝对**地址 */
        data class Follow(val url: String) : Decision

        /** 不可跟随：调用方必须**快速失败**（严禁静默挂死） */
        data class Reject(val reason: String) : Decision
    }

    /** 取 URL 的 scheme（非法/空返回 null） */
    fun schemeOf(url: String?): String? = url?.trim()?.toHttpUrlOrNull()?.scheme

    /**
     * 解析 Location 为绝对 URL（RFC 3986 相对解析，覆盖 绝对 / 相对 / protocol-relative）。
     *
     * @param baseUrl  发出该 3xx 的请求 URL。**必须是当跳响应 URL**：
     *                 多跳场景下 [AbsCallBack] 复用同一实例，`originalRequest` 仍停在首跳 URL，不可作 base。
     * @param location Location 值（可为空、可含首尾空白）
     * @return 绝对 URL 字符串；无法解析（空 / 非法 / 非 http(s)）返回 null —— **绝不抛异常**
     */
    fun resolve(baseUrl: String?, location: String?): String? {
        val loc = location?.trim().orEmpty()
        if (loc.isEmpty()) return null
        val base = baseUrl?.trim()?.toHttpUrlOrNull() ?: return null
        val resolved = base.resolve(loc) ?: return null
        val scheme = resolved.scheme
        if (scheme != "http" && scheme != "https") return null
        return resolved.toString()
    }

    /**
     * 跟随决策。
     *
     * @param allowCrossScheme 是否允许跨 scheme 跳转（对应 `OkHttpClient.followSslRedirects`）
     */
    fun decide(
        baseUrl: String?,
        location: String?,
        allowCrossScheme: Boolean
    ): Decision {
        val resolved = resolve(baseUrl, location)
            ?: return Decision.Reject("invalid or blank Location")
        if (!allowCrossScheme) {
            val baseScheme = schemeOf(baseUrl)
            val targetScheme = schemeOf(resolved)
            if (baseScheme != null && targetScheme != null && baseScheme != targetScheme) {
                return Decision.Reject("cross-scheme redirect not allowed")
            }
        }
        return Decision.Follow(resolved)
    }
}