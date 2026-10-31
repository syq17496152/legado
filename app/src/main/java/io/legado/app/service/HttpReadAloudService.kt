package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.player.PlaybackErrorAction
import io.legado.app.help.player.PlaybackErrorPolicy
import io.legado.app.help.player.PlaybackErrorSession
import io.legado.app.help.readaloud.TtsCacheSweep
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.CastingSegment
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.casting.TtsTagSplitter
import io.legado.app.help.readaloud.http.HttpSegmentEnqueuer
import io.legado.app.help.readaloud.http.HttpSegmentVoicePlan
import io.legado.app.help.readaloud.http.HttpTtsStreamFetcher
import io.legado.app.help.readaloud.prebuild.TtsCacheKeys
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.book.characterBookKey
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null

    /** F5/2.14：T8.6 HTTP 取流（重试记账 / 静音替代提示都收敛在取流器内） */
    private val streamFetcher by lazy { HttpTtsStreamFetcher(this) }
    /** T8.6：当前章生效的多角色规则集（null = 单声路径，行为与改造前完全一致） */
    private var activeRuleSet: CastingRuleSet? = null
    /**
     * W2 / REQ-13：朗读错误自愈记账（上限 3 次 + 冷却 60s）。
     * 取代原 `playErrorNo` 硬编码阈值 5：判据来源统一到 [PlaybackErrorPolicy]（与视频/听书同表）。
     */
    private val playbackErrorSession = PlaybackErrorSession()
    private val downloadTaskActiveLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        // W3 / REQ-16：HTTP 朗读链路的段落切换（自动推进/错误推进共用）⇒ 写段落锚点
        persistVoiceParagraphAnchor("http-paragraph")
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            if (!checkTimerAtChapterEnd()) {
                nextChapter()
            }
        }
    }

    // 当前章装配的引擎记录（AD-01）：由服务内按 ReadAloud.currentRoute.engineValue 查库装配，缺失明示报错
    private var currentHttpTts: HttpTTS? = null

    private fun resetCurrentHttpTts() {
        currentHttpTts = null
    }

    private suspend fun resolveCurrentHttpTts(): HttpTTS {
        currentHttpTts?.let { return it }
        val id = ReadAloud.currentRoute.engineValue.toLongOrNull()
            ?: throw NoStackTraceException("TTS 引擎配置无效（http id 缺失）")
        val httpTts = appDb.httpTTSDao.get(id)
            ?: throw NoStackTraceException("TTS 引擎记录不存在（id=$id），请检查引擎配置")
        currentHttpTts = httpTts
        // AD-04：脚本引擎首次使用时拉取音色目录缓存进 speakersJson（供选角模板声源引用）
        if (httpTts.type == 2 && httpTts.speakersJson.isBlank()) {
            runCatching {
                io.legado.app.help.readaloud.script.TtsScriptEngineClient.fetchVoicesCatalog(httpTts)
                    ?.let { catalog ->
                        appDb.httpTTSDao.update(httpTts.copy(speakersJson = catalog))
                        currentHttpTts = httpTts.copy(speakersJson = catalog)
                    }
            }.onFailure {
                AppLog.put("TTS 音色目录拉取失败：${it.localizedMessage}")
            }
        }
        return currentHttpTts!!
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        resetCurrentHttpTts()
        streamFetcher.resetSession()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = resolveCurrentHttpTts()
                activeRuleSet = runCatching {
                    TtsCastingStore.resolveActiveRuleSet(ReadBook.book?.bookUrl.orEmpty())
                }.getOrNull()
                playDownloadQueue(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    /** 按段下载队列装配（downloadAndPlayAudios/流式 type=2 降级共用；execute 块（CoroutineScope）内调用） */
    private suspend fun CoroutineScope.playDownloadQueue(httpTts: HttpTTS) {
        val enqueuer = segmentEnqueuer(httpTts)
        for (index in contentList.indices) {
            ensureActive()
            if (index < nowSpeak) continue
            var text = contentList[index]
            if (paragraphStartPos > 0 && index == nowSpeak) {
                text = text.substring(sentenceAlignedStart(text, paragraphStartPos))
            }
            val ruleSet = activeRuleSet
            val segments = ruleSet?.let {
                TtsTagSplitter.splitParagraph(index, text, it.rules)
            }.orEmpty()
            val fileName = md5SpeakFileName(text)
            val speakText = text.replace(AppPattern.notReadAloudRegex, "")
            if (segments.isNotEmpty()) {
                enqueuer.enqueue(index, text, segments, ruleSet!!)
                addParagraphPause(index)
            } else if (speakText.isEmpty()) {
                AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                try {
                    val inputStream = if (httpTts.type == 2) {
                        getEngineSpeakStream(httpTts, speakText)
                    } else {
                        getSpeakStream(httpTts, speakText)
                    }
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 语义对齐原实现：单段失败暂停朗读并退出装配（不抛错到 onError）
                    pauseReadAloud()
                    return
                }
            }
            if (segments.isEmpty()) enqueueParagraphMedia(fileName, index)
        }
        preDownloadAudios(httpTts)
    }

    /** 整段媒体入队（单声路径）+ 段落间静音（多角色路径只调 [addParagraphPause]） */
    private fun CoroutineScope.enqueueParagraphMedia(fileName: String, index: Int) {
        launch(Main) { exoPlayer.addMediaItem(MediaItem.fromUri(Uri.fromFile(getSpeakFileAsMd5(fileName)))) }
        addParagraphPause(index)
    }

    /** 段落间停顿：在非末段后插入静音项 (R7.1) */
    private fun CoroutineScope.addParagraphPause(index: Int) {
        val pauseMs = AppConfig.ttsParagraphPauseMs
        if (pauseMs > 0 && index < contentList.lastIndex) {
            val pauseItem = MediaItem.fromUri(Uri.fromFile(createParagraphPauseFile(pauseMs)))
            launch(Main) { exoPlayer.addMediaItem(pauseItem) }
        }
    }

    /** T8.6 段级换声入队器：宿主只提供 取流 / 文件读写 / 入队 三个能力，切段与归因在 HttpSegmentEnqueuer */
    private fun segmentEnqueuer(httpTts: HttpTTS): HttpSegmentEnqueuer {
        val chapterIndex = textChapter?.chapter?.index ?: -1
        return HttpSegmentEnqueuer(
            bookKey = ReadBook.book?.bookUrl.orEmpty(),
            roleBookKey = ReadBook.book?.characterBookKey().orEmpty(),
            chapterIndex = chapterIndex,
            hooks = object : HttpSegmentEnqueuer.Hooks {
                override fun fileNameOf(text: String, voiceId: String) = md5SpeakFileName(text, voiceId = voiceId)
                override fun hasFile(fileName: String) = hasSpeakFile(fileName)
                override fun writeFile(fileName: String, stream: InputStream) { createSpeakFile(fileName, stream) }
                override fun writeSilent(fileName: String) { createSilentSound(fileName) }
                override suspend fun fetch(text: String, toneID: String) =
                    getEngineSpeakStream(httpTts, text, toneID)
                override fun addMedia(fileName: String) {
                    val uri = Uri.fromFile(getSpeakFileAsMd5(fileName))
                    lifecycleScope.launch(Main) { exoPlayer.addMediaItem(MediaItem.fromUri(uri)) }
                }
            }
        )
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
            } else if (!hasSpeakFile(fileName)) {
                runCatching {
                    // P2-13 修复：预取按引擎类型分流（脚本引擎走 getEngineSpeakStream，防静默失效）
                    val inputStream = getEngineSpeakStream(httpTts, speakText)
                    if (inputStream != null) {
                        createSpeakFile(fileName, inputStream)
                    } else {
                        createSilentSound(fileName)
                    }
                }.onFailure {
                    if (it !is CancellationException) {
                        AppLog.put("下章预下载失败：${it.message}")
                    }
                }
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        resetCurrentHttpTts()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = resolveCurrentHttpTts()
                // P1-13 修复：type=2 分流判定移到 resolve 之后（首播时缓存字段尚未装配，
                // 旧判定 currentHttpTts==null 恒走错通道 → 脚本引擎+流式首播必现失败）
                if (httpTts.type == 2) {
                    playDownloadQueue(httpTts)
                    return@withLock
                }
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(sentenceAlignedStart(text, paragraphStartPos))
                    }
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                    }
                    val fileName = md5SpeakFileName(text)
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        exoPlayer.addMediaSource(mediaSource)
                    }
                    // 段落间停顿: 在非末段后插入静音项 (R7.1)
                    val pauseMs = AppConfig.ttsParagraphPauseMs
                    if (pauseMs > 0 && index < contentList.lastIndex) {
                        val pauseItem = MediaItem.fromUri(
                            Uri.fromFile(createParagraphPauseFile(pauseMs))
                        )
                        launch(Main) {
                            exoPlayer.addMediaItem(pauseItem)
                        }
                    }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val textChapter = ReadBook.nextTextChapter ?: return
        val contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1)
            .splitToSequence("\n")
            .filter { it.isNotEmpty() }
            .take(10)
            .toList()
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = md5SpeakFileName(content, textChapter)
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    /**
     * 引擎合成统一入口（§3.5/AD-04）：type=2 走脚本引擎（TtsScriptEngineClient），type=1 走 URL 模板
     */
    private suspend fun getEngineSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        toneID: String = ""
    ): InputStream? {
        if (httpTts.type == 2) {
            val request = io.legado.app.help.readaloud.script.TtsScriptEngineClient.synthesize(
                httpTts,
                speakText,
                // T8.6：段级音色优先；空则沿用全局路由音色（原行为）
                toneID.ifBlank { ReadAloud.currentRoute.toneID }.ifBlank { null },
                // P1-14 修复：全局语速送达脚本引擎（倍率=speechRate/10，与系统引擎同口径，默认 1.0；
                // 旧实现恒传 null → JS 端 rate||1 回退，全局语速滑杆对脚本引擎无效）
                speechRate / 10f,
                null, null
            )
            // 请求对象映射：url + "," + 选项JSON（method/headers/body，AnalyzeUrl 选项串解析）
            val optionJson = org.json.JSONObject().apply {
                put("method", request.method)
                if (request.headers.isNotEmpty()) {
                    put("headers", org.json.JSONObject(request.headers))
                }
                request.body?.let { put("body", it) }
            }.toString()
            val analyzeUrl = AnalyzeUrl(
                request.url + "," + optionJson,
                speakText = speakText,
                speakSpeed = speechRate,
                source = httpTts,
                readTimeout = 300 * 1000L,
                coroutineContext = currentCoroutineContext()
            )
            return analyzeUrl.getResponseAwait().body.byteStream()
        }
        return getSpeakStream(httpTts, speakText, toneID)
    }

    /** 取流（T8.6：toneID 透传给 URL 模板 {{currentToneID}} / 脚本引擎；重试与静默替代见 HttpTtsStreamFetcher） */
    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        toneID: String = ""
    ): InputStream? = streamFetcher.fetch(httpTts, speakText, speechRate, toneID)

    private fun md5SpeakFileName(
        content: String,
        textChapter: TextChapter? = this.textChapter,
        // T8.6：段级换声时缓存键必须带段级音色（同文本不同角色不串音）
        voiceId: String = ReadAloud.currentRoute.toneID
    ): String {
        // 键单源收敛修复（§3.7.1 契约 1/2/8，任务 2.10）：播放端改走 TtsCacheKeys 单源，
        // 与批量端同函数（修复缺陷：播放端残留旧 v1 内联键，导致预合成产物播放端命中不了）
        // E5/方向①：改走键因子三件套门面（engineKey/speedKey/voiceKey 字符串化收进单源）
        val fileName = TtsCacheKeys.speakFileName(
            engineId = currentHttpTts?.id,
            speechRate = speechRate,
            voiceId = voiceId,
            chapterIndex = textChapter?.chapter?.index ?: -1,
            // textChapter.title 已是 displayTitle 口径（ReadBook.kt:1331 getDisplayTitle 同源解析），
            // 与批量端 resolveChapterTitle 一致，禁改回 chapter.title 原始值（键失配）
            chapterTitle = textChapter?.title ?: "",
            unitText = content
        )
        // TtsTrace 真机联调：键单源证据（engineKey/chapterIndex 维度参与哈希）
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "cacheKey 生成 engineKey=${currentHttpTts?.id} ch=${textChapter?.chapter?.index ?: -1} len=${content.length} file=${fileName.takeLast(16)}",
            level = AppLog.Level.INFO
        )
        return fileName
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    /**
     * 生成段落停顿用的指定时长静音 WAV (8kHz 16bit 单声道)
     */
    private fun createParagraphPauseFile(durationMs: Int): File {
        val file = FileUtils.createFileIfNotExist("${ttsFolderPath}pause_$durationMs.wav")
        if (file.length() > 44L) {
            return file
        }
        val sampleRate = 8000
        val dataBytes = sampleRate * 2 * durationMs / 1000
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(ByteArray(dataBytes))
        }
        return file
    }

    private fun hasSpeakFile(name: String): Boolean {
        // P1-5：0 字节目标文件（上次写流失败的残留）不算命中，防播放空文件/批量端误判幂等
        val f = File("${ttsFolderPath}$name.mp3")
        return f.exists() && f.length() > 0
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        // 单一原子提交（§3.7.1-4 契约 5）：temp+rename，防半成品被播放读取；
        // P1-5 修复：目标文件延后到 rename 时才存在（旧实现先建空目标，写流失败遗留 0 字节文件毒化幂等判断）
        val target = File("${ttsFolderPath}$name.mp3")
        val temp = File("${ttsFolderPath}$name.mp3.part")
        try {
            temp.outputStream().use { out ->
                inputStream.use {
                    it.copyTo(out)
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * 移除缓存文件（R17：清扫判据抽到 [TtsCacheSweep]，与 App 启动兜底清扫共用单一来源）
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter?.title ?: "")
        val deleted = TtsCacheSweep.sweep(
            File(ttsFolderPath),
            keepStems = setOf(titleMd5),
            reserved = io.legado.app.help.readaloud.prebuild.TtsPrebuildManager.reservedKeys.keys
        )
        if (deleted > 0) {
            AppLog.putDebug("HttpTTS 换章清扫: deleted=$deleted")
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playbackErrorSession.reset()
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playbackErrorSession.reset()
        }
        // 段落停顿静音项不推进段落指针 (R7.1)
        if (mediaItem?.localConfiguration?.uri?.lastPathSegment?.startsWith("pause_") == true) {
            return
        }
        // T8.6 段级换声：同段内非末段（l0）只更新时间轴，段落指针等末段（l1）再推进
        if (HttpSegmentVoicePlan.isNonLastSegment(mediaItem?.localConfiguration?.uri?.lastPathSegment)) {
            upPlayPos()
            return
        }
        updateNextPos()
        upPlayPos()
    }

    /**
     * 朗读播放错误（W2 / REQ-13 / AD-04：接入统一三态裁决）。
     *
     * 与既有行为的**合并点**（非叠加）：原「连续 5 次错误才停」的硬编码阈值改由
     * [PlaybackErrorSession] 统一记账（上限 3 次 + 冷却 60s，与视频侧同一预置决策），
     * 因此「推进到下一段落」的自愈动作与「连续错误则暂停」的终止动作**沿用原代码**，只换判据来源：
     * - [PlaybackErrorAction.SELF_HEAL] / [PlaybackErrorAction.DEGRADE] ⇒ 删除坏缓存文件 + 推进下一段落
     *   （HTTP 朗读链路的既有自愈动作：换段落即重新合成，等价「换源」）；
     * - [PlaybackErrorAction.ABORT]（不可自愈类 / 预算耗尽 / 冷却中）⇒ 非空提示 + `pauseReadAloud()`。
     *
     * 输出安全：本方法只记录**段落长度**，不回显段落正文（原实现把正文写进日志）。
     */
    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误, 段落长度=${contentList.getOrNull(nowSpeak)?.length ?: 0}", error)
        deleteCurrentSpeakFile()
        val kind = PlaybackErrorPolicy.classify(error.errorCode)
        val action = playbackErrorSession.decide(kind)
        // 诊断日志（日志子规范：功能优化须留可定位日志；仅技术字段，无正文/URL）
        AppLog.putInfo(
            "ReadAloudSelfHeal: kind=$kind, action=$action, " +
                "code=${error.errorCode}(${error.errorCodeName}), " +
                "attempts=${playbackErrorSession.attempts}/${PlaybackErrorPolicy.MAX_ATTEMPTS}, " +
                "cooling=${playbackErrorSession.isCoolingDown()}, speakIdx=$nowSpeak"
        )
        if (action == PlaybackErrorAction.ABORT) {
            toastOnUi("朗读连续错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
