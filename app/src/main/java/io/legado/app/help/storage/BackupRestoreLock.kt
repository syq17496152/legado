package io.legado.app.help.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 备份/恢复跨流程共享互斥锁（R9）。
 *
 * 背景：Backup 与 Restore 原先各自持有一把独立 Mutex，却共享同一工作目录
 * [Backup.backupPath]；定时/手动备份与三条恢复入口（本地文件 / 云存储 / WebDAV）
 * 可并发进入，互相清空工作目录 → 恢复结果不完整甚至数据损坏。
 *
 * 约束（实施铁律）：
 * 1. 唯一数据源：所有「清空 / 写入 `Backup.backupPath`」的入口必须经 [withStorageLock]。
 * 2. **不可重入**：临界区内禁止再次申请本锁（含调用 `Restore.restoreLocked` /
 *    `Restore.restoreAll` / `Backup.backupLocked` / [withStorageLock]），否则自死锁。
 * 3. 本锁为**进程内**锁；本项目为单进程（`android:process` 零命中），故覆盖全部备份/恢复路径。
 */
object BackupRestoreLock {

    private val mutex = Mutex()

    /**
     * 在共享临界区内执行 [block]。
     * 注意：`Mutex` 不可重入，临界区内不得再次申请本锁。
     */
    suspend fun <T> withStorageLock(block: suspend () -> T): T = mutex.withLock { block() }
}