package io.legado.app.ui.about

import android.os.Bundle
import androidx.fragment.app.add
import androidx.fragment.app.commit
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityReadRecordStatsBinding
import io.legado.app.ui.main.readrecord.ARG_STANDALONE
import io.legado.app.ui.main.readrecord.ReadRecordFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 阅读统计页宿主（main-bottom-nav-simplify AD-01）
 * 轻壳 Activity：承载原底栏「统计」Tab 的 ReadRecordFragment 完整统计仪表盘，
 * 作为「我的 → 工具 → 阅读记录」子页打开（standalone 模式追加返回按钮）。
 */
class ReadRecordStatsActivity : BaseActivity<ActivityReadRecordStatsBinding>() {

    override val binding by viewBinding(ActivityReadRecordStatsBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                add(
                    R.id.fragment_container,
                    ReadRecordFragment().apply {
                        arguments = Bundle().apply { putBoolean(ARG_STANDALONE, true) }
                    }
                )
            }
        }
    }
}