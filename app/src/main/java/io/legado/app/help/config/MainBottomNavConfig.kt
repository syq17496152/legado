package io.legado.app.help.config

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object MainBottomNavConfig {

    const val KEY_BOOKSHELF = "bookshelf"
    const val KEY_DISCOVERY = "discovery"
    const val KEY_RSS = "rss"
    const val KEY_READ_RECORD = "readRecord"
    const val KEY_MY = "my"

    @Keep
    data class ItemState(
        val key: String,
        val visible: Boolean = true
    )

    data class ItemSpec(
        val key: String,
        @StringRes val titleRes: Int,
        @IdRes val menuId: Int,
        @DrawableRes val iconRes: Int,
        val fragmentId: Int,
        val lockedVisible: Boolean = false
    )

    val specs = listOf(
        ItemSpec(KEY_BOOKSHELF, R.string.bookshelf, R.id.menu_bookshelf, R.drawable.ic_bottom_books, 0, true),
        ItemSpec(KEY_DISCOVERY, R.string.discovery, R.id.menu_discovery, R.drawable.ic_bottom_explore, 1),
        ItemSpec(KEY_RSS, R.string.rss, R.id.menu_rss, R.drawable.ic_bottom_rss_feed, 2),
        ItemSpec(KEY_READ_RECORD, R.string.side_nav_stats, R.id.menu_read_record, R.drawable.ic_bottom_read_record, 3),
        ItemSpec(KEY_MY, R.string.my, R.id.menu_my_config, R.drawable.ic_bottom_person, 4, true)
    )

    val defaultItems: List<ItemState>
        get() = specs.map { ItemState(it.key, true) }

    fun items(): List<ItemState> {
        val prefs = appCtx.defaultSharedPreferences
        val stored = appCtx.getPrefString(PreferKey.mainBottomNavItems)
        val raw = if (stored.isNullOrBlank()) {
            legacyInitialItems()
        } else {
            GSON.fromJsonArray<ItemState>(stored).getOrDefault(defaultItems)
        }
        val normalized = normalize(raw)
        if (stored.isNullOrBlank() || normalized != raw) {
            save(normalized)
        } else if (!prefs.contains(PreferKey.mainBottomNavItems)) {
            save(normalized)
        }
        return normalized
    }

    fun visibleItems(): List<ItemState> {
        return items().filter { item ->
            item.visible || spec(item.key)?.lockedVisible == true
        }
    }

    fun save(items: List<ItemState>) {
        appCtx.putPrefString(PreferKey.mainBottomNavItems, GSON.toJson(normalize(items)))
    }

    fun spec(key: String): ItemSpec? {
        return specs.firstOrNull { it.key == key }
    }

    fun specForMenuId(@IdRes menuId: Int): ItemSpec? {
        return specs.firstOrNull { it.menuId == menuId }
    }

    fun specForFragmentId(fragmentId: Int): ItemSpec? {
        return specs.firstOrNull { it.fragmentId == fragmentId }
    }

    fun isVisible(key: String): Boolean {
        return visibleItems().any { it.key == key }
    }

    private fun normalize(items: List<ItemState>): List<ItemState> {
        val byKey = items
            .filter { state -> specs.any { it.key == state.key } }
            .distinctBy { it.key }
            .associateBy { it.key }
        val orderedKeys = buildList {
            items.forEach { state ->
                if (state.key !in this && specs.any { it.key == state.key }) {
                    add(state.key)
                }
            }
            specs.forEach { spec ->
                if (spec.key !in this) add(spec.key)
            }
        }
        return orderedKeys.mapNotNull { key ->
            val spec = spec(key) ?: return@mapNotNull null
            val visible = if (spec.lockedVisible) {
                true
            } else {
                byKey[key]?.visible ?: true
            }
            ItemState(key, visible)
        }
    }

    private fun legacyInitialItems(): List<ItemState> {
        val prefs = appCtx.defaultSharedPreferences
        fun legacyVisible(prefKey: String, defaultValue: Boolean): Boolean {
            return if (prefs.contains(prefKey)) {
                appCtx.getPrefBoolean(prefKey, defaultValue)
            } else {
                true
            }
        }
        return listOf(
            ItemState(KEY_BOOKSHELF, true),
            ItemState(KEY_DISCOVERY, legacyVisible(PreferKey.showDiscovery, true)),
            ItemState(KEY_RSS, legacyVisible(PreferKey.showRss, true)),
            ItemState(KEY_READ_RECORD, legacyVisible(PreferKey.showReadRecord, true)),
            ItemState(KEY_MY, true)
        )
    }
}