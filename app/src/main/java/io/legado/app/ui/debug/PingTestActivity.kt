package io.legado.app.ui.debug

import android.os.Bundle
import io.legado.app.ui.debug.DebugBaseActivity

class PingTestActivity : DebugBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setDebugContent {
            PingTestScreen(onBackClick = { finish() })
        }
    }
}
