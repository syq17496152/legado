package io.legado.app.lib.cloud

enum class S3ContainerScope(val key: String) {
    DEFAULT("default"),
    THEME("theme"),
    TOP_BAR("topBar"),
    NAVIGATION_BAR("navigationBar"),
    COVER_COLLECTION("coverCollection"),
    BUBBLE("bubble"),
    CACHE("cache"),
    MAIN_BACKUP("backup");

    companion object {
        fun from(value: String?): S3ContainerScope {
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.key.equals(value, ignoreCase = true)
            } ?: DEFAULT
        }
    }
}

