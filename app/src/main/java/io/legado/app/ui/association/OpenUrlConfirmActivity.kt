package io.legado.app.ui.association

import android.os.Bundle
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.base.composeShell
import io.legado.app.constant.SourceType
import io.legado.app.utils.showDialogFragment

class OpenUrlConfirmActivity :
    BaseActivity<ViewBinding>() {

    // 原 activity_translucence.xml 已退役（CE-b）：composeShell 合成壳 + 共享装配（5 个宿主共用）
    override val binding: ViewBinding by lazy { composeShell(this) }
    private val shell by lazy { TransparentShellViews(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        shell.install(binding.root)
        intent.getStringExtra("uri")?.let {
            val mimeType = intent.getStringExtra("mimeType")
            val sourceOrigin = intent.getStringExtra("sourceOrigin")
            val sourceName = intent.getStringExtra("sourceName")
            val sourceType = intent.getIntExtra("sourceType", SourceType.book)
            showDialogFragment(OpenUrlConfirmDialog(it, mimeType, sourceOrigin, sourceName, sourceType))
        } ?: finish()
    }

}
