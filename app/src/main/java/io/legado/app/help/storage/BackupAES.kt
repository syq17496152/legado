package io.legado.app.help.storage

import cn.hutool.crypto.symmetric.AES
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.MD5Utils

class BackupAES : AES(
    MD5Utils.md5Encode(LocalConfig.password ?: "").encodeToByteArray(0, 16)
) {

    companion object {

        /**
         * 备份导出时需 AES 加密的敏感 SP 键单一权威源（导出/导入路径共用）。
         * - web_dav_password：既有先例
         * - aiProviderList：P1-A1-1/A1-2 密钥防线（备份文件禁明文携带供应商 apiKey）
         */
        val sensitivePrefKeys = setOf(
            PreferKey.webDavPassword,
            PreferKey.aiProviderList
        )
    }
}