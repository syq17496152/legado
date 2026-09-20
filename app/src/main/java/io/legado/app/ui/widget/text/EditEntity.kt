package io.legado.app.ui.widget.text

import splitties.init.appCtx

data class EditEntity(
    var key: String,
    var value: String?,
    var hint: String,
    val viewType: Int = 0,
    /** F155：必填项（表单页在 label 上给出显式标记，避免「哪几个必填」靠记忆） */
    val required: Boolean = false
) {

    constructor(
        key: String,
        value: String?,
        hint: Int,
        viewType: Int = 0,
        required: Boolean = false
    ) : this(
        key,
        value,
        appCtx.getString(hint),
        viewType,
        required
    )

    /**
     * F155：字段级错误文案（保存校验失败时由页面写入，编辑器渲染为 error 态）。
     *
     * 不放进构造参数：存量 20+ 调用点只需必填项多传一个 `required`，错误态按需设置。
     */
    var error: String? = null

    @Suppress("unused")
    object ViewType {

        const val checkBox = 1
        const val textVideoOnly = 2

    }

}