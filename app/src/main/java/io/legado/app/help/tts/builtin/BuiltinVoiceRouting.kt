package io.legado.app.help.tts.builtin

/**
 * 音色 ↔ 引擎档位的路由规则（不变量 I-6 的唯一实现处）。
 *
 * 为什么单独抽成纯函数对象：合成、预合成、缓存分键三处口径必须逐字符一致，
 * 任何一处写岔都会造成「换了音色仍播旧音频」或「把克隆档参考音频送进多说话人档」这类
 * 无日志可查的错配；抽成纯函数后由 JVM 单测钉住（见 `BuiltinVoiceRoutingTest`）。
 */
object BuiltinVoiceRouting {

    /**
     * 选出当前档位可用的音色。
     * - 多说话人档（vits + speakerCount>1）：只认 `profileId + engine + sid>0`（内置音色），
     *   避免把同引擎的单声档音色或克隆档音色混进来（sid 越界会让 native 侧行为未定义）。
     * - 克隆档（zipvoice）：按 engine 过滤（存量 506/518 条 `engine` 为空 ⇒ [BuiltinVoice.engineOrDefault] 归一到 zipvoice）。
     * - 单声档：恒返回 null（音色不参与合成，保持旧行为）。
     */
    fun pick(
        profile: BuiltinModelProfile,
        voices: List<BuiltinVoice>,
        voiceName: String
    ): BuiltinVoice? {
        val candidates = when {
            profile.multiSpeaker -> voices.filter {
                it.profileId == profile.id && it.engineOrDefault() == profile.engine && it.sid > 0
            }
            profile.requiresReference -> voices.filter { it.engineOrDefault() == profile.engine }
            else -> emptyList()
        }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.matches(voiceName) }?.let { return it }
        return candidates.first()
    }

    /**
     * 切档后是否需要停下正在朗读的服务。
     *
     * 档位 id 变化即意味着已落库的 toneID（音色名，如 `multi-s1|艾舍尔 1`）对新档引擎无效：
     * 多音色档只认 `profileId == 当前档` 的 sid 音色，跨档音色名会被引擎静默回落成默认音
     * （真机铁证 2026-11-09：切到 hq 后朗读仍送 multi 音色名，声音与绑定不符）。
     * 停一次并让用户重开，比"静默换声"可解释。
     */
    fun mustStopReadingAfterProfileSwitch(
        previousProfileId: String,
        nextProfileId: String,
        isReading: Boolean
    ): Boolean = isReading &&
            previousProfileId.isNotBlank() &&
            nextProfileId.isNotBlank() &&
            previousProfileId != nextProfileId

    /**
     * 合成缓存分键口径：
     * 克隆档按绑定键（Voice.name）分键；多说话人档按 `sid` 分键（否则换声会命中上一个音色的缓存）；
     * 单声档固定 `single`（避免同一段文字因音色差异产生多份音频）。
     */
    fun voiceKey(profile: BuiltinModelProfile, voice: BuiltinVoice?): String = when {
        profile.requiresReference -> voice?.voiceName.orEmpty()
        voice != null && voice.sid > 0 -> "sid:${voice.sid}"
        else -> "single"
    }
}
