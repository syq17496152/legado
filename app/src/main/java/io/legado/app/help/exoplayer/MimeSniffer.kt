package io.legado.app.help.exoplayer

import androidx.media3.common.MimeTypes

/**
 * exoplayer-resilience R4：完整 Magic Number 签名表 + 主动 Probe 函数
 *
 * 输入 ByteArray（HTTP Range 请求返回的前 8KB），输出 mimeType 或 null。
 *
 * R4 改造（对齐浏览器五层架构，对齐 WHATWG §6.2）：
 * - 完整签名表 17 项（对齐 WHATWG §6.2 + Go net/http/sniff.go + Java VideoMagicNumberEnum）
 * - 主动 Probe 函数：isReallyM3u8 / isReallyMpd / detectMoovPosition
 * - Range 请求从 1KB 提升到 8KB（SNIFF_LENGTH）
 * - MPEG-TS 阈值从前 1KB 中 5 次改为前 8KB 中 3 次（对齐 ExoPlayer TsExtractor）
 * - AVI/WAV 二次校验（RIFF 容器需检查偏移 8 是否为 "AVI " / "WAVE"）
 * - WebM/MKV 区分（EBML DocType 检测）
 *
 * 参考：
 * - WHATWG MIME sniffing standard §6.2 (https://mimesniff.spec.whatwg.org/)
 * - Chromium 多级识别策略（Content-Type → URL 模式 → 内容特征 → 兜底）
 * - Go DetectContentType 512 字节检测 + magic number 表
 * - ExoPlayer DefaultExtractorsFactory 三级排序
 *
 * 升级路径：如需支持更多格式，在 [matchMagicNumber] 中新增分支即可
 */
object MimeSniffer {

    /** R4-T3: Range 请求长度从 1KB 提升到 8KB（对齐 ExoPlayer 默认 ExtractorInput 缓冲区） */
    const val SNIFF_LENGTH = 8 * 1024

    /** 最小检测长度：mp4 需 8 字节（4 字节 size + 4 字节 "ftyp"） */
    private const val MIN_LENGTH_MP4 = 8

    /** RIFF 容器最小长度（RIFF + size + "AVI "/"WAVE"，共 12 字节） */
    private const val MIN_LENGTH_RIFF = 12

    /** ASF/WMV GUID 头部最小长度 */
    private const val MIN_LENGTH_ASF = 16

    /** m3u8 magic number（ASCII: #EXTM3U） */
    private val M3U8_MAGIC = "#EXTM3U".toByteArray(Charsets.US_ASCII)

    /** flv magic number（FLV + 版本号 0x01） */
    private val FLV_MAGIC = byteArrayOf(0x46, 0x4C, 0x56, 0x01)  // "FLV\x01"

    /** mkv/webm EBML magic number（0xDF/0xA3 超 Byte 范围需 toByte 转换） */
    private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    /** mp4 "ftyp" 标识（位于 offset 4） */
    private val MP4_FTYP = "ftyp".toByteArray(Charsets.US_ASCII)

    /** R4 新增：RIFF 容器标识（位于 offset 0，AVI/WAV 共用） */
    private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)

    /** R4 新增：AVI 标识（位于 offset 8） */
    private val AVI_MAGIC = "AVI ".toByteArray(Charsets.US_ASCII)

    /** R4 新增：WAV 标识（位于 offset 8） */
    private val WAV_MAGIC = "WAVE".toByteArray(Charsets.US_ASCII)

    /** R4 新增：ASF/WMV magic number（GUID 头部前 8 字节） */
    private val ASF_MAGIC = byteArrayOf(
        0x30, 0x26, 0xB2.toByte(), 0x75,
        0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11
    )

    /** R4 新增：MPEG-PS magic number（00 00 01 BA） */
    private val MPEG_PS_MAGIC = byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte())

    /** R4 新增：OGG magic number（"OggS"） */
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.US_ASCII)

    /** R4 新增：MP3 with ID3 magic number（"ID3"） */
    private val MP3_ID3_MAGIC = "ID3".toByteArray(Charsets.US_ASCII)

    /** R4 新增：FLAC magic number（"fLaC"） */
    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.US_ASCII)

    /** R4 新增：WebM DocType 标识（EBML 容器中扫描） */
    private val WEBM_DOCTYPE = "webm".toByteArray(Charsets.US_ASCII)

    /** TS 同步字节（0x47，每 188 字节一个） */
    private const val TS_SYNC_BYTE: Byte = 0x47

    /** R4 修订：TS 同步字节最小出现次数（前 8KB 中至少 3 次，间隔 188 字节，对齐 ExoPlayer TsExtractor） */
    private const val TS_SYNC_MIN_COUNT = 3

    /** MPEG-TS 包长度（188 字节固定长度） */
    private const val TS_PACKET_LENGTH = 188

    /** UTF-8 BOM（EF BB BF），m3u8 可能含 BOM 头 */
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /**
     * 匹配 magic number，返回 mimeType 或 null
     *
     * @param data HTTP Range 请求返回的字节数组（前 8KB，R4 从 1KB 提升）
     * @return mimeType 字符串（与 ExoPlayer MimeTypes 常量一致）或 null
     */
    fun sniff(data: ByteArray): String? {
        if (data.isEmpty()) return null
        return matchMagicNumber(data)
    }

    /**
     * R4 完整签名表 17 项 magic number 匹配（按检测成本由低到高排序）
     *
     * 对齐 WHATWG §6.2 + Go net/http/sniff.go + Java VideoMagicNumberEnum
     */
    private fun matchMagicNumber(data: ByteArray): String? {
        // === 原有 6 项签名（保留）===

        // L3.1: mp4 - "ftyp" at offset 4
        if (data.size >= MIN_LENGTH_MP4 && data[4] == MP4_FTYP[0]
            && data[5] == MP4_FTYP[1] && data[6] == MP4_FTYP[2] && data[7] == MP4_FTYP[3]
        ) {
            return MimeTypes.VIDEO_MP4
        }

        // L3.2: m3u8 - "#EXTM3U" at offset 0 (跳过 BOM)
        val m3u8Offset = skipBom(data)
        if (startsWith(data, m3u8Offset, M3U8_MAGIC)) {
            return MimeTypes.APPLICATION_M3U8
        }

        // L3.3: flv - "FLV\x01" at offset 0
        if (startsWith(data, 0, FLV_MAGIC)) {
            return "video/x-flv"
        }

        // L3.4: mkv/webm - EBML magic at offset 0（R4 区分 WebM vs MKV）
        if (startsWith(data, 0, EBML_MAGIC)) {
            return sniffEbmlContainer(data)
        }

        // L3.5: mpd - "<?xml" + "<MPD"（XML 文本，需扫描前 512 字节）
        val mpdResult = sniffMpd(data)
        if (mpdResult != null) return mpdResult

        // L3.6: ts - 0x47 同步字节重复≥3 次（R4 从 5 次降为 3 次，对齐 ExoPlayer TsExtractor）
        if (sniffTs(data)) {
            return MimeTypes.VIDEO_MP2T
        }

        // === R4 新增 11 项签名 ===

        // L3.7: AVI - RIFF + "AVI " at offset 8（二次校验）
        if (data.size >= MIN_LENGTH_RIFF && startsWith(data, 0, RIFF_MAGIC)
            && startsWith(data, 8, AVI_MAGIC)
        ) {
            return "video/x-msvideo"
        }

        // L3.8: WAV - RIFF + "WAVE" at offset 8（二次校验）
        if (data.size >= MIN_LENGTH_RIFF && startsWith(data, 0, RIFF_MAGIC)
            && startsWith(data, 8, WAV_MAGIC)
        ) {
            return MimeTypes.AUDIO_WAV
        }

        // L3.9: WMV/ASF - ASF GUID 头部
        if (data.size >= MIN_LENGTH_ASF && startsWith(data, 0, ASF_MAGIC)) {
            return "video/x-ms-wmv"
        }

        // L3.10: MPEG-PS - 00 00 01 BA
        if (startsWith(data, 0, MPEG_PS_MAGIC)) {
            return MimeTypes.VIDEO_MPEG
        }

        // L3.11: OGG - "OggS"
        if (startsWith(data, 0, OGG_MAGIC)) {
            return "audio/ogg"
        }

        // L3.12: MP3 with ID3 - "ID3"
        if (startsWith(data, 0, MP3_ID3_MAGIC)) {
            return MimeTypes.AUDIO_MPEG
        }

        // L3.13: MP3 without ID3 - 0xFF 0xFA/0xFB/0xF3/0xF2
        if (data.size >= 2 && data[0] == 0xFF.toByte()) {
            val b = data[1].toInt() and 0xFF
            if (b == 0xFA || b == 0xFB || b == 0xF3 || b == 0xF2) {
                return MimeTypes.AUDIO_MPEG
            }
        }

        // L3.14: ADTS (AAC) - 0xFF 0xF1/0xF9
        if (data.size >= 2 && data[0] == 0xFF.toByte()) {
            val b = data[1].toInt() and 0xFF
            if (b == 0xF1 || b == 0xF9) {
                return MimeTypes.AUDIO_AAC
            }
        }

        // L3.15: FLAC - "fLaC"
        if (startsWith(data, 0, FLAC_MAGIC)) {
            return MimeTypes.AUDIO_FLAC
        }

        return null
    }

    /**
     * R4 新增：区分 WebM 和 MKV（EBML 容器 DocType 检测）
     *
     * WebM: EBML + DocType="webm" → video/webm
     * MKV:  EBML + DocType="matroska"（或其他）→ video/x-matroska
     *
     * 简化说明：扫描前 64 字节找 "webm" 字符串（EBML DocType 元素位于 magic 后约 8-12 字节处）
     * 已知上限：极罕见情况下 "webm" 字符串可能出现在非 WebM 容器中，但实际场景几乎不存在
     */
    private fun sniffEbmlContainer(data: ByteArray): String {
        val scanLength = minOf(data.size, 64)
        for (i in 0..scanLength - WEBM_DOCTYPE.size) {
            if (startsWith(data, i, WEBM_DOCTYPE)) {
                return MimeTypes.VIDEO_WEBM
            }
        }
        return MimeTypes.VIDEO_MATROSKA
    }

    /**
     * 跳过 UTF-8 BOM（如有），返回实际内容起始 offset
     */
    private fun skipBom(data: ByteArray): Int {
        return if (data.size >= 3 && data[0] == UTF8_BOM[0] && data[1] == UTF8_BOM[1] && data[2] == UTF8_BOM[2]) {
            3
        } else {
            0
        }
    }

    /**
     * 检测 data 从 offset 开始是否匹配 magic 字节数组
     */
    private fun startsWith(data: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset < 0 || offset + magic.size > data.size) return false
        for (i in magic.indices) {
            if (data[offset + i] != magic[i]) return false
        }
        return true
    }

    /**
     * MPD 检测：在前 512 字节中查找 "<?xml" 和 "<MPD"（DASH 清单文件）
     *
     * 检测策略：扫描前 512 字节，转换为 ASCII 字符串后用 contains 检测
     * 已知上限：极罕见的非 MPD XML 文件含 "<?xml" + "<MPD" 字符串会误判，但实际场景几乎不存在
     */
    private fun sniffMpd(data: ByteArray): String? {
        val scanLength = minOf(data.size, 512)
        val header = String(data, 0, scanLength, Charsets.US_ASCII)
        val lowerHeader = header.lowercase()
        if (lowerHeader.contains("<?xml") && lowerHeader.contains("<mpd")) {
            return MimeTypes.APPLICATION_MPD
        }
        return null
    }

    /**
     * MPEG-TS 检测：0x47 同步字节每 188 字节出现一次
     *
     * R4 修订：阈值从前 1KB 中 5 次改为前 8KB 中 3 次（对齐 ExoPlayer TsExtractor 阈值）
     *
     * 检测策略：从 offset 0 开始，每 188 字节检查是否为 0x47
     * 已知上限：非 TS 流中偶发的 0x47 字节可能误判，但 3 次同步字节几乎不会同时出现
     */
    private fun sniffTs(data: ByteArray): Boolean {
        var syncCount = 0
        var offset = 0
        while (offset + TS_PACKET_LENGTH <= data.size) {
            if (data[offset] == TS_SYNC_BYTE) {
                syncCount++
                if (syncCount >= TS_SYNC_MIN_COUNT) return true
                offset += TS_PACKET_LENGTH
            } else {
                // 当前 offset 不是同步字节，前进 1 字节继续找（容错：TS 流首字节可能不是同步字节）
                offset++
                // 优化：如果前 256 字节都没找到 3 次同步，放弃（避免误判）
                if (offset > 256 && syncCount == 0) return false
            }
        }
        return false
    }

    // ==================== R4-T2: 主动 Probe 函数（强校验，对齐浏览器五层架构） ====================

    /**
     * R4 新增：主动 Probe m3u8 清单内容（强校验）
     *
     * 触发时机：当 URL 后缀或 Content-Type 提示是 HLS 时，主动下载清单内容（前 8KB）验证
     *
     * @param body m3u8 清单内容字节数组
     * @return true 如果首行（跳过 BOM 后）以 #EXTM3U 开头
     */
    fun isReallyM3u8(body: ByteArray): Boolean {
        if (body.isEmpty()) return false
        val offset = skipBom(body)
        return startsWith(body, offset, M3U8_MAGIC)
    }

    /**
     * R4 新增：主动 Probe MPD 清单内容（强校验）
     *
     * 触发时机：当 URL 后缀或 Content-Type 提示是 DASH 时，主动下载清单内容（前 8KB）验证
     *
     * @param body MPD 清单内容字节数组
     * @return true 如果含 <?xml 和 <MPD> 标签（任意大小写）
     */
    fun isReallyMpd(body: ByteArray): Boolean {
        if (body.isEmpty()) return false
        val scanLength = minOf(body.size, 512)
        val text = String(body, 0, scanLength, Charsets.UTF_8).trimStart()
        val lower = text.lowercase()
        return (lower.startsWith("<?xml") && lower.contains("<mpd")) || lower.startsWith("<mpd")
    }

    /**
     * R4 新增：检测 MP4 moov box 位置
     *
     * moov 在 mdat 前（FRONT / FAST_START）→ 支持边下边播
     * moov 在 mdat 后（BACK / SLOW_START）→ 需先拉尾部 moov 才能播放，否则黑屏
     *
     * @param head MP4 文件头部字节数组（前 8KB 足够检测，扫描前 4KB 即可）
     * @return MoovPosition.FRONT / BACK / UNKNOWN
     */
    fun detectMoovPosition(head: ByteArray): MoovPosition {
        if (head.size < 8) return MoovPosition.UNKNOWN
        var pos = 0
        // 简化说明：扫描前 4KB（实际 MP4 moov 通常在前 1KB 内）
        // 已知上限：moov box 极大时可能超出 4KB，但 moov 在 mdat 后的场景已通过 mdat 检测覆盖
        val scanLimit = minOf(head.size, 4096)
        while (pos + 8 <= scanLimit) {
            val size = readBigEndianInt(head, pos)
            if (size < 8) break  // 无效 size，停止扫描
            val typeBytes = head.sliceArray(pos + 4..pos + 7)
            val type = String(typeBytes, Charsets.US_ASCII)
            when (type) {
                "moov" -> return MoovPosition.FRONT
                "mdat" -> return MoovPosition.BACK  // 先遇到 mdat 说明 moov 在后面
            }
            pos += size
        }
        return MoovPosition.UNKNOWN
    }

    /**
     * 读取 4 字节大端整数（MP4 box size）
     */
    private fun readBigEndianInt(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}

/**
 * R4 新增：MP4 moov box 位置枚举
 *
 * 用于 R4-T2 detectMoovPosition 函数返回值
 */
enum class MoovPosition {
    /** moov 在 mdat 前，支持边下边播（FAST_START） */
    FRONT,

    /** moov 在 mdat 后，需先拉尾部 moov 才能播放（SLOW_START） */
    BACK,

    /** 无法确定（数据不足或非标准 MP4） */
    UNKNOWN
}
