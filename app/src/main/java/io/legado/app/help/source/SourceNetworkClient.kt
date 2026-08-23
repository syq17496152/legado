package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.http.StrResponse
import io.legado.app.model.Debug
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.CancellationException

/**
 * M6 SourceNetworkClient — 统一网络请求组件（source-arch-mutual-borrow spec design.md AD-03）
 *
 * 机制层互补：抽取 WebBook.kt 5 处 + Rss.kt 2 处重复的
 * "SourceLastHostHelper.fillBack + runCatching + getStrResponseAwait + checkJs + getErrStrResponse + checkRedirect"
 * 完整流程，行为等同改造前。
 *
 * 数据流：
 *   1. 调用方创建 AnalyzeUrl 后传入本组件
 *   2. 本组件回填 lastHost（SourceLastHostHelper.fillBack，变化才写 DB）
 *   3. 发起网络请求 getStrResponseAwait（可选 jsStr/sourceRegex，仅 WebBook.getContentAwait 需要）
 *   4. 若 checkJs 非空，执行登录检测 JS（evalJS）
 *   5. 失败时若 checkJs 非空，构造错误响应 getErrStrResponse 重试 checkJs
 *   6. CancellationException 强制重新抛出（协程取消守卫）
 *   7. 检测重定向并记录 Debug 日志（用 source.getKey() 统一 tag）
 *   8. 返回 StrResponse
 *
 * 保留改造前所有行为细节：
 *   - checkJs 非空且重试后 code==500 时抛出原始 throwable
 *   - checkJs 非空但重试过程异常（非 CancellationException）时抛出原始 throwable
 *   - checkJs 为空时直接抛出原始 throwable
 */
object SourceNetworkClient {

    /**
     * 统一网络请求 + 登录检测流程
     *
     * @param analyzeUrl 已完成解析的 AnalyzeUrl
     * @param source 书源或订阅源（用于 lastHost 回填 + Debug 日志 tag）
     * @param checkJs 登录检测 JS（null 或空则跳过登录检测）
     * @param jsStr WebJs（仅 WebBook.getContentAwait 传入 contentRule.webJs，其他调用点 null）
     * @param sourceRegex SourceRegex（仅 WebBook.getContentAwait 传入 contentRule.sourceRegex，其他调用点 null）
     * @return StrResponse
     */
    suspend fun requestWithLoginCheck(
        analyzeUrl: AnalyzeUrl,
        source: BaseSource,
        checkJs: String?,
        jsStr: String? = null,
        sourceRegex: String? = null
    ): StrResponse {
        // 步骤1：回填 lastHost（变化才写 DB，内存缓存减少 DB 读）
        SourceLastHostHelper.fillBack(source, analyzeUrl)

        // 步骤2：发起网络请求 + 登录检测（checkJs 非空时执行）
        val res = kotlin.runCatching {
            analyzeUrl.getStrResponseAwait(
                jsStr = jsStr,
                sourceRegex = sourceRegex
            ).let { response ->
                if (!checkJs.isNullOrBlank()) {
                    // 检测源是否已登录（类型容错：loginCheckJs 可能返回 String/其他类型而非 StrResponse）
                    analyzeLoginResult(analyzeUrl.evalJS(checkJs, response), response)
                } else {
                    response
                }
            }
        }.getOrElse { throwable ->
            // 步骤3：失败时若 checkJs 非空，构造错误响应重试登录检测
            if (!checkJs.isNullOrBlank()) {
                val errResponse = analyzeUrl.getErrStrResponse(throwable)
                try {
                    analyzeLoginResult(analyzeUrl.evalJS(checkJs, errResponse), errResponse).also {
                        if (it.code() == 500) {
                            throw throwable
                        }
                    }
                } catch (ce: CancellationException) {
                    // 守卫：协程取消异常必须重新抛出（不可被 checkJs 重试吞掉）
                    throw ce
                } catch (e: Throwable) {
                    // checkJs 重试过程异常，记录 WARN 日志（统一 Rss.kt 原有日志 + WebBook.kt 新增）
                    // 输出安全：不记录 URL，只记录 sourceKey 前 30 字符
                    AppLog.putDebugWithTag(
                        AppLog.TAG_SOURCE_MECHANISM,
                        "登录检测重试失败 sourceKey=${source.getKey().take(30)}",
                        e,
                        level = AppLog.Level.WARN
                    )
                    throw throwable
                }
            } else {
                throw throwable
            }
        }

        // 步骤4：检测重定向并记录 Debug 日志
        // 用 source.getKey() 统一 tag（BookSource=bookSourceUrl, RssSource=sourceUrl）
        checkRedirect(source, res)

        return res
    }

    /**
     * 登录检测 JS 结果类型容错（修复实测 ClassCastException：String→StrResponse 强转崩溃）
     *
     * loginCheckJs 通常返回 StrResponse（JS 内构造 {code:200,...}），但部分源返回 String 或 null：
     *   - StrResponse → 直接使用（登录状态判断依据）
     *   - String → 以 fallback 的 url 构造 StrResponse（body=返回串，code 默认 200）
     *   - 其他/空 → 回退 fallback（视为未检测出异常，保持原响应继续流程）
     */
    private fun analyzeLoginResult(result: Any?, fallback: StrResponse): StrResponse {
        return when (result) {
            is StrResponse -> result
            is String -> StrResponse(fallback.url, result)
            else -> fallback
        }
    }

    /**
     * 检测重定向（统一 WebBook.checkRedirect + Rss.checkRedirect）
     *
     * 行为等同改造前：
     *   - response.raw.priorResponse 存在且 isRedirect 时记录 3 行 Debug 日志
     *   - tag 用 source.getKey()（BookSource.bookSourceUrl / RssSource.sourceUrl）
     */
    private fun checkRedirect(source: BaseSource, response: StrResponse) {
        response.raw.priorResponse?.let {
            if (it.isRedirect) {
                val tag = source.getKey()
                Debug.log(tag, "≡检测到重定向(${it.code})")
                Debug.log(tag, "┌重定向后地址")
                Debug.log(tag, "└${response.url}")
            }
        }
    }
}
