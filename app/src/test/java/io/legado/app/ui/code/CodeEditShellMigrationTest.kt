package io.legado.app.ui.code

import io.legado.app.testkit.SourceFileProbe
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CE 5.2 第 8 页（`activity_code_edit`）**换装的 CB-1 结构不变量**（配对测试，JVM 可跑）。
 *
 * 背景：本页原为「竖向 LinearLayout 壳 + 3 个节点」：
 *   `compose_top_bar`（已 Compose）/ `CodeEditor@editText`（Sora 编辑器内核，`0dp` + weight 1）
 *   / `search_group`（搜索替换面板：命中计数 + 正则开关 + 查找行 + 替换行 + 按钮条，默认 `gone`）。
 * CE 5.2 把页面改为 `composeShell` + `attachComposeContent` 单源承载，**两处 View 内核以 `AndroidView`
 * 原样托管**（Sora 编辑器与面板子树均无 Compose 等价物；面板节点在代码里逐项复刻 XML）。
 *
 * 本测试锁死七类事实，防回归（只写文档的约束一律失效）：
 *   ①单源装配（composeShell + attachComposeContent），且**不再走 viewBinding / 引用 R.layout**
 *   ②XML 已退役（`activity_code_edit.xml` 不存在）
 *   ③两处 View 内核**以 AndroidView 原样托管**，控件类与被 AppCompat 替换后的实际类型一致
 *   ④面板显隐**仍是 View 语义驱动**（不得改成条件组合：会重建视图并叠加监听器/搜索订阅）
 *   ⑤**CB-1 ⑤ insets 链路仍在**（锚点为合成壳 `root`，监听体只更新键盘工具初始内边距）
 *   ⑥宿主业务逻辑**未消失**（逐项断言方法名）
 *   ⑦行为不变量：搜索/替换链路 + 脏态跟踪 + 面板底色走运行时面 token（R30 技术债 code-side 清偿）
 */
class CodeEditShellMigrationTest {

    private val page = "ui/code/CodeEditActivity.kt"

    /**
     * 面板**视图构造**所在文件（4.8c 按职责拆分：约 190 行程序化 View 构造下沉到独立类，
     * 为 800 行单文件门禁让路）。控件类型/内边距/取色口径未变 ⇒ 原断言改指此文件。
     */
    private val panelPage = "ui/code/CodeEditSearchPanelViews.kt"

    private fun src(): String = SourceFileProbe.sourceText(page)

    private fun panelSrc(): String = SourceFileProbe.sourceText(panelPage)

    @Test
    fun singleSourceAssemblyAndNoLegacyBinding() {
        val s = src()
        assertTrue("必须经 composeShell 创建合成壳", s.contains("composeShell(this)"))
        assertTrue("必须走 attachComposeContent 单源挂载", s.contains("binding.root.attachComposeContent {"))
        assertFalse("禁止自行设置 ViewCompositionStrategy", s.contains("ViewCompositionStrategy"))
        assertFalse("换装后不得再走 viewBinding 委托", s.contains("viewBinding("))
        assertFalse("换装后不得再引用已退役布局", s.contains("ActivityCodeEditBinding"))
        assertTrue(
            "宿主必须显式改绑 ViewBinding 泛型参数（否则编译期类型不匹配）",
            s.contains("VMBaseActivity<ViewBinding, CodeEditViewModel>()")
        )
        // 词边界防止把 `import androidx.viewbinding.ViewBinding` 误判为「换装后仍用 binding」
        val bindingRefs = Regex("(?<![A-Za-z])binding\\.[A-Za-z]+").findAll(s).map { it.value }.toSet()
        assertEquals("换装后 binding 只允许 root（合成壳）：${bindingRefs}", setOf("binding.root"), bindingRefs)
    }

    @Test
    fun xmlIsRetired() {
        val f = File(SourceFileProbe.layoutDir(), "activity_code_edit.xml")
        assertFalse("activity_code_edit.xml 应已退役（CE 5.2）", f.exists())
    }

    @Test
    fun viewKernelsHostedViaAndroidView() {
        val s = src()
        assertTrue(
            "两处 View 内核必须以 AndroidView 托管（编辑器 / 搜索替换面板）",
            s.contains("AndroidView(") &&
                s.contains("factory = { editor }") &&
                s.contains("factory = { searchPanel }")
        )
        assertTrue(
            "编辑器必须是程序化构造的 Sora CodeEditor 内核（不得换成普通 EditText）",
            s.contains("CodeEditor(this)") && s.contains("private val editor: CodeEditor by lazy")
        )
        assertTrue(
            "编辑器初值字号必须仍读原 XML 口径（@dimen/text_18sp）",
            s.contains("R.dimen.text_18sp")
        )
        val p = panelSrc()
        assertTrue(
            "查找/替换输入必须是 TextInputLayout + TextInputEditText 成对结构（XML 同构）",
            p.contains("TextInputLayout(context, null)") && p.contains("TextInputEditText(context)")
        )
        assertTrue(
            "正则开关必须仍是 SwitchCompat（XML `<Switch>` 经 AppCompat 膨胀即此类型）",
            p.contains("SwitchCompat(context)")
        )
        assertTrue(
            "按钮条必须复刻原 buttonBar 样式属性（容器 buttonBarStyle / 按钮 buttonBarButtonStyle）",
            p.contains("android.R.attr.buttonBarStyle") &&
                p.contains("android.R.attr.buttonBarButtonStyle") &&
                p.contains("AppCompatButton(context, null, android.R.attr.buttonBarButtonStyle)")
        )
    }

    @Test
    fun panelVisibilityStaysViewDriven() {
        val s = src()
        assertTrue("面板显示必须仍走 View 可见性", s.contains("searchPanel.visibility = View.VISIBLE"))
        assertTrue("面板关闭必须仍走 View 可见性", s.contains("searchPanel.visibility = View.GONE"))
        assertTrue("替换行展开必须仍走 View 可见性", s.contains("replaceGroup.visibility = View.VISIBLE"))
        assertTrue("替换行收起必须仍走 View 可见性", s.contains("replaceGroup.visibility = View.GONE"))
        assertTrue("「全部替换」按钮启用态口径不得丢", s.contains("btnReplaceAll.isEnabled = true"))
        assertTrue("「全部替换」按钮禁用态口径不得丢", s.contains("btnReplaceAll.isEnabled = false"))
        assertFalse(
            "不得把面板改成条件组合（重建视图会叠加文本监听与搜索订阅，行为不等价）",
            s.contains("searchPanelVisible")
        )
    }

    @Test
    fun insetsAnchorChainPreserved() {
        val s = src()
        assertTrue("insets 监听必须仍在", s.contains("setOnApplyWindowInsetsListenerCompat"))
        assertTrue(
            "insets 锚点须为合成壳 root（组合内 AndroidView 不保证收到派发）",
            s.contains("binding.root.setOnApplyWindowInsetsListenerCompat")
        )
        assertTrue(
            "键盘工具初始内边距口径不得改动（原监听体唯一副作用）",
            s.contains("softKeyboardTool.initialPadding = windowInsets.imeHeight")
        )
    }

    @Test
    fun hostLogicPreserved() {
        val s = src()
        listOf(
            "override fun onActivityCreated(",
            "private fun initComposeContent(",
            "private fun initView(",
            "private fun initDirtyTracking(",
            "private fun buildMenuActions(",
            "private fun setSearchOptions(",
            "private fun search(",
            "private fun searchTxt(",
            "private fun updateSearchResults(",
            "private fun save(",
            "override fun upEdit(",
            "override fun initTheme(",
            "override fun upTheme(",
            "override fun finish(",
            "override fun onDestroy(",
            "override fun helpActions(",
            "override fun onHelpActionSelect(",
            "override fun sendText(",
            "override fun onUndoClicked(",
            "override fun onRedoClicked(",
        ).forEach { marker ->
            assertTrue("换装不得删改宿主逻辑：缺少 `$marker`", s.contains(marker))
        }
        // 面板**视图构造**的等价方法（4.8c 拆分后位于独立类；语义逐行不变，故断言不删只改指）
        listOf(
            "private fun panelText(",
            "private fun panelButton(",
            "private fun inputField(",
            "private fun panelCloseIcon(",
        ).forEach { marker ->
            assertTrue("面板视图构造不得删改：缺少 `$marker`", panelSrc().contains(marker))
        }
    }

    @Test
    fun behaviorInvariantsKept() {
        val s = src()
        // 顶栏（原 compose_top_bar：标题 / F196 脏态 / F197 只读 / 搜索 / 保存 / 更多菜单）
        assertTrue("顶栏标题口径必须保留", s.contains("R.string.edit_code"))
        assertTrue("F196 未保存提示必须保留", s.contains("R.string.code_edit_unsaved"))
        assertTrue("F197 只读提示必须保留", s.contains("R.string.code_edit_readonly_bar"))
        assertTrue("F197 只读徽章必须保留", s.contains("R.string.code_edit_readonly_badge"))
        assertTrue("顶栏搜索入口必须保留", s.contains("IconButton(onClick = { search() })"))
        assertTrue("顶栏保存入口必须保留", s.contains("IconButton(onClick = { save(false) })"))
        // 编辑器生命周期口径
        assertTrue("编辑器配色初始化必须保留", s.contains("editor.colorScheme = TextMateColorScheme2.create"))
        assertTrue("编辑器释放链路必须保留", s.contains("editor.release()"))
        // 搜索/替换链路（编辑器内核 API 不得被换掉）
        assertTrue("搜索必须仍走 EditorSearcher", s.contains("editorSearcher.search("))
        assertTrue("上一个/下一个必须保留", s.contains("editorSearcher.gotoPrevious()") && s.contains("editorSearcher.gotoNext()"))
        assertTrue("替换当前/全部必须保留", s.contains("editorSearcher.replaceCurrentMatch(") && s.contains("editorSearcher.replaceAll("))
        assertTrue("命中计数回写必须保留", s.contains("tvSearchResult.text ="))
        assertTrue("正则选项构造必须保留", s.contains("RegexBackrefGrammar.DEFAULT") && s.contains("SearchOptions.TYPE_REGULAR_EXPRESSION"))
        // F196 脏态跟踪
        assertTrue("脏态跟踪必须仍订阅内容变更事件", s.contains("editor.subscribeEvent(ContentChangeEvent::class.java)"))
        assertTrue("初始化期事件必须仍被吞掉", s.contains("editorInitDone = true"))
        // R30 技术债清偿：面板底色改走运行时面 token，且不得出现静态色/字面色回退
        val p = panelSrc()
        assertTrue("面板底色必须走运行时面 token", p.contains("setBackgroundColor(context.themeCardColorOrDefault())"))
        assertFalse("不得回退成静态资源色", p.contains("R.color.background_card"))
        assertFalse("不得用字面色绕门禁", p.contains("Color(0x"))
    }
}