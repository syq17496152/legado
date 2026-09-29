package io.legado.app.help.web

/**
 * 控制台包**验签公钥**（web-mcp-productization 四期 · tasks 0.3.1 / REQ-4-111）。
 *
 * **Ed25519**（RFC 8032）。签名算法：对 `console-vX.Y.Z.zip` 的**原始字节**签名，App 侧用本公钥验签通过后才安装。
 *
 * # 密钥保管（**安全铁律**）
 * - ✅ 本文件只放**公钥**（可公开）；
 * - 🚫 **私钥绝不入库、绝不进 APK** —— 它只存发布侧 CI secret（GitHub Actions `secrets.CONSOLE_SIGNING_KEY`）。
 *   `scripts/` 侧的签名脚本从 secret 读私钥，仓库里任何位置都不得出现私钥。
 * - 🔴 校验方式：全仓 `Grep` **PEM 私钥头标记**（PEM 的 `PRIVATE` 前缀行）在 `app/` 与 `scripts/` 命中必须为 **0**
 *   （本文件 KDoc 亦**故意不写出该标记的完整字面量**，以免自伤扫描）。
 *
 * # 轮换
 * 更换密钥对 = ①重新生成 Ed25519 对 ②更新本常量 ③更新 CI secret ④**旧版本 App 将拒绝新包**（无法验签）
 * ⇒ 轮换须与 App 版本发布同步规划（`consoleApiLevel` 可作兼容位）。
 *
 * # 本仓库的公钥来源
 * 生成命令（2026-09-29，发布侧首次生成）：
 * ```
 * openssl genpkey -algorithm ED25519 -out console_private.pem
 * openssl pkey -in console_private.pem -pubout -outform DER | tail -c 32 | base64 -w0
 * ```
 * 私钥文件**留在生成者本机**（本轮放在 `.temp/console-keys/`，已 gitignore）并需另行转入 CI secret。
 */
object ConsoleKeys {

    /**
     * Ed25519 公钥（**raw 32 字节**的 Base64；非 SPKI/PEM —— 用 `java.security.PublicKey` 需自行按
     * RFC 8032 构造，或走 BouncyCastle/`Ed25519` 支持。当前 Android minSdk 23 无 JDK15+ 的 Ed25519，
     * 故实现侧需引入轻量 Ed25519 实现或复用项目已有加密库 —— 见 `ConsoleInstaller` 的验签实现注释）。
     */
    const val CONSOLE_PUBLIC_KEY_BASE64 = "5DdaIsqFgfrH0JnIvm1RlUgJsQ9M577Tur+2H0tuN54="

    /** 签名文件在控制台包旁的固定名（`<zip>` + 本后缀）。 */
    const val SIGNATURE_SUFFIX = ".sig"
}