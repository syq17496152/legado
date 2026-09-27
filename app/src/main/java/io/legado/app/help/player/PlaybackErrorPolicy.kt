package io.legado.app.help.player

import androidx.media3.common.PlaybackException

/**
 * 播放错误大类（W2 / REQ-13 / AD-04）。
 *
 * 裁决表把「错误是什么」与「该怎么办」分离：本枚举只做**分类**（纯函数），
 * 三态动作由 [PlaybackErrorPolicy.actionOf] 给出，重试预算由 [PlaybackErrorSession] 记账。
 * 分类口径对齐 ExoPlayer `PlaybackException` 官方错误码分组。
 */
enum class PlaybackErrorKind {
    /** 网络类：连接失败 / 连接超时 / IO 未指定 —— 同源重试即可能恢复（可自愈）。 */
    NETWORK,

    /** HTTP 状态类：服务器返回错误状态码 —— 当前链接已不可用，须换链/换源。 */
    HTTP_STATUS,

    /** 资源类：文件不存在 / 内容类型无效 / 无权限 —— 换链。 */
    RESOURCE,

    /** 清单与容器解析类：格式不符 —— 换链（换 contentType / 换源）。 */
    PARSE,

    /** 解码类（含 DRM / 音轨）：**不可自愈，换线无意义** —— 直接终止。 */
    DECODE,

    /** 直播落后窗口：追帧即愈（仅直播流会产生该错误码）。 */
    LIVE_WINDOW,

    /** 未归类：保守终止（宁可提示，不做无界重试）。 */
    UNKNOWN
}

/**
 * 三态裁决（W2 / REQ-13 / AD-04）：
 * - [SELF_HEAL]：自愈（音频原地重试 / 视频追帧）；
 * - [DEGRADE]：降级（换链、换线路、推进到下一项）；
 * - [ABORT]：终止（交回既有错误提示链路）。
 */
enum class PlaybackErrorAction {
    SELF_HEAL,
    DEGRADE,
    ABORT
}

/**
 * 播放错误分类与三态裁决表（W2 / REQ-13 / AD-04，纯逻辑、零副作用 ⇒ 可 JVM 单测）。
 *
 * 设计要点：
 * - **不可自愈类不换线**：解码/DRM/音轨类失败换线必然复现，直接 [PlaybackErrorAction.ABORT]；
 * - **面向上层复用**：视频侧（`Exo2MediaPlayer`）与音频侧（`AudioPlayService` / `HttpReadAloudService`）
 *   共用同一裁决表，避免三处各自维护阈值与优先级（本项目已多次发生同类漂移）；
 * - 阈值预置决策：重试上限 **3** 次、冷却 **60s**（design §9.2#16，与嗅探赛马阈值一致便于统一观测）。
 *
 * 注意：`PlaybackException.ERROR_CODE_*` 均为编译期常量（会被内联），因此本文件不引入 Android 运行时依赖。
 */
object PlaybackErrorPolicy {

    /** 自愈/降级动作上限（预置决策 design §9.2#16）。 */
    const val MAX_ATTEMPTS = 3

    /** 上限耗尽后的冷却时长（预置决策 design §9.2#16）。 */
    const val COOLDOWN_MS = 60_000L

    /**
     * 错误码 → 大类。
     *
     * 映射依据（media3 `PlaybackException` 错误码分组）：
     * - 2000-2003：IO 类（未指定 / 连接失败 / 连接超时 / HTTP 状态错误）
     * - 2004-2007：IO 内容与资源类（内容类型无效 / 文件不存在 / 明文被禁 / 无权限）
     * - 1002：直播落后窗口
     * - 3001-3005：解析类（容器/清单 格式错误或不支持）
     * - 4001-4003：解码类；6000-6003：DRM 类；5001-5002：音轨类
     */
    fun classify(errorCode: Int): PlaybackErrorKind = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> PlaybackErrorKind.NETWORK

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackErrorKind.HTTP_STATUS

        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlaybackErrorKind.RESOURCE

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackErrorKind.PARSE

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> PlaybackErrorKind.DECODE

        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> PlaybackErrorKind.LIVE_WINDOW

        else -> PlaybackErrorKind.UNKNOWN
    }

    /**
     * 大类 → 动作（**不含**预算判断；预算由 [PlaybackErrorSession] 记账）。
     *
     * 裁决表：
     * | 大类 | 动作 | 理由 |
     * |------|------|------|
     * | LIVE_WINDOW | SELF_HEAL | 追帧即愈，换源无意义 |
     * | NETWORK | SELF_HEAL | 抖动类，同源重试可恢复 |
     * | HTTP_STATUS / RESOURCE / PARSE | DEGRADE | 当前链接不可用，须换链 |
     * | DECODE / UNKNOWN | ABORT | 不可自愈，换线必然复现 |
     */
    fun actionOf(kind: PlaybackErrorKind): PlaybackErrorAction = when (kind) {
        PlaybackErrorKind.LIVE_WINDOW,
        PlaybackErrorKind.NETWORK -> PlaybackErrorAction.SELF_HEAL

        PlaybackErrorKind.HTTP_STATUS,
        PlaybackErrorKind.RESOURCE,
        PlaybackErrorKind.PARSE -> PlaybackErrorAction.DEGRADE

        PlaybackErrorKind.DECODE,
        PlaybackErrorKind.UNKNOWN -> PlaybackErrorAction.ABORT
    }

    /** 换线裁决结果（W2 / REQ-13：把「能否换线 + 换到哪条」收敛为纯函数，便于 JVM 单测）。 */
    data class RouteSelfHealDecision(
        val action: PlaybackErrorAction,
        /** 目标线路索引；[NO_ROUTE_SELF_HEAL] 表示**不换线**（走既有错误提示） */
        val targetIndex: Int,
        val attempts: Int,
        val coolingDown: Boolean
    ) {
        /** 是否允许本次换线。 */
        val allowed: Boolean get() = targetIndex >= 0
    }

    /** 不允许换线的哨兵值。 */
    const val NO_ROUTE_SELF_HEAL = -1

    /**
     * 「首线路失败自动换线」裁决（design 3.1 验收 / AD-04）。
     *
     * 规则：
     * - 线路数 ≤ 1 或无错误大类 ⇒ 不换（单线路源无换线语义；无分类时保守终止）；
     * - 策略裁决 [PlaybackErrorAction.ABORT]（解码 / DRM / 未知等**不可自愈类**，或预算耗尽 / 冷却期）
     *   ⇒ **不换线**；
     * - 其余（SELF_HEAL / DEGRADE）⇒ 换到 `currentIndex + 1`（末条回卷首条，与线路选择器一致）。
     *
     * 副作用：SELF_HEAL/DEGRADE 时已由 [PlaybackErrorSession] 计入预算（ABORT 不计）。
     */
    fun decideRouteSelfHeal(
        kind: PlaybackErrorKind?,
        routeCount: Int,
        currentIndex: Int,
        session: PlaybackErrorSession
    ): RouteSelfHealDecision {
        if (routeCount <= 1 || kind == null) {
            return RouteSelfHealDecision(
                PlaybackErrorAction.ABORT, NO_ROUTE_SELF_HEAL, session.attempts, session.isCoolingDown()
            )
        }
        val action = session.decide(kind)
        val target = if (action == PlaybackErrorAction.ABORT) {
            NO_ROUTE_SELF_HEAL
        } else {
            ((currentIndex + 1) % routeCount)
        }
        return RouteSelfHealDecision(action, target, session.attempts, session.isCoolingDown())
    }
}

/**
 * 播放会话级的裁决记账（W2 / REQ-13）。
 *
 * 契约（design 3.2 生命周期约束）：
 * - **与播放会话同生命周期**：会话开始 / 结束由调用方 [reset]，避免跨书、跨章串扰；
 * - **上限 + 冷却**：自愈/降级动作累计达 [maxAttempts] 即**进入冷却**并转 [PlaybackErrorAction.ABORT]，
 *   冷却期内一律 ABORT（既防无界循环，也防网络全断时快速遍历所有线路）；
 * - **不可自愈类不消耗预算**：[PlaybackErrorKind.DECODE]/`UNKNOWN` 直接 ABORT（无冷却，不阻塞后续网络自愈）；
 * - **时钟可注入**：便于 JVM 单测构造冷却场景（默认系统墙钟）。
 *
 * 线程安全：所有状态仅由播放错误回调线程（主线程）读写，无需加锁（与既有 `retryCount` 同口径）。
 */
class PlaybackErrorSession(
    private val maxAttempts: Int = PlaybackErrorPolicy.MAX_ATTEMPTS,
    private val cooldownMs: Long = PlaybackErrorPolicy.COOLDOWN_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** 本会话已消耗的自愈/降级次数。 */
    var attempts: Int = 0
        private set

    private var cooldownUntil: Long = 0L

    /** 是否处于冷却期（冷却期内不产生任何自愈/降级动作）。 */
    fun isCoolingDown(): Boolean = clock() < cooldownUntil

    /**
     * 裁决入口：按错误码给出本会话此刻应执行的动作。
     *
     * @return 三态之一；[PlaybackErrorAction.SELF_HEAL]/[PlaybackErrorAction.DEGRADE] 已被计入预算
     */
    fun decide(errorCode: Int): PlaybackErrorAction =
        decide(PlaybackErrorPolicy.classify(errorCode))

    fun decide(kind: PlaybackErrorKind): PlaybackErrorAction {
        if (isCoolingDown()) return PlaybackErrorAction.ABORT
        // 冷却窗口已结束 ⇒ 复位预算，开启新一轮自愈窗口
        // （否则冷却形同虚设：预算一满就永久 ABORT，用户重试也再不自愈）
        if (cooldownUntil != 0L) {
            attempts = 0
            cooldownUntil = 0L
        }
        val action = PlaybackErrorPolicy.actionOf(kind)
        if (action == PlaybackErrorAction.ABORT) return PlaybackErrorAction.ABORT
        if (attempts >= maxAttempts) {
            armCooldown()
            return PlaybackErrorAction.ABORT
        }
        attempts++
        return action
    }

    /** 主动进入冷却（上限耗尽 / 上层判定不可继续自愈时调用）。 */
    fun armCooldown() {
        cooldownUntil = clock() + cooldownMs
    }

    /** 会话（重）开始：复位计数与冷却态。 */
    fun reset() {
        attempts = 0
        cooldownUntil = 0L
    }
}