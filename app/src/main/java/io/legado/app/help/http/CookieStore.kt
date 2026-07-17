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
     */
    override fun setCookie(url: String, cookie: String?) {
        try {
            val domain = NetworkUtils.getSubDomain(url)
            // Issue-7 调试日志：追踪 cookie 保存链路（脱敏：只记录长度和域名前3字符）
            if (cookie.isNullOrEmpty()) {
                AppLog.put("[CookieDebug] setCookie skipped: domainPrefix=${domain.take(3)}, domainLen=${domain.length}, reason=nullOrEmpty")
                return
            }
            CacheManager.putMemory("${domain}_cookie", cookie)
            val cookieBean = Cookie(domain, cookie)
            appDb.cookieDao.insert(cookieBean)
            AppLog.put("[CookieDebug] setCookie saved: domainPrefix=${domain.take(3)}, domainLen=${domain.length}, cookieLen=${cookie.length}")
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

    override fun replaceCookie(url: String, cookie: String) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(cookie)) {
            return
        }
        val oldCookie = getCookieNoSession(url)
        if (TextUtils.isEmpty(oldCookie)) {
            setCookie(url, cookie)
        } else {
            val cookieMap = cookieToMap(oldCookie)
            cookieMap.putAll(cookieToMap(cookie))
            val newCookie = mapToCookie(cookieMap)
            setCookie(url, newCookie)
        }
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
        var lruTriggered = false
        while (ck.length > 4096) {
            lruTriggered = true
            // LRU 淘汰：优先 tracking Cookie，其次 key 长度降序，避免随机删除误伤登录态
            val removeKey = selectCookieKeyToRemove(cookieMap) ?: break
            CookieManager.removeCookie(url, removeKey)
            cookieMap.remove(removeKey)
            ck = mapToCookie(cookieMap) ?: ""
        }
        // Issue-7 调试日志：追踪 cookie 读取链路（脱敏：只记录长度和域名前3字符）
        AppLog.put("[CookieDebug] getCookie: domainPrefix=${domain.take(3)}, domainLen=${domain.length}, cookieLen=${ck.length}, sessionLen=${sessionCookie?.length ?: 0}, noSessionLen=${cookie.length}, lruTriggered=$lruTriggered, keyCount=${cookieMap.size}")
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