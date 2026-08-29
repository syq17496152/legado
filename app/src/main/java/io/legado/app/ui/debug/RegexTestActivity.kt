package io.legado.app.ui.debug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.legado.app.ui.debug.DebugBaseActivity

class RegexTestActivity : DebugBaseActivity() {

    companion object {
        fun startIntent(
            context: Context,
            pattern: String = "",
            replacement: String = "",
            isRegex: Boolean = true
        ): Intent {
            return Intent(context, RegexTestActivity::class.java).apply {
                putExtra("pattern", pattern)
                putExtra("replacement", replacement)
                putExtra("isRegex", isRegex)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pattern = intent.getStringExtra("pattern") ?: ""
        val replacement = intent.getStringExtra("replacement") ?: ""
        val isRegex = intent.getBooleanExtra("isRegex", true)

        setDebugContent {
            RegexTestScreen(
                onBackClick = { finish() },
                initialPattern = pattern,
                initialReplacement = replacement,
                initialIsRegex = isRegex
            )
        }
    }
}
