package io.legado.app.ui.debug

import android.os.Bundle
import io.legado.app.ui.debug.DebugBaseActivity

class CurlTestActivity : DebugBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDebugContent {
            CurlTestScreen(onBackClick = { finish() })
        }
    }
}
