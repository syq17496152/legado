@file:Suppress("DEPRECATION")

package io.legado.app.ui.rss.favorites

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssStar
import io.legado.app.databinding.ActivityRssFavoritesBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppUiTokens
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.gone
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 收藏夹
 *
 * L-D7 S2 改造：Compose 顶栏桥接（GlassTopAppBar + AppDropdownMenu 分组/删除菜单，
 * Compose ConfirmDialog 删除确认），ViewPager + TabLayout + 分组 Fragment 内核保留。
 */
class RssFavoritesActivity : BaseActivity<ActivityRssFavoritesBinding>() {

    override val binding by viewBinding(ActivityRssFavoritesBinding::inflate)
    private val adapter by lazy { TabFragmentPageAdapter() }
    private var groupList = mutableListOf<String>()

    // Compose 桥接状态：分组列表 / 当前分组 / 菜单展开 / 待删除确认
    private var composeGroups by mutableStateOf(listOf<String>())
    private var currentGroup by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private var groupMenuExpanded by mutableStateOf(false)
    private var pendingDelete by mutableStateOf<PendingDelete?>(null)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeTopBar()
        initEmptyState()
        initView()
        upFragments()
    }

    /**
     * F1 空态操作化（优化 4）：一条收藏都没有时（⇒ 无分组、无 Tab、无列表页）不再整页空白。
     *
     * ⚠️ 落地口径与蓝图不同（已反哺蓝图 §1.5）：蓝图按「空**分组**」设计，但分组清单由 `rssStars`
     * 派生（`flowGroups: select group … group by group`）⇒ **空分组在数据模型里无法持久存在**，
     * 分组层空态不可达（仅清空瞬间的瞬态窗口）。真正可达且对用户有意义的是「全库无收藏」的新手态，
     * 故空态落在 Activity 层；出口取「去订阅源逛逛」（无分组可切换，跨页找文章点星标才是真实下一步）。
     */
    private fun initEmptyState() {
        binding.emptyOverlay.setContent {
            LegadoTheme {
                // 状态驱动（与顶栏标题同源）：composeGroups 为空 = 全库无收藏 ⇒ 渲染空态；
                // 非空时不渲染任何内容（透明且无触摸 ⇒ 不遮挡列表），无需切换 View 可见性
                if (composeGroups.isEmpty()) {
                    EmptyStatePlaceholder(
                        icon = Icons.Outlined.StarBorder,
                        title = getString(R.string.favorites_empty_title),
                        subtitle = getString(R.string.favorites_empty_desc),
                        primaryAction = EmptyStateAction(
                            label = getString(R.string.favorites_empty_browse_rss),
                            onClick = { MainActivity.openRss(this) }
                        )
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        //从ReadRssActivity退出时，判断是否需要重新定位tabLayout选中项
        if (currentGroup.isNotEmpty() && groupList.isNotEmpty()) {
            var item = groupList.indexOf(currentGroup)
            val currentItem = binding.viewPager.currentItem
            //如果坐标没有变化，则结束
            if (item == currentItem) {
                return
            }
            if (item == -1) {
                item = currentItem
            }
            lifecycleScope.launch {
                delay(100)
                binding.tabLayout.getTabAt(item)?.select()
            }
        }
    }

    /**
     * L-D7 S2 改造：Compose 顶栏（GlassTopAppBar + 更多菜单 AppDropdownMenu），
     * 删除整组/删除全部/条目删除确认统一走 Compose ConfirmDialog。
     * topbar-icon-semantics-fix 3.3：分组恢复一级图标（原版 rss_favorites menu_group always），
     * 点击弹分组切换子菜单（对齐 CacheActivity 分组模式）。
     */
    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Box {
                    GlassTopAppBar(
                        // F145（优化 6）：单分组时 TabLayout 被隐藏（见 upFragments）⇒ 当前分组名必须由
                        // 标题承载，否则用户不知道收藏存在哪个分组里（多分组时仍由 Tab 承载上下文）。
                        title = if (composeGroups.size == 1) {
                            getString(R.string.favorites) + " · " + composeGroups.first()
                        } else {
                            getString(R.string.favorites)
                        },
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            // 分组一级图标：点击展开分组切换子菜单
                            Box {
                                IconButton(onClick = { groupMenuExpanded = true }) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = getString(R.string.group)
                                    )
                                }
                                AppDropdownMenu(
                                    expanded = groupMenuExpanded,
                                    onDismiss = { groupMenuExpanded = false },
                                    actions = buildGroupMenuActions()
                                )
                            }
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            AppDropdownMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                actions = buildMenuActions(AppUiTokens.dialogStyle().danger)
                            )
                        }
                    )
                    pendingDelete?.let { pending ->
                        val deleteMessage = when (pending) {
                            is PendingDelete.Group -> getString(R.string.sure_del) + "\n<" + pending.group + ">" + getString(R.string.group)
                            is PendingDelete.All -> getString(R.string.sure_del) + "\n<" + getString(R.string.all) + ">" + getString(R.string.favorite)
                            is PendingDelete.Star -> getString(R.string.sure_del) + "\n<" + pending.star.title + ">"
                        }
                        ConfirmDialog(
                            title = getString(R.string.draw),
                            text = deleteMessage,
                            confirmText = getString(R.string.ok),
                            cancelText = getString(R.string.cancel),
                            destructive = true,
                            onConfirm = { executeDelete(pending) },
                            onDismiss = { pendingDelete = null }
                        )
                    }
                }
            }
        }
    }

    /**
     * 分组切换子菜单（3.3：分组一级图标点击展开；当前分组勾选态）
     */
    private fun buildGroupMenuActions(): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        composeGroups.forEachIndexed { index, group ->
            actions += MenuAction(
                icon = Icons.Default.Folder,
                title = group,
                checked = group == currentGroup,
                onClick = {
                    groupMenuExpanded = false
                    binding.viewPager.setCurrentItem(index)
                }
            )
        }
        return actions
    }

    /**
     * 顶栏更多菜单：删除整组 + 删除全部（分组跳转已拆出至分组一级图标子菜单）。
     *
     * F144（优化 5）：两项原先同图标同色等权并排，而「删除所有」是**全库级不可逆**操作 ——
     * 现将其**置底**并以危险分组头隔开 + 走 danger 语义色（[danger] 单源传入，禁止本页写色值）；
     * 「删除当前分组」属页内低危项，保持默认色（二次确认已由 ConfirmDialog destructive 承担）。
     */
    private fun buildMenuActions(danger: Color): List<MenuAction> {
        val actions = mutableListOf<MenuAction>()
        actions += MenuAction(
            icon = Icons.Default.DeleteSweep,
            title = getString(R.string.delete_select_group),
            onClick = {
                menuExpanded = false
                if (composeGroups.isNotEmpty()) {
                    pendingDelete = PendingDelete.Group(composeGroups[binding.viewPager.currentItem])
                }
            }
        )
        // 危险分组头（复用既有「危险操作」文案：与订阅分类页菜单分组同源同语义）
        actions += MenuAction(
            icon = Icons.Default.DeleteSweep,
            title = getString(R.string.rss_sort_group_danger),
            header = true,
            onClick = {}
        )
        actions += MenuAction(
            icon = Icons.Default.DeleteSweep,
            tint = danger,
            title = getString(R.string.delete_all),
            onClick = {
                menuExpanded = false
                pendingDelete = PendingDelete.All
            }
        )
        return actions
    }

    /** 条目删除确认：RssFavoritesFragment 长按收藏项时上抛（AD-20 内核桥接） */
    fun confirmDeleteStar(star: RssStar) {
        pendingDelete = PendingDelete.Star(star)
    }

    private fun executeDelete(pending: PendingDelete) {
        pendingDelete = null
        when (pending) {
            is PendingDelete.Group -> lifecycleScope.launch(IO) {
                appDb.rssStarDao.deleteByGroup(pending.group)
            }
            is PendingDelete.All -> lifecycleScope.launch(IO) {
                appDb.rssStarDao.deleteAll()
            }
            is PendingDelete.Star -> lifecycleScope.launch(IO) {
                appDb.rssStarDao.delete(pending.star.origin, pending.star.link)
            }
        }
    }

    private fun initView() {
        binding.viewPager.adapter = adapter
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
            }

            override fun onPageSelected(position: Int) {
                currentGroup = groupList[position]
            }

            override fun onPageScrollStateChanged(state: Int) {}

        })
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        binding.tabLayout.setSelectedTabIndicatorColor(accentColor)
    }

    private fun upFragments() {
        lifecycleScope.launch {
            appDb.rssStarDao.flowGroups().catch {
                AppLog.put("订阅分组数据获取失败\n${it.localizedMessage}", it)
            }.distinctUntilChanged().flowOn(IO).collect {
                groupList.clear()
                groupList.addAll(it)
                composeGroups = it
                if (groupList.size == 1) {
                    binding.tabLayout.gone()
                } else {
                    binding.tabLayout.visible()
                }
                adapter.notifyDataSetChanged()
            }
        }
    }

    /** 删除确认载荷 */
    private sealed interface PendingDelete {
        data class Group(val group: String) : PendingDelete
        object All : PendingDelete
        data class Star(val star: RssStar) : PendingDelete
    }

    private inner class TabFragmentPageAdapter :
        FragmentStatePagerAdapter(supportFragmentManager, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItemPosition(`object`: Any): Int {
            return POSITION_NONE
        }

        override fun getPageTitle(position: Int): CharSequence {
            return groupList[position]
        }

        override fun getItem(position: Int): Fragment {
            val group = groupList[position]
            return RssFavoritesFragment(group)
        }

        override fun getCount(): Int {
            return groupList.size
        }

    }
}
