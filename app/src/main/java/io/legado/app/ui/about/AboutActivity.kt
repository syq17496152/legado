package io.legado.app.ui.about

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StarRate
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAboutBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.installGlassTopBar
import io.legado.app.utils.openUrl
import io.legado.app.utils.share
import io.legado.app.utils.viewbindingdelegate.viewBinding


class AboutActivity : BaseActivity<ActivityAboutBinding>() {

    override val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
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

    // my-compose-full W2.2：壳纯化——顶栏运行时替换为 GlassTopAppBar（透壁纸语义），
    // 原 MainTopBarView Mode.SUB 与 llAbout 自绘卡背景（UiCorner）一并移除
    private fun initTopBar() {
        installGlassTopBar(
            binding,
            titleProvider = { getString(R.string.about) },
            actionsProvider = {
                listOf(
                    MenuAction(
                        iconRes = R.drawable.ic_star_border,
                        title = getString(R.string.scoring),
                        alwaysShow = true
                    ) { openUrl("market://details?id=$packageName") },
                    MenuAction(
                        iconRes = R.drawable.ic_share,
                        title = getString(R.string.share),
                        alwaysShow = true
                    ) {
                        share(getString(R.string.app_share_description_sigma), getString(R.string.app_name))
                    }
                )
            },
            onBack = { finish() }
        )
    }

}