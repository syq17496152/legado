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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
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
                            title = stringResource(R.string.del_login_header),
                            onClick = { source?.removeLoginHeader() }
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
                            },
                            actions = {
                                LegadoMiuixActionButton(
                                    text = stringResource(R.string.ok),
                                    palette = palette,
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
        lifecycleScope.launch(IO) {
            val loginData = getLoginData(currentRows)
            if (loginData.isEmpty()) {
                source.removeLoginInfo()
                withContext(Main) {
                    dismiss()
                }
            } else if (source.putLoginInfo(GSON.toJson(loginData))) {
                try {
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
                    context?.toastOnUi(R.string.success)
                    withContext(Main) {
                        dismiss()
                    }
                } catch (e: Exception) {
                    AppLog.put("登录出错\n${e.localizedMessage}", e)
                    context?.toastOnUi("登录出错\n${e.localizedMessage}")
                    e.printOnDebug()
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
        super.onDismiss(dialog)
        activity?.finish()
    }

    private fun <T> execute(
        context: CoroutineContext = Dispatchers.IO,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(lifecycleScope, context) { block() }

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
    LaunchedEffect(value) {
        if (debouncedAction == null) return@LaunchedEffect
        if (!armed) {
            armed = true
            return@LaunchedEffect
        }
        delay(600)
        debouncedAction.invoke()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = isPassword,
        visualTransformation = if (isPassword) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
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
