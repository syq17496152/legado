package io.legado.app.lib.permission

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.registerForActivityResult
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import androidx.core.content.edit
import androidx.core.net.toUri

class PermissionActivity : AppCompatActivity() {

    private var rationaleDialog: AlertDialog? = null

    private val settingActivityResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            onRequestPermissionFinish()
        }
    private val settingActivityResultAwait =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult())
    private val requestPermissionResult =
        registerForActivityResult(ActivityResultContracts.RequestPermission())
    private val requestPermissionsResult =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions())

    @SuppressLint("BatteryLife")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rationale = intent.getStringExtra(KEY_RATIONALE)
//        val requestCode = intent.getIntExtra(KEY_INPUT_PERMISSIONS_CODE, 1000)
        val permissions = intent.getStringArrayExtra(KEY_INPUT_PERMISSIONS)!!
        when (intent.getIntExtra(KEY_INPUT_REQUEST_TYPE, Request.TYPE_REQUEST_PERMISSION)) {
            //权限请求
            Request.TYPE_REQUEST_PERMISSION -> showSettingDialog(permissions, rationale) {
                lifecycleScope.launch {
                    try {
                        val result = requestPermissionsResult.launch(permissions)
                        if (result.values.all { it }) {
                            onRequestPermissionFinish()
                        } else {
                            openSettingsActivity()
                        }
                    } catch (e: Exception) {
                        AppLog.put("请求权限出错\n$e", e, true)
                        RequestPlugins.sRequestCallback?.onError(e)
                        finish()
                    }
                }
            }
            //跳转到设置界面
            Request.TYPE_REQUEST_SETTING -> showSettingDialog(permissions, rationale) {
                openSettingsActivity()
            }
            //所有文件的管理权限
            Request.TYPE_MANAGE_ALL_FILES_ACCESS -> showSettingDialog(permissions, rationale) {
                try {
                    if (Permissions.isManageExternalStorage()) {
                        val settingIntent =
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        settingActivityResult.launch(settingIntent)
                    } else {
                        throw NoStackTraceException("no MANAGE_ALL_FILES_ACCESS_PERMISSION")
                    }
                } catch (e: Exception) {
                    AppLog.put("请求所有文件的管理权限出错\n$e", e, true)
                    RequestPlugins.sRequestCallback?.onError(e)
                    finish()
                }
            }

            Request.TYPE_REQUEST_NOTIFICATIONS -> showSettingDialog(permissions, rationale) {
                lifecycleScope.launch {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                            && requestPermissionResult.launch(Permissions.POST_NOTIFICATIONS)
                        ) {
                            onRequestPermissionFinish()
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            //这种方案适用于 API 26, 即8.0（含8.0）以上可以用
                            val intent = Intent()
                            intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                            intent.putExtra(Settings.EXTRA_CHANNEL_ID, applicationInfo.uid)
                            settingActivityResult.launch(intent)
                        } else {
                            openSettingsActivity()
                        }
                    } catch (e: Exception) {
                        AppLog.put("请求通知权限出错\n$e", e, true)
                        RequestPlugins.sRequestCallback?.onError(e)
                        finish()
                    }
                }
            }

            Request.TYPE_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> showSettingDialog(
                permissions, rationale
            ) {
                lifecycleScope.launch {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        intent.setData(Uri.parse("package:$packageName"))
                        val className =
                            "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations"
                        val activities = packageManager.queryIntentActivities(
                            intent,
                            PackageManager.MATCH_DEFAULT_ONLY
                        )
                        if (activities.any { it.activityInfo.name == className }) {
                            val component = intent.resolveActivity(packageManager)
                            if (component.className != className) {
                                intent.setClassName("com.android.settings", className)
                                settingActivityResultAwait.launch(intent)
                            }
                        }
                        intent.component = null
                        settingActivityResult.launch(intent)
                    } catch (e: Exception) {
                        AppLog.put("请求后台权限出错\n$e", e, true)
                        RequestPlugins.sRequestCallback?.onError(e)
                        finish()
                    }
                }
            }
            Request.TYPE_SYSTEM_ALERT_WINDOW -> showSettingDialog(permissions, rationale) {
                lifecycleScope.launch {
                    try {
                        if (requestPermissionResult.launch(Permissions.SYSTEM_ALERT_WINDOW)) {
                            onRequestPermissionFinish()
                        } else {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                            settingActivityResult.launch(intent)
                        }
                    } catch (e: Exception) {
                        AppLog.put("请求悬浮窗权限出错\n$e", e, true)
                        RequestPlugins.sRequestCallback?.onError(e)
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) {

        }
    }

    private fun onRequestPermissionFinish() {
        RequestPlugins.sRequestCallback?.onSettingActivityResult()
        finish()
    }

    private fun openSettingsActivity() {
        try {
            val settingIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            settingIntent.data = Uri.fromParts("package", packageName, null)
            settingActivityResult.launch(settingIntent)
        } catch (e: Exception) {
            toastOnUi(R.string.tip_cannot_jump_setting_page)
            RequestPlugins.sRequestCallback?.onError(e)
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        RequestPlugins.sRequestCallback?.onRequestPermissionsResult(
            permissions,
            grantResults
        )
        // NEW-83 修复：授权成功必须清零该权限的拒绝计数。
        // 背景（用户可感）：`getDenyCount(it) > 5` 分支会**直接静音**本次申请并跳设置页（showSettingDialog:216-219），
        // 而计数只在用户点「取消」时累加（:232），**此前从无重置通道**（`resetDenyCount` 全仓零调用）
        // ⇒ 用户前 5 次取消、第 6 次起即便愿意授权也再也看不到系统弹窗，只能去系统设置手动开，
        // 且计数永久残留（除非清数据）⇒ 属「当时赶工漏掉写回」的设计缺陷。
        resetDenyCountsIfGranted(grantResults, permissions, ::resetDenyCount)
        finish()
    }


    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun showSettingDialog(
        permissions: Array<String>,
        rationale: CharSequence?,
        onOk: () -> Unit
    ) {
        rationaleDialog?.dismiss()
        if (rationale.isNullOrEmpty()) {
            finish()
            return
        }
        // NEW-84 修复：连续拒绝超过阈值后**不再静默结束**（旧行为＝用户既看不到弹窗、也没有任何去处）。
        // 改为把用户送到系统设置页（`openSettingsActivity`，失败有 toast 兜底）；配合 NEW-83（授权成功清零计数）
        // ⇒ 用户手动开启权限后不会被永久静音。
        // 注：`Request.toSetting()`（TYPE_REQUEST_SETTING）仍是零调用登记豁免项 —— 其设计路径会再次经过本
        // 阈值闸，走 openSettingsActivity 单点收敛更直接，故本处不改用它。
        permissions.forEach {
            if (getDenyCount(it) > 5) {
                openSettingsActivity()
                return
            }
        }
        rationaleDialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title)
            .setMessage(rationale)
            .setPositiveButton(R.string.dialog_setting) { _, _ ->
                onOk.invoke()
            }
            .setNegativeButton(R.string.dialog_cancel) { _, _ ->
                RequestPlugins.sRequestCallback?.onRequestPermissionsResult(
                    permissions,
                    IntArray(0)
                )
                permissions.forEach { incrementDenyCount(it) }
                finish()
            }.setOnCancelListener {
                RequestPlugins.sRequestCallback?.onRequestPermissionsResult(
                    permissions,
                    IntArray(0)
                )
                finish()
            }
            .show()
    }

    private fun incrementDenyCount(permission: String): Int {
        val prefs = getSharedPreferences("permission_deny_count", MODE_PRIVATE)
        val count = prefs.getInt(permission, 0) + 1
        prefs.edit { putInt(permission, count) }
        return count
    }
    private fun getDenyCount(permission: String): Int {
        val prefs = getSharedPreferences("permission_deny_count", MODE_PRIVATE)
        return prefs.getInt(permission, 0)
    }
    private fun resetDenyCount(permission: String) {
        val prefs = getSharedPreferences("permission_deny_count", MODE_PRIVATE)
        prefs.edit { remove(permission) }
    }

    companion object {

        const val KEY_RATIONALE = "KEY_RATIONALE"
        const val KEY_INPUT_REQUEST_TYPE = "KEY_INPUT_REQUEST_TYPE"
        const val KEY_INPUT_PERMISSIONS_CODE = "KEY_INPUT_PERMISSIONS_CODE"
        const val KEY_INPUT_PERMISSIONS = "KEY_INPUT_PERMISSIONS"
    }
}

/**
 * NEW-83：把「授权成功 ⇒ 拒绝计数清零」抽成**纯函数**，便于 JVM 断言（活动/SharedPreferences 不可 JVM 化）。
 *
 * 只对 `grantResults[i] == PERMISSION_GRANTED` 的位回调 [reset]，返回清零个数。
 * 安全边界：`grantResults` 可能为空数组（用户取消）或长度与 `permissions` 不等 ⇒ 用 [getOrNull] 按下标取值，
 * 不抛 `IndexOutOfBoundsException`。**拒绝不解零**（保留拒绝次数，才能维持「多次拒绝后跳设置页」的既有语义）。
 */
internal fun resetDenyCountsIfGranted(
    grantResults: IntArray,
    permissions: Array<String>,
    reset: (String) -> Unit
): Int {
    var count = 0
    for (i in permissions.indices) {
        if (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) {
            reset(permissions[i])
            count++
        }
    }
    return count
}
