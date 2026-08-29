package io.legado.app.ui.debug

import android.os.Bundle
import io.legado.app.ui.debug.DebugBaseActivity

class EncodeToolsActivity : DebugBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDebugContent {
            EncodeToolsScreen(onBackClick = { finish() })
        }
    }
}
