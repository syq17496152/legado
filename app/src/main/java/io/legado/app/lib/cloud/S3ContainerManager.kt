package io.legado.app.lib.cloud

import cn.hutool.crypto.symmetric.AES
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object S3ContainerManager {

    const val SCOPE_DEFAULT = "default"
    const val SCOPE_BACKUP = "backup"
    const val SCOPE_THEME = "theme"
    const val SCOPE_TOP_BAR = "topBar"
    const val SCOPE_NAVIGATION_BAR = "navigationBar"
    const val SCOPE_COVER_COLLECTION = "coverCollection"
    const val SCOPE_BUBBLE = "bubble"
    const val SCOPE_CACHE = "cache"

    private val lock = Any()

    fun containers(): List<S3Container> = synchronized(lock) {
        val configured = GSON.fromJsonObject<List<S3Container>>(appCtx.getPrefString(PreferKey.s3Containers))
            .getOrDefault(emptyList())
            .map { it.normalized() }
            .filter { it.id.isNotBlank() }
        if (configured.isNotEmpty()) return@synchronized configured
        legacyContainer()?.let { legacy ->
            saveContainersLocked(listOf(legacy))
            return@synchronized listOf(legacy)
        }
        emptyList()
    }

    fun enabledContainers(): List<S3Container> = containers().filter { it.enabled }

    fun saveContainers(containers: List<S3Container>) = synchronized(lock) {
        saveContainersLocked(containers.map { it.normalized() }.distinctBy { it.id })
    }

    fun upsert(container: S3Container) = synchronized(lock) {
        val normalized = container.normalized().let {
            if (it.id.isBlank()) it.copy(id = S3Container.newId()) else it
        }
        val items = containers().toMutableList()
        val index = items.indexOfFirst { it.id == normalized.id }
        if (index >= 0) items[index] = normalized else items += normalized
        saveContainersLocked(items)
        normalized
    }

    fun delete(id: String) = synchronized(lock) {
        val items = containers().filterNot { it.id == id }
        saveContainersLocked(items)
        val selections = selections().filterValues { it != id }.toMutableMap()
        saveSelectionsLocked(selections)
    }

    fun get(id: String?): S3Container? {
        if (id.isNullOrBlank()) return null
        return containers().firstOrNull { it.id == id }
    }

    fun selected(scope: String = SCOPE_DEFAULT): S3Container? {
        val items = enabledContainers()
        if (items.isEmpty()) return null
        val selectedId = selections()[scope] ?: selections()[SCOPE_DEFAULT]
        return items.firstOrNull { it.id == selectedId } ?: items.firstOrNull { !it.isFull } ?: items.firstOrNull()
    }

    fun selectedId(scope: String = SCOPE_DEFAULT): String? = selected(scope)?.id

    fun select(scope: String, id: String?) = synchronized(lock) {
        val selections = selections().toMutableMap()
        if (id.isNullOrBlank()) {
            selections.remove(scope)
        } else {
            selections[scope] = id
            if (scope == SCOPE_DEFAULT) selections.putIfAbsent(SCOPE_BACKUP, id)
        }
        saveSelectionsLocked(selections)
    }

    fun updateUsage(id: String, usedBytes: Long, isFull: Boolean? = null): S3Container? = synchronized(lock) {
        val items = containers().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val current = items[index]
        val full = isFull ?: current.capacityBytes()?.let { usedBytes >= it } ?: false
        val updated = current.copy(
            usedBytes = usedBytes.coerceAtLeast(0),
            lastRefreshTime = System.currentTimeMillis(),
            isFull = full
        )
        items[index] = updated
        saveContainersLocked(items)
        updated
    }

    fun markFull(id: String): S3Container? = synchronized(lock) {
        val items = containers().toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = items[index].copy(isFull = true, lastRefreshTime = System.currentTimeMillis())
        items[index] = updated
        saveContainersLocked(items)
        updated
    }

    fun chooseForUpload(scope: String, bytesToAdd: Long): S3Container? {
        val items = enabledContainers()
        if (items.isEmpty()) return null
        val preferred = selected(scope)
        val ordered = buildList {
            preferred?.let { add(it) }
            addAll(items.filterNot { it.id == preferred?.id })
        }
        if (!AppConfig.autoSwitchS3Container) {
            return ordered.firstOrNull()?.takeIf { it.hasCapacityFor(bytesToAdd) }
        }
        return ordered.firstOrNull { it.hasCapacityFor(bytesToAdd) }
    }


    fun listContainers(): List<S3Container> = containers()

    fun addContainer(container: S3Container): S3Container = upsert(container)

    fun updateContainer(container: S3Container) {
        upsert(container)
    }

    fun deleteContainer(id: String) = delete(id)

    fun container(id: String?): S3Container? = get(id)

    fun selectedContainer(scope: S3ContainerScope = S3ContainerScope.DEFAULT): S3Container? = selected(scope.key)

    fun selectedContainerId(scope: S3ContainerScope = S3ContainerScope.DEFAULT): String? = selectedId(scope.key)

    fun selectContainer(scope: S3ContainerScope, id: String?) = select(scope.key, id)

    fun saveContainerUsage(containerId: String, usedBytes: Long, lastRefreshTime: Long = System.currentTimeMillis()) {
        updateUsage(containerId, usedBytes)
    }

    fun adjustContainerUsage(containerId: String, deltaBytes: Long) {
        val current = get(containerId) ?: return
        updateUsage(containerId, (current.usedBytes + deltaBytes).coerceAtLeast(0L))
    }

    fun markFull(containerId: String, full: Boolean = true) {
        val current = get(containerId) ?: return
        updateUsage(containerId, current.usedBytes, full)
    }

    fun displayLabel(container: S3Container?): String = label(container)

    fun toEncryptedBackupJson(aes: AES): String? {
        val items = containers()
        if (items.isEmpty()) return null
        return GSON.toJson(items.map { item ->
            item.copy(
                secretKey = encryptForBackup(aes, item.secretKey),
                sessionToken = item.sessionToken?.let { encryptForBackup(aes, it) }
            )
        })
    }

    fun restoreEncryptedBackupJson(raw: String, aes: AES): String {
        val items = GSON.fromJsonObject<List<S3Container>>(raw).getOrNull() ?: return raw
        return GSON.toJson(items.map { item ->
            item.copy(
                secretKey = decryptFromBackup(aes, item.secretKey),
                sessionToken = item.sessionToken?.let { decryptFromBackup(aes, it) }
            )
        })
    }

    private fun encryptForBackup(aes: AES, value: String): String {
        if (value.isBlank()) return value
        return aes.runCatching { encryptBase64(value) }.getOrDefault(value)
    }

    private fun decryptFromBackup(aes: AES, value: String): String {
        if (value.isBlank()) return value
        return aes.runCatching { decryptStr(value) }.getOrDefault(value)
    }

    fun label(container: S3Container?): String {
        return container?.name?.takeIf { it.isNotBlank() }
            ?: container?.bucket?.takeIf { it.isNotBlank() }
            ?: container?.id.orEmpty()
    }

    private fun saveContainersLocked(containers: List<S3Container>) {
        appCtx.putPrefString(PreferKey.s3Containers, GSON.toJson(containers))
    }

    private fun selections(): Map<String, String> {
        return GSON.fromJsonObject<Map<String, String>>(appCtx.getPrefString(PreferKey.s3ContainerSelections))
            .getOrDefault(emptyMap())
    }

    private fun saveSelectionsLocked(selections: Map<String, String>) {
        appCtx.putPrefString(PreferKey.s3ContainerSelections, GSON.toJson(selections))
    }

    private fun legacyContainer(): S3Container? {
        val endpoint = appCtx.getPrefString(PreferKey.s3Endpoint).orEmpty()
        val accessKey = appCtx.getPrefString(PreferKey.s3AccessKey).orEmpty()
        val secretKey = appCtx.getPrefString(PreferKey.s3SecretKey).orEmpty()
        if (endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank()) return null
        val pathStyle = appCtx.defaultSharedPreferences.all[PreferKey.s3PathStyle] as? Boolean
        val parsed = S3Config.parseAddress(
            endpoint,
            appCtx.getPrefString(PreferKey.s3Bucket).orEmpty(),
            appCtx.getPrefString(PreferKey.s3Region).orEmpty(),
            pathStyle
        )
        return S3Container(
            id = S3Container.LEGACY_DEFAULT_ID,
            name = parsed.bucket.ifBlank { "Default" },
            endpoint = parsed.endpoint,
            region = parsed.region,
            bucket = parsed.bucket,
            prefix = appCtx.getPrefString(PreferKey.s3Prefix, "legado").orEmpty(),
            accessKey = accessKey,
            secretKey = secretKey,
            sessionToken = appCtx.getPrefString(PreferKey.s3SessionToken),
            pathStyle = parsed.pathStyle,
            enabled = true
        ).normalized()
    }

}
