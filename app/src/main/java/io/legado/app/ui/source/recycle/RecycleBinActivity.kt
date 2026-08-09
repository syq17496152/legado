package io.legado.app.ui.source.recycle

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SourceRecycleBin
import io.legado.app.databinding.ActivityRecycleBinBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

class RecycleBinActivity : VMBaseActivity<ActivityRecycleBinBinding, RecycleBinViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    RecycleBinAdapter.CallBack {

    override val viewModel by viewModels<RecycleBinViewModel>()
    override val binding by viewBinding(ActivityRecycleBinBinding::inflate)
    private val adapter by lazy { RecycleBinAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initSelectActionView()
        observeData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.recycle_bin, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.recycle_bin_restore)
        binding.selectActionBar.inflateMenu(R.menu.recycle_bin_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun observeData() {
        lifecycleScope.launch {
            appDb.sourceRecycleBinDao.flowAll().catch {
                AppLog.put("回收站获取数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it, adapter.diffItemCallBack)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.upResumed(true)
    }

    override fun onPause() {
        adapter.upResumed(false)
        super.onPause()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_recycle_bin_empty -> emptyRecycleBin()
            R.id.menu_recycle_bin_help -> showHelp("SourceRecycleBinHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_recycle_bin_delete_selection -> deleteSelection()
        }
        return true
    }

    override fun onClickSelectBarMainAction() {
        val selection = adapter.selection
        if (selection.isEmpty()) return
        selection.forEach {
            checkRestore(it)
        }
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun restore(item: SourceRecycleBin) {
        checkRestore(item)
    }

    override fun delete(item: SourceRecycleBin) {
        alert(R.string.draw) {
            setMessage(getString(R.string.recycle_bin_delete_msg))
            noButton()
            yesButton {
                viewModel.delete(item)
            }
        }
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(
            adapter.selection.size,
            adapter.itemCount
        )
    }

    private fun checkRestore(item: SourceRecycleBin) {
        viewModel.hasConflict(item).onSuccess { conflict ->
            if (conflict) {
                alert(R.string.draw) {
                    setMessage(getString(R.string.recycle_bin_restore_conflict) + "\n" + item.name)
                    noButton()
                    yesButton {
                        viewModel.restore(item, true)
                    }
                }
            } else {
                viewModel.restore(item, false)
            }
        }
    }

    private fun deleteSelection() {
        val selection = adapter.selection
        if (selection.isEmpty()) return
        alert(R.string.draw) {
            setMessage(getString(R.string.recycle_bin_delete_selection_msg))
            noButton()
            yesButton {
                viewModel.delete(*selection.toTypedArray())
            }
        }
    }

    private fun emptyRecycleBin() {
        alert(R.string.draw) {
            setMessage(getString(R.string.recycle_bin_empty_msg))
            noButton()
            yesButton {
                viewModel.empty()
            }
        }
    }
}
