package io.legado.app.help.book

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.ReadMenuCustomButton

class ReadMenuCustomButtonSource(
    private val button: ReadMenuCustomButton
) : BaseSource {
    override var concurrentRate: String? = null
    override var loginUrl: String? = button.loginUrl
    override var loginUi: String? = button.loginUi
    override var header: String? = null
    override var enabledCookieJar: Boolean? = button.enabledCookieJar
    override var jsLib: String? = button.jsLib

    override fun getTag(): String = "ReadMenuButton:${button.id}:${button.displayName()}"

    override fun getKey(): String = "read_menu_button_${button.id}"
}
