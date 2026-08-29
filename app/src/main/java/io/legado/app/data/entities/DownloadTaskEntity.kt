package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 下载任务持久化实体（download-manager-maturity）
 *
 * 让下载任务从"纯内存"升级为"Room 主存"：进程被杀/崩溃后任务不丢、进度可续。
 * 状态字段用字符串存枚举 name（DIRECT/HLS、WAITING/RUNNING/PAUSED/COMPLETED/FAILED），
 * 便于 Room 直接存储与按状态筛选。
 *
 * 断点续传/暂停恢复依赖的续传点 JSON 与 m3u8 分片清单也一并落表（P2 阶段填充）。
 */
@Parcelize
@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 下载地址 */
    val url: String,
    /** 默认文件名（视频标题） */
    val fileName: String = "",
    /** 任务类型枚举 name：DIRECT / HLS */
    val taskType: String = "DIRECT",
    /** 下载请求头（防盗链 Referer/Cookie 等），JSON 串，续传/恢复时复用 */
    val headersJson: String? = null,
    /** 状态枚举 name：WAITING / RUNNING / PAUSED / COMPLETED / FAILED */
    val status: String = "WAITING",
    /** 进度百分比 0-100 */
    val progress: Int = 0,
    /** 总字节数 */
    val totalSize: Long = 0,
    /** 已下载字节数 */
    val downloadedSize: Long = 0,
    /** 下载速度（字节/秒），仅用于管理页展示，非限速 */
    val speed: Long = 0,
    /** 失败错误码（DownloadError 名），成功为 null */
    val errorCode: String? = null,
    /** 完成后的本地文件绝对路径 */
    val localPath: String? = null,
    /** 下载目标目录（FR-11 可配置），为 null 用默认公有 Downloads/Legado */
    val targetDir: String? = null,
    /** 任务创建时间 */
    val startTime: Long = System.currentTimeMillis()
) : Parcelable