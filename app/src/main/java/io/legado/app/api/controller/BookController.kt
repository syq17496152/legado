package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.service.kernel.BookKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.runBlocking

/**
 * 书籍域 Web 门面（web-mcp-productization 一期 · 2.1.6）。
 *
 * 职责被压缩为三件（design §1.4.3）：**解析参数 → 调 [BookKernel] → 装信封**。
 * 业务逻辑（DAO 访问 / 网络取书 / 图片解码）全在 Kernel，本类**不再出现** `appDb.` / `Dao.`
 * （门禁 `service/kernel` 依赖方向 + tasks §2.1.6 的 `Grep "Dao\." 命中 0` 判据）。
 *
 * **为何保留非 `suspend` 的旧签名**：本类是**共享门面**，除 Web 路由外还被
 * `api/ReaderProvider.kt`（外部阅读器 ContentProvider）、`service/relay/RelayReadDispatcher.kt`、
 * `model/AutoTaskProtocol.kt` 调用，其中两处**非挂起上下文**无法调用 suspend 函数 ⇒
 * 一律改成 suspend 会把改动扩散到 4 个无关调用点。故保留原签名，在门面内以 `runBlocking`
 * 作为**同步边界**（design §1.4.3 明确允许：要清零的是 **Kernel 内部**的 runBlocking）。
 */
object BookController {

    /**
     * 书架所有书籍。
     *
     * 保留**属性**形态（`ReaderProvider` / `RelayReadDispatcher` 以属性方式访问）。
     */
    val bookshelf: ReturnData
        get() {
            val books = runBlocking { BookKernel.bookshelf() }
            return if (books.isEmpty()) {
                ReturnData().setErrorMsg("还没有添加小说")
            } else {
                ReturnData().setData(books)
            }
        }

    /**
     * 获取封面
     */
    fun getCover(parameters: Map<String, List<String>>): ReturnData {
        val coverPath = parameters["path"]?.firstOrNull()
        return try {
            ReturnData().setData(runBlocking { BookKernel.cover(coverPath) })
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.localizedMessage ?: "getCover error")
        }
    }

    /**
     * 获取正文图片
     */
    fun getImg(parameters: Map<String, List<String>>): ReturnData {
        val returnData = ReturnData()
        val bookUrl = parameters["url"]?.firstOrNull()
            ?: return returnData.setErrorMsg("bookUrl为空")
        val src = parameters["path"]?.firstOrNull()
            ?: return returnData.setErrorMsg("图片链接为空")
        val width = parameters["width"]?.firstOrNull()?.toInt() ?: 640
        return try {
            returnData.setData(runBlocking { BookKernel.image(bookUrl, src, width) })
        } catch (e: Exception) {
            returnData.setErrorMsg(e.localizedMessage ?: "bookUrl不对")
        }
    }

    /**
     * 更新目录
     */
    fun refreshToc(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        if (bookUrl.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        return try {
            ReturnData().setData(runBlocking { BookKernel.refreshToc(bookUrl) })
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.localizedMessage ?: "refresh toc error")
        }
    }

    /**
     * 获取目录（未缓存则触发一次目录刷新，行为与改造前一致）
     */
    fun getChapterList(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        if (bookUrl.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        val chapterList = runBlocking { BookKernel.chapterList(bookUrl) }
        if (chapterList.isEmpty()) {
            return refreshToc(parameters)
        }
        return ReturnData().setData(chapterList)
    }

    /**
     * 获取正文
     *
     * 可选分页参数 `offset` / `length`（2.1.5 / REQ-1-308，缺省即整章 ⇒ 老页零影响，见决策 #13）。
     */
    fun getBookContent(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        if (bookUrl.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        val index = parameters["index"]?.firstOrNull()?.toInt()
            ?: return ReturnData().setErrorMsg("参数index不能为空, 请指定目录序号")
        val offset = parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
        val length = parameters["length"]?.firstOrNull()?.toIntOrNull() ?: BookKernel.CONTENT_NO_LIMIT
        return try {
            val content = runBlocking { BookKernel.bookContent(bookUrl, index, offset, length) }
            if (content == null) {
                ReturnData().setErrorMsg("未找到")
            } else {
                ReturnData().setData(content)
            }
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.localizedMessage ?: "获取正文失败")
        }
    }

    fun getRelayBookCover(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
            ?: return ReturnData().setErrorMsg("bookUrl为空")
        return try {
            ReturnData().setData(runBlocking { BookKernel.relayBookCover(bookUrl) })
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.localizedMessage ?: "bookUrl不对")
        }
    }

    fun getRelayBookContent(parameters: Map<String, List<String>>): ReturnData {
        val bookUrl = parameters["url"]?.firstOrNull()
        if (bookUrl.isNullOrEmpty()) {
            return ReturnData().setErrorMsg("参数url不能为空，请指定书籍地址")
        }
        val index = parameters["index"]?.firstOrNull()?.toInt()
            ?: return ReturnData().setErrorMsg("参数index不能为空, 请指定目录序号")
        return try {
            ReturnData().setData(runBlocking { BookKernel.relayBookContent(bookUrl, index) })
        } catch (e: Exception) {
            ReturnData().setErrorMsg(e.localizedMessage ?: "正文格式无效")
        }
    }

    /**
     * 保存书籍
     */
    suspend fun saveBook(postData: String?): ReturnData {
        val book = GSON.fromJsonObject<Book>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("格式不对")
        BookKernel.saveBook(book)
        return ReturnData().setData("")
    }

    /**
     * 删除书籍
     */
    fun deleteBook(postData: String?): ReturnData {
        val book = GSON.fromJsonObject<Book>(postData).getOrNull()
            ?: return ReturnData().setErrorMsg("格式不对")
        runBlocking { BookKernel.deleteBook(book) }
        return ReturnData().setData("")
    }

    /**
     * 保存进度
     *
     * 校验口径见 [BookKernel.validateProgress]（决策 #12：只做**非负**校验，不做章节越界硬拒）。
     */
    suspend fun saveBookProgress(postData: String?): ReturnData {
        val bookProgress = GSON.fromJsonObject<BookProgress>(postData)
            .onFailure { it.printOnDebug() }
            .getOrNull()
            ?: return ReturnData().setErrorMsg("格式不对")
        BookKernel.validateProgress(bookProgress)?.let {
            return ReturnData().setErrorMsg(it)
        }
        return if (BookKernel.saveBookProgress(bookProgress)) {
            ReturnData().setData("")
        } else {
            ReturnData().setErrorMsg("格式不对")
        }
    }

    /**
     * 添加本地书籍
     */
    fun addLocalBook(
        parameters: Map<String, List<String>>,
        files: Map<String, String>
    ): ReturnData {
        val fileName = parameters["fileName"]?.firstOrNull()
            ?: return ReturnData().setErrorMsg("fileName 不能为空")
        val fileData = files["fileData"]
            ?: return ReturnData().setErrorMsg("fileData 不能为空")
        return kotlin.runCatching {
            runBlocking { BookKernel.addLocalBook(fileName, fileData) }
        }.fold(
            onSuccess = { ReturnData().setData(true) },
            onFailure = { error ->
                when (error) {
                    is SecurityException -> ReturnData().setErrorMsg("需重新设置书籍保存位置!")
                    else -> ReturnData().setErrorMsg("保存书籍错误\n${error.localizedMessage}")
                }
            }
        )
    }

    /**
     * 保存web阅读界面配置
     */
    fun saveWebReadConfig(postData: String?): ReturnData {
        runBlocking { BookKernel.saveWebReadConfig(postData) }
        return ReturnData().setData("")
    }

    /**
     * 获取web阅读界面配置
     */
    fun getWebReadConfig(): ReturnData {
        val data = runBlocking { BookKernel.webReadConfig() }
            ?: return ReturnData().setErrorMsg("没有配置")
        return ReturnData().setData(data)
    }

}
