package io.legado.app.help.http

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
            throw e
        } catch (e: CancellationException) {
            throw e  // 守卫：协程取消异常必须重新抛出，不能包装成 IOException
        } catch (e: Throwable) {
            throw IOException(e)
        }
    }

}
