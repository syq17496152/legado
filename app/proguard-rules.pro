# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# 混合时不使用大小写混合，混合后的类名为小写
-dontusemixedcaseclassnames

# 这句话能够使我们的项目混淆后产生映射文件
# 包含有类名->混淆后类名的映射关系
-verbose

# 保留Annotation不混淆
-keepattributes *Annotation*,InnerClasses

# 避免混淆泛型
-keepattributes Signature

# 指定混淆是采用的算法，后面的参数是一个过滤器
# 这个过滤器是谷歌推荐的算法，一般不做更改
-optimizations !code/simplification/cast,!field/*,!class/merging/*

-flattenpackagehierarchy

#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################
# 屏蔽错误Unresolved class name
#noinspection ShrinkerUnresolvedReference

# 移除Log类打印各个等级日志的代码，打正式包的时候可以做为禁log使用，这里可以作为禁止log打印的功能使用
# 记得proguard-android.txt中一定不要加-dontoptimize才起作用
# 另外的一种实现方案是通过BuildConfig.DEBUG的变量来控制
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# 保持js引擎调用的java类
-keep class * extends io.legado.app.help.JsExtensions{*;}
# 数据类
-keep class **.data.entities.**{*;}
# hutool-core hutool-crypto
-keep class
!cn.hutool.core.util.RuntimeUtil,
!cn.hutool.core.util.ClassLoaderUtil,
!cn.hutool.core.util.ReflectUtil,
!cn.hutool.core.util.SerializeUtil,
!cn.hutool.core.util.ClassUtil,
cn.hutool.core.codec.**,
cn.hutool.core.util.**{*;}
-keep class cn.hutool.crypto.**{*;}
-dontwarn cn.hutool.**
# 缓存 Cookie
-keep class **.help.http.CookieStore{*;}
-keep class **.help.CacheManager{*;}
# StrResponse
-keep class **.help.http.StrResponse{*;}

# markwon
-dontwarn org.commonmark.ext.gfm.**

-keep class okhttp3.*{*;}
-keep class okio.*{*;}
-keep class com.jayway.jsonpath.*{*;}

# LiveEventBus
-keepclassmembers class androidx.lifecycle.LiveData {
    *** mObservers;
    *** mActiveCount;
}
-keepclassmembers class androidx.arch.core.internal.SafeIterableMap {
    *** size();
    *** putIfAbsent(...);
}

## ChangeBookSourceDialog initNavigationView
-keepclassmembers class androidx.appcompat.widget.Toolbar {
    *** mNavButtonView;
}

# MenuExtensions applyOpenTint
-keepnames class androidx.appcompat.view.menu.SubMenuBuilder
-keep class androidx.appcompat.view.menu.MenuBuilder {
    *** setOptionalIconsVisible(...);
    *** getNonActionItems();
}

# FileDocExtensions.kt treeDocumentFileConstructor
-keep class androidx.documentfile.provider.TreeDocumentFile {
    <init>(...);
}

# JsoupXpath
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.AxisSelector{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.NodeTest{*;}
-keep,allowobfuscation class * implements org.seimicrawler.xpath.core.Function{*;}

## JSOUP
-keep class org.jsoup.**{*;}
-dontwarn org.jspecify.annotations.NullMarked

## ExoPlayer 反射设置ua 保证该私有变量不被混淆
-keepclassmembers class androidx.media3.datasource.cache.CacheDataSource$Factory {
    *** upstreamDataSourceFactory;
}
## ExoPlayer 如果还不能播放就取消注释这个
# -keep class com.google.android.exoplayer2.** {*;}

## 对外提供api
-keep class io.legado.app.api.ReturnData{*;}

# Cronet
-keepclassmembers class org.chromium.net.X509Util {
    *** sDefaultTrustManager;
    *** sTestTrustManager;
}
# Cronet 149+ ProGuard 规则（铁律：必须保留所有 provider 类，缺失会导致 R8 移除运行时抛 "All available Cronet providers are disabled"）
# 铁证：2026-07-30 release 包 R8 报 Missing class android.net.http.Proxy 相关，Cronet 新增 Proxy 类被移除
-keep class org.chromium.net.NativeCronetProvider { *; }
-keep class org.chromium.net.JavaCronetProvider { *; }
-keep class org.chromium.net.HttpEngineNativeProvider { *; }
-keep class org.chromium.net.CronetProviderInstaller { *; }
-keep class org.chromium.net.impl.NativeCronetEngineBuilderImpl { *; }
-keep class org.chromium.net.AndroidProxy { *; }
-keep class org.chromium.net.AndroidProxyOptions { *; }
-dontwarn android.net.http.Proxy
-dontwarn android.net.http.Proxy$HttpConnectCallback
-dontwarn android.net.http.ProxyOptions
-dontwarn android.net.http.HttpEngine
-dontwarn android.net.http.HttpEngine$Builder
-dontwarn org.chromium.net.AndroidProxy
-dontwarn org.chromium.net.AndroidProxyOptions

# Cronet API 入口类（libcronet.so JNI_OnLoad 通过 FindClass 反射调用）
# 铁证：2026-07-31 release 包 R8 移除 org.chromium.net.Cronet 类，
#   libcronet.so JNI_OnLoad 调用 GetStaticMethodID(null, "nativeLoad") 触发 SIGABRT（9 次崩溃同模式）
#   日志：docs/issues/user/temp/20260731/001/extracted/logcat.txt
# 修复：保留所有 libcronet.so 通过 JNI 反射调用的 API 入口类，确保 FindClass 不返回 null
-keep class org.chromium.net.Cronet { *; }
-keep class org.chromium.net.CronetEngine { *; }
-keep class org.chromium.net.CronetEngine$Builder { *; }
-keep class org.chromium.net.ExperimentalCronetEngine { *; }
-keep class org.chromium.net.ExperimentalCronetEngine$Builder { *; }
-keep class org.chromium.net.CronetException { *; }
-keep class org.chromium.net.UrlRequest { *; }
-keep class org.chromium.net.UrlRequest$Callback { *; }
-keep class org.chromium.net.UrlRequest$Status { *; }
-keep class org.chromium.net.UrlResponseInfo { *; }
-keep class org.chromium.net.UploadDataProvider { *; }
-keep class org.chromium.net.UploadDataSink { *; }
-keep class org.chromium.net.BidirectionalStream { *; }
-keep class org.chromium.net.NetworkQualityRttListener { *; }
-keep class org.chromium.net.NetworkQualityThroughputListener { *; }
-keep class org.chromium.net.RequestFinishedInfo { *; }
-keep class org.chromium.net.ResourceRequestChecker { *; }
-keep class org.chromium.net.impl.CronetLibraryLoader { *; }
-keep class org.chromium.net.impl.VersionField { *; }

# P0-fix(2026-07-31): 保留所有 impl 包内部类
# 铁证：mapping.txt 显示 org.chromium.net.impl.CronetLibraryLoaderJni -> R8$$REMOVED$$CLASS$$1300
#   libcronet.so JNI_OnLoad 通过 FindClass 查找 CronetLibraryLoaderJni（含 native 方法声明）
#   R8 移除该类 → FindClass 返回 null → GetStaticMethodID(null,...) → SIGABRT
#   真机 arm64 加载 libcronet.so 触发崩溃；模拟器 x86_64 无 arm64 so 走降级路径不崩溃
#   之前 keep 规则只保留 API 入口类（Cronet/CronetEngine），遗漏内部实现类
# 修复：保留整个 impl 包，确保所有 JNI 反射调用的内部类不被移除/混淆
-keep class org.chromium.net.impl.** { *; }

# P0-fix(2026-07-31 V2): 保留整个 org.chromium 包
# 铁证：mapping.txt 显示 R8 移除了 16 个 org.chromium JNI 类（非 impl 包）：
#   org.chromium.base.CommandLineJni / TraceEventJni
#   org.chromium.net.HttpNegotiateAuthenticatorJni / HttpUtilJni / NetworkActiveNotifierJni
#   org.chromium.net.NetworkChangeNotifierJni / ProxyChangeListenerJni / X509UtilJni
#   org.chromium.base.ApiCompatibilityUtils / TimeUtils / TraceEvent 等
#   libcronet.so JNI_OnLoad 通过 FindClass 查找这些类返回 null → GetStaticMethodID(null,...) → SIGABRT
#   之前 keep 规则只保留 org.chromium.net.impl.**，遗漏 org.chromium.base 和 org.chromium.net 下的 Jni 类
# 修复：保留整个 org.chromium 包，确保所有 JNI 反射调用的类不被移除/混淆
-keep class org.chromium.** { *; }
-dontwarn org.chromium.**

# P0-fix(2026-07-31 V3): 保留 internal.org.jni_zero 包（Cronet 150+ JNI 注册新机制）
# 铁证：mapping.txt 显示 internal.org.jni_zero.GEN_JNI -> R8$$REMOVED$$CLASS$$1009
#   Cronet 150+ 版本 JNI 注册机制变更：从 org.chromium.base.XxxJni 迁移到 internal.org.jni_zero 包
#   GEN_JNI 是所有 JNI 方法的总入口类，libcronet.so JNI_OnLoad 通过 FindClass 查找
#   "internal.org.jni_zero.JniZero" 失败 → ClassNotFoundException → java_class == null → SIGABRT
#   模拟器 x86_64 铁证：07-31 08:57:11.570 ClassNotFoundException: Didn't find class
#     "internal.org.jni_zero.JniZero" → SIGABRT java_class == null
#   之前 V2 修复保留 org.chromium.** 不够，遗漏 internal.org.jni_zero 新包
# 修复：保留整个 internal.org.jni_zero 包，确保 GEN_JNI 等 JNI 注册入口类不被移除
-keep class internal.org.jni_zero.** { *; }
-dontwarn internal.org.jni_zero.**

# Throwable
-keepnames class * extends java.lang.Throwable
-keepclassmembernames,allowobfuscation class * extends java.lang.Throwable{*;}

# Sora Editor
-keep class org.eclipse.tm4e.** { *; }
-keep class org.joni.** { *; }

# GSYVideoPlayer
-keep class com.shuyu.gsyvideoplayer.** { *; }
-dontwarn com.shuyu.gsyvideoplayer.**
#-keep class com.shuyu.gsyvideoplayer.video.** { *; }
#-dontwarn com.shuyu.gsyvideoplayer.video.**
#-keep class com.shuyu.gsyvideoplayer.video.base.** { *; }
#-dontwarn com.shuyu.gsyvideoplayer.video.base.**
#-keep class com.shuyu.gsyvideoplayer.utils.** { *; }
#-dontwarn com.shuyu.gsyvideoplayer.utils.**
#-keep class com.shuyu.gsyvideoplayer.player.** {*;}
#-dontwarn com.shuyu.gsyvideoplayer.player.**
#-keep class tv.danmaku.ijk.** { *; }
#-dontwarn tv.danmaku.ijk.**
#-keep class androidx.media3.** {*;}
#-keep interface androidx.media3.**
#-keep class com.shuyu.alipay.** {*;}
#-keep interface com.shuyu.alipay.**
-keep public class * extends android.view.View{
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, java.lang.Boolean);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 线程池拆分：保留 AppConfig.threadCount 兼容字段（@Deprecated 但备份恢复仍需读写）
# 防止 R8 在 release 构建中移除该字段导致旧版本备份恢复失败
-keepclassmembers class io.legado.app.help.config.AppConfig {
    var threadCount;
}

# P1-fix(2026-07-31): 嗅探能力提升新增类的 keep 规则
# 铁证：release 包 R8 可能移除未保留的新增类，导致运行时反射找不到类
# - M3u8PreCheckDataSource: HEAD 预检机制，被 ExoPlayerHelper 反射调用
# - HlsKeyDataSourceFactory + AuthKeyDataSource: AES-128 密钥请求注入，被 ExoPlayerHelper HLS 分支调用
# - RedirectCacheInterceptor + RedirectEntry: 302 重定向缓存，被 OkHttp 拦截器链调用
-keep class io.legado.app.help.exoplayer.M3u8PreCheckDataSource { *; }
-keep class io.legado.app.help.exoplayer.M3u8PreCheckDataSource$PreCheckResult { *; }
-keep class io.legado.app.help.exoplayer.M3u8PreCheckDataSource$PreCheckResult$Success { *; }
-keep class io.legado.app.help.exoplayer.M3u8PreCheckDataSource$PreCheckResult$Fail { *; }
-keep class io.legado.app.help.exoplayer.HlsKeyDataSourceFactory { *; }
-keep class io.legado.app.help.exoplayer.HlsKeyDataSourceFactory$AuthKeyDataSource { *; }
-keep class io.legado.app.help.http.RedirectCacheInterceptor { *; }
-keep class io.legado.app.help.http.RedirectCacheInterceptor$RedirectEntry { *; }
