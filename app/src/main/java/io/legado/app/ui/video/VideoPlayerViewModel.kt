package io.legado.app.ui.video

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.model.VideoPlay
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.utils.toastOnUi

class VideoPlayerViewModel(application: Application) : BaseViewModel(application) {
    val upStarMenuData = MutableLiveData<Boolean>()
    fun removeFromBookshelf(success: (() -> Unit)?) {
        execute {
            VideoPlay.book?.let {
                appDb.bookDao.delete(it)
            }
        }.onSuccess {
            success?.invoke()
        }
    }

    /**
     * 收藏当前播放条目（video-live-favorite-fix AD-02 三段式）。
     *
     * ① 已收藏（`rssStar != null`）→ 不改数据，直接回调（调用方弹编辑框）；
     * ② 有阅读记录（`rssRecord != null`）→ 记录转收藏入库（字段更全，保持既有优先）；
     * ③ 均无 → 用**当前播放条目直接构造**收藏入库（独立收藏路径，修复「无记录即静默空转」）。
     *
     * 三段都不成立（拿不到任何文章上下文）→ 返回 false，由调用方给出 toast（REQ-6：不静默）。
     */
    fun addFavorite(success: () -> Unit) {
        execute {
            if (VideoPlay.rssStar == null) {
                val star = VideoPlay.rssRecord?.toStar() ?: VideoPlay.buildCurrentStar()
                    ?: return@execute false
                appDb.rssStarDao.insert(star)
                VideoPlay.rssStar = star
            }
            true
        }.onSuccess { ok ->
            upStarMenuData.postValue(true)
            if (ok) {
                success.invoke()
            } else {
                context.toastOnUi(R.string.video_favorite_no_context)
            }
        }
    }

    fun updateFavorite(title: String?, group: String?) {
        execute {
            (VideoPlay.rssStar ?: VideoPlay.rssRecord?.toStar())?.let {
                it.title = title ?: it.title
                it.group = group ?: it.group
                appDb.rssStarDao.update(it)
                VideoPlay.rssStar = it
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun delFavorite() {
        execute {
            VideoPlay.rssStar?.let {
                appDb.rssStarDao.delete(it.origin, it.link)
                VideoPlay.rssRecord = it.toRecord()
                VideoPlay.rssStar = null
            }
        }.onSuccess {
            upStarMenuData.postValue(true)
        }
    }

    fun upSource(success: (() -> Unit)? = null) {
        when (val source = VideoPlay.source) {
            is BookSource -> {
                VideoPlay.source = appDb.bookSourceDao.getBookSource(source.getKey())?.also {
                    success?.invoke()
                }
            }
            is RssSource -> {
                VideoPlay.source = appDb.rssSourceDao.getByKey(source.getKey())
            }
        }
    }

    fun onButtonClick(activity: AppCompatActivity, name: String, click: String) {
        val source = VideoPlay.source ?: return
        val book = VideoPlay.book ?: return
        execute {
            val java = SourceLoginJsExtensions(activity, source)
            runScriptWithContext {
                source.evalJS(click) {
                    put("result", null)
                    put("java", java)
                    put("book", book)
                }
            }
        }.onError {
            AppLog.put("${source.getTag()}: ${it.localizedMessage}", it)
            context.toastOnUi("$name click error\n${it.localizedMessage}")
        }
    }
}