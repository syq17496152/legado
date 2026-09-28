package io.legado.app.web

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Web 服务三级令牌管理（web-mcp-productization 一期 · 1.1 · REQ-1-101 ~ REQ-1-104）。
 *
 * 设计要点：
 * 1. 明文令牌为 **32 位随机串**，**仅在 [generate] 返回时出现一次**，本类不保存明文；
 * 2. 存储层只落 **SHA-256 十六进制摘要** —— 即使 Preferences 文件泄露（root / 备份），
 *    也无法还原出可用令牌；
 * 3. 校验使用 **常数时间比较**（固定长度字节逐位异或累加），避免"前缀匹配长度"造成的时序侧信道。
 *
 * 简化说明: [PreferKey.webTokenGeneratedAt] 为**单个全局键**（按 tasks 0.4 冻结的 6 键清单），
 * 因此 [TokenStatus.generatedAt] 对已生成级别返回的是「**最近一次令牌生成时间**」而非各级独立时间。
 * 已知上限: 三级令牌各自精确生成时间无法区分。升级路径: 若 UI 需要分级精确时间，追加
 * `webTokenGeneratedAtReadonly/_Manage/_Admin` 三键（需同步二期 MCP 分级表）。
 *
 * 测试接缝说明（[store]）：存储读写抽为 [TokenStore]，默认实现走 Preferences；
 * 单测注入内存实现即可在**纯 JVM**下覆盖 生成 / 校验 / 级别 / 撤销 / 常数时间，无需 Robolectric。
 */
object TokenManager {

    /** 令牌级别（权限从低到高）。[NONE] = 未匹配 / 无效 / 未携带。 */
    enum class Level { NONE, READONLY, MANAGE, ADMIN }

    /** 令牌状态（供 UI 展示，**不含明文**）。 */
    data class TokenStatus(
        val level: Level,
        /** null = 未生成 */
        val generatedAt: Long?
    )

    /**
     * 令牌存储层（唯一可替换点，仅供单测注入）。
     *
     * 生产实现 = [PrefsTokenStore]（Preferences）；单测用内存实现。
     */
    internal interface TokenStore {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun getLong(key: String, defValue: Long): Long
        fun putLong(key: String, value: Long)
        fun getBoolean(key: String, defValue: Boolean): Boolean
        fun remove(key: String)
    }

    /** 生产实现：读写默认 Preferences（键名来自 [PreferKey]，与全仓口径一致）。 */
    private object PrefsTokenStore : TokenStore {
        override fun getString(key: String): String? = appCtx.getPrefString(key, null)
        override fun putString(key: String, value: String) = appCtx.putPrefString(key, value)
        override fun getLong(key: String, defValue: Long): Long = appCtx.getPrefLong(key, defValue)
        override fun putLong(key: String, value: Long) = appCtx.putPrefLong(key, value)
        override fun getBoolean(key: String, defValue: Boolean): Boolean =
            appCtx.getPrefBoolean(key, defValue)

        override fun remove(key: String) = appCtx.removePref(key)
    }

    /** 当前存储实现（单测可替换为内存实现）。 */
    internal var store: TokenStore = PrefsTokenStore

    /** 明文长度（位）。 */
    private const val TOKEN_LENGTH = 32

    /**
     * 明文可选字符集：去掉易混淆字符（`0/O`、`1/I/l`），便于用户人工抄录。
     * 58 个字符 ≈ 5.86 bit/字符 ⇒ 32 位明文 ≈ 187 bit 熵，远超暴力破解可行范围。
     */
    internal const val CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /** 摘要为 SHA-256 ⇒ 64 个十六进制字符。 */
    private const val DIGEST_HEX_LENGTH = 64

    /** 可生成的级别（不含 NONE），顺序即权限升序。 */
    private val GENERATABLE_LEVELS = listOf(Level.READONLY, Level.MANAGE, Level.ADMIN)

    private val secureRandom by lazy { SecureRandom() }

    /**
     * 生成指定级别的令牌。
     *
     * @return **32 位明文令牌** —— 调用方须"只展示一次"，本类不会保存明文。
     * @throws IllegalArgumentException 当 [level] 为 [Level.NONE] 时。
     */
    fun generate(level: Level): String {
        require(level != Level.NONE) { "NONE 不是可生成的令牌级别" }
        val token = buildString(TOKEN_LENGTH) {
            repeat(TOKEN_LENGTH) {
                append(CHARSET[secureRandom.nextInt(CHARSET.length)])
            }
        }
        store.putString(keyOf(level), sha256Hex(token))
        store.putLong(PreferKey.webTokenGeneratedAt, System.currentTimeMillis())
        return token
    }

    /**
     * 校验令牌并返回其级别；未匹配 / 未生成 / 入参为空一律返回 [Level.NONE]。
     *
     * 实现注意：**不短路返回** —— 对每一级都做完 [constantTimeEquals] 才给出结论，
     * 避免"命中即返回"暴露"哪个级别匹配"的时序差异。
     */
    fun verify(token: String?): Level {
        if (token.isNullOrBlank()) return Level.NONE
        val digest = sha256(token)
        var matched = Level.NONE
        for (level in GENERATABLE_LEVELS) {
            val stored = storedDigest(level) ?: continue
            if (constantTimeEquals(stored, digest)) {
                matched = level
            }
        }
        return matched
    }

    /** 撤销指定级别的令牌（删除其摘要）。[Level.NONE] 为空操作。 */
    fun revoke(level: Level) {
        if (level == Level.NONE) return
        store.remove(keyOf(level))
    }

    /** 撤销全部令牌（四期 S7「一键断电」用）。 */
    fun revokeAll() {
        GENERATABLE_LEVELS.forEach { revoke(it) }
    }

    /** 查询三级令牌的生成状态（UI 用；不含明文）。 */
    fun listStatus(): List<TokenStatus> {
        val generatedAt = store.getLong(PreferKey.webTokenGeneratedAt, 0L).takeIf { it > 0L }
        return GENERATABLE_LEVELS.map { level ->
            TokenStatus(
                level = level,
                generatedAt = if (hasToken(level)) generatedAt else null
            )
        }
    }

    /** 指定级别是否已生成令牌。 */
    fun hasToken(level: Level): Boolean = storedDigest(level) != null

    /** 过渡开关：`false`（默认）= 写操作强制令牌 / 读放行；`true` = 全端点强制（REQ-1-108）。 */
    val strict: Boolean
        get() = store.getBoolean(PreferKey.webAuthStrict, false)

    // ---------------------------------------------------------------- 内部

    private fun keyOf(level: Level): String = when (level) {
        Level.READONLY -> PreferKey.webTokenReadonly
        Level.MANAGE -> PreferKey.webTokenManage
        Level.ADMIN -> PreferKey.webTokenAdmin
        Level.NONE -> error("NONE 无对应 Preferences 键")
    }

    /** 读取并解码某级别已存的摘要；未生成或格式非法（非 64 位十六进制）返回 null。 */
    private fun storedDigest(level: Level): ByteArray? {
        val hex = store.getString(keyOf(level))
        if (hex.isNullOrBlank() || hex.length != DIGEST_HEX_LENGTH) return null
        return runCatching { hexToBytes(hex) }.getOrNull()
    }

    internal fun sha256(s: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    internal fun sha256Hex(s: String): String = bytesToHex(sha256(s))

    /**
     * 常数时间比较：长度不同直接 false（长度本身不是秘密），
     * 长度相同则逐位异或累加后一次性判零 —— **不使用** `String.equals` / `contentEquals`（会短路）。
     */
    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    private const val HEX_CHARS = "0123456789abcdef"
}
