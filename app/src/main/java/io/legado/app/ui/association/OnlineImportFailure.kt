package io.legado.app.ui.association

import java.io.IOException

/**
 * 在线导入失败原因（F353）。
 *
 * 背景（2026-09-21 源码复核发现的既有缺陷）：原实现把**英文内部消息**直接抛成 IOException，
 * 再经 `error.localizedMessage` 原样塞进错误弹窗 ⇒ 中文用户看到的是
 * "Import download failed with HTTP 404" 这类英文；且消息与逻辑耦合，无法按语言取资源。
 * 现改为：域层（路由解析 / 下载校验 / 包解析）只抛**语义 kind**（英文消息仅留给日志），
 * UI 层按 kind 取本地化资源 —— 中英双语用户都拿到可读原因。
 */
enum class OnlineImportFailureKind {
    /** 导入地址不是 http/https */
    SOURCE_URL_INVALID,

    /** 导入地址携带账号密码 */
    CREDENTIALS_NOT_ALLOWED,

    /** 导入地址指向 localhost */
    LOCALHOST_NOT_ALLOWED,

    /** 回环 / 链路本地 / 组播等不允许的地址（detail = host） */
    UNSAFE_ADDRESS,

    /** 域名无法解析（detail = host） */
    HOST_UNRESOLVED,

    /** 服务端返回非成功状态码（detail = 状态码） */
    HTTP_STATUS,

    /** 导入包超出大小上限（detail = 上限字节数） */
    TOO_LARGE,

    /** 下载内容为空 */
    EMPTY_DOWNLOAD,

    /** 跳转地址非法 */
    REDIRECT_INVALID,

    /** HTTPS 跳转到 HTTP */
    REDIRECT_DOWNGRADE,

    /** 跳转次数过多 */
    TOO_MANY_REDIRECTS,

    /** 不允许经代理导入 */
    PROXY_NOT_ALLOWED,

    /** 导入连接无法建立（路由信息缺失） */
    ROUTE_UNAVAILABLE,

    /** 链接 host 不是 import */
    HOST_NOT_IMPORT,

    /** 链接缺少 src 参数 */
    SRC_MISSING,

    /** 导入包结构非法（JSON / 必需字段） */
    PACKAGE_MALFORMED,

    /** 导入包格式或版本不受支持 */
    PACKAGE_UNSUPPORTED,

    /** 导入包内规则字段非法 */
    PACKAGE_RULE_INVALID,

    /** 导入包条数 / 长度 / 变量数超限 */
    PACKAGE_LIMIT_EXCEEDED
}

/**
 * 在线导入失败异常：`kind` 供 UI 取本地化文案，`detail` 供文案占位（如状态码 / 上限），
 * `message` 保持英文供日志排查（不进用户视野）。
 */
class OnlineImportFailureException(
    val kind: OnlineImportFailureKind,
    val detail: String? = null,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)