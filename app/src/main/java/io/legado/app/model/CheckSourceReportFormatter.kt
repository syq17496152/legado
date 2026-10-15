package io.legado.app.model

/**
 * 巡检结果导出（CSV）—— **纯函数，JVM 可测**（缺口4：巡检结果此前只能看横幅/单行消息，无法留存与上报）。
 *
 * 数据源＝结构化任务态 [CheckSourceTaskState]（与页内横幅、单源行文案**同一份状态** ⇒ 导出内容与界面一致，
 * 不另建报告模型）。
 *
 * 口径：
 *  · 首行 `#` 汇总（状态/总数/通过/失败/跳过/平均分/生成时间）——人可读；机器解析按 `#` 跳过即可；
 *  · 表头行恒输出（**空任务也导出表头**：用户看到的是"本次没有记录"，而不是空文件的假象）；
 *  · 字段含 `,` / `"` / 换行一律按 CSV 规范转义（整字段双引号包裹，内部引号翻倍）；
 *  · 用户导出的报告保留**源名与地址原文**（打码只作用于 HTTP/MCP 出参边界，见 DiagReadKernel.mask）。
 *
 * 读者：书源管理页「导出校验报告」（走系统分享）。REST/MCP 侧的同一份结果读出口径见 pending 缺口4 后半
 * （结构化出参，需打码）。
 */
internal fun formatCheckSourceReportCsv(
    state: CheckSourceTaskState,
    generatedAtMillis: Long = System.currentTimeMillis(),
): String {
    val sb = StringBuilder(256 + state.items.size * 64)
    sb.append("# 巡检报告 生成时间=").append(generatedAtMillis)
        .append(" 状态=").append(taskStatusText(state.status))
        .append(" 总数=").append(state.totalCount)
        .append(" 通过=").append(state.passedCount)
        .append(" 失败=").append(state.failedCount)
        .append(" 跳过=").append(state.blockedCount)
    state.averageScore?.let { sb.append(" 平均分=").append(it) }
    sb.append('\n')
    sb.append("序号,书源名称,书源地址,状态,阶段,耗时(ms),评分,消息\n")
    state.items.forEachIndexed { index, item ->
        sb.append(index + 1).append(',')
            .append(csvField(item.sourceName)).append(',')
            .append(csvField(item.origin)).append(',')
            .append(itemStatusText(item.status)).append(',')
            .append(item.stage.displayName).append(',')
            .append(item.durationMillis).append(',')
            .append(if (item.score > 0) item.score.toString() else "").append(',')
            .append(csvField(item.message))
            .append('\n')
    }
    return sb.toString()
}

/** CSV 字段转义：含分隔符/引号/换行时整字段加双引号，内部 `"` 翻倍。 */
private fun csvField(value: String): String {
    val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuote) return value
    return '"' + value.replace("\"", "\"\"") + '"'
}

internal fun taskStatusText(status: CheckSourceTaskStatus): String = when (status) {
    CheckSourceTaskStatus.IDLE -> "未开始"
    CheckSourceTaskStatus.RUNNING -> "进行中"
    CheckSourceTaskStatus.COMPLETED -> "已完成"
    CheckSourceTaskStatus.CANCELLED -> "已取消"
}

internal fun itemStatusText(status: CheckSourceItemStatus): String = when (status) {
    CheckSourceItemStatus.WAITING -> "等待"
    CheckSourceItemStatus.RUNNING -> "进行中"
    CheckSourceItemStatus.PASSED -> "通过"
    CheckSourceItemStatus.FAILED -> "失败"
    CheckSourceItemStatus.BLOCKED -> "跳过"
    CheckSourceItemStatus.CANCELLED -> "已取消"
}
