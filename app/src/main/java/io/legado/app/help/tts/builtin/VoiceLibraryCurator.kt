package io.legado.app.help.tts.builtin

import io.legado.app.utils.LogUtils

/**
 * 音色库整理（2026-11-10 用户裁定：282 个音色「乱七八糟」）：
 *
 * 实测问题（K100 真机 dump 统计）：
 * - 274 / 282 条是播种音色的**无语义序号名**（`艾舍尔 1..174` / `珂珂 3..102`）；
 * - 性别/年龄/标签中英混存，`未定` 被当成一个值写进索引；
 * - 池名把内部英文值（narration/youth）直接暴露在中文界面；
 * - 没有任何去重收口 ⇒ 重复导入/重复播种后条目成倍增长。
 *
 * 本对象负责两件事（纯逻辑，音频文件不动）：
 * 1. [curate]：值域归一 + 去重（预设按 `profileId+sid`，导入按音频 md5 + 同名）；
 * 2. [archive]：把音色按「本档可用 → 性别 → 年龄」切成可折叠的分组，供列表渲染。
 */
object VoiceLibraryCurator {

    data class Report(
        val total: Int = 0,
        val normalized: Int = 0,
        val removedDuplicates: Int = 0,
        /** 同一段音频但名称不同：可能是用户有意做的别名，只统计不删除 */
        val suspiciousDuplicates: Int = 0,
        val message: String = ""
    )

    /** 归档分组（顺序即渲染顺序） */
    data class Section(
        val key: String,
        val title: String,
        val voices: List<BuiltinVoice>
    )

    /** 单条归一：性别/年龄/标签统一值域；`未定` 清空（未知必须是"没有值"） */
    fun normalize(voice: BuiltinVoice): BuiltinVoice {
        val gender = VoiceTagNormalizer.gender(voice.gender)
        val age = VoiceTagNormalizer.age(voice.age).ifBlank {
            VoiceTagNormalizer.age(voice.tags.joinToString(" "))
        }
        val tags = VoiceTagNormalizer.tags(voice.tags, gender, age)
        if (gender == voice.gender && age == voice.age && tags == voice.tags) return voice
        return voice.copy(gender = gender, age = age, tags = tags)
    }

    /** 预设音色的身份键（同一档位的同一 sid 只应存在一条） */
    private fun presetKey(voice: BuiltinVoice): String? {
        if (voice.profileId.isBlank()) return null
        return if (voice.sid > 0) {
            "preset|${voice.profileId}|${voice.sid}"
        } else {
            null
        }
    }

    /**
     * 整理音色库（幂等，可反复点）：
     * ① 全部条目值域归一；
     * ② 预设按 `profileId+sid` 去重（保留 createdAt 最早的一条）；
     * ③ 非预设按「音频 md5 + 归一后名称」去重；同音频不同名只计 [Report.suspiciousDuplicates]；
     * ④ 池归属重算（[BuiltinVoicePools.sync]）。
     */
    fun curate(store: BuiltinVoiceStore, dedupeByAudio: Boolean = true): Report {
        val original = store.list()
        if (original.isEmpty()) return Report(message = "音色库为空")
        var normalizedCount = 0
        val normalized = original.map { voice ->
            val n = normalize(voice)
            if (n !== voice) normalizedCount++
            n
        }

        // ① 预设 sid 去重
        val kept = ArrayList<BuiltinVoice>(normalized.size)
        val seenPreset = HashMap<String, BuiltinVoice>()
        var removed = 0
        normalized.forEach { voice ->
            val key = presetKey(voice)
            if (key == null) {
                kept.add(voice)
                return@forEach
            }
            val existing = seenPreset[key]
            if (existing == null) {
                seenPreset[key] = voice
                kept.add(voice)
            } else {
                // 保留更早创建的一条（createdAt=0 视为最旧），其余丢弃
                if (isOlder(voice, existing)) {
                    kept.remove(existing)
                    kept.add(voice)
                    seenPreset[key] = voice
                }
                removed++
            }
        }

        // ② 音频 md5 + 名称去重（预设没有参考音频，跳过）
        var suspicious = 0
        if (dedupeByAudio) {
            val seen = HashMap<String, BuiltinVoice>()
            val audioSeenNames = HashSet<String>()
            val result = ArrayList<BuiltinVoice>(kept.size)
            kept.forEach { voice ->
                if (voice.readOnly || voice.refAudio.isBlank()) {
                    result.add(voice)
                    return@forEach
                }
                val hash = runCatching {
                    val file = store.audioFile(voice)
                    if (file.isFile && file.length() > 0) BuiltinHash.md5Hex(file.readBytes()) else ""
                }.getOrDefault("")
                if (hash.isBlank()) {
                    result.add(voice)
                    return@forEach
                }
                val nameKey = hash + "|" + baseName(voice.displayName)
                if (nameKey in audioSeenNames) {
                    removed++
                    return@forEach
                }
                val sameAudio = seen.containsKey(hash)
                if (sameAudio) suspicious++
                seen[hash] = voice
                audioSeenNames.add(nameKey)
                result.add(voice)
            }
            kept.clear()
            kept.addAll(result)
        }

        val changed = kept != original
        if (changed) {
            // 索引可能包含只读预设：直接整批落盘（store.delete 会拒绝只读条目）
            store.save(kept)
            runCatching { BuiltinVoicePools.sync(store) }
                .onFailure { LogUtils.e(TAG, "池同步失败：${it.message}") }
        } else {
            // 只同步池（归一后 tags 变了，池归属也应跟着变）
            runCatching { BuiltinVoicePools.sync(store) }
                .onFailure { LogUtils.e(TAG, "池同步失败：${it.message}") }
        }

        val unlabeled = kept.count { VoiceTagNormalizer.gender(it.gender).isBlank() }
        val message = buildString {
            append("整理完成：共 ${kept.size} 个")
            if (normalizedCount > 0) append(" · 规范标签 $normalizedCount")
            if (removed > 0) append(" · 去重 $removed")
            if (suspicious > 0) append(" · 疑似同音异名 $suspicious")
            if (unlabeled > 0) append(" · 未标性别 $unlabeled")
        }
        return Report(
            total = kept.size,
            normalized = normalizedCount,
            removedDuplicates = removed,
            suspiciousDuplicates = suspicious,
            message = message
        )
    }

    private fun isOlder(candidate: BuiltinVoice, existing: BuiltinVoice): Boolean {
        val a = candidate.createdAt.takeIf { it > 0 } ?: Long.MIN_VALUE
        val b = existing.createdAt.takeIf { it > 0 } ?: Long.MIN_VALUE
        return a < b
    }

    /**
     * 去重用的名称基名：store 对重名会自动加 `_2`/`_3` 后缀，
     * 这些后缀是机器消歧产物而不是用户命名 ⇒ 视为同名（否则重复导入永远去不掉）。
     */
    internal fun baseName(name: String): String = name.replace(Regex("_\\d+$"), "").trim()

    /**
     * 归档分组：`本档可用 → 名称族 → 性别 → 年龄`（空组不出现，未知一律排最后）。
     *
     * 名称族（2026-11-10 用户要求「按名称…归档」）：只对**序号名**生效
     * （`艾舍尔 47` / `珂珂 88` ⇒ 族名 `艾舍尔` / `珂珂`），因为这类名字在真机上占 274/282，
     * 平铺时用户无法按"这是哪个音色包/哪一族"来找。非序号名（用户自命名/导入包名）不切族，
     * 避免 500 条导入音色被切成上百个只有 1 条的分组。
     *
     * @param activeProfileId 当前激活档位；为 null 时不做可用性分区（全部按名称/性别/年龄归档）
     */
    fun archive(
        voices: List<BuiltinVoice>,
        activeProfileId: String? = null
    ): List<Section> {
        if (voices.isEmpty()) return emptyList()
        val buckets = LinkedHashMap<String, MutableList<BuiltinVoice>>()
        val titles = HashMap<String, String>()
        voices.forEach { voice ->
            val usable = activeProfileId == null ||
                voice.profileId.isBlank() ||
                voice.profileId == activeProfileId
            val gender = VoiceTagNormalizer.gender(voice.gender)
            val age = VoiceTagNormalizer.age(voice.age)
            val family = nameFamily(voice.displayName)
            val key = listOf(usable.toString(), family, gender, age).joinToString("|")
            buckets.getOrPut(key) { ArrayList() }.add(voice)
            titles[key] = buildString {
                if (activeProfileId != null) {
                    append(if (usable) "本档可用" else "其他档位")
                    append(" · ")
                }
                if (family.isNotBlank()) {
                    append(family)
                    append(" · ")
                }
                append(
                    when (gender) {
                        VoiceTagNormalizer.GENDER_FEMALE -> "女声"
                        VoiceTagNormalizer.GENDER_MALE -> "男声"
                        else -> "未标性别"
                    }
                )
                append(" · ")
                append(VoiceTagNormalizer.ageLabel(age).ifBlank { "未标年龄" })
            }
        }
        return buckets.entries
            .sortedWith(
                compareBy<Map.Entry<String, MutableList<BuiltinVoice>>> { entry ->
                    // 本档可用排最前（"false" 开头的 key 为其他档位）
                    if (entry.key.startsWith("false")) 1 else 0
                }.thenBy { sectionRank(it.key) }
            )
            .map { (key, list) ->
                Section(
                    key = key,
                    title = "${titles[key]}（${list.size}）",
                    voices = list.sortedWith(
                        compareBy<BuiltinVoice> { it.createdAt }.thenComparator { a, b ->
                            naturalCompare(a.name, b.name)
                        }
                    )
                )
            }
    }

    /**
     * 名称族：只有「非数字前缀 + 空格 + 纯数字」这种**序号名**才有族名（`艾舍尔 47` ⇒ `艾舍尔`）。
     * 其余返回空串（不切族）——避免把用户自定义名字切成"一条一组"。
     */
    internal fun nameFamily(name: String): String {
        val match = Regex("^(\\S+)\\s+\\d+$").find(name.trim()) ?: return ""
        return match.groupValues[1]
    }

    /**
     * 序号名自然排序：`艾舍尔 2` 必须排在 `艾舍尔 10` 前面（纯字符串排序会把 10 提到 2 之前，
     * 这是用户看到「乱七八糟」的直接来源之一）。
     */
    internal fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(j, ej).trimStart('0').ifEmpty { "0" }
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ei
                j = ej
                continue
            }
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
        return (a.length - i) - (b.length - j)
    }

    /** 组排序权重：女 → 男 → 未标；年龄按 teen→youth→middle→aged→未标（key=usable|family|gender|age） */
    private fun sectionRank(key: String): String {
        val parts = key.split("|")
        val gender = parts.getOrElse(2) { "" }
        val age = parts.getOrElse(3) { "" }
        val genderRank = when (gender) {
            VoiceTagNormalizer.GENDER_FEMALE -> "0"
            VoiceTagNormalizer.GENDER_MALE -> "1"
            else -> "2"
        }
        val ageRank = VoiceTagNormalizer.AGE_ORDER.indexOf(age)
            .takeIf { it >= 0 }?.toString() ?: "9"
        return genderRank + ageRank
    }

    private const val TAG = "VoiceLibraryCurator"
}
