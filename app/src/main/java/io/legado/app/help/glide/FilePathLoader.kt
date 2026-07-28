package io.legado.app.help.glide

import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.File

class FilePathLoader : ModelLoader<String, File> {
    override fun buildLoadData(
        model: String,
        width: Int,
        height: Int,
        options: com.bumptech.glide.load.Options
    ): ModelLoader.LoadData<File>? {
        return ModelLoader.LoadData(ObjectKey(model), FilePathFetcher(model))
    }

    /**
     * 修复（image-canvas-display-fix-20260728）：只处理本地文件路径，不拦截 http/https URL
     *
     * 根因：原 handles() 对所有 String 返回 true，导致 downloadOnly()（目标类型 File）
     * 的 http URL 请求被 FilePathLoader 拦截，FilePathFetcher 把 http URL 当本地文件路径，
     * File("https://xxx.com/xxx.jpg").exists() 必然返回 false，触发 onLoadFailed，
     * 图片永远不显示（铁证：006 日志包 21:47:28-42 场景，48 张图解析成功但 0 张 HTTP 请求）。
     *
     * 修复：http/https URL 返回 false，让 Glide 走默认的 StringLoader → OkHttpModelLoader → OkHttpStreamFetcher 流程。
     * 本地文件路径仍返回 true，保持原有行为。
     */
    override fun handles(model: String): Boolean {
        return !model.startsWith("http://") && !model.startsWith("https://")
    }

    class FilePathFetcher(private val filePath: String) : DataFetcher<File> {
        override fun loadData(
            priority: Priority,
            callback: DataFetcher.DataCallback<in File>
        ) {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                callback.onDataReady(file)
            } else {
                callback.onLoadFailed(Exception("File not found: $filePath"))
            }
        }

        override fun cleanup() {}

        override fun cancel() {}

        override fun getDataClass(): Class<File> = File::class.java

        override fun getDataSource(): DataSource = DataSource.LOCAL
    }

    class Factory : ModelLoaderFactory<String, File> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<String, File> {
            return FilePathLoader()
        }

        override fun teardown() {}
    }
}


