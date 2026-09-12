package io.legado.app.base

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.viewbinding.ViewBinding

/**
 * 纯 Compose 页面的合成 ViewBinding 空壳工厂（compose-shell-binding-fix，2026-09-12）。
 *
 * ⚠️ 语言陷阱说明（勿删）：ViewBinding 是 Java 接口，其 getRoot() 在 Kotlin 中暴露为
 * 合成属性 `root`。若手写 `object : ViewBinding { override fun getRoot() = root }` 且
 * `root` 是外层 Activity 的类级属性，标识符解析会命中匿名对象自身的合成属性（即 getRoot
 * 本身）而非外层属性，编译期无告警，运行期无限自递归 StackOverflowError
 * （铁证 crash-2026-09-12-11-34-19，dexdump：getRoot 的 invoke-virtual 调用自身）。
 *
 * 本工厂把 root 创建收进函数局部作用域——局部变量优先于接口合成属性解析，
 * 结构上不可能再产生自递归。新纯 Compose 页面一律经此工厂创建 binding，禁止手写匿名壳。
 */
fun composeShell(context: Context): ViewBinding {
    val root = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
    return object : ViewBinding {
        override fun getRoot(): View = root
    }
}
