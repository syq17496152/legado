package io.legado.app.model.analyzeRule

import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 把书源 `body`（JSON 文本）解析为键值对，供 multipart 上传构造表单。
 *
 * core-flow-test-coverage：原 `AnalyzeUrl.upload` 内联 `GSON.fromJsonObject<HashMap<…>>(body)
 * .getOrNull()!!` —— 当 body 为空或**非合法 JSON 对象**（用户书源规则驱动）时抛
 * **NullPointerException**，源不可用即崩溃。抽为单源函数并改抛 [NoStackTraceException]
 * （AGENTS.md 约定：业务异常继承该类），使上层 `catch (e: Exception)` / `runCatching`
 * 能优雅降级。
 */
internal fun parseBodyMapOrThrow(body: String?): MutableMap<String, Any> {
    if (body.isNullOrBlank()) {
        throw NoStackTraceException("body 为空，无法构造 multipart 表单")
    }
    return GSON.fromJsonObject<HashMap<String, Any>>(body).getOrNull()?.toMutableMap()
        ?: throw NoStackTraceException("body 不是合法 JSON 对象")
}
