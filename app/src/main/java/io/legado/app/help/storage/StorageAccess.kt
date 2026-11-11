package io.legado.app.help.storage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileDoc
import splitties.init.appCtx
import java.io.File

/**
 * 全盘文件访问（MANAGE_EXTERNAL_STORAGE）状态。
 *
 * - [GRANTED] 已授权，可直接以 file:// 路径浏览设备存储
 * - [DENIED]  未授权（或当前系统版本无该能力）
 */
enum class FullAccessState { GRANTED, DENIED }

/**
 * 当前可浏览根的模式（互斥优先级，见 design AD-04）。
 */
enum class BrowseMode { FULL_ACCESS, SAF_DIRS, NONE }

/**
 * 一个已授权的 SAF 目录。
 *
 * @param available 授权是否仍然有效（系统回收/目录删除后会变 false，需重新授权）
 * @param isDefaultSaveDir 是否为默认保存目录（对应旧键 `defaultBookTreeUri`）
 */
data class SafeDirEntry(
    val uri: String,
    val name: String,
    val available: Boolean,
    val isDefaultSaveDir: Boolean
)

/**
 * 解析后的书籍保存位置。
 *
 * @param value content:// 形式为 SAF tree uri，其余为文件绝对路径
 * @param isContent 是否为 SAF 目录
 * @param degraded 是否发生了回退（首个候选不可用，退到了后面的候选）
 * @param isPrivate 是否退化到了应用私有目录（用户不可见，调用方应提示或拒绝）
 */
data class SaveDir(
    val value: String,
    val isContent: Boolean,
    val degraded: Boolean,
    val isPrivate: Boolean
)

/** 允许添加的 SAF 目录上限：超过后 UI 数量与逐目录校验开销都会失控 */
internal const val MAX_SAFE_DIRS = 16

/** 全盘权限下默认的书籍保存子目录（相对设备存储根） */
internal const val DEFAULT_PUBLIC_SUB_DIR = "Documents/Books"

private const val ANDROID_DATA = "/Android/data"
private const val ANDROID_OBB = "/Android/obb"

/**
 * 浏览模式判定（纯函数，JVM 可测）。
 *
 * 全盘权限与 SAF 目录互斥：有全盘权限时只返回设备根，避免同一目录出现两个入口。
 */
internal fun resolveBrowseMode(fullAccess: FullAccessState, dirCount: Int): BrowseMode {
    return when {
        fullAccess == FullAccessState.GRANTED -> BrowseMode.FULL_ACCESS
        dirCount > 0 -> BrowseMode.SAF_DIRS
        else -> BrowseMode.NONE
    }
}

/**
 * 合并一个新目录（去重保序 + 上限截断）。
 *
 * 达到上限时原样返回，调用方需先通过 [maxDirsReached] 给出提示。
 */
internal fun mergeSafeDirs(newUri: String, existing: List<String>): List<String> {
    if (newUri.isBlank()) return existing
    if (existing.size >= MAX_SAFE_DIRS) return existing
    if (existing.contains(newUri)) return existing
    return (existing + newUri).take(MAX_SAFE_DIRS)
}

/** 移除一个目录（不存在时原样返回） */
internal fun removeSafeDirFrom(uri: String, existing: List<String>): List<String> {
    return existing.filterNot { it == uri }
}

/**
 * 旧单值 `defaultBookTreeUri` 到多目录列表的迁移（纯函数）。
 *
 * 迁移只允许发生一次：[migrated] 为 true 后无论列表是否为空都不再迁移，
 * 否则用户主动移除全部目录后会被旧值重新填回（见 red-team 第 2 轮）。
 */
internal fun migrateLegacyDir(
    legacy: String?,
    current: List<String>,
    migrated: Boolean
): List<String> {
    if (migrated) return current
    if (legacy.isNullOrBlank()) return current
    if (current.contains(legacy)) return current
    return (listOf(legacy) + current).take(MAX_SAFE_DIRS)
}

/**
 * 保存位置候选顺序（纯函数）：显式保存目录 → 已授权目录 → 全盘权限下的公共目录 → 私有目录。
 *
 * 只给出顺序，可写性由调用方逐项探测（IO 不可放进纯函数）。
 */
internal fun saveDirCandidates(
    explicit: String?,
    safeDirs: List<String>,
    deviceRoot: String?,
    privateRoot: String
): List<String> {
    val list = ArrayList<String>(4)
    explicit?.takeIf { it.isNotBlank() }?.let(list::add)
    list.addAll(safeDirs.filter { it.isNotBlank() })
    if (!deviceRoot.isNullOrBlank()) {
        list.add("$deviceRoot/$DEFAULT_PUBLIC_SUB_DIR")
    }
    list.add(privateRoot)
    return list.distinct()
}

/** 系统级硬禁区：Android 11+ 对这两个目录一律拒绝，任何权限都无法突破 */
internal fun isRestrictedPath(path: String): Boolean {
    val normalized = path.replace('\\', '/').trimEnd('/')
    return normalized.endsWith(ANDROID_DATA) ||
        normalized.contains("$ANDROID_DATA/") ||
        normalized.endsWith(ANDROID_OBB) ||
        normalized.contains("$ANDROID_OBB/")
}

/** 目录数量是否已达上限 */
internal fun maxDirsReached(count: Int): Boolean = count >= MAX_SAFE_DIRS

/** 字符串是否为 SAF content uri（纯字符串判断，避免 JVM 单测依赖 android.net.Uri） */
internal fun isContentUri(value: String): Boolean = value.startsWith("content://")

/**
 * 存储访问的统一事实来源（Android 封装层，业务判断全部委托上面的纯函数）。
 *
 * 设置页、本地书库、本地导入、保存链路都只通过本对象取状态，禁止各自判断版本号。
 */
object StorageAccess {

    /** 目录数量上限（供 UI 提示使用，与 [MAX_SAFE_DIRS] 同源） */
    const val MAX_DIRS: Int = MAX_SAFE_DIRS

    /** 是否还能继续添加目录 */
    fun canAddSafeDir(): Boolean = !maxDirsReached(safeDirs().size)

    /** 设备存储根（/storage/emulated/0） */
    fun storageRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

    /** 当前全盘访问状态 */
    fun fullAccessState(): FullAccessState {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                if (Environment.isExternalStorageManager()) {
                    FullAccessState.GRANTED
                } else {
                    FullAccessState.DENIED
                }
            // Android 10 在 targetSdk>=30 下已无 File 公有写能力，与 DownloadService 口径一致
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> FullAccessState.DENIED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                if (
                    ContextCompat.checkSelfPermission(
                        appCtx,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    FullAccessState.GRANTED
                } else {
                    FullAccessState.DENIED
                }
            else -> FullAccessState.DENIED
        }
    }

    /** 当前系统是否具备「所有文件访问权限」这一设置项（API 30+） */
    fun supportsAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * 已授权目录（读取时执行一次性迁移并落盘）。
     *
     * 迁移标记无论列表是否为空都会置真，避免用户清空后被旧值回填。
     */
    fun safeDirs(): List<String> {
        val migrated = AppConfig.safeDirsMigrated
        val current = AppConfig.safeDirs
        val next = migrateLegacyDir(AppConfig.defaultBookTreeUri, current, migrated)
        if (next != current) {
            AppConfig.safeDirs = next
        }
        if (!migrated) {
            AppConfig.safeDirsMigrated = true
        }
        return next
    }

    /** 添加目录，返回添加后的列表（达上限或重复时原样返回） */
    fun addSafeDir(uri: String): List<String> {
        val current = safeDirs()
        val next = mergeSafeDirs(uri, current)
        if (next != current) {
            AppConfig.safeDirs = next
        }
        return next
    }

    /** 移除目录，返回移除后的列表 */
    fun removeSafeDir(uri: String): List<String> {
        val next = removeSafeDirFrom(uri, safeDirs())
        AppConfig.safeDirs = next
        if (AppConfig.defaultBookTreeUri == uri) {
            AppConfig.defaultBookTreeUri = null
        }
        return next
    }

    /** 把某个目录设为默认保存目录（影响 WebDAV 下载/远程导入的落盘位置） */
    fun setDefaultSaveDir(uri: String) {
        AppConfig.defaultBookTreeUri = uri
    }

    /** 目录授权是否仍然有效（持久授权存在 + 可读） */
    fun isDirAvailable(uri: String): Boolean {
        val parsed = kotlin.runCatching { uri.toUri() }.getOrNull() ?: return false
        return kotlin.runCatching {
            if (isContentUri(uri)) {
                val granted = appCtx.contentResolver.persistedUriPermissions.any {
                    it.uri == parsed && it.isReadPermission
                }
                if (!granted) return@runCatching false
                DocumentFile.fromTreeUri(appCtx, parsed)?.canRead() == true
            } else {
                val path = parsed.path ?: return@runCatching false
                val file = File(path)
                file.isDirectory && file.canRead()
            }
        }.getOrDefault(false)
    }

    /** 已授权目录条目（带名称与有效性），供设置页与书库列表展示 */
    fun safeDirEntries(): List<SafeDirEntry> {
        val dirs = safeDirs()
        val defaultDir = AppConfig.defaultBookTreeUri
        return dirs.map { uri ->
            val parsed = kotlin.runCatching { uri.toUri() }.getOrNull()
            val name = kotlin.runCatching {
                if (isContentUri(uri)) {
                    parsed?.let { DocumentFile.fromTreeUri(appCtx, it)?.name }
                } else {
                    File(parsed?.path ?: "").name
                }
            }.getOrNull().orEmpty().ifBlank { uri.substringAfterLast('/') }
            SafeDirEntry(
                uri = uri,
                name = name,
                available = isDirAvailable(uri),
                isDefaultSaveDir = uri == defaultDir
            )
        }
    }

    /** 当前可浏览根集合（全盘权限优先，否则为已授权目录） */
    fun browseRoots(): List<FileDoc> {
        val dirs = safeDirs()
        return when (resolveBrowseMode(fullAccessState(), dirs.size)) {
            BrowseMode.FULL_ACCESS -> listOf(FileDoc.fromDir(storageRootPath()))
            BrowseMode.SAF_DIRS -> dirs.mapNotNull { uri ->
                kotlin.runCatching { FileDoc.fromUri(uri.toUri(), true) }.getOrNull()
            }
            BrowseMode.NONE -> emptyList()
        }
    }

    /**
     * 解析书籍保存位置：按 [saveDirCandidates] 顺序找第一个可写项。
     *
     * 全部不可写时返回私有目录（保证永远有落点），并标记 degraded。
     */
    fun resolveSaveDir(): SaveDir {
        val privateRoot = File(appCtx.filesDir, "books").absolutePath
        val deviceRoot = if (fullAccessState() == FullAccessState.GRANTED) {
            storageRootPath()
        } else {
            null
        }
        val candidates = saveDirCandidates(
            explicit = AppConfig.defaultBookTreeUri,
            safeDirs = safeDirs(),
            deviceRoot = deviceRoot,
            privateRoot = privateRoot
        )
        val chosen = candidates.firstOrNull { isWritable(it) } ?: privateRoot
        return SaveDir(
            value = chosen,
            isContent = isContentUri(chosen),
            degraded = chosen != candidates.firstOrNull(),
            isPrivate = chosen == privateRoot
        )
    }

    /** 探测某个候选位置是否可写（SAF 用 canWrite，File 用存在性 + mkdirs 兜底） */
    private fun isWritable(value: String): Boolean {
        val parsed = kotlin.runCatching { value.toUri() }.getOrNull() ?: return false
        return kotlin.runCatching {
            if (isContentUri(value)) {
                DocumentFile.fromTreeUri(appCtx, parsed)?.canWrite() == true
            } else {
                val path = parsed.path ?: return@runCatching false
                val dir = File(path)
                (dir.isDirectory && dir.canWrite()) || dir.mkdirs()
            }
        }.onFailure {
            AppLog.put("存储位置可写性探测失败: ${it.localizedMessage}", it)
        }.getOrDefault(false)
    }

    /** 系统设置页是否可跳转（部分 ROM 移除了该页面） */
    fun canOpenAllFilesAccessSetting(): Boolean {
        if (!supportsAllFilesAccess()) return false
        return kotlin.runCatching {
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${appCtx.packageName}")
            )
            intent.resolveActivity(appCtx.packageManager) != null
        }.getOrDefault(false)
    }
}
