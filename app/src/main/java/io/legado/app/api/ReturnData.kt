package io.legado.app.api

import androidx.annotation.Keep

@Keep
class ReturnData {

    var isSuccess: Boolean = false
        private set

    var errorMsg: String = "未知错误,请联系开发者!"
        private set

    var data: Any? = null
        private set

    /**
     * 业务 / HTTP 状态码（web-mcp-productization 一期 · 3.1 / REQ-1-301）。
     *
     * **新增字段**：`isSuccess` / `errorMsg` / `data` 三字段语义**保持不变**（REQ-1-302 ⇒ 老 vue 页零改动）；
     * 默认 200 ⇒ 未显式设置时响应仍为 HTTP 200，与改造前行为一致。
     */
    var code: Int = 200
        private set

    fun setErrorMsg(errorMsg: String): ReturnData {
        this.isSuccess = false
        this.errorMsg = errorMsg
        return this
    }

    fun setData(data: Any): ReturnData {
        this.isSuccess = true
        this.errorMsg = ""
        this.data = data
        return this
    }

    /** 设置业务 / HTTP 状态码（与响应状态码一致，由 [io.legado.app.web.api.ApiEnvelope] / 鉴权层调用）。 */
    fun setCode(code: Int): ReturnData {
        this.code = code
        return this
    }
}
