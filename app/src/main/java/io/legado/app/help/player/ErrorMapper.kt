package io.legado.app.help.player

import io.legado.app.R
import io.legado.app.constant.AppLog

/**
 * AD-03: 播放错误码→用户友好提示映射器（纯UI，无副作用）
 *
 * 覆盖 5 大类错误：
 * - IO 类（网络连接/DNS/无效HTTP内容类型/超时）
 * - 解码类（解码器初始化失败/格式不支持）
 * - DRM 类（DRM 不支持）
 * - 源无效类（内容类型无效/源失效）
 * - 未知错误
 *
 * 成熟方案参考：ExoPlayer PlaybackException 错误码定义
 */
object ErrorMapper {

    /**
     * 用户面向错误结构
     * @param titleResId 错误标题资源ID（简短，如"网络错误"）
     * @param messageResId 错误详情资源ID（用户可读，禁止技术术语）
     * @param canRetry 是否可重试（网络类错误可重试，解码类不可重试）
     * @param canSwitchSource 是否可切换源（除DRM外均可切换）
     */
    data class UserFacingError(
        val titleResId: Int,
        val messageResId: Int,
        val canRetry: Boolean = true,
        val canSwitchSource: Boolean = true,
    )

    /**
     * 将错误信息字符串映射为用户友好错误提示
     *
     * 映射逻辑：根据 errorInfo 内容关键词判断错误类型
     * - 网络类：network/connection/timeout/超时/网络
     * - DNS类：dns/domain/域名
     * - 解码类：decoder/decode/codec/format/解码
     * - DRM类：drm/copyright/版权
     * - 源无效类：invalid/content_type/source/无效/失效
     * - 未知：其他
     *
     * @param errorInfo 错误信息字符串（可能包含错误码或关键词）
     * @return UserFacingError 用户友好错误结构
     */
    fun map(errorInfo: String): UserFacingError {
        val lowerInfo = errorInfo.lowercase()
        AppLog.put("ErrorMapper: mapping error, infoLen=${errorInfo.length}")

        return when {
            // 网络连接失败/超时
            lowerInfo.contains("network") ||
            lowerInfo.contains("connection") ||
            lowerInfo.contains("io_network") ||
            lowerInfo.contains("timeout") ||
            lowerInfo.contains("超时") ||
            lowerInfo.contains("网络") -> UserFacingError(
                titleResId = R.string.player_error_network_title,
                messageResId = R.string.player_error_network_message,
                canRetry = true,
                canSwitchSource = true
            )

            // DNS 解析失败
            lowerInfo.contains("dns") ||
            lowerInfo.contains("domain") ||
            lowerInfo.contains("域名") -> UserFacingError(
                titleResId = R.string.player_error_dns_title,
                messageResId = R.string.player_error_dns_message,
                canRetry = false,
                canSwitchSource = true
            )

            // 解码错误
            lowerInfo.contains("decoder") ||
            lowerInfo.contains("decode") ||
            lowerInfo.contains("codec") ||
            lowerInfo.contains("format") ||
            lowerInfo.contains("解码") -> UserFacingError(
                titleResId = R.string.player_error_decoder_title,
                messageResId = R.string.player_error_decoder_message,
                canRetry = false,
                canSwitchSource = true
            )

            // DRM 错误
            lowerInfo.contains("drm") ||
            lowerInfo.contains("copyright") ||
            lowerInfo.contains("版权") -> UserFacingError(
                titleResId = R.string.player_error_drm_title,
                messageResId = R.string.player_error_drm_message,
                canRetry = false,
                canSwitchSource = false
            )

            // 源无效
            lowerInfo.contains("invalid") ||
            lowerInfo.contains("content_type") ||
            lowerInfo.contains("source") ||
            lowerInfo.contains("无效") ||
            lowerInfo.contains("失效") -> UserFacingError(
                titleResId = R.string.player_error_source_title,
                messageResId = R.string.player_error_source_message,
                canRetry = false,
                canSwitchSource = true
            )

            // 未知错误
            else -> UserFacingError(
                titleResId = R.string.player_error_unknown_title,
                messageResId = R.string.player_error_unknown_message,
                canRetry = true,
                canSwitchSource = true
            )
        }
    }
}
