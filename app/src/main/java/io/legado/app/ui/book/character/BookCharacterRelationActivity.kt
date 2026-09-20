package io.legado.app.ui.book.character

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.ui.book.character.compose.CharacterRelationScreen
import io.legado.app.ui.book.character.compose.RelationEditDraft
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCharacterRelationActivity : BaseActivity<ViewBinding>(
    fullScreen = false,
    imageBg = false
) {

    private lateinit var composeView: ComposeView
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        SimpleViewBinding(composeView)
    }

    private var bookUrl: String = ""
    private var characterBookKey: String = ""
    private var characters by mutableStateOf<List<BookCharacter>>(emptyList())
    private var relations by mutableStateOf<List<BookCharacterRelation>>(emptyList())
    private var selectedCenterId by mutableStateOf(0L)
    private var selectedRelation by mutableStateOf<BookCharacterRelation?>(null)
    private var editingRelation by mutableStateOf<RelationEditDraft?>(null)

    /** F50：跨页直达时指定的中心角色（一次性生效，不覆盖用户后续切换） */
    private var requestedCenterId: Long = 0L

    /** F54 中心视角记忆键：按书（characterBookKey）隔离 */
    private fun centerMemoryKey(): String = "character_relation_center_$characterBookKey"

    /** F54：切换中心时落盘，下次进页恢复（ACache 为项目既有的页面态持久化通道） */
    private fun selectCenter(centerId: Long) {
        selectedCenterId = centerId
        if (characterBookKey.isNotBlank() && centerId > 0L) {
            ACache.get().put(centerMemoryKey(), centerId.toString())
        }
    }

    /** F54：读回上次中心（无记录返回 0） */
    private fun rememberedCenterId(): Long {
        if (characterBookKey.isBlank()) return 0L
        return ACache.get().getAsString(centerMemoryKey())?.toLongOrNull() ?: 0L
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        characterBookKey = intent.getStringExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY).orEmpty()
        // F50：由角色卡片页带入的中心角色（跨页直达时以该角色为中心）
        requestedCenterId = intent.getLongExtra(EXTRA_CENTER_ID, 0L)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            CharacterRelationScreen(
                characters = characters,
                relations = relations,
                selectedCenterId = selectedCenterId,
                selectedRelation = selectedRelation,
                editingRelation = editingRelation,
                onBack = ::finish,
                onAddRelation = ::addRelation,
                onEditRelation = ::editRelation,
                onDeleteRelation = ::confirmDeleteRelation,
                onSaveRelation = ::saveRelation,
                onDismissEdit = { editingRelation = null },
                onSelectCenter = ::selectCenter,
                onOpenCard = ::openCharacterCard,
                onSelectRelation = { selectedRelation = it }
            )
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (bookUrl.isBlank() && characterBookKey.isBlank()) {
            characters = emptyList()
            relations = emptyList()
            return
        }
        lifecycleScope.launch {
            val data = withContext(IO) {
                val key = characterBookKey.ifBlank {
                    BookCharacterIdentityMigrator.migrate(appDb.bookDao.getBook(bookUrl))
                }
                characterBookKey = key
                appDb.bookCharacterDao.characters(key) to appDb.bookCharacterDao.relations(key)
            }
            val validCharacters = data.first
            val validIds = validCharacters.map { it.id }.toSet()
            val validRelations = data.second.filter {
                it.fromCharacterId in validIds && it.toCharacterId in validIds
            }
            val relatedIds = validRelations
                .flatMap { listOf(it.fromCharacterId, it.toCharacterId) }
                .toSet()
            characters = validCharacters
            relations = validRelations
            val centerCandidates = if (relatedIds.isNotEmpty()) {
                validCharacters.filter { it.id in relatedIds }
            } else {
                emptyList()
            }
            if (selectedCenterId !in relatedIds) {
                // 优先级：F50 跨页直达指定 → F54 上次记忆 → 主角优先 → 重要角色 → 首个
                val fallback = centerCandidates.firstOrNull { it.roleLevel == BookCharacter.ROLE_MAIN }?.id
                    ?: centerCandidates.firstOrNull { it.roleLevel == BookCharacter.ROLE_IMPORTANT }?.id
                    ?: centerCandidates.firstOrNull()?.id
                    ?: 0L
                val target = when {
                    requestedCenterId > 0L && requestedCenterId in relatedIds -> {
                        requestedCenterId.also { requestedCenterId = 0L }
                    }
                    rememberedCenterId().takeIf { it > 0L && it in relatedIds } != null -> {
                        rememberedCenterId()
                    }
                    else -> fallback
                }
                selectCenter(target)
            }
        }
    }

    private fun addRelation() {
        if (characters.size < 2) {
            toastOnUi("至少需要两个角色")
            return
        }
        editingRelation = RelationEditDraft(
            fromCharacterId = selectedCenterId.takeIf { it > 0L } ?: characters.first().id,
            toCharacterId = characters.firstOrNull { it.id != selectedCenterId }?.id ?: characters.last().id
        )
    }

    private fun editRelation(relation: BookCharacterRelation) {
        editingRelation = relation.toDraft()
    }

    private fun saveRelation(draft: RelationEditDraft) {
        val from = draft.fromCharacterId
        val to = draft.toCharacterId
        if (from <= 0L || to <= 0L || from == to) {
            toastOnUi("请选择两个不同角色")
            return
        }
        lifecycleScope.launch {
            withContext(IO) {
                val old = draft.id.takeIf { it > 0L }?.let { appDb.bookCharacterDao.getRelation(it) }
                val now = System.currentTimeMillis()
                val saving = (old ?: BookCharacterRelation(bookUrl = characterBookKey)).copy(
                    bookUrl = characterBookKey,
                    fromCharacterId = from,
                    toCharacterId = to,
                    relationName = draft.relationName.trim().ifBlank { "关系" },
                    relationType = draft.relationType.trim(),
                    description = draft.description.trim(),
                    strength = draft.strength.coerceIn(0, 100),
                    sortOrder = draft.sortOrder.takeIf { it > 0 }
                        ?: old?.sortOrder?.takeIf { it > 0 }
                        ?: ((appDb.bookCharacterDao.maxRelationOrder(characterBookKey) ?: -1) + 1),
                    updatedAt = now
                )
                if (saving.id > 0) {
                    appDb.bookCharacterDao.updateRelation(saving)
                } else {
                    appDb.bookCharacterDao.insertRelation(saving)
                }
            }
            editingRelation = null
            load()
        }
    }

    private fun confirmDeleteRelation(relation: BookCharacterRelation) {
        showComposeConfirmDialog(
            title = "删除关系",
            message = "确定删除「${relation.displayName()}」？",
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookCharacterDao.deleteRelation(relation)
                    }
                    selectedRelation = null
                    load()
                }
            }
        )
    }

    private fun openCharacterCard(character: BookCharacter) {
        startActivity<BookCharacterCardActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl)
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY, characterBookKey)
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, character.id)
        }
    }

    private fun BookCharacterRelation.toDraft(): RelationEditDraft = RelationEditDraft(
        id = id,
        fromCharacterId = fromCharacterId,
        toCharacterId = toCharacterId,
        relationName = relationName,
        relationType = relationType,
        description = description,
        strength = strength,
        sortOrder = sortOrder
    )

    companion object {
        /** F50：跨页直达时指定关系网中心角色 */
        const val EXTRA_CENTER_ID = "characterCenterId"
    }
}