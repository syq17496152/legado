package io.legado.app.model.reader.render

import io.legado.app.exception.NoStackTraceException

/**
 * 阅读器错误类型（epub-md-rich-rendering blueprint §八；阶段 2/3 共用）。
 *
 * 存在理由：降级链需要**按错误语义**决定动作——「旧内核」应整链下探且**提示用户**，
 * 「坐标不可换算」只应**记录**（属预期降级，不该弹提示），「磁盘满」应**禁写缓存并提示**
 * 而不是反复重试。若全部用裸 `Exception`，调用方只能靠字符串判断（脆弱且不可审计）。
 *
 * 全部继承 [NoStackTraceException]（项目 landmine：业务异常不采集调用栈，避免高频降级时开销）。
 */
sealed class ReaderError(message: String) : NoStackTraceException(message) {

    /** 模板脚本超时（可恢复：降级为静态帧/内置模板）。 */
    class TemplateScriptTimeout(message: String) : ReaderError(message)

    /** 模板渲染失败（可恢复：回退内置/纯 EPUB）。 */
    class TemplateRenderFailed(message: String) : ReaderError(message)

    /** 后端不可用（旧内核/渲染进程崩溃）：**整链下探**并提示。 */
    class BackendUnavailable(message: String) : ReaderError(message)

    /** 坐标不可换算（仅记录，不提示；AD-14 明确"以章内锚点兜底"）。 */
    class CoordConvertUnavailable(message: String) : ReaderError(message)

    /** 缓存写失败（磁盘满/权限）：禁写并提示，不阻断阅读。 */
    class CacheWriteFailed(message: String) : ReaderError(message)

    /** 本章内容超限（md 单章 >2Mi 等）：**显式报错不截断**。 */
    class ContentTooLarge(message: String) : ReaderError(message)
}