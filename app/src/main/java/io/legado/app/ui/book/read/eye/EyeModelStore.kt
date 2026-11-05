package io.legado.app.ui.book.read.eye

import android.content.Context
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 眼动模型（MediaPipe Face Landmarker，3.58MB）按需下载 —— 1.5 S3 体积路线。
 *
 * 为什么可以移出 APK：它是**公共资源**（Google 官方地址任何人可重下），且只有开启「眼动翻页」的用户才需要，
 * 属于典型的「体积缩减后进入二次下载」；而用户私有、不可重建的数据（音色/规则/书）仍留在备份口径里。
 *
 * 解析顺序：私有目录已下载 > 随包 assets（**升级兼容**：老版本用户不存在"升级后立即失效"）。
 * 安装位置 `filesDir/eye/face_landmarker.task`，写入走 `.part` + sha256 校验 + 原子改名，绝不半装。
 */
object EyeModelStore {

    const val ASSET_PATH = "eye_page/face_landmarker.task"

    /**
     * 下载源（顺序 = 优先级）。官方 storage.googleapis.com 在国内**实测不可达**（设备 curl 60s 超时），
     * 故首选 ModelScope 镜像（实测 200 / 3758596B / sha256 与官方一致），官方作为海外回退。
     */
    val SOURCES: List<String> = listOf(
        "https://www.modelscope.cn/models/seetapsych/seetapsych-models/resolve/master/mediapipe/face_landmarker/face_landmarker.task",
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
    )

    /** 随包/官方同一文件的 sha256（下载后强校验，防镜像返回 HTML/半包） */
    const val SHA256 = "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"

    private const val FILE_NAME = "face_landmarker.task"

    enum class State {
        /** 已按需下载到私有目录 */
        READY_LOCAL,

        /** 仍可从 APK assets 读到（升级用户/内置构建） */
        READY_BUNDLED,

        /** 两者都没有 ⇒ 必须下载 */
        MISSING
    }

    fun state(context: Context): State {
        val local = file(context)
        if (local.isFile && local.length() > 0) return State.READY_LOCAL
        val bundled = runCatching {
            context.assets.open(ASSET_PATH).use { it.read() }
        }.isSuccess
        return if (bundled) State.READY_BUNDLED else State.MISSING
    }

    fun file(context: Context): File = File(context.filesDir, "eye/$FILE_NAME")

    /**
     * MediaPipe 的 `setModelAssetBuffer` 需要 ByteBuffer；用**直接缓冲**（native 侧不接受堆缓冲的拷贝语义，
     * 06-27 眼动批次踩过堆 ByteBuffer 的兼容问题）。
     */
    fun readBuffer(context: Context): ByteBuffer? {
        val bytes = when (state(context)) {
            State.READY_LOCAL -> runCatching { file(context).readBytes() }.getOrNull()
            State.READY_BUNDLED -> runCatching {
                context.assets.open(ASSET_PATH).use { it.readBytes() }
            }.getOrNull()
            State.MISSING -> null
        } ?: return null
        if (bytes.isEmpty()) return null
        return ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
    }

    /** 校验落盘文件（sha256 + 非空），供下载与自检复用 */
    fun verify(file: File): Boolean = runCatching {
        file.isFile && file.length() > 0 && sha256(file) == SHA256
    }.getOrDefault(false)

    /** 下载并安装（多源依次回退）；返回 true 表示私有目录已有通过校验的模型 */
    suspend fun download(context: Context, onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val target = file(context)
            target.parentFile?.mkdirs()
            SOURCES.forEach { url ->
                val part = File(target.parentFile, "$FILE_NAME.part")
                val ok = runCatching { fetch(url, part, onProgress) }.getOrDefault(false)
                if (ok && verify(part)) {
                    if (target.exists()) target.delete()
                    val renamed = part.renameTo(target)
                    if (renamed && verify(target)) {
                        AppLog.put("眼动模型就绪：${target.length()}B（源=${url.substringBefore('/').take(48)}）")
                        return@withContext true
                    }
                    AppLog.put("眼动模型落盘失败（rename=$renamed），尝试下一个源")
                } else if (ok) {
                    // 半包/镜像返回 HTML：宁可换源重下也不把坏文件交给 native
                    AppLog.put("眼动模型校验失败（大小=${part.length()}），已丢弃")
                }
                part.delete()
            }
            false
        }

    /** 单源下载到 [part]（不做校验，由调用方统一验 sha） */
    private fun fetch(url: String, part: File, onProgress: (Int) -> Unit): Boolean {
        part.delete()
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.put("眼动模型下载失败：HTTP ${resp.code}（${url.take(48)}）")
                return false
            }
            val total = resp.body.contentLength()
            var written = 0L
            resp.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            return true
        }
    }

    fun delete(context: Context): Boolean = runCatching { file(context).delete() }.getOrDefault(false)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
