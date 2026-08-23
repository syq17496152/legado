package io.legado.app.lib.cloud

data class S3BackupIndex(
    val items: List<S3BackupIndexItem> = emptyList()
)

data class S3BackupIndexItem(
    val fileName: String = "",
    val time: Long = 0,
    val size: Long = 0,
    val containerId: String = ""
)

