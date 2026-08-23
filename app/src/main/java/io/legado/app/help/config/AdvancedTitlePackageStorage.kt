package io.legado.app.help.config

import java.io.File

internal object AdvancedTitlePackageStorage {

    private const val MAX_STALE_STAGING_DIRECTORIES = 256
    private val stagingNamePattern = Regex(
        "^\\.[A-Za-z0-9_-]{1,64}\\.staging-[0-9a-fA-F-]{36}$"
    )

    fun requireDirectoryMatchesId(
        directoryName: String,
        configId: String,
        requireMatch: Boolean
    ) {
        if (requireMatch) {
            require(configId == directoryName) {
                "Advanced title directory does not match its id"
            }
        }
    }

    fun cleanupStaleStagingDirectories(root: File): Int {
        if (!root.isDirectory) return 0
        val parent = runCatching { root.canonicalFile }.getOrNull() ?: return 0
        var deleted = 0
        parent.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory && isStagingDirectoryName(it.name) }
            .take(MAX_STALE_STAGING_DIRECTORIES)
            .forEach { directory ->
                if (deleteStagingDirectory(parent, directory)) deleted++
            }
        return deleted
    }

    fun deleteStagingDirectory(root: File, directory: File): Boolean {
        if (!isStagingDirectoryName(directory.name)) return false
        val parent = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val target = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        if (target.parentFile != parent) return false
        if (!target.exists()) return true
        return runCatching {
            target.deleteRecursively() || !target.exists()
        }.getOrDefault(false)
    }

    fun isStagingDirectoryName(name: String): Boolean = stagingNamePattern.matches(name)
}