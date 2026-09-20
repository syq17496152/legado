package io.legado.app.ui.book.character

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.readaloud.ReadAloudConfigChangeNotifier
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.ui.book.character.compose.CharacterCardScreen
import io.legado.app.ui.book.character.compose.CharacterSpeechEngineUi
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCharacterCardActivity : BaseActivity<ViewBinding>(
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
    private var characterId: Long = 0L
    private var character by mutableStateOf<BookCharacter?>(null)
    private var speechEngines by mutableStateOf<List<CharacterSpeechEngineUi>>(emptyList())
    /** F50：本角色直接关系数（关系网入口徽标；从既有 relations 查询派生，零新增存储） */
    private var directRelationCount by mutableStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        characterBookKey = intent.getStringExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY).orEmpty()
        characterId = intent.getLongExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, 0L)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            CharacterCardScreen(
                character = character,
                onBack = ::finish,
                onEdit = ::openEdit,
                speechEngines = speechEngines,
                onSpeechRouteChange = ::updateSpeechRoute,
                relationCount = directRelationCount,
                onOpenRelations = ::openRelations
            )
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (characterId <= 0L) {
            toastOnUi("角色不存在")
            finish()
            return
        }
        lifecycleScope.launch {
            val data = withContext(IO) {
                val key = characterBookKey.ifBlank {
                    BookCharacterIdentityMigrator.migrate(appDb.bookDao.getBook(bookUrl))
                }
                characterBookKey = key
                val relations = appDb.bookCharacterDao.relations(key)
                Triple(
                    appDb.bookCharacterDao.getCharacter(characterId),
                    SpeechVoiceCatalogRepository
                        .allGroups(applicationContext, appDb.httpTTSDao.all)
                        .map { CharacterSpeechEngineUi(it) },
                    relations.count {
                        it.fromCharacterId == characterId || it.toCharacterId == characterId
                    }
                )
            }
            val item = data.first
            if (item == null) {
                toastOnUi("角色不存在")
                finish()
                return@launch
            }
            character = item
            speechEngines = data.second
            directRelationCount = data.third
        }
    }

    /** F50 角色关系跨页直达：直达关系网并以本角色为中心（复用既有 EXTRA_CENTER_ID 通道） */
    private fun openRelations() {
        if (characterId <= 0L) return
        startActivity<BookCharacterRelationActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl.ifBlank { character?.bookUrl.orEmpty() })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY, characterBookKey)
            putExtra(BookCharacterRelationActivity.EXTRA_CENTER_ID, characterId)
        }
    }

    private fun updateSpeechRoute(routeJson: String) {
        val item = character ?: return
        lifecycleScope.launch {
            val updated = item.copy(
                speechRouteJson = routeJson.trim(),
                updatedAt = System.currentTimeMillis()
            )
            withContext(IO) {
                appDb.bookCharacterDao.updateCharacter(updated)
            }
            ReadAloudConfigChangeNotifier.notifySpeech()
            character = updated
            toastOnUi("已更新角色配音")
        }
    }

    private fun openEdit() {
        val item = character ?: return
        startActivity<BookCharacterEditActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl.ifBlank { item.bookUrl })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_BOOK_KEY, characterBookKey.ifBlank { item.bookUrl })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, item.id)
        }
    }
}