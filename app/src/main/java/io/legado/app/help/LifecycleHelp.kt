package io.legado.app.help

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.legado.app.base.BaseService
import io.legado.app.utils.LogUtils
import java.lang.ref.WeakReference

/**
 * Activity管理器,管理项目中Activity的状态
 */
@Suppress("unused")
object LifecycleHelp : Application.ActivityLifecycleCallbacks {

    private const val TAG = "LifecycleHelp"

    private val activities: MutableList<WeakReference<Activity>> = arrayListOf()
    private val services: MutableList<WeakReference<BaseService>> = arrayListOf()
    private var appFinishedListener: (() -> Unit)? = null

    fun activitySize(): Int {
        return activities.size
    }

    /**
     * 判断指定Activity是否存在
     */
    fun isExistActivity(activityClass: Class<*>): Boolean {
        activities.forEach { item ->
            if (item.get()?.javaClass == activityClass) {
                return true
            }
        }
        return false
    }

    /**
     * 关闭指定 activity(class)
     */
    fun finishActivity(vararg activityClasses: Class<*>) {
        val waitFinish = ArrayList<WeakReference<Activity>>()
        for (temp in activities) {
            for (activityClass in activityClasses) {
                if (temp.get()?.javaClass == activityClass) {
                    waitFinish.add(temp)
                    break
                }
            }
        }
        waitFinish.forEach {
            it.get()?.finish()
        }
    }

    fun setOnAppFinishedListener(appFinishedListener: (() -> Unit)) {
        this.appFinishedListener = appFinishedListener
    }

    override fun onActivityPaused(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onPause")
        DebugFloatBallManager.onActivityPaused(activity)
    }

    override fun onActivityResumed(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onResume")
        DebugFloatBallManager.onActivityResumed(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onStart")
    }

    override fun onActivityDestroyed(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onDestroy")
        DebugFloatBallManager.onActivityDestroyed(activity)
        activities.removeAll { it.get() == null || it.get() === activity }
        if (services.isEmpty() && activities.isEmpty()) {
            onAppFinished()
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        LogUtils.d(TAG, "${activity::class.simpleName} onSaveInstanceState")
    }

    override fun onActivityStopped(activity: Activity) {
        LogUtils.d(TAG, "${activity::class.simpleName} onStop")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        LogUtils.d(TAG, "${activity::class.simpleName} onCreate")
        activities.add(WeakReference(activity))
    }

    @Synchronized
    fun onServiceCreate(service: BaseService) {
        LogUtils.d(TAG, "${service::class.simpleName} onCreate")
        services.add(WeakReference(service))
    }

    @Synchronized
    fun onServiceDestroy(service: BaseService) {
        LogUtils.d(TAG, "${service::class.simpleName} onDestroy")
        services.removeAll { it.get() == null || it.get() === service }
        if (services.isEmpty() && activities.isEmpty()) {
            onAppFinished()
        }
    }

    private fun onAppFinished() {
        appFinishedListener?.invoke()
    }
}