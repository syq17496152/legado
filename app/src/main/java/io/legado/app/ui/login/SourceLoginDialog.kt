package io.legado.app.ui.login

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.data.entities.rule.RowUi.Type
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSelectField
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.GSON
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.openUrl
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class SourceLoginDialog() : ComposeDialogFragment(), SourceLoginJsExtensions.Callback {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private val viewModel by activityViewModels<SourceLoginViewModel>()
    private var lastClickTime: Long = 0
    private var oKToClose = false
    private var hasChange = false
    private var loginUrl: String? = null

    private var rowUis by mutableStateOf<List<RowUi>>(emptyList())
    private var fieldValues by mutableStateOf<Map<String, String>>(emptyMap())
    private var loginInfoState by mutableStateOf<Map<String, String>>(emptyMap())
    private var evaluatedNames by mutableStateOf<Map<String, String>>(emptyMap())
    private var formGeneration by mutableIntStateOf(0)

    /** 优化4（F165）：登录执行态与失败详情——执行期按钮转圈禁点，失败留在表单内可复制/重试 */
    private var loginRunning by mutableStateOf(false)
    private var loginError by mutableStateOf<String?>(null)

    private val sourceLoginJsExtensions by lazy {
        SourceLoginJsExtensions(
            activity as AppCompatActivity,
            viewModel.source,
            viewModel.bookType,
            this
        )
    }

    private var initHandler = false
    private val handler by lazy {
        initHandler = true
        buildMainHandler()
    }

    override fun upUiData(data: Map<String, Any?>?) {
        try {
            activity?.runOnUiThread { // 在主线程中更新 UI
                handleUpUiData(data)
            }
        } catch (e: Exception) {
            AppLog.put("upLoginData Error: " + e.localizedMessage, e)
        }
    }

    override fun reUiView(deltaUp: Boolean) {
        activity?.runOnUiThread {
            handleReUiView(deltaUp)
        }
    }

    private fun handleReUiView(deltaUp: Boolean) {
        val source = viewModel.source ?: return
        val loginUiStr = source.loginUi ?: return
        val codeStr = loginUiStr.let {
            when {
                it.startsWith("@js:") -> it.substring(4)
                it.startsWith("<js>") -> it.substring(4, it.lastIndexOf("<"))
                else -> null
            }
        }
        if (codeStr != null) {
            hasChange = true
            lifecycleScope.launch(Main) {
                val rows = withContext(IO) {
                    val loginUiJson = evalUiJs(codeStr)
                    loginUi(loginUiJson)
                }
                buildRows(source, rows, deltaUp)
            }
        } else {
            buildRows(source, loginUi(loginUiStr), deltaUp)
        }
    }

    private fun handleUpUiData(data: Map<String, Any?>?) {
        hasChange = true
        if (data == null) {
            val newLoginInfo: MutableMap<String, String> = mutableMapOf()
            val newFields = fieldValues.toMutableMap()
            rowUis.forEach { rowUi ->
                val default = rowUi.default
                when (rowUi.type) {
                    Type.text, Type.password -> {
                        val value = default ?: ""
                        newLoginInfo[rowUi.name] = value
                        newFields[rowUi.name] = value
                    }

                    Type.toggle -> {
                        val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                        newLoginInfo[rowUi.name] = default ?: chars.getOrNull(0) ?: ""
                    }

                    Type.select -> {
                        val chars = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null")
                        newLoginInfo[rowUi.name] = default ?: chars.getOrNull(0) ?: ""
                    }
                }
            }
            viewModel.loginInfo = newLoginInfo
            loginInfoState = newLoginInfo.toMap()
            fieldValues = newFields
            return
        }
        data.forEach { (key, value) ->
            val strValue = value?.toString()
            val rowUi = rowUis.firstOrNull { it.name == key }
            if (rowUi == null) {
                setLoginValue(key, strValue ?: "")
                return@forEach
            }
            val resolved = strValue ?: rowUi.default
            when (rowUi.type) {
                Type.text, Type.password -> {
                    val textValue = resolved ?: ""
                    setLoginValue(rowUi.name, textValue)
                    setFieldValue(rowUi.name, textValue)
                }

                Type.button -> {
                    val label = resolved ?: return@forEach
                    evaluatedNames = evaluatedNames + (rowUi.name to label)
                }

                Type.toggle -> {
                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                    setLoginValue(rowUi.name, resolved ?: chars.getOrNull(0) ?: "")
                }

                Type.select -> {
                    val items = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null")
                    val index = items.indexOf(resolved)
                    if (index >= 0) {
                        val char = items[index]
                        if (loginInfoState[rowUi.name] != char) {
                            hasChange = true
                            setLoginValue(rowUi.name, char)
                            rowUi.action?.let {
                                handleButtonClick(viewModel.source, it, rowUi.name, false)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    val palette = style.toMiuixPalette()
                    val source = viewModel.source
                    // 低频操作收纳三点菜单（ui-theme-governance-polish P2，对齐换源弹框范式）
                    var overflowExpanded by remember { mutableStateOf(false) }
                    val overflowActions = listOf(
                        MenuAction(
                            icon = Icons.Filled.Visibility,
                            title = stringResource(R.string.show_login_header),
                            onClick = { showLoginHeaderDialog(source) }
                        ),
                        MenuAction(
                            icon = Icons.Filled.Delete,
                            tint = palette.danger,
                            title = stringResource(R.string.del_login_header),
                            onClick = { confirmRemoveLoginHeader(source) }
                        ),
                        MenuAction(
                            icon = Icons.Filled.Description,
                            title = stringResource(R.string.log),
                            onClick = { showDialogFragment<AppLogDialog>() }
                        )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { dismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        AppDialogFrame(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {},
                            title = getString(R.string.login_source, source?.getTag() ?: ""),
                            scrollContent = true,
                            titleTrailing = {
                                Box {
                                    IconButton(onClick = { overflowExpanded = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.MoreVert,
                                            contentDescription = stringResource(R.string.more)
                                        )
                                    }
                                    AppDropdownMenu(
                                        expanded = overflowExpanded,
                                        onDismiss = { overflowExpanded = false },
                                        actions = overflowActions
                                    )
                                }
                            },
                            content = {
                                LoginRowsContent(source, style)
                                // 优化4（F165）：失败不再只弹 2 秒 toast——错误卡留在表单内，
                                // 异常全文可滚动可复制，处理完（或重试成功）才消失
                                loginError?.let { detail ->
                                    LoginErrorCard(
                                        detail = detail,
                                        style = style,
                                        onRetry = {
                                            loginError = null
                                            source?.let(::login)
                                        }
                                    )
                                }
                            },
                            actions = {
                                LegadoMiuixActionButton(
                                    text = if (loginRunning) {
                                        stringResource(R.string.logging_in)
                                    } else {
                                        stringResource(R.string.ok)
                                    },
                                    palette = palette,
                                    enabled = !loginRunning,
                                    loading = loginRunning,
                                    onClick = {
                                        oKToClose = true
                                        source?.let(::login)
                                    },
                                    primary = true,
                                    cornerRadius = style.actionRadius
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun LoginRowsContent(source: BaseSource?, style: AppDialogStyle) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rowUis.forEachIndexed { index, rowUi ->
                val rowModifier = when (rowUi.type) {
                    Type.text, Type.password, Type.select -> Modifier.fillMaxWidth()
                    else -> {
                        val basis = rowUi.style().layout_flexBasisPercent
                        if (basis in 0f..1f) Modifier.fillMaxWidth(basis) else Modifier
                    }
                }
                Box(modifier = rowModifier) {
                    LoginRowContent(
                        rowUi = rowUi,
                        style = style,
                        fieldValue = fieldValues[rowUi.name],
                        loginValue = loginInfoState[rowUi.name],
                        displayLabel = rowDisplayLabel(rowUi),
                        generation = formGeneration,
                        onFieldValueChange = { value -> setFieldValue(rowUi.name, value) },
                        onSelectOption = { option -> onSelectOption(rowUi, option) },
                        onPress = { downTime, upTime -> onRowPress(rowUi, downTime, upTime) },
                        debouncedAction = rowUi.action?.let { action ->
                            {
                                handleButtonClick(source, action, rowUi.name, false)
                            }
                        }
                    )
                }
            }
        }
    }

    suspend fun evalUiJs(jsStr: String): String? {
        val source = viewModel.source ?: return null
        val loginJS = loginUrl ?: ""
        val result = rowUis.takeIf { it.isNotEmpty() }?.let {
            getLoginData(it)
        } ?: viewModel.loginInfo.toMutableMap()
        return try {
            runScriptWithContext {
                source.evalJS("$loginJS\n$jsStr") {
                    put("result", result)
                    put("book", viewModel.book)
                    put("chapter", viewModel.chapter)
                }.toString()
            }
        } catch (e: Exception) {
            AppLog.put(source.getTag() + " loginUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    fun loginUi(json: String?): List<RowUi>? {
        return GSON.fromJsonArray<RowUi>(json).onFailure {
            AppLog.put("loginUi json parse err:" + it.localizedMessage, it)
        }.getOrNull()
    }

    private fun buildRows(source: BaseSource, rows: List<RowUi>?, deltaUp: Boolean) {
        val safeRows = rows.orEmpty()
        rowUis = safeRows
        if (!deltaUp) {
            formGeneration++
        }
        val nextFields = if (deltaUp) fieldValues.toMutableMap() else mutableMapOf()
        val loginInfoMap = viewModel.loginInfo
        safeRows.forEach { rowUi ->
            when (rowUi.type) {
                Type.text, Type.password -> {
                    if (!nextFields.containsKey(rowUi.name)) {
                        nextFields[rowUi.name] = loginInfoMap[rowUi.name] ?: rowUi.default ?: ""
                    }
                }

                Type.select -> {
                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null")
                    val infoV = loginInfoMap[rowUi.name]
                    val char = if (infoV.isNullOrEmpty()) {
                        hasChange = true
                        rowUi.default ?: chars.getOrNull(0) ?: ""
                    } else {
                        infoV
                    }
                    loginInfoMap[rowUi.name] = char
                }

                Type.toggle -> {
                    val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
                    val infoV = loginInfoMap[rowUi.name]
                    val char = if (infoV.isNullOrEmpty()) {
                        hasChange = true
                        rowUi.default ?: chars.getOrNull(0) ?: ""
                    } else {
                        infoV
                    }
                    loginInfoMap[rowUi.name] = char
                }
            }
            val viewName = rowUi.viewName
            if (viewName != null && !isQuotedViewName(viewName)) {
                execute {
                    evalUiJs(viewName)
                }.onSuccess { n ->
                    evaluatedNames = evaluatedNames + (rowUi.name to if (n.isNullOrEmpty()) "null" else n)
                }.onError { _ ->
                    evaluatedNames = evaluatedNames + (rowUi.name to "err")
                }
            }
        }
        fieldValues = nextFields
        loginInfoState = loginInfoMap.toMap()
    }

    private fun rowDisplayLabel(rowUi: RowUi): String {
        val viewName = rowUi.viewName ?: return rowUi.name
        return if (isQuotedViewName(viewName)) {
            viewName.substring(1, viewName.length - 1)
        } else {
            evaluatedNames[rowUi.name] ?: rowUi.name
        }
    }

    private fun isQuotedViewName(viewName: String): Boolean {
        return viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\''
    }

    private fun setFieldValue(name: String, value: String) {
        fieldValues = fieldValues + (name to value)
    }

    private fun setLoginValue(name: String, value: String) {
        viewModel.loginInfo[name] = value
        loginInfoState = viewModel.loginInfo.toMap()
    }

    private fun onRowPress(rowUi: RowUi, downTime: Long, upTime: Long) {
        if (upTime - lastClickTime < 200) {
            return
        }
        lastClickTime = upTime
        val isLongClick = upTime > downTime + 666
        if (rowUi.type == Type.toggle) {
            cycleToggle(rowUi, isLongClick)
        } else {
            handleButtonClick(viewModel.source, rowUi.action, rowUi.name, isLongClick)
        }
    }

    private fun cycleToggle(rowUi: RowUi, isLongClick: Boolean) {
        val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
        val current = loginInfoState[rowUi.name].orEmpty()
        val nextIndex = (chars.indexOf(current) + 1) % chars.size
        val char = chars.getOrNull(nextIndex) ?: ""
        hasChange = true
        setLoginValue(rowUi.name, char)
        handleButtonClick(viewModel.source, rowUi.action, rowUi.name, isLongClick)
    }

    private fun onSelectOption(rowUi: RowUi, option: String) {
        hasChange = true
        setLoginValue(rowUi.name, option)
        rowUi.action?.let {
            handleButtonClick(viewModel.source, it, rowUi.name, false)
        }
    }

    /**
     * 修复2（A1-2 破坏性操作三要素）：删除登录头会一并清掉用户名/密码/Cookie（源站会话失效，不可撤销），
     * 原实现菜单一点即删；补二次确认（对象名 + 影响面 + 红色确认键），确认文案复用菜单项标题真值。
     */
    private fun confirmRemoveLoginHeader(source: BaseSource?) {
        source ?: return
        showComposeConfirmDialog(
            title = getString(R.string.del_login_header),
            message = getString(R.string.del_login_header_confirm, source.getTag()),
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { source.removeLoginHeader() }
        )
    }

    private fun showLoginHeaderDialog(source: BaseSource?) {
        alert {
            setTitle(R.string.login_header)
            source?.getLoginHeader()?.let { loginHeader ->
                setMessage(loginHeader)
                positiveButton(R.string.copy_text) {
                    appCtx.sendToClip(loginHeader)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val source = viewModel.source ?: return
        loginUrl = source.getLoginJs()
        val loginUiStr = source.loginUi ?: return
        val codeStr = loginUiStr.let {
            when {
                it.startsWith("@js:") -> it.substring(4)
                it.startsWith("<js>") -> it.substring(4, it.lastIndexOf("<"))
                else -> null
            }
        }
        if (codeStr != null) {
            lifecycleScope.launch(Main) {
                val rows = withContext(IO) {
                    val loginUiJson = evalUiJs(codeStr)
                    loginUi(loginUiJson)
                }
                buildRows(source, rows, false)
            }
        } else {
            buildRows(source, loginUi(loginUiStr), false)
        }
    }

    private fun handleButtonClick(source: BaseSource?, action: String?, name: String, isLongClick: Boolean) {
        val currentRows = rowUis
        lifecycleScope.launch(IO) {
            if (action.isAbsUrl()) {
                context?.openUrl(action!!)
            } else if (action != null) {
                // JavaScript
                val evalSource = source ?: return@launch
                val buttonFunctionJS = action
                val loginJS = loginUrl ?: return@launch
                kotlin.runCatching {
                    runScriptWithContext {
                        evalSource.evalJS("$loginJS\n$buttonFunctionJS") {
                            put("java", sourceLoginJsExtensions)
                            put("result", getLoginData(currentRows))
                            put("book", viewModel.book)
                            put("chapter", viewModel.chapter)
                            put("isLongClick", isLongClick)
                        }
                    }
                }.onFailure { e ->
                    ensureActive()
                    AppLog.put("LoginUI Button $name JavaScript error", e)
                }
            }
        }
    }

    private fun getLoginData(rows: List<RowUi>?): MutableMap<String, String> {
        val loginData = hashMapOf<String, String>()
        rows?.forEach { rowUi ->
            when (rowUi.type) {
                Type.text, Type.password -> {
                    // 没文本的时候存空字符串,而不是删除loginInfo
                    loginData[rowUi.name] = fieldValues[rowUi.name] ?: rowUi.default ?: ""
                }
            }
        }
        return viewModel.loginInfo.toMutableMap().apply { putAll(loginData) }
    }

    private fun login(source: BaseSource) {
        val currentRows = rowUis
        if (loginRunning) return
        loginRunning = true
        loginError = null
        lifecycleScope.launch(IO) {
            try {
                val loginData = getLoginData(currentRows)
                if (loginData.isEmpty()) {
                    source.removeLoginInfo()
                    withContext(Main) {
                        dismiss()
                    }
                } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                    val buttonFunctionJS = "if (typeof login=='function'){ login.apply(this); } else { throw('Function login not implements!!!') }"
                    val loginJS = loginUrl ?: return@launch
                    runScriptWithContext {
                        source.evalJS("$loginJS\n$buttonFunctionJS") {
                            put("java", sourceLoginJsExtensions)
                            put("result", loginData)
                            put("book", viewModel.book)
                            put("chapter", viewModel.chapter)
                            put("isLongClick", false)
                        }
                    }
                    // 先提示后关窗（原序）：dismiss 内部会 finish 宿主 Activity，toast 需在窗口销毁前发出
                    context?.toastOnUi(R.string.success)
                    withContext(Main) {
                        dismiss()
                    }
                }
            } catch (e: CancellationException) {
                // 协程取消不是登录失败（宿主销毁/弹窗关闭），必须原样抛出，否则会误报错误卡
                throw e
            } catch (e: Exception) {
                AppLog.put("登录出错\n${e.localizedMessage}", e)
                e.printOnDebug()
                // 优化4（F165）：失败升级为表单内错误卡（完整堆栈可复制 + 重试），不再 2 秒即逝
                withContext(Main) {
                    loginError = e.stackTraceToString()
                }
            } finally {
                withContext(Main) {
                    loginRunning = false
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!oKToClose && hasChange) {
            val loginInfo = viewModel.loginInfo
            if (loginInfo.isEmpty()) {
                viewModel.source?.removeLoginInfo()
            } else {
                viewModel.source?.putLoginInfo(GSON.toJson(loginInfo))
            }
        }
        if (initHandler) {
            handler.removeCallbacksAndMessages(null)
        }
        // 保留：弹窗销毁前清理执行态，避免重建实例读到上一次的进行中状态
        loginRunning = false
        super.onDismiss(dialog)
        // 真机实证缺陷修复（2026-09-20）：宿主因配置变更/主题事件**重建**时弹窗会被一起销毁，
        // 此处若仍 finish 宿主 ⇒ 一次重建就整页消失（冷启动即命中原生 RECREATE 事件：书源登录表单
        // 分支一进就被关掉；WEB 分支无弹窗故无此现象）。故只在「用户主动关闭」时结束宿主页。
        if (userClosed) {
            activity?.finish()
        }
    }

    private fun <T> execute(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(lifecycleScope, context) { block() }

    /** 用户主动关闭标记（见 [onDismiss]）：点外部/返回键/成功路径都会置位，宿主重建销毁不会 */
    private var userClosed = false

    override fun dismiss() {
        userClosed = true
        super.dismiss()
    }

    override fun onCancel(dialog: DialogInterface) {
        // 返回键 / 系统取消同属「用户主动关闭」
        userClosed = true
        super.onCancel(dialog)
    }

}

@Composable
private fun LoginRowContent(
    rowUi: RowUi,
    style: AppDialogStyle,
    fieldValue: String?,
    loginValue: String?,
    displayLabel: String,
    generation: Int,
    onFieldValueChange: (String) -> Unit,
    onSelectOption: (String) -> Unit,
    onPress: (Long, Long) -> Unit,
    debouncedAction: (() -> Unit)?
) {
    when (rowUi.type) {
        Type.text -> LoginTextFieldRow(
            rowUi = rowUi,
            label = displayLabel,
            value = fieldValue ?: "",
            isPassword = false,
            textAlign = textAlignFor(rowUi),
            style = style,
            generation = generation,
            onValueChange = onFieldValueChange,
            debouncedAction = debouncedAction
        )

        Type.password -> LoginTextFieldRow(
            rowUi = rowUi,
            label = displayLabel,
            value = fieldValue ?: "",
            isPassword = true,
            textAlign = textAlignFor(rowUi),
            style = style,
            generation = generation,
            onValueChange = onFieldValueChange,
            debouncedAction = debouncedAction
        )

        Type.select -> LoginSelectRow(
            label = displayLabel,
            selected = loginValue ?: "",
            options = rowUi.chars?.filterNotNull() ?: listOf("chars", "is null"),
            palette = style.toMiuixPalette(),
            onSelected = onSelectOption
        )

        Type.button -> LoginPressRow(
            text = displayLabel,
            style = style,
            onPress = onPress
        )

        Type.toggle -> {
            val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
            val char = loginValue ?: chars.getOrNull(0) ?: ""
            val left = rowUi.style().layout_justifySelf != "right"
            val text = if (left) char + displayLabel else displayLabel + char
            LoginPressRow(
                text = text,
                style = style,
                onPress = onPress
            )
        }
    }
}

private fun textAlignFor(rowUi: RowUi): TextAlign {
    return when (rowUi.style().layout_justifySelf) {
        "center" -> TextAlign.Center
        "flex_end" -> TextAlign.End
        else -> TextAlign.Start
    }
}

@Composable
private fun LoginTextFieldRow(
    rowUi: RowUi,
    label: String,
    value: String,
    isPassword: Boolean,
    textAlign: TextAlign,
    style: AppDialogStyle,
    generation: Int,
    onValueChange: (String) -> Unit,
    debouncedAction: (() -> Unit)?
) {
    var armed by remember(rowUi.name, generation) { mutableStateOf(false) }
    // 优化5（F166）：密码行默认掩码，可在行内切换明文逐位核对（状态只在本次弹窗内记忆，不落盘）
    var passwordVisible by remember(rowUi.name, generation) { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (debouncedAction == null) return@LaunchedEffect
        if (!armed) {
            armed = true
            return@LaunchedEffect
        }
        delay(600)
        debouncedAction.invoke()
    }
    val passwordTrailingIcon: (@Composable () -> Unit)? = if (isPassword) {
        {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = stringResource(
                        if (passwordVisible) R.string.password_hide else R.string.password_show
                    )
                )
            }
        }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = isPassword,
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = passwordTrailingIcon,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = style.primaryText,
            textAlign = textAlign
        ),
        shape = RoundedCornerShape(style.actionRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            cursorColor = style.accent,
            focusedBorderColor = style.accent.copy(alpha = 0.55f),
            unfocusedBorderColor = style.stroke,
            focusedLabelColor = style.secondaryText,
            unfocusedLabelColor = style.secondaryText
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun LoginSelectRow(
    label: String,
    selected: String,
    options: List<String>,
    palette: LegadoMiuixPalette,
    onSelected: (String) -> Unit
) {
    LegadoMiuixSelectField(
        label = label,
        options = options,
        selected = selected,
        optionLabel = { it },
        onSelected = onSelected,
        palette = palette,
        compact = true
    )
}

@Composable
private fun LoginPressRow(
    text: String,
    style: AppDialogStyle,
    onPress: (Long, Long) -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val latestOnPress by rememberUpdatedState(onPress)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(style.actionRadius))
            .background(if (pressed) style.accent.copy(alpha = 0.16f) else style.fieldSurface)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        val downTime = System.currentTimeMillis()
                        val released = tryAwaitRelease()
                        pressed = false
                        if (!released) return@detectTapGestures
                        latestOnPress(downTime, System.currentTimeMillis())
                    }
                )
            }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = style.primaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 优化4（F165）登录失败错误卡：标题沿用源码真值文案「登录出错」，正文为完整异常堆栈
 * （等宽字体 + 限高滚动，不外溢撑破弹窗），动作「复制错误详情 / 重试」——
 * 书源排障的第一句对话永远是「把报错发我」，复制按钮直接服务这个流程。
 */
@Composable
private fun LoginErrorCard(
    detail: String,
    style: AppDialogStyle,
    onRetry: () -> Unit
) {
    val palette = style.toMiuixPalette()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(style.actionRadius))
            .background(style.danger.copy(alpha = 0.13f))
            .padding(12.dp)
    ) {
        Text(
            text = stringResource(R.string.login_error),
            color = style.danger,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Text(
            text = detail,
            color = style.primaryText,
            fontFamily = FontFamily.Monospace,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .padding(top = 6.dp)
                .verticalScroll(rememberScrollState())
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.End
        ) {
            LegadoMiuixActionButton(
                text = stringResource(R.string.copy_error_detail),
                palette = palette,
                onClick = { appCtx.sendToClip(detail) },
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.retry),
                palette = palette,
                onClick = onRetry,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    }
}
