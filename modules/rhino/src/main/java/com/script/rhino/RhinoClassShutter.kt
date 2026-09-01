/*
 * Copyright (c) 2005, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.script.rhino

import android.os.Build
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.lang.reflect.Member
import java.nio.file.FileSystem
import java.nio.file.Path
import java.util.Collections

/**
 * This class prevents script access to certain sensitive classes.
 * Note that this class checks over and above SecurityManager. i.e., although
 * a SecurityManager would pass, class shutter may still prevent access.
 *
 * @author A. Sundararajan
 * @since 1.6
 */
object RhinoClassShutter : ClassShutter {

    // P0-S4 类导入策略灰度：书源模式下的宿主 App 类前缀（D5 观察放行，SourceGuard 数据驱动二期白名单）
    const val APP_CLASS_PREFIX = "io.legado.app."

    // P0-S4 D11 实拦集：书源模式命中即拒绝，不随观察档放行（维持 JS 侧 Cookie 隔离语义）
    private val bookSourceProtectedClassNames = setOf(
        "android.webkit.CookieManager",
        "android.webkit.CookieSyncManager",
    )

    // 书源模式可重入深度与源标识（ThreadLocal，Rhino 求值同线程完成；finally 恢复防残留）
    private val bookSourcePolicyDepth = ThreadLocal<Int>()
    private val bookSourceLabel = ThreadLocal<String?>()

    /**
     * D13 类访问观察者：modules 层不可依赖 app 模块 AppLog，由 app 模块启动时注册实现回写日志
     */
    interface ClassAccessObserver {
        fun onObserveClass(className: String, sourceLabel: String?)
        fun onBlockClass(className: String, sourceLabel: String?)
    }

    @Volatile
    var classAccessObserver: ClassAccessObserver? = null

    private fun policyDepth(): Int = bookSourcePolicyDepth.get() ?: 0

    fun currentBookSourceLabel(): String? = bookSourceLabel.get()

    /**
     * P0-S4 书源类策略包裹：enabled=false 直接执行（非书源上下文零行为变化）；
     * depth+1、label 非空才覆盖；finally 恢复 depth 与 label（可重入，防协程线程切换残留）
     */
    fun <T> withBookSourceClassPolicy(enabled: Boolean, sourceLabel: String?, block: () -> T): T {
        if (!enabled) {
            return block()
        }
        val depth = policyDepth() + 1
        bookSourcePolicyDepth.set(depth)
        val previousLabel = bookSourceLabel.get()
        if (!sourceLabel.isNullOrEmpty()) {
            bookSourceLabel.set(sourceLabel)
        }
        try {
            return block()
        } finally {
            if (depth <= 1) {
                bookSourcePolicyDepth.remove()
            } else {
                bookSourcePolicyDepth.set(depth - 1)
            }
            bookSourceLabel.set(previousLabel)
        }
    }

    private val protectedClassNamesMatcher by lazy {
        listOf(
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.net.URLClassLoader",
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessImpl",
            "java.lang.UNIXProcess",
            "java.io.File",
            "java.io.FileDescriptor",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.PrintStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.PrintWriter",
            "java.io.UnixFileSystem",
            "java.io.RandomAccessFile",
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream",
            "java.security.AccessController",
            "java.nio.file.Paths",
            "java.nio.file.Files",
            "java.nio.file.FileSystems",
            "java.util.Formatter",
            "sun.misc.Unsafe",
            "android.content.Intent",
            "android.provider.Settings",
            "android.app.ActivityThread",
            "android.app.AppGlobals",
            "android.os.Looper",
            "android.os.Process",
            "android.os.FileUtils",

            "cn.hutool.core.lang.JarClassLoader",
            "cn.hutool.core.lang.Singleton",
            "cn.hutool.core.util.RuntimeUtil",
            "cn.hutool.core.util.ClassLoaderUtil",
            "cn.hutool.core.util.ReflectUtil",
            "cn.hutool.core.util.SerializeUtil",
            "cn.hutool.core.util.ClassUtil",
            "org.mozilla.javascript.DefiningClassLoader",
            "io.legado.app.data.AppDatabase",
            "io.legado.app.data.AppDatabase_Impl",
            "io.legado.app.data.AppDatabaseKt",
            "io.legado.app.utils.ContextExtensionsKt",
            "androidx.core.content.FileProvider",
            "splitties.init.AppCtxKt",
            "okio.JvmSystemFileSystem",
            "okio.JvmFileHandle",
            "okio.NioSystemFileSystem",
            "okio.NioFileSystemFileHandle",
            "okio.Path",

            "android.system",
            "android.database",
            "androidx.sqlite.db",
            "androidx.room",
            "cn.hutool.core.io",
            "cn.hutool.core.bean",
            "cn.hutool.core.lang.reflect",
            "dalvik.system",
            "java.nio.file",
            "java.lang.reflect",
            "java.lang.invoke",
            "io.legado.app.data.dao",
            "com.script",
            "org.mozilla",
            "sun",
            "libcore",
        ).let { ClassNameMatcher(it) }
    }

    private val systemClassProtectedName by lazy {
        Collections.unmodifiableSet(hashSetOf("load", "loadLibrary", "exit"))
    }

    private val protectedClasses by lazy {
        arrayOf(
            ClassLoader::class.java,
            Class::class.java,
            Member::class.java,
            Context::class.java,
            ObjectInputStream::class.java,
            ObjectOutputStream::class.java,
            okio.FileSystem::class.java,
            okio.FileHandle::class.java,
            okio.Path::class.java,
            android.content.Context::class.java,
        ) + if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            arrayOf(FileSystem::class.java, Path::class.java)
        } else {
            emptyArray()
        }
    }

    fun visibleToScripts(obj: Any): Boolean {
        when (obj) {
            is ClassLoader,
            is Class<*>,
            is Member,
            is Context,
            is ObjectInputStream,
            is ObjectOutputStream,
            is okio.FileSystem,
            is okio.FileHandle,
            is okio.Path,
            is android.content.Context -> return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (obj) {
                is FileSystem,
                is Path -> return false
            }
        }
        return visibleToScripts(obj.javaClass.name)
    }

    fun visibleToScripts(clazz: Class<*>): Boolean {
        protectedClasses.forEach {
            if (it.isAssignableFrom(clazz)) {
                return false
            }
        }
        return true
    }

    fun wrapJavaClass(scope: Scriptable, javaClass: Class<*>): Scriptable {
        return when (javaClass) {
            System::class.java -> {
                ProtectedNativeJavaClass(scope, javaClass, systemClassProtectedName)
            }

            else -> ProtectedNativeJavaClass(scope, javaClass)
        }
    }

    override fun visibleToScripts(fullClassName: String): Boolean {
        // 前置 matcher 段不变：全局防护行为不变
        if (protectedClassNamesMatcher.match(fullClassName)) {
            return false
        }
        // P0-S4 书源模式段（depth>0）：
        // ① D11 实拦：CookieManager/CookieSyncManager 命中即拒（观察者回写限流采样日志）
        // ② D5 观察放行：宿主 App 类放行+观察计数（首期不阻断，SourceGuard 数据驱动二期白名单）
        if (policyDepth() > 0) {
            if (fullClassName in bookSourceProtectedClassNames) {
                classAccessObserver?.onBlockClass(fullClassName, currentBookSourceLabel())
                return false
            }
            if (fullClassName.startsWith(APP_CLASS_PREFIX)) {
                classAccessObserver?.onObserveClass(fullClassName, currentBookSourceLabel())
                return true
            }
        }
        return true
    }

}