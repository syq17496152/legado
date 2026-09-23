package io.legado.app.model

import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject

/**
 * R11（B2，2026-09-23）：把脚本（Rhino）返回值转换为 **Gson 可安全序列化的 JSON 兼容结构**。
 *
 * 为什么必须转换（原实现直接 `GSON.toJson(result)`）：
 * 1. `NativeArray` 会被当成普通对象序列化成 `{"0":…,"1":…}` ⇒ **数组语义丢失**，
 *    下游 `parseActionsFromJson` 拿到的是对象而非数组，动作被静默丢弃；
 * 2. `NativeObject` 会把 `__proto__` 等 Rhino 内部键一并写出，污染协议字段；
 * 3. 脚本里常见的自引用（`obj.a = obj`）会让 Gson 抛栈溢出，而调用点用 `runCatching`
 *    吞掉异常 ⇒ **用户看到「脚本没报错但什么都没发生」**。
 *
 * 转换规则：
 * - `NativeArray` → `List`；`NativeObject` / `Map` → `LinkedHashMap<String, Any?>`（键 `toString`、剔除内部键）
 * - `Collection` / 数组（含原始类型数组）→ `List`
 * - 字符串/数字/布尔/字符 → 原值；其他 → `toString()`
 * - **循环引用**：按对象 identity 记录递归路径，命中即断链为 `null`（共享引用不受影响）
 *
 * @param seen 递归路径上的对象集合（调用方一般无需传参）
 */
internal fun toJsonCompatible(value: Any?, seen: MutableSet<Any> = mutableSetOf()): Any? {
    return when (value) {
        null -> null
        is String, is Number, is Boolean, is Char -> value
        is CharSequence -> value.toString()
        is NativeArray -> guardCycle(value, seen) {
            // Rhino 的 `getLength()` 返回 long ⇒ 显式收窄为 Int 索引（脚本数组长度不可能超 Int）
            (0 until value.length.toInt()).map { idx -> toJsonCompatible(value.get(idx, value), seen) }
        }
        is NativeObject -> guardCycle(value, seen) { nativeObjectToMap(value, seen) }
        is Map<*, *> -> guardCycle(value, seen) {
            val out = LinkedHashMap<String, Any?>()
            value.forEach { (k, v) ->
                val key = k?.toString() ?: return@forEach
                if (isInternalScriptKey(key)) return@forEach
                out[key] = toJsonCompatible(v, seen)
            }
            out
        }
        is Collection<*> -> guardCycle(value, seen) { value.map { toJsonCompatible(it, seen) } }
        is Array<*> -> guardCycle(value, seen) { value.map { toJsonCompatible(it, seen) } }
        else -> {
            val clazz = value.javaClass
            if (clazz.isArray) {
                guardCycle(value, seen) {
                    (0 until java.lang.reflect.Array.getLength(value)).map {
                        toJsonCompatible(java.lang.reflect.Array.get(value, it), seen)
                    }
                }
            } else {
                value.toString()
            }
        }
    }
}

/** 循环引用防护：进入时登记 identity，退出时撤销（保证共享引用可正常展开）。 */
private inline fun guardCycle(identity: Any, seen: MutableSet<Any>, block: () -> Any?): Any? {
    if (!seen.add(identity)) return null
    return try {
        block()
    } finally {
        seen.remove(identity)
    }
}

/** Rhino 内部键（属原型链/引擎元数据，不得进入协议数据）。 */
private fun isInternalScriptKey(key: String): Boolean = key == "__proto__" || key == "prototype"

private fun nativeObjectToMap(obj: NativeObject, seen: MutableSet<Any>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (id in obj.ids) {
        val key = id?.toString() ?: continue
        if (isInternalScriptKey(key)) continue
        out[key] = toJsonCompatible(obj.get(key, obj), seen)
    }
    return out
}