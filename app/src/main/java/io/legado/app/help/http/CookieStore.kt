@file:Suppress("unused")

package io.legado.app.help.http

import android.text.TextUtils
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern.equalsRegex
import io.legado.app.constant.AppPattern.semicolonRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Cookie
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieManager.getCookieNoSession
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.help.http.api.CookieManagerInterface
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.removeCookie
import io.legado.app.utils.splitNotBlank

/**
 * Tracking Cookie 识别（用于 LRU 淘汰时优先删除）
 * 纯函数，无 Android 依赖，便于 JVM 单元测试
 */
private val trackingCookiePrefixes = listOf("_ga", "_gid", "_gat", "_hjid")

private val trackingCookieRegex = Regex("^Hm_(lvt|lpvt)_.*")

fun isTrackingCookieKey(key: String): Boolean {
    val trimmed = key.trim()
    if (trackingCookiePrefixes.any { trimmed == it || trimmed.startsWith("${it}_") }) {
        return true
    }
    return trackingCookieRegex.matches(trimmed)
}

/**
 * 选择下一删除 key：优先 tracking Cookie，其次按 key 长度降序
 * 纯函数，便于单元测试；不依赖 CookieStore object 状态，避免触发 Android 初始化
 */
fun selectCookieKeyToRemove(cookieMap: Map<String, String>): String? {
    if (cookieMap.isEmpty()) return null
    // 1. 优先删除 tracking Cookie（取 key 最长者，最大化释放空间）
    val trackingKey = cookieMap.keys
        .filter { isTrackingCookieKey(it) }
        .maxByOrNull { it.length }
    if (trackingKey != null) return trackingKey
    // 2. 其次按 key 长度降序删除（长 key 通常是追踪/临时 token，短 key 如 JSESSIONID/token/sid 通常是登录态）
    return cookieMap.keys.maxByOrNull { it.length }
}

@Keep
object CookieStore : CookieManagerInterface {

    /**
     *保存cookie到数据库，会自动识别url的二级域名
     *
     * P18 修复：null 跳过（WebView Cookie 未就绪时的误调用，防止覆盖有效 Cookie）
     * "" 保留原版行为：允许通过空串清除旧 cookie（issue7 回归修复场景）
     * WebViewModel.kt L135 注释佐证：根因是 setCookie 空值覆盖导致 refetch 不带 Cookie 被服务器拒绝
     * 已知上限：无法区分"WebView未就绪null"和"主动清除null"，但 android.webkit.CookieManager.getCookie 返回 null 表示无 Cookie，不会主动清除 | 升级路径：新增 removeCookie(url) 方法分离清除语义
     */
    override fun setCookie(url: String, cookie: String?) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            // P18 修复：null 跳过，防止 WebView Cookie 未就绪时覆盖有效 Cookie
            if (cookie == null) {
                AppLog.put("setCookie: cookie为null，跳过覆盖 (domain=$domain)")
                return
            }
            // "" 保留原版行为：允许通过空串清除旧 cookie（issue7 回归修复）
            CacheManager.putMemory("${domain}_cookie", cookie)
            val cookieBean = Cookie(domain, cookie)
            appDb.cookieDao.insert(cookieBean)
        } catch (e: Exception) {
            AppLog.put("保存Cookie失败\n$e", e)
        }
    }

    fun setWebCookie(url: String, cookie: String) {
        try {
            val baseUrl = NetworkUtils.getBaseUrl(url) ?: return
            val cookies = cookie.splitNotBlank(";")
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeSessionCookies(null)
            cookies.forEach {
                cookieManager.setCookie(baseUrl, it)
            }
        } catch (e: Exception) {
            AppLog.put("设置WebCookie失败\n$e", e)
        }
    }

    /**
     * P1+P6+P9 修复：replaceCookie 空值删除标记 + 同步保护
     *
     * P9: 读-改-写非原子，并发场景可能丢失更新 → synchronized 同步保护
     * P6: cookieToMap 过滤空值，导致服务端 Set-Cookie: key=; max-age=0 无法删除旧值
     *     修复：新增 cookieToMapWithEmpty 保留空值，空值视为删除标记
     * P1: 过期 Cookie 清理通过 P6 的删除标记机制实现
     *
     * 已知上限：synchronized 粒度为整个 CookieStore object，并发性能下降但 Cookie 写入频率低可接受 | 升级路径：改用细粒度锁按 domain 加锁
     */
    override fun replaceCookie(url: String, cookie: String) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(cookie)) {
            return
        }
        // P9 修复：同步保护，防止并发读改写竞态
        synchronized(this) {
            val oldCookie = getCookieNoSession(url)
            if (TextUtils.isEmpty(oldCookie)) {
                setCookie(url, cookie)
            } else {
                val cookieMap = cookieToMap(oldCookie)
                val newMap = cookieToMapWithEmpty(cookie)
                // P6 修复：空值视为删除标记（服务端 Set-Cookie: key=; max-age=0）
                newMap.forEach { (k, v) ->
                    if (v.isBlank()) {
                        cookieMap.remove(k)
                    } else {
                        cookieMap[k] = v
                    }
                }
                val newCookie = mapToCookie(cookieMap)
                setCookie(url, newCookie)
            }
        }
    }

    /**
     * 解析 cookie 字符串为 map，保留空值（用于检测删除标记）
     * 与 cookieToMap 的区别：空值不会被过滤，用于 replaceCookie 检测服务端的删除标记
     * 服务端通过 Set-Cookie: key=; max-age=0 标记 Cookie 过期，空值是删除指令
     */
    private fun cookieToMapWithEmpty(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank()) {
            return cookieMap
        }
        val pairArray = cookie.split(semicolonRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pair in pairArray) {
            val pairs = pair.split(equalsRegex, 2).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size <= 1) {
                continue
            }
            val key = pairs[0].trim { it <= ' ' }
            val value = pairs[1].trim { it <= ' ' }
            cookieMap[key] = value
        }
        return cookieMap
    }

    /**
     *获取url所属的二级域名的cookie
     */
    override fun getCookie(url: String): String {
        val domain = NetworkUtils.getSubDomain(url)

        val cookie = getCookieNoSession(url)
        val sessionCookie = CookieManager.getSessionCookie(domain)

        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)

        var ck = mapToCookie(cookieMap) ?: ""
        while (ck.length > 4096) {
            // LRU 淘汰：优先 tracking Cookie，其次 key 长度降序，避免随机删除误伤登录态
            val removeKey = selectCookieKeyToRemove(cookieMap) ?: break
            CookieManager.removeCookie(url, removeKey)
            cookieMap.remove(removeKey)
            ck = mapToCookie(cookieMap) ?: ""
        }
        return ck
    }

    fun getKey(url: String, key: String): String {
        val cookie = getCookie(url)
        val sessionCookie = CookieManager.getSessionCookie(url)
        val cookieMap = mergeCookiesToMap(cookie, sessionCookie)
        return cookieMap[key] ?: ""
    }

    override fun removeCookie(url: String) {
        val domain = NetworkUtils.getSubDomain(url)
        appDb.cookieDao.delete(domain)
        CacheManager.deleteMemory("${domain}_cookie")
        CacheManager.deleteMemory("${domain}_session_cookie")
        android.webkit.CookieManager.getInstance().removeCookie(url)
    }

    override fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank()) {
            return cookieMap
        }
        val pairArray = cookie.split(semicolonRegex).dropLastWhile { it.isEmpty() }.toTypedArray()
        for (pair in pairArray) {
            val pairs = pair.split(equalsRegex, 2).dropLastWhile { it.isEmpty() }.toTypedArray()
            if (pairs.size <= 1) {
                continue
            }
            val key = pairs[0].trim { it <= ' ' }
            val value = pairs[1]
            if (value.isNotBlank() || value.trim { it <= ' ' } == "null") {
                cookieMap[key] = value.trim { it <= ' ' }
            }
        }
        return cookieMap
    }

    override fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) {
            return null
        }
        val builder = StringBuilder()
        cookieMap.keys.forEachIndexed { index, key ->
            if (index > 0) builder.append("; ")
            builder.append(key).append("=").append(cookieMap[key])
        }
        return builder.toString()
    }

    fun clear() {
        appDb.cookieDao.deleteOkHttp()
    }

}