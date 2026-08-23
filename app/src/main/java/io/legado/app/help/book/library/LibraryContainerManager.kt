package io.legado.app.help.book.library

import io.legado.app.constant.PreferKey
import io.legado.app.constant.EventBus
import io.legado.app.lib.cloud.S3Container
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object LibraryContainerManager {

    private const val SCOPE_DEFAULT = "default"
    private val lock = Any()

    fun containers(): List<LibraryContainerConfig> = synchronized(lock) {
        GSON.fromJsonObject<List<LibraryContainerConfig>>(appCtx.getPrefString(PreferKey.libraryS3Containers))
            .getOrDefault(emptyList())
            .map { it.normalized() }
            .filter { it.id.isNotBlank() }
    }

    fun enabledContainers(): List<LibraryContainerConfig> {
        return containers().filter { it.container.enabled }
    }

    fun selected(): LibraryContainerConfig? {
        val items = enabledContainers()
        if (items.isEmpty()) return null
        val selectedId = selectedId()
        return items.firstOrNull { it.id == selectedId } ?: items.firstOrNull()
    }

    fun readContainer(): LibraryContainerConfig? {
        return selected()
    }

    fun selectedId(): String? {
        return selections()[SCOPE_DEFAULT]
    }

    fun select(id: String?) = synchronized(lock) {
        val selections = selections().toMutableMap()
        if (id.isNullOrBlank()) {
            selections.remove(SCOPE_DEFAULT)
        } else {
            selections[SCOPE_DEFAULT] = id
        }
        saveSelectionsLocked(selections)
        notifyChangedLocked()
    }

    fun upsert(config: LibraryContainerConfig): LibraryContainerConfig = synchronized(lock) {
        val normalized = config.normalized().let {
            if (it.id.isBlank()) it.copy(container = it.container.copy(id = S3Container.newId())) else it
        }
        val items = containers().toMutableList()
        val index = items.indexOfFirst { it.id == normalized.id }
        if (index >= 0) items[index] = normalized else items += normalized
        saveContainersLocked(items)
        if (selected() == null) select(normalized.id)
        notifyChangedLocked()
        normalized
    }

    fun delete(id: String) = synchronized(lock) {
        saveContainersLocked(containers().filterNot { it.id == id })
        if (selectedId() == id) select(containers().firstOrNull { it.id != id }?.id)
        notifyChangedLocked()
    }

    fun updateUsage(id: String, usedBytes: Long): LibraryContainerConfig? = synchronized(lock) {
        val items = containers().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val current = items[index]
        val capacityBytes = current.container.capacityBytes()
        val updated = current.copy(
            container = current.container.copy(
                usedBytes = usedBytes.coerceAtLeast(0),
                lastRefreshTime = System.currentTimeMillis(),
                isFull = capacityBytes?.let { usedBytes >= it } ?: false
            )
        )
        items[index] = updated
        saveContainersLocked(items)
        updated
    }

    fun matchForSource(sourceUrl: String?): LibraryContainerConfig? {
        return enabledContainers().firstOrNull { it.matchesSource(sourceUrl) }
            ?: selected()?.takeIf { it.matchesSource(sourceUrl) }
    }

    fun displayLabel(config: LibraryContainerConfig?): String {
        val container = config?.container
        return container?.name?.takeIf { it.isNotBlank() }
            ?: container?.bucket?.takeIf { it.isNotBlank() }
            ?: config?.id.orEmpty()
    }

    private fun saveContainersLocked(items: List<LibraryContainerConfig>) {
        appCtx.putPrefString(PreferKey.libraryS3Containers, GSON.toJson(items.map { it.normalized() }))
    }

    private fun selections(): Map<String, String> {
        return GSON.fromJsonObject<Map<String, String>>(appCtx.getPrefString(PreferKey.libraryS3ContainerSelections))
            .getOrDefault(emptyMap())
    }

    private fun saveSelectionsLocked(selections: Map<String, String>) {
        appCtx.putPrefString(PreferKey.libraryS3ContainerSelections, GSON.toJson(selections))
    }

    private fun notifyChangedLocked() {
        LibraryCloudSync.clearSessions()
        postEvent(EventBus.LIBRARY_CONTAINER_CHANGED, true)
    }
}
