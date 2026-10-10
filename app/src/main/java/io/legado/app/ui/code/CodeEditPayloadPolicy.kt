package io.legado.app.ui.code

/**
 * 代码编辑器**载荷通道策略**（epub-md-rich-rendering 阶段 4.8c）。
 *
 * ### 为什么需要
 * 编辑器载荷原先一律走 `Intent.putExtra("text", …)`。模板 CSS 可达 ~320KB
 * （导入 archive 主题的实测体量），大字符串走 Intent 有两个真实隐患：
 * 1. **Binder 事务上限**（1MB/事务，且与 Activity 状态叠加）⇒ 极端体量会
 *    `TransactionTooLargeException`（用户看到的是"点编辑直接崩"）；
 * 2. 调用方与系统各持一份拷贝 ⇒ 内存峰值翻倍。
 * 故超过 [MaxInlineChars] 改走**临时文件通道**（`textFile` 只传路径），
 * 由编辑器在 IO 线程读取 ⇒ 载荷不再经过 Binder，体量只受磁盘限制。
 *
 * ### 为什么是纯对象
 * 阈值与通道选择是"可被测试钉住的产品口径"：阈值一旦被改成 0 或极大，
 * 大 CSS 会悄悄退回 Binder 通道（症状只在极端体量下出现，日常完全看不出来）。
 */
object CodeEditPayloadPolicy {

    /**
     * 内联通道的字符数上限（64K 字符）。
     *
     * 取值依据：远低于 Binder 事务上限（≈1MB），同时覆盖书源/订阅源规则这类
     * 常见小载荷（几 KB）⇒ 它们继续走内联，**行为零变化**；
     * 只有模板 CSS（数百 KB）这类大载荷才切到文件通道。
     */
    const val MaxInlineChars = 64 * 1024

    /** 是否必须走文件通道（`true` ⇒ 只传路径，不传正文）。 */
    fun useFileChannel(textLength: Int): Boolean = textLength > MaxInlineChars

    /** 临时载荷文件名前缀（编辑器读完即删；同目录其余残留由缓存目录自身生命周期清理）。 */
    const val TempDirName = "code_edit_payload"

    /**
     * Intent extra：载荷文件绝对路径（**进出双向同键**）。
     *
     * 入方向：调用方 → 编辑器（初始文本）；出方向：编辑器 → 调用方（编辑结果）。
     * 同键的理由：语义只有一个——"载荷在这个文件里"；键名分叉是本项目反复栽过的坑
     * （两侧各写各的字面量 ⇒ 读不到 ⇒ 静默失败）。
     */
    const val ExtraTextFile = "textFile"

    /**
     * Intent extra：调用方**显式开启**文件通道（默认关）。
     *
     * 为什么是 opt-in 而不是按体量自动判定：`CodeEditActivity` 被书源/订阅源等多处复用，
     * 若对它们也自动改走文件通道，那些调用方（只读 `getStringExtra("text")`）会拿到一个**路径字符串**
     * 并当正文保存 ⇒ 静默写坏数据。故只有明确支持该通道的调用方（当前为模板编辑器）才开启。
     */
    const val ExtraFileChannelOptIn = "payloadFileChannel"
}