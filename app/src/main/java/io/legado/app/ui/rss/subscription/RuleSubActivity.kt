package io.legado.app.ui.rss.subscription

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RuleSub
import io.legado.app.databinding.ActivityRuleSubBinding
import io.legado.app.ui.association.ImportBookSourceDialog
import io.legado.app.ui.association.ImportReplaceRuleDialog
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.replaceByIndex
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
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
 * 规则订阅管理界面
 */
class RuleSubActivity : BaseActivity<ActivityRuleSubBinding>(),
    RuleSubEditComposeDialog.Callback {

    override val binding by viewBinding(ActivityRuleSubBinding::inflate)

    private val ruleSubsState = mutableStateListOf<RuleSub>()
    private val searchQueryState = mutableStateOf("")
    private val typeLabels by lazy { resources.getStringArray(R.array.rule_type).toList() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.source_subscription, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> addSubscription()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initComposeContent() {
        binding.titleBar.visibility = View.GONE
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        container.removeView(binding.tvEmptyMsg)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                val filteredItems = filterRuleSubs(searchQueryState.value)
                AppManagementScaffold(
                    title = getString(R.string.rule_subscription),
                    selectedCount = 0,
                    totalCount = filteredItems.size,
                    searchQuery = searchQueryState.value,
                    searchHint = getString(R.string.search),
                    onSearchChange = { searchQueryState.value = it },
                    topActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.add),
                            iconRes = R.drawable.ic_add,
                            onClick = ::addSubscription
                        )
                    ),
                    onBack = { finish() }
                ) {
                    RuleSubScreen(
                        subscriptions = filteredItems,
                        typeLabels = typeLabels,
                        emptyMessage = if (searchQueryState.value.isBlank()) {
                            getString(R.string.rule_sub_empty_msg)
                        } else {
                            getString(R.string.search_result)
                        },
                        dragEnabled = searchQueryState.value.isBlank(),
                        onOpen = ::openSubscription,
                        onEdit = ::editSubscription,
                        ruleSubMenuActions = ::ruleSubMenuActions,
                        onMoveBy = ::moveSubscriptionBy,
                        onMoveFinished = ::persistSubscriptionOrder
                    )
                }
            }
        }
        container.addView(cv, index)
    }

    private fun initData() {
        lifecycleScope.launch {
            appDb.ruleSubDao.flowAll().catch {
                AppLog.put("规则订阅界面获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).conflate().collect {
                ruleSubsState.replaceByIndex(it, ::sameRuleSubContent)
            }
        }
    }

    private fun filterRuleSubs(query: String): List<RuleSub> {
        val key = query.trim()
        if (key.isEmpty()) {
            return ruleSubsState
        }
        return ruleSubsState.filter { item ->
            item.name.contains(key, ignoreCase = true) ||
                item.url.contains(key, ignoreCase = true) ||
                typeLabels.getOrNull(item.type).orEmpty().contains(key, ignoreCase = true)
        }
    }

    private fun addSubscription() {
        val order = (ruleSubsState.maxOfOrNull { it.customOrder } ?: appDb.ruleSubDao.maxOrder) + 1
        editSubscription(RuleSub(customOrder = order))
    }

    private fun openSubscription(ruleSub: RuleSub) {
        when (ruleSub.type) {
            0 -> showDialogFragment(ImportBookSourceDialog(ruleSub.url))
            1 -> showDialogFragment(ImportRssSourceDialog(ruleSub.url))
            2 -> showDialogFragment(ImportReplaceRuleDialog(ruleSub.url))
        }
    }

    private fun editSubscription(ruleSub: RuleSub) {
        showDialogFragment(RuleSubEditComposeDialog.create(ruleSub))
    }

    override fun saveRuleSub(ruleSub: RuleSub, onSaved: () -> Unit) {
        if (ruleSub.url.isBlank()) {
            toastOnUi(getString(R.string.null_url))
            return
        }
        lifecycleScope.launch {
            val duplicate = withContext(IO) {
                appDb.ruleSubDao.findByUrl(ruleSub.url)
            }
            if (duplicate != null && duplicate.id != ruleSub.id) {
                toastOnUi("${getString(R.string.url_already)}(${duplicate.name})")
                return@launch
            }
            val itemToSave = ruleSubsState.firstOrNull { it.id == ruleSub.id }?.copy(
                name = ruleSub.name,
                url = ruleSub.url,
                type = ruleSub.type,
                customOrder = ruleSub.customOrder,
                autoUpdate = ruleSub.autoUpdate,
                updateInterval = ruleSub.updateInterval,
                silentUpdate = ruleSub.silentUpdate
            ) ?: ruleSub
            withContext(IO) {
                appDb.ruleSubDao.insert(itemToSave)
            }
            onSaved()
        }
    }

    private fun ruleSubMenuActions(ruleSub: RuleSub): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.to_top)) {
                moveSubscription(ruleSub, 0)
            },
            AppManagementMenuAction(getString(R.string.to_bottom)) {
                moveSubscription(ruleSub, ruleSubsState.lastIndex)
            },
            AppManagementMenuAction(getString(R.string.edit)) {
                editSubscription(ruleSub)
            },
            AppManagementMenuAction(
                text = getString(R.string.delete),
                danger = true,
                onClick = { delSubscription(ruleSub) }
            )
        )
    }

    private fun delSubscription(ruleSub: RuleSub) {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del) + "\n" + ruleSub.name.ifBlank { ruleSub.url },
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            onPositive = {
                lifecycleScope.launch(IO) {
                    appDb.ruleSubDao.delete(ruleSub)
                }
            }
        )
    }

    private fun moveSubscription(ruleSub: RuleSub, targetIndex: Int) {
        moveSubscription(ruleSub, targetIndex, true)
    }

    private fun moveSubscriptionBy(ruleSub: RuleSub, offset: Int) {
        if (offset == 0 || ruleSubsState.size < 2) {
            return
        }
        val fromIndex = ruleSubsState.indexOfFirst { it.id == ruleSub.id }
        if (fromIndex < 0) {
            return
        }
        moveSubscription(ruleSub, (fromIndex + offset).coerceIn(0, ruleSubsState.lastIndex), false)
    }

    private fun moveSubscription(ruleSub: RuleSub, targetIndex: Int, persist: Boolean) {
        val items = ruleSubsState.map { it.copy() }.toMutableList()
        val fromIndex = items.indexOfFirst { it.id == ruleSub.id }
        if (fromIndex < 0 || items.size < 2) {
            return
        }
        val item = items.removeAt(fromIndex)
        items.add(targetIndex.coerceIn(0, items.size), item)
        items.forEachIndexed { index, sub ->
            sub.customOrder = index + 1
        }
        ruleSubsState.replaceByIndex(items, ::sameRuleSubContent)
        if (persist) {
            persistSubscriptionOrder()
        }
    }

    private fun persistSubscriptionOrder() {
        if (ruleSubsState.isEmpty()) {
            return
        }
        updateSourceSub(*ruleSubsState.map { it.copy() }.toTypedArray())
    }

    private fun updateSourceSub(vararg ruleSub: RuleSub) {
        lifecycleScope.launch(IO) {
            appDb.ruleSubDao.update(*ruleSub)
        }
    }

    private fun sameRuleSubContent(old: RuleSub, new: RuleSub): Boolean {
        return old.id == new.id &&
            old.name == new.name &&
            old.url == new.url &&
            old.type == new.type &&
            old.customOrder == new.customOrder &&
            old.autoUpdate == new.autoUpdate &&
            old.update == new.update &&
            old.updateInterval == new.updateInterval &&
            old.silentUpdate == new.silentUpdate &&
            old.js == new.js &&
            old.showRule == new.showRule &&
            old.sourceUrl == new.sourceUrl
    }

}
