package io.legado.app.api.controller

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.service.kernel.BackupKernel
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * Web端备份控制器，提供一键备份功能，支持下载ZIP备份文件
 *
 * web-mcp-productization 一期 §2.3.3：**业务已全量下沉到 [BackupKernel]**，本类只剩
 * 「把内核产物包成 HTTP 下载响应 + 失败话术映射」：
 * - 内核返回 `File`（不再返回 `Response`）⇒ MCP 等非 HTTP 通道也能复用同一备份实现；
 * - `runBlocking` 在此仅作**同步边界**（路由 handler 非挂起，design §1.4.3 明确允许：
 *   要清零的是 **Kernel 内部**的 `runBlocking`，门禁 G-22 亦只拦 `service/kernel/`）。
 */
object BackupController {

    /**
     * 执行备份并返回 ZIP 文件流。
     *
     * 与原实现的行为等价点：超时 → 500 + `备份超时`；失败 → 500 + `备份失败: …`（REQ-08 非空兜底）；
     * 成功 → `application/zip` + `Content-Disposition`。
     * 差异点（详见内核文档）：超时由「放弃等待、备份继续在后台跑」改为**取消该次备份**。
     */
    fun backup(): NanoHTTPD.Response {
        val result = runCatching { runBlocking { BackupKernel.backup() } }
        val file = result.getOrNull()
        if (file == null) {
            val error = result.exceptionOrNull() ?: RuntimeException("备份文件生成失败")
            val message = if (error is CancellationException) {
                "备份超时"
            } else {
                // REQ-08：底层异常 message 可能为 null/空白 ⇒ 非空兜底，避免前端显示 "备份失败: null"
                "备份失败: ${error.message?.takeIf { it.isNotBlank() } ?: "未知错误"}"
            }
            return jsonResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                ReturnData().setErrorMsg(message)
            )
        }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/zip",
            // 流关闭（NanoHTTPD 发送完毕）后删除临时包，避免残包堆积
            TempFileInputStream(file),
            file.length()
        ).apply {
            addHeader("Content-Disposition", "attachment; filename=\"backup.zip\"")
        }
    }

    /**
     * 获取备份内容预览
     */
    fun getBackupPreview(): ReturnData {
        val returnData = ReturnData()
        return try {
            returnData.setData(runBlocking { BackupKernel.preview() })
        } catch (e: Exception) {
            returnData.setErrorMsg("获取备份预览失败: ${e.message}")
        }
    }

    private fun jsonResponse(
        status: NanoHTTPD.Response.Status,
        returnData: ReturnData
    ): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        status,
        "application/json",
        GSON.toJson(returnData)
    )

    /** 读取内核产出的临时 zip；`close()` 时顺带删除该文件（响应跑完即回收）。 */
    private class TempFileInputStream(
        private val file: File
    ) : FilterInputStream(FileInputStream(file)) {

        override fun close() {
            try {
                super.close()
            } finally {
                file.delete()
            }
        }
    }
}
