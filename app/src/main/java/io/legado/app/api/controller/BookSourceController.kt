package io.legado.app.api.controller


import io.legado.app.api.ReturnData
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookSource
import io.legado.app.service.kernel.BookSourceKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking

/**
 * 书源域 Web 门面（一期 · 2.2.4）：解析 → 调 [BookSourceKernel] → 装信封。
 *
 * 保留非 `suspend` 旧签名（`val sources` 属性形态亦保留），理由同 [BookController] 类注释。
 */
object BookSourceController {

    val sources: ReturnData
        get() {
            val bookSources = runBlocking { BookSourceKernel.sources() }
            return if (bookSources.isEmpty()) {
                ReturnData().setErrorMsg("设备源列表为空")
            } else {
                ReturnData().setData(bookSources)
            }
        }

    fun saveSource(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据不能为空")
        val bookSource = GSON.fromJsonObject<BookSource>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("转换源失败")
        // 校验链（2.2.2）与 App 导入页同口径：结构残缺源拒收并给出白话原因
        BookSourceKernel.skipReason(bookSource)?.let {
            return ReturnData().setErrorMsg(it)
        }
        runBlocking { BookSourceKernel.saveSource(bookSource) }
        return ReturnData().setData("")
    }

    fun saveSources(postData: String?): ReturnData {
        postData ?: return ReturnData().setErrorMsg("数据为空")
        val validated = try {
            runBlocking { BookSourceKernel.parseAndValidate(postData) }
        } catch (e: Exception) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        if (validated.accepted.isEmpty() && validated.skipped.isEmpty()) {
            return ReturnData().setErrorMsg("转换源失败")
        }
        if (validated.skipped.isNotEmpty()) {
            AppLog.put(
                "Web 批量推送书源：结构残缺跳过 ${validated.skipped.size} 条（首条原因：${validated.skipped.first().reason}）",
                null,
                toast = false
            )
        }
        // 老页口径（已核实 ToolBar.vue:112-129）：`data` 必须是"成功数组"，
        // 失败数由前端以 `总数 - data.length` 计算 ⇒ 不得改成对象，否则提示整块失效
        return ReturnData().setData(runBlocking { BookSourceKernel.saveSources(validated.accepted) })
    }

    fun getSource(parameters: Map<String, List<String>>): ReturnData {
        val url = parameters["url"]?.firstOrNull()
        if (url.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("参数url不能为空，请指定源地址")
        }
        val bookSource = runBlocking { BookSourceKernel.source(url) }
            ?: return ReturnData().setErrorMsg("未找到源，请检查书源地址")
        return ReturnData().setData(bookSource)
    }

    fun deleteSources(postData: String?): ReturnData {
        kotlin.runCatching {
            val sources = GSON.fromJsonArray<BookSource>(postData).getOrThrow()
            runBlocking { BookSourceKernel.deleteSources(sources) }
        }.onFailure {
            return ReturnData().setErrorMsg(it.localizedMessage ?: "数据格式错误")
        }
        return ReturnData().setData("已执行"/*okSources*/)
    }
}
