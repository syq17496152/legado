package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.databinding.ActivityParagraphRuleEditBinding
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.widget.code.addJsPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getClipText
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadMenuCustomButtonEditActivity : BaseActivity<ActivityParagraphRuleEditBinding>() {

    override val binding by viewBinding(ActivityParagraphRuleEditBinding::inflate)
    private var button = ReadMenuCustomButton()
    private var focusedEditText: EditText? = null

    private val textEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val view = focusedEditText ?: return@registerForActivityResult
            result.data?.getStringExtra("text")?.let { view.setText(it) }
            result.data?.getIntExtra("cursorPosition", -1)
                ?.takeIf { it in 0..view.text.length }
                ?.let { view.setSelection(it) }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        val id = intent.getLongExtra("id", 0L)
        lifecycleScope.launch {
            button = withContext(Dispatchers.IO) {
                appDb.readMenuCustomButtonDao.get(id)
            } ?: ReadMenuCustomButton()
            bindButton()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.paragraph_rule_edit, menu)
        menu.findItem(R.id.menu_debug_rule)?.isVisible = false
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_fullscreen_edit -> onFullEditClicked()
            R.id.menu_save -> save()
            R.id.menu_copy_rule -> sendToClip(GSON.toJson(getButton()))
            R.id.menu_paste_rule -> pasteButton()
            R.id.menu_help -> showHelp("readMenuCustomButtonHelp")
        }
        return true
    }

    private fun initView() = binding.run {
        titleBar.title = getString(R.string.read_menu_custom_button_edit)
        tilScript.hint = getString(R.string.read_menu_button_script)
        listOf(etLoginUrl, etLoginUi, etScript, etJsLib).forEach { codeView ->
            codeView.addJsPattern()
            codeView.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus && v is EditText) focusedEditText = v
            }
        }
        etName.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is EditText) focusedEditText = v
        }
        etTimeout.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus && v is EditText) focusedEditText = v
        }
    }

    private fun bindButton() = binding.run {
        etName.setText(button.name)
        etLoginUrl.setText(button.loginUrl)
        etLoginUi.setText(button.loginUi)
        cbIsEnableCookie.isChecked = button.enabledCookieJar
        etTimeout.setText(button.validTimeout().toString())
        etScript.setText(button.script)
        etJsLib.setText(button.jsLib)
    }

    private fun onFullEditClicked() {
        val view = (window.decorView.findFocus() as? EditText) ?: focusedEditText
        if (view == null) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        focusedEditText = view
        textEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", view.text.toString())
            putExtra("title", hintFor(view))
            putExtra("cursorPosition", view.selectionStart)
        })
    }

    private fun hintFor(view: View): String? = when (view.id) {
        R.id.et_name -> getString(R.string.name)
        R.id.et_login_url -> getString(R.string.login_url)
        R.id.et_login_ui -> getString(R.string.login_ui)
        R.id.et_timeout -> getString(R.string.timeout_millisecond)
        R.id.et_script -> getString(R.string.read_menu_button_script)
        R.id.et_js_lib -> "jsLib"
        else -> null
    }

    private fun pasteButton() {
        val raw = getClipText()
        val imported = GSON.fromJsonObject<ReadMenuCustomButton>(raw).getOrNull()
        if (imported == null) {
            toastOnUi(R.string.wrong_format)
            return
        }
        button = imported.copy(id = button.id, order = button.order, updateTime = System.currentTimeMillis())
        bindButton()
    }

    private fun getButton(): ReadMenuCustomButton = binding.run {
        button.copy(
            name = etName.text?.toString().orEmpty().trim(),
            loginUrl = etLoginUrl.text?.toString().orEmpty(),
            loginUi = etLoginUi.text?.toString().orEmpty(),
            enabledCookieJar = cbIsEnableCookie.isChecked,
            timeoutMillisecond = etTimeout.text?.toString()?.toLongOrNull() ?: 3000L,
            script = etScript.text?.toString().orEmpty(),
            jsLib = etJsLib.text?.toString().orEmpty(),
            updateTime = System.currentTimeMillis()
        )
    }

    private fun save() {
        val edited = getButton()
        if (edited.name.isBlank() || edited.script.isBlank()) {
            toastOnUi(R.string.read_menu_custom_button_save_invalid)
            return
        }
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                if (edited.id == 0L) {
                    val order = (appDb.readMenuCustomButtonDao.maxOrder() ?: 0) + 1
                    appDb.readMenuCustomButtonDao.insert(edited.copy(order = order))
                } else {
                    appDb.readMenuCustomButtonDao.update(edited)
                    edited.id
                }
            }
            postEvent(EventBus.READ_MENU_BUTTON_CHANGED, true)
            setResult(Activity.RESULT_OK, Intent().putExtra("id", id))
            finish()
        }
    }

}
