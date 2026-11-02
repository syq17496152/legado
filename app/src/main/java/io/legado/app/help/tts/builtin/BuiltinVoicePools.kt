package io.legado.app.help.tts.builtin

/**
 * 声音池（T8.16，CloneTTS `l6/h8.java` 同构）：**扫音色 tags 自动聚合建组**——
 * 池名 = tag 名，同一 tag 的音色进同一个池；池成员供配声优先选择与列表筛选。
 *
 * 只认"有人味/有语义"的池名（性别/年龄/旁白），避免把来源标签（zip/self）也当成池。
 */
object BuiltinVoicePools {

    /** 认可的池名（顺序即优先级：先命中的 tag 作为主池） */
    val POOL_NAMES = listOf(
        "旁白", "narration",
        "少年", "teen",
        "青年", "youth",
        "中年", "middle",
        "老年", "aged",
        "女声", "female",
        "男声", "male"
    )

    /** 音色归属池：第一个命中的 tag（大小写不敏感），无命中返回空串（不入池） */
    fun poolOf(voice: BuiltinVoice): String {
        val tags = voice.tags.map { it.trim() }
        return POOL_NAMES.firstOrNull { name -> tags.any { it.equals(name, true) } }.orEmpty()
    }

    /** 池 → 成员（顺序按音色 createdAt，稳定；空池不出现） */
    fun groups(voices: List<BuiltinVoice>): Map<String, List<BuiltinVoice>> {
        return voices.mapNotNull { voice ->
            val pool = poolOf(voice)
            if (pool.isBlank()) null else pool to voice
        }.groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.sortedBy { it.createdAt } }
    }

    /**
     * 把池归属写回音色索引（幂等）：组名变化才写盘，返回更新条数。
     * 用途：导入 653 音色后一次同步，列表按池筛选才有数据。
     */
    fun sync(store: BuiltinVoiceStore): Int {
        val changed = store.list().mapNotNull { voice ->
            val pool = poolOf(voice)
            if (voice.groupId == pool) null else voice.copy(groupId = pool)
        }
        if (changed.isEmpty()) return 0
        // 整批一次写盘：500 级音色逐条 upsert 会写 500 次全量索引
        store.upsertAll(changed)
        return changed.size
    }
}
