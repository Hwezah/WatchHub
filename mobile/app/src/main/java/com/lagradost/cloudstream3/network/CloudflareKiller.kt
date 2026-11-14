package com.lagradost.cloudstream3.network

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URI

/**
 * An OkHttp interceptor plugins attach to sites behind Cloudflare's browser check (compat; see
 * CloudStreamApp.kt). A 403/503 from Cloudflare opens the page in a hidden WebView until the
 * `cf_clearance` cookie appears; that cookie and the WebView's user agent then go with every
 * request to the host. Ported from CloudStream's own.
 */
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        fun parseCookieMap(cookie: String): Map<String, String> = cookie.split(";").associate {
            val split = it.split("=")
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    init {
        // New cookies per session. Can throw on devices without a WebView.
        runCatching { CookieManager.getInstance().removeAllCookies(null) }
    }

    val savedCookies: MutableMap<String, Map<String, String>> = mutableMapOf()

    /** The headers, cookies and WebView user agent included, for a request made some other way (the player). */
    fun getCookieHeaders(url: String): Headers {
        val agent = WebViewResolver.webViewUserAgent?.let { mapOf("user-agent" to it) } ?: emptyMap()
        return headersOf(agent, savedCookies[URI(url).host] ?: emptyMap())
    }

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val cookies = savedCookies[request.url.host]
        if (cookies != null) return@runBlocking proceed(request, cookies)
        val response = chain.proceed(request)
        if (!(response.header("Server") in CLOUDFLARE_SERVERS && response.code in ERROR_CODES)) return@runBlocking response
        response.close()
        bypassCloudflare(request)?.let {
            Log.d(TAG, "Passed Cloudflare: ${request.url.host}")
            return@runBlocking it
        }
        Log.w(TAG, "Failed Cloudflare at ${request.url.host}")
        chain.proceed(request)
    }

    private fun getWebViewCookie(url: String): String? = runCatching { CookieManager.getInstance()?.getCookie(url) }.getOrNull()

    /** True once the WebView holds the clearance cookie for this host; the cookies are kept then. */
    private fun trySolveWithSavedCookies(request: Request): Boolean =
        getWebViewCookie(request.url.toString())?.let { cookie ->
            cookie.contains("cf_clearance").also { solved -> if (solved) savedCookies[request.url.host] = parseCookieMap(cookie) }
        } ?: false

    private suspend fun proceed(request: Request, cookies: Map<String, String>): Response {
        val agent = WebViewResolver.getWebViewUserAgent()?.let { mapOf("user-agent" to it) } ?: emptyMap()
        val own = request.headers.toMap()
        val sent = request.header("Cookie")?.let(::parseCookieMap) ?: emptyMap()
        val headers = headersOf(own + agent, cookies + sent)
        return withContext(Dispatchers.IO) { app.baseClient.newCall(request.newBuilder().headers(headers).build()).execute() }
    }

    private suspend fun bypassCloudflare(request: Request): Response? {
        if (!trySolveWithSavedCookies(request)) {
            Log.d(TAG, "Opening a WebView to pass Cloudflare at ${request.url.host}")
            WebViewResolver(
                // Never stop on a url: stop once the cookie is there.
                Regex(".^"),
                // Cloudflare wants the WebView's own user agent.
                userAgent = null,
                // Intercepting through OkHttp loses the cookies.
                useOkhttp = false,
                additionalUrls = listOf(Regex(".")),
            ).resolveUsingWebView(request.url.toString()) { trySolveWithSavedCookies(request) }
        }
        val cookies = savedCookies[request.url.host] ?: return null
        return proceed(request, cookies)
    }

    /** Default user agent < given headers < cookies, like CloudStream's getHeaders. */
    private fun headersOf(headers: Map<String, String>, cookies: Map<String, String>): Headers {
        val cookie = if (cookies.isNotEmpty()) mapOf("Cookie" to cookies.entries.joinToString(" ") { "${it.key}=${it.value};" }) else emptyMap()
        val all = mapOf("user-agent" to USER_AGENT) + headers + cookie
        return Headers.Builder().apply { all.forEach { (k, v) -> set(k, v) } }.build()
    }
}
