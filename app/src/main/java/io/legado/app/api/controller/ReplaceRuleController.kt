package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.service.kernel.ReplaceRuleKernel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking

/**
 * 替换规则域 Web 门面（一期 · 2.3.2）：解析 → 调 [ReplaceRuleKernel] → 装信封。
 *
 * ⚠️ **已知口径（pre-existing，本次仅搬家未改）**：`saveRule` / `delete` 在**成功**时仍返回
 * 「默认信封」（`isSuccess=false` + `errorMsg="未知错误,请联系开发者!"`）—— 这是改造前就存在的行为，
 * 属"成功却回错误态"的语义缺陷。本期**只搬家不改行为**（改它会动到老 vue 页的判定），
 * 已登记为后续行为对齐候选（见 tasks §2.3.2 备注）。
 */
object ReplaceRuleController {

    val allRules: ReturnData
        get() = ReturnData().setData(GSON.toJson(runBlocking { ReplaceRuleKernel.allRules() }))


    fun saveRule(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val rule = GSON.fromJsonObject<ReplaceRule>(postData).getOrNull()
        if (rule == null) {
            returnData.setErrorMsg("格式不对")
        } else {
            runBlocking { ReplaceRuleKernel.saveRule(rule) }
        }
        return returnData
    }


    fun delete(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val rule = GSON.fromJsonObject<ReplaceRule>(postData).getOrNull()
        if (rule == null) {
            returnData.setErrorMsg("格式不对")
        } else {
            runBlocking { ReplaceRuleKernel.deleteRule(rule) }
        }
        return returnData
    }

    /**
     * 传入测试数据格式
     * {
     *  rule: Replace,
     *  text: "xxx"
     * }
     */
    fun testRule(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val map = GSON.fromJsonObject<Map<String, *>>(postData).getOrNull()
            ?: return returnData.setErrorMsg("格式不对")
        val rule = map["rule"]?.let {
            if (it is String) {
                GSON.fromJsonObject<ReplaceRule>(it).getOrNull()
            } else {
                GSON.fromJsonObject<ReplaceRule>(GSON.toJson(it)).getOrNull()
            }
        }
        if (rule == null) {
            returnData.setErrorMsg("格式不对")
            return returnData
        }
        if (rule.pattern.isEmpty()) {
            returnData.setErrorMsg("替换规则不能为空")
        }
        val text = map["text"] as String
        // 与原实现一致：即便 pattern 为空也会走完试算并 setData（故最终仍是成功态）
        returnData.setData(ReplaceRuleKernel.testRule(rule, text))
        return returnData
    }

}
