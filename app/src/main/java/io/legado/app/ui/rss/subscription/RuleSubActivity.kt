package io.legado.app.ui.rss.subscription

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import io.legado.app.databinding.ActivityRuleSubBinding
import io.legado.app.databinding.DialogRuleSubEditBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 规则订阅界面
 *
 * L-D8 S2 改造：Compose 顶栏桥接（GlassTopAppBar + 添加按钮 + ConfirmDialog 删除确认），
 * RecyclerView 列表 + DialogRuleSubEditBinding 编辑表单（独有联动逻辑）内核保留。
 */
class RuleSubActivity : BaseActivity<ActivityRuleSubBinding>(),
    RuleSubAdapter.Callback {

    override val binding by viewBinding(ActivityRuleSubBinding::inflate)
    private val adapter by lazy { RuleSubAdapter(this, this) }

    // Compose 桥接状态：待删除确认
    private var pendingDelete by mutableStateOf<RuleSub?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeTopBar()
        initView()
        initData()
    }

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Box {
                    GlassTopAppBar(
                        title = getString(R.string.rule_subscription),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            IconButton(onClick = { onAddSubscription() }) {
                                Icon(Icons.Default.Add, contentDescription = getString(R.string.add))
                            }
                        }
                    )
                    pendingDelete?.let { ruleSub ->
                        ConfirmDialog(
                            title = getString(R.string.draw),
                            text = getString(R.string.sure_del) + "\n<" + ruleSub.name + ">",
                            confirmText = getString(R.string.ok),
                            cancelText = getString(R.string.cancel),
                            destructive = true,
                            onConfirm = { executeDelete(ruleSub) },
                            onDismiss = { pendingDelete = null }
                        )
                    }
                }
            }
        }
    }

    /** 新增订阅：取最大排序后进入编辑（与旧版 menu_add 行为一致） */
    private fun onAddSubscription() {
        lifecycleScope.launch(IO) {
            val order = appDb.ruleSubDao.maxOrder + 1
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                editSubscription(RuleSub(customOrder = order))
            }
        }
    }

    private fun initView() {
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.ruleSubDao.flowAll().catch {
                AppLog.put("规则订阅界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                binding.tvEmptyMsg.isGone = it.isNotEmpty()
                adapter.setItems(it)
            }
        }
    }

    override fun openSubscription(ruleSub: RuleSub) {
        when (ruleSub.type) {
            0 -> showDialogFragment(
                ImportBookSourceDialog(ruleSub.url)
            )
            1 -> showDialogFragment(
                ImportRssSourceDialog(ruleSub.url)
            )
            2 -> showDialogFragment(
                ImportReplaceRuleDialog(ruleSub.url)
            )
        }
    }

    @SuppressLint("SetTextI18n")
    override fun editSubscription(ruleSub: RuleSub) {
        alert(R.string.rule_subscription) {
            val alertBinding = DialogRuleSubEditBinding.inflate(layoutInflater).apply {
                if (ruleSub.type !in 0..<spType.count) {
                    ruleSub.type = 0
                }
                spType.setSelection(ruleSub.type)
                etName.setText(ruleSub.name)
                etUrl.setText(ruleSub.url)
                autoUpdate.isChecked = ruleSub.autoUpdate
                silentUpdate.isChecked = ruleSub.silentUpdate
                etUpdateInterval.setText(ruleSub.updateInterval.toString())
                etUpdateInterval.isEnabled = ruleSub.autoUpdate
                if (ruleSub.updateInterval > 0) {
                    silentUpdate.isEnabled = true
                }
                autoUpdate.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && ruleSub.updateInterval == 0){
                        etUpdateInterval.setText("24")
                    }
                    else if (!isChecked) {
                        etUpdateInterval.setText("0")
                    }
                    etUpdateInterval.isEnabled = isChecked
                    silentUpdate.isEnabled = isChecked
                }
                etUpdateInterval.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    }
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    }
                    override fun afterTextChanged(s: Editable?) {
                        if (s.toString().toIntOrNull() == 0) {
                            silentUpdate.isChecked = false
                            autoUpdate.isChecked = false
                            silentUpdate.isEnabled = false
                        }
                        else {
                            silentUpdate.isEnabled = true
                        }
                    }
                })
            }
            customView { alertBinding.root }
            okButton {
                lifecycleScope.launch {
                    ruleSub.type = alertBinding.spType.selectedItemPosition
                    ruleSub.name = alertBinding.etName.text?.toString() ?: ""
                    ruleSub.url = alertBinding.etUrl.text?.toString() ?: ""
                    ruleSub.autoUpdate = alertBinding.autoUpdate.isChecked
                    ruleSub.silentUpdate = alertBinding.silentUpdate.isChecked
                    val intervalText = alertBinding.etUpdateInterval.text?.toString()
                    ruleSub.updateInterval = if (intervalText.isNullOrEmpty()) {
                        0
                    } else {
                        intervalText.toIntOrNull() ?: 0
                    }
                    if (ruleSub.url.isBlank()) {
                        toastOnUi(getString(R.string.null_url))
                        return@launch
                    }
                    val rs = withContext(IO) {
                        appDb.ruleSubDao.findByUrl(ruleSub.url)
                    }
                    if (rs != null && rs.id != ruleSub.id) {
                        toastOnUi("${getString(R.string.url_already)}(${rs.name})")
                        return@launch
                    }
                    withContext(IO) {
                        appDb.ruleSubDao.insert(ruleSub)
                    }
                }
            }
            cancelButton()
        }
    }

    override fun delSubscription(ruleSub: RuleSub) {
        pendingDelete = ruleSub
    }

    private fun executeDelete(ruleSub: RuleSub) {
        pendingDelete = null
        lifecycleScope.launch(IO) {
            appDb.ruleSubDao.delete(ruleSub)
        }
    }

    override fun updateSourceSub(vararg ruleSub: RuleSub) {
        lifecycleScope.launch(IO) {
            appDb.ruleSubDao.update(*ruleSub)
        }
    }

    override fun upOrder() {
        lifecycleScope.launch(IO) {
            val sourceSubs = appDb.ruleSubDao.all
            for ((index: Int, ruleSub: RuleSub) in sourceSubs.withIndex()) {
                ruleSub.customOrder = index + 1
            }
            appDb.ruleSubDao.update(*sourceSubs.toTypedArray())
        }
    }

}
