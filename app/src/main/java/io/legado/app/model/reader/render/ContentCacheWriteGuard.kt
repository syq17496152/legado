package io.legado.app.model.reader.render

/**
 * 内容缓存写入的**稳定性边界**判据（AD-28 ⑥ / NFR-07 ①「磁盘满：禁写并提示」）。
 *
 * ### 存在理由
 * 内容缓存写入链（`BookHelp.saveText` / `saveContent`）此前把写失败**只记日志** ⇒ 磁盘将满时
 * ①反复重试写 ②写到一半失败留下**半写缓存** ③**用户毫无感知**（书看起来"读过了"，其实没缓存下来）。
 * 而 [ReaderError.CacheWriteFailed] 声明了「禁写并提示，不阻断阅读」却**全仓无处抛出**（审计实证）。
 *
 * 本对象把「该不该写 / 失败算不算磁盘满或拒绝写 / 提示要不要去重」收成**纯函数** ⇒ JVM 可测、
 * 调用方（写入链）只需一行判据即可获得完整边界语义。
 *
 * 纯策略（无 Android 依赖）⇒ 可直接进 commit 门禁的配对单测。
 */
object ContentCacheWriteGuard {

    /** 写前要求保留的**最小剩余空间**：低于此值视为「磁盘将满」，直接禁写并提示。 */
    const val MIN_FREE_BYTES = 8L * 1024 * 1024

    /** 单字符预估字节上限（CJK 3 字节 / BMP 表情 4 字节 ⇒ 取 3 为保守上界，仅用于空间估算）。 */
    private const val BYTES_PER_CHAR_UPPER_BOUND = 3

    /** 同因提示的**去重窗口**：批量缓存时避免同一提示反复刷屏。 */
    const val NOTIFY_DEDUPE_MS = 60_000L

    /**
     * 写前剩余空间判据（payload-aware）。
     *
     * @param usableBytes 目标卷剩余字节（`File.usableSpace`）
     * @param contentChars 本次要写入的**字符数**（内部按上界换算成字节）
     *
     * ⚠️ `usableBytes <= 0` 视为**未知**（`usableSpace` 取不到时返回 0）⇒ 判为**有空间**：
     * 不能因为"读不到容量"就把正常环境判成磁盘满，从而**阻断全部缓存**。
     */
    fun hasRoomForCache(usableBytes: Long, contentChars: Long): Boolean {
        if (usableBytes <= 0L) return true
        val payloadUpperBound = contentChars.coerceAtLeast(0L) * BYTES_PER_CHAR_UPPER_BOUND
        return usableBytes - payloadUpperBound > MIN_FREE_BYTES
    }

    /**
     * 写失败分类：**磁盘满 / 空间不足 / 拒绝写** ⇒ [ReaderError.CacheWriteFailed]（调用方据此提示用户）；
     * 其它异常返回 `null`（按原样处理，不改变既有语义）。
     *
     * 判据基于 `errno` 语义化文案（`ENOSPC`/`No space left on device`/`EACCES`/`Read-only file system`）——
     * Android 的 `IOException` 不携带结构化 errno，只能按文案识别；无法识别时**不误报**（返回 null）。
     */
    fun classifyWriteFailure(error: Throwable?): ReaderError.CacheWriteFailed? {
        val text = (error?.message ?: "").lowercase()
        if (text.isEmpty()) return null
        val blocked = text.contains("enospc") ||
            text.contains("no space left") ||
            text.contains("disk full") ||
            text.contains("quota exceeded") ||
            text.contains("eacces") ||
            text.contains("permission denied") ||
            text.contains("read-only file system")
        if (!blocked) return null
        return ReaderError.CacheWriteFailed(
            "内容写入被拒（磁盘满或无写权限）：${error?.message ?: ""}"
        )
    }

    /** 提示去重判据（`lastNotifiedAt <= 0` 表示从未提示 ⇒ 首次必提示）。 */
    fun shouldNotify(lastNotifiedAt: Long, now: Long): Boolean =
        lastNotifiedAt <= 0L || now - lastNotifiedAt >= NOTIFY_DEDUPE_MS
}