package io.legado.app.help.rhino

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.script.rhino.RhinoClassShutter
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * P0-S4 类导入策略灰度单测（分册 §9.1 编号 15-19）
 *
 * 覆盖 RhinoClassShutter.withBookSourceClassPolicy（D5 观察档可重入 + finally 恢复）、
 * visibleToScripts(fullClassName) 书源模式段（D11 实拦 + D5 观察放行）、
 * 非书源模式零行为变化、前置保护 matcher 恒拦；
 * 以及 BookSourceGuardLog V6 计数化断言（AtomicLong 累加 / 首条+10 幂次节流 / LRU 上限淘汰 / 分钟限流 / 开关门控）。
 *
 * JVM 说明：仅使用 visibleToScripts(String) 版本（不触及 android.os.Build 分支）；
 * BookSourceGuardLog 依赖 AppConfig（appCtx 读 SP），测试注入最小 FakePrefsContext 穿透 splitties appCtx，
 * fake SP 对 PreferKey.recordLog 返回 true 使观察日志进入 AppLog.logs 可断言（纯 JVM，无文件写入）。
 */
class RhinoClassShutterTest {

    /** 最小 fake SP：getBoolean 支持 overrides + recordLog 强制 true（供 AppLog 内存日志断言） */
    private object FakePrefs : SharedPreferences {

        @Volatile
        var overrides: Map<String, Boolean> = emptyMap()

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            if (key == null) return defValue
            overrides[key]?.let { return it }
            return if (key == PreferKey.recordLog) true else defValue
        }

        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = error("测试路径不应触发写操作")
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    /**
     * 最小 fake Context：继承 Application 绕过 splitties canLeakMemory 的 ContextWrapper baseContext
     * 递归检查（Application 分支直接判定不泄漏），覆写 SP 读取链路所需方法
     */
    private class FakePrefsContext : android.app.Application() {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = FakePrefs
        override fun getPackageName(): String = "io.legado.app.test"
        override fun getApplicationContext(): Context = this
    }

    private val observed = mutableListOf<Pair<String, String?>>()
    private val blocked = mutableListOf<Pair<String, String?>>()

    companion object {
        // AppConfig 可用性探针：全量运行时既有测试类可能先于本类触发 AppConfig <clinit>
        // （appCtx 未注入 → splitties getProcessName 加载 android.app.ActivityThread 失败 →
        // <clinit> 失败被 JVM 永久标记，注入无法挽回）。此时 guardLog 用例 Assume 降级跳过
        // 登记 L2；类过滤独立运行（注入先行）时完整验证。
        private var appConfigUsable = false

        @BeforeClass
        @JvmStatic
        fun initFakeAppCtx() {
            appConfigUsable = kotlin.runCatching {
                // splitties injectAsAppCtx 为 Kotlin internal（JVM public），反射注入最小 fake Context
                val inject = Class.forName("splitties.init.AppCtxKt")
                    .getMethod("injectAsAppCtx", Context::class.java)
                inject.invoke(null, FakePrefsContext())
                // 立即验证 AppConfig 可完成 <clinit>（fake SP 全链路走通）
                Class.forName("io.legado.app.help.config.AppConfig")
                true
            }.getOrDefault(false)
        }

        private fun assumeGuardLogRunnable() {
            org.junit.Assume.assumeTrue(
                "本 JVM 中 AppConfig <clinit> 已被先前测试类破坏（appCtx 未及注入），" +
                    "guardLog 日志/计数断言降级 L2 真机验证",
                appConfigUsable
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun observeCounterValue(label: String, className: String): Long {
            val field = BookSourceGuardLog::class.java.getDeclaredField("observeCounters")
            field.isAccessible = true
            val map = field.get(null) as ConcurrentHashMap<String, AtomicLong>
            return map["$label|$className"]?.get() ?: 0L
        }

        private fun reportedKeysSize(): Int {
            val field = BookSourceGuardLog::class.java.getDeclaredField("reportedKeys")
            field.isAccessible = true
            val map = field.get(null) as Map<String, *>
            return synchronized(map) { map.size }
        }
    }

    @Before
    fun setUp() {
        BookSourceGuardLog.reset()
        observed.clear()
        blocked.clear()
        RhinoClassShutter.classAccessObserver = object : RhinoClassShutter.ClassAccessObserver {
            override fun onObserveClass(className: String, sourceLabel: String?) {
                observed.add(className to sourceLabel)
            }

            override fun onBlockClass(className: String, sourceLabel: String?) {
                blocked.add(className to sourceLabel)
            }
        }
    }

    @After
    fun tearDown() {
        // 进程级单例状态用例间清态，防跨测试类污染
        RhinoClassShutter.classAccessObserver = null
        BookSourceGuardLog.reset()
        FakePrefs.overrides = emptyMap()
    }

    // ============ 分册 #15 重入与 finally 恢复 ============

    @Test
    fun withBookSourceClassPolicy_reentrantDepthRestoredInFinally() {
        RhinoClassShutter.withBookSourceClassPolicy(true, "L1") {
            // depth=1：D11 实拦集命中（depth>0 才进入书源段）
            assertFalse(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
            RhinoClassShutter.withBookSourceClassPolicy(true, "L2") {
                // 嵌套内：label 覆盖、depth>0
                assertEquals("L2", RhinoClassShutter.currentBookSourceLabel())
                assertFalse(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
                assertTrue(RhinoClassShutter.visibleToScripts("io.legado.app.help.BookHelp"))
            }
            // 内层 finally：label 回滚 L1，depth 仍 >0（外层未退出）
            assertEquals("L1", RhinoClassShutter.currentBookSourceLabel())
            assertFalse(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
        }
        // 外层 finally：depth/label 完全复位（depth<=1 remove）
        assertNull(RhinoClassShutter.currentBookSourceLabel())
        assertTrue("depth=0 时 CookieManager 与升级前一致（放行）", RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
    }

    // ============ 分册 #16 观察放行 + V6 计数化 ============

    @Test
    fun visibleToScripts_bookSourceMode_appClassObservedAndAllowed() {
        RhinoClassShutter.withBookSourceClassPolicy(true, "nsA") {
            assertTrue(RhinoClassShutter.visibleToScripts("io.legado.app.help.BookHelp"))
        }
        // 观察者收到（D5 数据来源），无实拦误报
        assertEquals(listOf("io.legado.app.help.BookHelp" to "nsA"), observed)
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun guardLog_observeClass_countsAccumulateAndThrottlesAtPowersOfTen() {
        AppLog.clear()
        BookSourceGuardLog.reset()
        val cls = "io.legado.app.help.BookHelp"
        fun logCount(): Int = AppLog.logs.count {
            it.message.startsWith("observeClass ns=nsV6 ") && it.message.contains("class=$cls ")
        }
        BookSourceGuardLog.observeClass("nsV6", cls)
        assertEquals("首条仅 1 次（count=1）", 1, logCount())
        repeat(8) { BookSourceGuardLog.observeClass("nsV6", cls) }
        assertEquals("2..9 次不记", 1, logCount())
        BookSourceGuardLog.observeClass("nsV6", cls)
        assertEquals("第 10 次（10 的幂次）记累计频次", 2, logCount())
        repeat(89) { BookSourceGuardLog.observeClass("nsV6", cls) }
        assertEquals("11..99 次不记", 2, logCount())
        BookSourceGuardLog.observeClass("nsV6", cls)
        assertEquals("第 100 次记累计频次", 3, logCount())
        assertEquals("AtomicLong 计数累加到 100", 100L, observeCounterValue("nsV6", cls))
    }

    @Test
    fun guardLog_observeClass_lruCapEvictsOldestKey() {
        AppLog.clear()
        BookSourceGuardLog.reset()
        // MAX_REPORTED_KEYS=512：513 个不同键触发最旧键淘汰
        for (i in 1..513) BookSourceGuardLog.observeClass("k$i", "cls")
        assertEquals("去重键 LRU 上限 512", 512, reportedKeysSize())
        AppLog.clear()
        // 被淘汰的最旧键 k1：首报状态重置 → 再次记日志
        BookSourceGuardLog.observeClass("k1", "cls")
        assertEquals(1, AppLog.logs.count { it.message.contains("observeClass ns=k1 ") })
        // 对照：未淘汰键 k512（isFirstReport=false 且 count 非幂次）不记新日志
        BookSourceGuardLog.observeClass("k512", "cls")
        assertEquals(0, AppLog.logs.count { it.message.contains("observeClass ns=k512 ") })
    }

    @Test
    fun guardLog_blockedClass_rateLimitedPerMinute() {
        AppLog.clear()
        BookSourceGuardLog.blockedClass("nsW", "android.webkit.CookieManager")
        BookSourceGuardLog.blockedClass("nsW", "android.webkit.CookieManager")
        val count = AppLog.logs.count { it.message.contains("blockedClass ns=nsW ") }
        assertEquals("实拦事件每键每分钟仅 1 条限流采样", 1, count)
    }

    @Test
    fun guardLog_observeClass_switchOffIsSilent() {
        assumeGuardLogRunnable()
        // 开关 bookSourceClassPolicyLog 仅门控观察日志（实拦事件始终记录）
        FakePrefs.overrides = mapOf(PreferKey.bookSourceClassPolicyLog to false)
        try {
            AppLog.clear()
            BookSourceGuardLog.observeClass("nsOff", "io.legado.app.help.BookHelp")
            assertEquals(0, AppLog.logs.count { it.message.contains("observeClass ns=nsOff ") })
        } finally {
            FakePrefs.overrides = emptyMap()
        }
    }

    // ============ 分册 #17 D11 实拦 ============

    @Test
    fun visibleToScripts_bookSourceMode_cookieManagerBlocked() {
        RhinoClassShutter.withBookSourceClassPolicy(true, "nsB") {
            assertFalse(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
            assertFalse(RhinoClassShutter.visibleToScripts("android.webkit.CookieSyncManager"))
        }
        val blockedClasses = blocked.map { it.first }.toSet()
        assertEquals(
            setOf("android.webkit.CookieManager", "android.webkit.CookieSyncManager"),
            blockedClasses
        )
        assertEquals(listOf("nsB", "nsB"), blocked.map { it.second })
        assertTrue("实拦不应误入观察通道", observed.isEmpty())
    }

    // ============ 分册 #18 非书源模式零行为变化 ============

    @Test
    fun visibleToScripts_nonBookSourceMode_unchanged() {
        // depth=0：与升级前逐行为一致（保护表拒、其余放行、零回调）
        assertTrue(RhinoClassShutter.visibleToScripts("io.legado.app.help.BookHelp"))
        assertTrue(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
        assertFalse(RhinoClassShutter.visibleToScripts("java.io.File"))
        assertFalse(RhinoClassShutter.visibleToScripts("org.mozilla.javascript.DefiningClassLoader"))
        assertTrue(RhinoClassShutter.visibleToScripts("com.example.Anything"))
        assertTrue(observed.isEmpty())
        assertTrue(blocked.isEmpty())
        // enabled=false 直接执行：不进策略段（label/depth 不动、零回调）
        RhinoClassShutter.withBookSourceClassPolicy(false, "L1") {
            assertNull(RhinoClassShutter.currentBookSourceLabel())
            assertTrue(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
        }
        assertTrue(observed.isEmpty())
        assertTrue(blocked.isEmpty())
    }

    // ============ 分册 #19 保护 matcher 恒拦 ============

    @Test
    fun visibleToScripts_protectedMatcher_alwaysBlocked() {
        // depth=0：前置 matcher 段命中即拒
        assertFalse(RhinoClassShutter.visibleToScripts("java.io.File"))
        // depth>0（书源模式）：前置 matcher 段仍先于书源段，无论模式必拒且不走观察者
        RhinoClassShutter.withBookSourceClassPolicy(true, "nsC") {
            assertFalse(RhinoClassShutter.visibleToScripts("java.io.File"))
            assertFalse(RhinoClassShutter.visibleToScripts("java.lang.Runtime"))
            assertFalse(RhinoClassShutter.visibleToScripts("io.legado.app.data.AppDatabase"))
        }
        assertTrue(observed.isEmpty())
        assertTrue(blocked.isEmpty())
    }

    // ============ E6/方向⑤ TTS 脚本档四象限（tasks 2.18） ============

    @Test
    fun visibleToScripts_ttsPolicy_fourQuadrants() {
        // ① TTS 档：宿主 App 前缀=实拦（观察放行升级为拒绝，红队 R5 前提）
        RhinoClassShutter.withTtsScriptClassPolicy("tts:1") {
            assertFalse(RhinoClassShutter.visibleToScripts("io.legado.app.help.config.AppConfig"))
            assertFalse(RhinoClassShutter.visibleToScripts("io.legado.app.model.ReadBook"))
        }
        // ② 书源档：宿主 App 前缀=观察放行（书源档行为逐字节零变化）
        RhinoClassShutter.withBookSourceClassPolicy(true, "ns") {
            assertTrue(RhinoClassShutter.visibleToScripts("io.legado.app.help.config.AppConfig"))
        }
        // ③ D11 实拦集两档均拒（Cookie 隔离语义在 TTS 档同样收紧）
        RhinoClassShutter.withTtsScriptClassPolicy("tts:1") {
            assertFalse(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
        }
        RhinoClassShutter.withBookSourceClassPolicy(true, "ns") {
            assertFalse(RhinoClassShutter.visibleToScripts("android.webkit.CookieManager"))
        }
        // ④ 无档：非 matcher 命中的普通类放行（行为不变）
        assertTrue(RhinoClassShutter.visibleToScripts("com.example.Anything"))
        // TTS 档非 app 前缀 Java 类维持放行现状（措辞收敛：仅宿主 app 类实拦，红队 R2-6）
        RhinoClassShutter.withTtsScriptClassPolicy("tts:1") {
            assertTrue(RhinoClassShutter.visibleToScripts("org.json.JSONObject"))
        }
        // finally 恢复：出档后 app 前缀回到无档放行（无档位残留）
        assertTrue(RhinoClassShutter.visibleToScripts("io.legado.app.help.config.AppConfig"))
        // 拦截回调账目：TTS 档 app 前缀×2 + TTS 档 CookieManager×1 + 书源档 CookieManager×1 = 4；
        // 书源档 app 前缀观察放行走 observed
        assertEquals(4, blocked.size)
        assertTrue(observed.isNotEmpty())
    }
}
