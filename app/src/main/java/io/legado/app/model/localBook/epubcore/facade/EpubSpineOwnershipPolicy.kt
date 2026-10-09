package io.legado.app.model.localBook.epubcore.facade

/** Resolves the logical TOC item that owns an unlisted physical spine page. */
internal object EpubSpineOwnershipPolicy {

    data class Owner(
        val spineOrder: Int,
        val tocOrder: Int,
        val url: String,
        val title: String
    )

    fun ownerAt(spineOrder: Int, owners: List<Owner>): Owner? {
        return owners
            .asSequence()
            .filter { it.spineOrder <= spineOrder }
            .maxWithOrNull(compareBy<Owner> { it.spineOrder }.thenBy { it.tocOrder })
    }
}
