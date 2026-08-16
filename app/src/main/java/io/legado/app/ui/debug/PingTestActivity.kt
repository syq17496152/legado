package io.legado.app.ui.debug

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.ui.theme.initLegadoComposeTheme
import io.legado.app.ui.theme.setLegadoContent

class PingTestActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        initLegadoComposeTheme()
        super.onCreate(savedInstanceState)
        setLegadoContent {
            PingTestScreen(onBackClick = { finish() })
        }
    }
}
