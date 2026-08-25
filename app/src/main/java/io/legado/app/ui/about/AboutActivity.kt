package io.legado.app.ui.about

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAboutBinding
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.viewbindingdelegate.viewBinding


class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    override val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        binding.llAbout.background = UiCorner.opaqueRounded(
            themeCardColorOrDefault(),
            UiCorner.panelRadius(this)
        )
        val fTag = "aboutFragment"
        var aboutFragment = supportFragmentManager.findFragmentByTag(fTag)
        if (aboutFragment == null) aboutFragment = AboutFragment()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fl_fragment, aboutFragment, fTag)
            .commit()
        binding.tvAppSummary.post {
            kotlin.runCatching {
                val span = ForegroundColorSpan(accentColor)
                val spannableString = SpannableString(binding.tvAppSummary.text)
                val gzh = getString(R.string.legado_gzh)
                val start = spannableString.indexOf(gzh)
                spannableString.setSpan(
                    span, start, start + gzh.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                binding.tvAppSummary.text = spannableString
            }
        }
    }

    private fun initTopBar() = binding.titleBar.run {
        applyStatusBarPadding(withInitialPadding = true)
        setMode(MainTopBarView.Mode.SUB)
        setTitle(getString(R.string.about))
        setSearchEntryVisible(false)
        titleSelect.setOnClickListener { finish() }
        addActionButton(R.drawable.ic_scoring, R.string.scoring) {
            openUrl("market://details?id=$packageName")
        }
        addActionButton(R.drawable.ic_share, R.string.share) {
            share(getString(R.string.app_share_description_sigma), getString(R.string.app_name))
        }
    }

}