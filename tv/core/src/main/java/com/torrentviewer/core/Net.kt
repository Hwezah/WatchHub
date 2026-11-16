package com.torrentviewer.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** Work that outlives a screen. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** [body] is what the service said back — an OAuth error is in there, not in the status. */
class HttpException(val code: Int, message: String, val body: String = "") : IOException(message)

fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/**
 * HTTP with a timeout and the connectivity bookkeeping. Only a request that never
 * got a status counts as offline — a 404 means the network is fine and the service is not.
 */
object Net {
    private const val PROBE = "https://v3-cinemeta.strem.io/manifest.json"
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    private val probing = AtomicBoolean(false)

    suspend fun get(url: String, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        request(url, "GET", null, timeoutMs, quiet, headers = headers)

    suspend fun postJson(url: String, body: String, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", body, timeoutMs, quiet, headers = headers)

    suspend fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 15_000): String =
        request(url, "POST", fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }, timeoutMs, false, "application/x-www-form-urlencoded")

    /** multipart/form-data, text fields only — what TorBox's create endpoints take. */
    suspend fun postMultipart(url: String, fields: Map<String, String>, timeoutMs: Int = 15_000, quiet: Boolean = false, headers: Map<String, String> = emptyMap()): String {
        val boundary = "watchly${System.nanoTime()}"
        val body = buildString {
            fields.forEach { (k, v) -> append("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n") }
            append("--$boundary--\r\n")
        }
        return request(url, "POST", body, timeoutMs, quiet, "multipart/form-data; boundary=$boundary", headers)
    }

    /**
     * A file's bytes (a subtitle, which may come zipped), at most [cap] of them: null past that, so a link to
     * something far bigger than it should be is never read whole.
     */
    suspend fun bytes(url: String, timeoutMs: Int = 20_000, quiet: Boolean = true, cap: Int = 8 * 1024 * 1024): ByteArray? =
        send(url, "GET", null, timeoutMs, quiet) { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > cap) return@send null
            }
            out.toByteArray()
        }

    private suspend fun request(
        url: String, method: String, body: String?, timeoutMs: Int, quiet: Boolean,
        type: String = "application/json", headers: Map<String, String> = emptyMap(),
    ): String = send(url, method, body, timeoutMs, quiet, type, headers) { it.bufferedReader().use { r -> r.readText() } }

    /** One request, its answer read by [read]; a status outside 2xx throws [HttpException] with what the service said. */
    private suspend fun <T> send(
        url: String, method: String, body: String?, timeoutMs: Int, quiet: Boolean,
        type: String = "application/json", headers: Map<String, String> = emptyMap(), read: (java.io.InputStream) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val conn = try {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = method
                    setRequestProperty("User-Agent", "WatchlyTV/2")
                    setRequestProperty("Accept", "application/json, text/xml, */*")
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", type)
                        outputStream.use { it.write(body.toByteArray()) }
                    }
                }
            } catch (e: IOException) {
                if (!quiet) lost()
                throw e
            }
            try {
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    if (!quiet) lost()
                    throw e
                }
                _online.value = true
                if (code !in 200..299) {
                    val said = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                    throw HttpException(code, "${url.substringBefore('?')} returned $code", said)
                }
                conn.inputStream.use(read)
            } finally {
                conn.disconnect()
            }
        }

    /** Poll until something answers, then flip back. One loop at a time. */
    private fun lost() {
        _online.value = false
        if (!probing.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.IO).launch {
            var wait = 2000L
            while (true) {
                delay(wait)
                if (runCatching { request(PROBE, "HEAD", null, 8000, true) }.isSuccess) break
                wait = minOf(wait * 2, 30_000)
            }
            probing.set(false)
            _online.value = true
        }
    }
}
