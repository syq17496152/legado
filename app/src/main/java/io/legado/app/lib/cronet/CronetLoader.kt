package io.legado.app.lib.cronet

import androidx.annotation.Keep
import io.legado.app.help.http.Cronet

/**
 * Cronet 500.0.1（cronet-bundled）加载器（精简版）
 *
 * 历史：Cronet 150 时代此对象负责手工加载本地/动态下载的 libcronet.so——
 *   cronetlib/ 多 jar（cronet_api + cronet_impl_* + cronet_shared）+ jniLibs 内置 so +
 *   cronet.json md5 校验 + NativeCronetEngineBuilderImpl 强制 native，并保留一套完整的
 *   动态下载 / 版本校验 / 崩溃降级逻辑（含 ANR 防御）。这些在本版本已全部不需要。
 *
 * 现状：cronet-bundled 单体 AAR 把 API + 实现 + libcronet.so 一并打包，APK 安装时系统按 ABI
 *   自动提取 .so，CronetEngine.Builder.build() 时自动加载 native。因此此处退化为无操作存根，
 *   仅保留 install()/preDownload() 以满足 Cronet.LoaderInterface 与
 *   CronetInterceptor/CronetCoroutineInterceptor 的调用点（兼容旧代码路径）。
 */
@Keep
object CronetLoader : Cronet.LoaderInterface {

    /** cronet-bundled 已内置 libcronet.so，视为始终可用 */
    override fun install(): Boolean = true

    /** cronet-bundled 无需预下载 so，空实现 */
    override fun preDownload() = Unit

}