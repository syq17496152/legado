package io.legado.app.help.http

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CancellationException
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

object OkHttpExceptionInterceptor : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        try {
            return chain.proceed(chain.request())
        } catch (e: IOException) {
            // P2-B 修复：记录 DNS 解析失败的主机名，便于定位是哪些书源域名有问题
            if (e is java.net.UnknownHostException) {
                val host = chain.request().url.host
                AppLog.put("DNS 解析失败: host=${host.take(50)}, path=${chain.request().url.encodedPath.take(50)}")
            }
            throw e
        } catch (e: CancellationException) {
            throw e  // 守卫：协程取消异常必须重新抛出，不能包装成 IOException
        } catch (e: Throwable) {
            throw IOException(e)
        }
    }

}
