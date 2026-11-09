package io.legado.app.ui.book.read.config

/**
 * 档位能力（AD-12）：纯函数，供引导页按档位分支，避免"选了省空间档却用不了多角色"。
 *
 * 数据口径与 `builtin_models.json` 一致：
 * - `zipvoice`（clone）需要参考音频 ⇒ 必须先导入声音包；
 * - 只有 `speakerCount > 1` 才是多说话人档（可多角色）；
 * - `vits` 单声档（fast）没有音色列表，引导第 2 步必须跳过。
 */
data class OnboardingCapability(
    val profileId: String,
    val label: String,
    val multiSpeaker: Boolean,
    val needsVoicePack: Boolean,
    val speakerCount: Int,
    val sampleRate: Int,
    val sizeMb: Int,
    val recommended: Boolean
) {

    companion object {

        const val PROFILE_FAST = "fast"
        const val PROFILE_MULTI = "multi"
        const val PROFILE_HQ = "hq"
        const val PROFILE_CLONE = "clone"

        const val ENGINE_ZIPVOICE = "zipvoice"

        /**
         * @param engine 引擎名（`vits` / `zipvoice` / `matcha` / `kokoro`）
         * @param speakerCount 档位内置音色数（0 或 1 = 单声）
         * @param sizeBytes 体积（用于展示 MB）
         */
        fun of(
            profileId: String,
            engine: String,
            speakerCount: Int,
            sampleRate: Int,
            sizeBytes: Long,
            recommended: Boolean = profileId == PROFILE_HQ
        ): OnboardingCapability {
            val needsVoicePack = engine == ENGINE_ZIPVOICE
            return OnboardingCapability(
                profileId = profileId,
                label = labelOf(profileId),
                multiSpeaker = !needsVoicePack && speakerCount > 1,
                needsVoicePack = needsVoicePack,
                speakerCount = speakerCount.coerceAtLeast(0),
                sampleRate = sampleRate,
                sizeMb = (sizeBytes / (1024L * 1024L)).toInt(),
                recommended = recommended
            )
        }

        fun labelOf(profileId: String): String = when (profileId) {
            PROFILE_HQ -> "高清多音色"
            PROFILE_MULTI -> "轻量多音色"
            PROFILE_FAST -> "极速单声"
            PROFILE_CLONE -> "克隆音色"
            else -> profileId
        }

        /** 第 2 步形态 */
        fun step2Mode(capability: OnboardingCapability): OnboardingStep2Mode = when {
            capability.needsVoicePack -> OnboardingStep2Mode.IMPORT_VOICE_PACK
            !capability.multiSpeaker -> OnboardingStep2Mode.SKIPPED_SINGLE
            else -> OnboardingStep2Mode.INLINE_VOICES
        }

        /** 某种演播方式在该档位下是否可用（fast 只能用普通朗读） */
        fun cardAvailable(capability: OnboardingCapability, cardId: String): Boolean =
            when (cardId) {
                CastSceneState.CARD_MONO -> true
                else -> capability.multiSpeaker || capability.needsVoicePack
            }

        /** 置灰原因（可用时返回 null） */
        fun disabledReason(capability: OnboardingCapability, cardId: String): String? {
            if (cardAvailable(capability, cardId)) return null
            return "当前为单声档，需切换到多音色档（高清 / 轻量多音色）"
        }

        /** 默认档位：低端机极速档（省内存），否则高清档 */
        fun defaultProfileId(lowEndDevice: Boolean): String =
            if (lowEndDevice) PROFILE_FAST else PROFILE_HQ

        /** 引导第 1 步卡片排序：推荐档置顶，其余按体积升序 */
        fun orderForDisplay(capabilities: List<OnboardingCapability>): List<OnboardingCapability> =
            capabilities.sortedWith(
                compareByDescending<OnboardingCapability> { it.recommended }.thenBy { it.sizeMb }
            )
    }
}

/**
 * 引导第 2 步的三种形态。
 * 放在文件级：companion 内嵌的枚举无法通过 `OnboardingCapability.X` 访问，会让调用方别扭。
 */
enum class OnboardingStep2Mode {
    /** 正常展示音色列表（多说话人档） */
    INLINE_VOICES,

    /** 整步跳过（单声档） */
    SKIPPED_SINGLE,

    /** 改为导入声音包（克隆档） */
    IMPORT_VOICE_PACK
}
