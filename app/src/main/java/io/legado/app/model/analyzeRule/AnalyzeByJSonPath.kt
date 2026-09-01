package io.legado.app.model.analyzeRule

import androidx.annotation.Keep
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.ReadContext
import io.legado.app.constant.AppLog
import io.legado.app.utils.printOnDebug


@Suppress("RegExpRedundantEscape")
@Keep
class AnalyzeByJSonPath(json: Any) {

    companion object {

        fun parse(json: Any): ReadContext {
            return when (json) {
                is ReadContext -> json
                is String -> JsonPath.parse(json) //JsonPath.parse<String>(json)
                else -> JsonPath.parse(json) //JsonPath.parse<Any>(json)
            }
        }
    }

    private var ctx: ReadContext = parse(json)

    /**
     * 改进解析方法
     * 解决阅读”&&“、”||“与jsonPath支持的”&&“、”||“之间的冲突
     * 解决{$.rule}形式规则可能匹配错误的问题，旧规则用正则解析内容含‘}’的json文本时，用规则中的字段去匹配这种内容会匹配错误.现改用平衡嵌套方法解决这个问题
     * */
    fun getString(rule: String): String? {
        if (rule.isEmpty()) return null
        AppLog.putDebugWithTag(AppLog.TAG_ANALYZE, "getString 入口 expr=${rule.take(100)}", level = AppLog.Level.INFO)
        var result: String
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||")

        if (rules.size == 1) {

            ruleAnalyzes.reSetPos() //将pos重置为0，复用解析器

            result = ruleAnalyzes.innerRule("{$.") { getString(it) } //替换所有{$.rule...}

            if (result.isEmpty()) { //st为空，表明无成功替换的内嵌规则
                try {
                    val ob = ctx.read<Any>(rule)
                    result = when {
                        // 修复：对象/Map 元素不能 joinToString（Map.toString 非法 JSON，下游解析全坏）；
                        // 字符串元素保持旧拼接语义，混合/对象元素序列化为合法 JSON 文本
                        ob is List<*> && ob.all { it is String || it == null } -> ob.joinToString("\n")
                        ob is List<*> -> org.json.JSONArray(ob).toString()
                        ob is Map<*, *> -> org.json.JSONObject(ob).toString()
                        else -> ob.toString()
                    }
                } catch (e: Exception) {
                    e.printOnDebug()
                    AppLog.putDebugWithTag(AppLog.TAG_ANALYZE, "JSONPath getString 解析失败 expr=${rule.take(100)}", e)
                }
            }
            return result
        } else {
            val textList = arrayListOf<String>()
            for (rl in rules) {
                val temp = getString(rl)
                if (!temp.isNullOrEmpty()) {
                    textList.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            return textList.joinToString("\n")
        }
    }

    internal fun getStringList(rule: String): List<String> {
        val result = ArrayList<String>()
        if (rule.isEmpty()) return result
        AppLog.putDebugWithTag(AppLog.TAG_ANALYZE, "getStringList 入口 expr=${rule.take(100)}", level = AppLog.Level.INFO)
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            ruleAnalyzes.reSetPos() //将pos重置为0，复用解析器
            val st = ruleAnalyzes.innerRule("{$.") { getString(it) } //替换所有{$.rule...}
            if (st.isEmpty()) { //st为空，表明无成功替换的内嵌规则
                try {
                    val obj = ctx.read<Any>(rule)
                    if (obj is List<*>) {
                        // F-5.2: JSONPath 列表项可能为 null，跳过避免 NPE
                        // 修复：对象/Map 元素转合法 JSON 字符串（toString 产生 Map 字符串破坏下游解析）
                        for (o in obj) {
                            when (o) {
                                null -> Unit
                                is Map<*, *> -> result.add(org.json.JSONObject(o).toString())
                                else -> result.add(o.toString())
                            }
                        }
                    } else if (obj != null) {
                        // F-5.2: JSONPath 匹配不到时 read 返回 null，判空避免 NPE
                        result.add(obj.toString())
                    }
                } catch (e: Exception) {
                    e.printOnDebug()
                    AppLog.putDebugWithTag(AppLog.TAG_ANALYZE, "JSONPath getStringList 解析失败 expr=${rule.take(100)}", e)
                }
            } else {
                result.add(st)
            }
            return result
        } else {
            val results = ArrayList<List<String>>()
            for (rl in rules) {
                val temp = getStringList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in results[0].indices) {
                        for (temp in results) {
                            if (i < temp.size) {
                                result.add(temp[i])
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        result.addAll(temp)
                    }
                }
            }
            return result
        }
    }

    internal fun getObject(rule: String): Any {
        return ctx.read(rule)
    }

    internal fun getList(rule: String): ArrayList<Any>? {
        val result = ArrayList<Any>()
        if (rule.isEmpty()) return result
        AppLog.putDebugWithTag(AppLog.TAG_ANALYZE, "getList 入口 expr=${rule.take(100)}", level = AppLog.Level.INFO)
        val ruleAnalyzes = RuleAnalyzer(rule, true) //设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")
        if (rules.size == 1) {
            ctx.let {
                try {
                    return it.read<ArrayList<Any>>(rules[0])
                } catch (e: Exception) {
                    e.printOnDebug()
                    AppLog.putDebugWithTag(AppLog.TAG_ANALYZE, "JSONPath getList 解析失败 expr=${rules[0].take(100)}", e)
                }
            }
        } else {
            val results = ArrayList<ArrayList<*>>()
            for (rl in rules) {
                val temp = getList(rl)
                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (temp.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.size > 0) {
                if ("%%" == ruleAnalyzes.elementsType) {
                    for (i in 0 until results[0].size) {
                        for (temp in results) {
                            if (i < temp.size) {
                                temp[i]?.let { result.add(it) }
                            }
                        }
                    }
                } else {
                    for (temp in results) {
                        result.addAll(temp)
                    }
                }
            }
        }
        return result
    }

}
