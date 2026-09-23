package io.legado.app.help.glide

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import io.legado.app.BuildConfig
import io.legado.app.help.MemoryPressure
import io.legado.app.help.config.AppConfig
import java.io.File
import java.io.InputStream
import kotlin.math.min


@Suppress("unused")
@GlideModule
class LegadoGlideModule : AppGlideModule() {

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpModeLoaderFactory
        )
        registry.prepend(
            String::class.java,
            InputStream::class.java,
            LegadoDataUrlLoader.Factory()
        )
        registry.prepend(
            String::class.java,
            File::class.java,
            FilePathLoader.Factory()
        )
    }

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        super.applyOptions(context, builder)
        val calculatorBuilder = MemorySizeCalculator.Builder(context)
        // B13: 小内存设备缩小 Glide 缓存池，减少 OOM 风险
        if (MemoryPressure.isSmallHeap) {
            calculatorBuilder
                .setMemoryCacheScreens(1f)
                .setBitmapPoolScreens(1f)
                .setArrayPoolSize(1024 * 1024)
                .setMaxSizeMultiplier(0.18f)
                .setLowMemoryMaxSizeMultiplier(0.12f)
        }
        val calculator = calculatorBuilder.build()
        val bitmapPoolSize = if (MemoryPressure.isSmallHeap) {
            min(calculator.bitmapPoolSize, (MemoryPressure.maxMemory / 24).toInt())
        } else {
            calculator.bitmapPoolSize
        }
        val bitmapPool = AsyncRecycleBitmapPool(bitmapPoolSize)
        builder.setMemorySizeCalculator(calculator)
        builder.setBitmapPool(bitmapPool)
        // B4·R19：封面双区磁盘缓存（封面持久区 filesDir + 普通临时区 cacheDir）
        // 改造前为单区 InternalCacheDiskCacheFactory(context, 1000MB)：清缓存会把封面一起清掉，
        // 导致「清缓存 ⇒ 封面全部重下」。双区后总量口径不变（256MB + 768MB）。
        builder.setDiskCache(MultiDiskCacheFactory(context))
        // 配置图片加载线程数(仅启动时生效,修改后需重启App)
        // 失败不影响启动,降级到Glide默认线程数
        kotlin.runCatching {
            val sourceExecutor = GlideExecutor.newSourceExecutor(
                AppConfig.imageLoadConcurrency,
                "legado-img",
                GlideExecutor.UncaughtThrowableStrategy.DEFAULT
            )
            builder.setSourceExecutor(sourceExecutor)
        }
        if (!BuildConfig.BUILD_DEBUG && !AppConfig.recordLog) {
            // 注意：这里用的是 **Glide 的日志级别常量**（不是本项目日志），故全限定引用而不引入
            // `android.util.Log`——避免裸 Log 导入被 G-10（临时排查日志清零门禁）判为违规。
            builder.setLogLevel(android.util.Log.ERROR)
        }
    }
}