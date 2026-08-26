package io.legado.app.help.download

import java.io.IOException

/** 携带错误码的下载异常，供引擎内部精确标记失败原因 */
class DownloadException(val code: DownloadError, message: String) : IOException(message)

/**
 * 下载失败错误码（download-manager-maturity FR-7 / AD-04）
 *
 * 失败原因归一为枚举 name 落库（DownloadTaskEntity.errorCode），
 * 管理页 / 通知按 name 映射字符串资源展示（无硬编码中文）。
 */
enum class DownloadError {
    /** 服务返回非成功状态码（404/403/500…） */
    HTTP,

    /** 本地文件读写失败（目录无权限/磁盘满） */
    IO,

    /** 网络异常/超时/断连（瞬时，可自动重试） */
    NETWORK,

    /** 加密流（非 AES-128：SAMPLE-AES/DRM 等）当前不支持；标准 AES-128 已支持解密下载 */
    ENCRYPT,

    /** ts→mp4 重封装失败（native 边界，已保守回退） */
    NATIVE_REMUX,

    /** 协议/格式不支持（非 m3u8 密码流、无法解析清单等） */
    UNSUPPORTED,

    /** 完整性校验失败（下载 size 与预期不匹配） */
    INCOMPLETE
}