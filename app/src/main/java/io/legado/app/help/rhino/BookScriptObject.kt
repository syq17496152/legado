package io.legado.app.help.rhino

import com.script.rhino.JavaObjectWrapFactory
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable

/**
 * Book 的 Rhino 包装对象，拦截 setUseReplaceRule 防书源 JS 篡改替换规则开关。
 * 仅对 Book 类型实例生效（App.initRhino 中注册），其他成员访问不受影响。
 */
class BookScriptObject(scope: Scriptable?, javaObject: Any, staticType: Class<*>?) :
    NativeJavaObject(scope, javaObject, staticType) {

    override fun has(name: String, start: Scriptable): Boolean {
        if (name == SET_USE_REPLACE_RULE) {
            return false
        }
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (name == SET_USE_REPLACE_RULE) {
            return NOT_FOUND
        }
        return super.get(name, start)
    }

    companion object {
        private const val SET_USE_REPLACE_RULE = "setUseReplaceRule"

        val factory = JavaObjectWrapFactory { scope, javaObject, staticType ->
            BookScriptObject(scope, javaObject, staticType)
        }
    }
}
