package com.torrentviewer.mobile.cs

import com.torrentviewer.core.SettingsStore
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The choices: Auto (the default — the phone's own lookup, and Cloudflare's when that fails or is blocked),
 * Cloudflare or Google always, or Off (the phone's own only).
 */
val SECURE_DNS = listOf("" to "Auto", "cloudflare" to "Cloudflare", "google" to "Google", "off" to "Off")

/** A DoH provider's JSON endpoint, by its IP address so asking it needs no lookup of its own. Null: off. */
fun dohEndpoint(provider: String): String? = when (provider) {
    "cloudflare" -> "https://1.1.1.1/dns-query"
    "google" -> "https://8.8.8.8/resolve"
    else -> null
}

/** The addresses in a DoH JSON answer (A and AAAA records only, not CNAMEs), each with its time to live in seconds. */
fun parseDohAnswers(json: String): List<Pair<String, Int>> {
    val answers = runCatching { JSONObject(json).optJSONArray("Answer") }.getOrNull() ?: return emptyList()
    return (0 until answers.length()).mapNotNull { i ->
        val a = answers.optJSONObject(i) ?: return@mapNotNull null
        val type = a.optInt("type")
        if (type != 1 && type != 28) return@mapNotNull null
        a.optString("data").takeIf { it.isNotBlank() }?.let { it to a.optInt("TTL", 300) }
    }
}

private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

/** Whether [host] is already an address ("1.2.3.4", or IPv6 with colons): nothing to look up. */
fun isIpLiteral(host: String) = IPV4.matches(host) || ':' in host

/**
 * Secure DNS for the plugins' traffic, as CloudStream's setting: site names are looked up over HTTPS at
 * Cloudflare or Google instead of through the phone's provider, which often blocks streaming sites by
 * name. Answers are kept for their TTL; anything that goes wrong falls back to the phone's own lookup.
 */
object SecureDns : Dns {
    /** Asks the DoH provider. Its own address is an IP, so it never needs this resolver. */
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    /** Forget every answer: after the provider is changed. */
    fun clear() = cache.clear()

    override fun lookup(hostname: String): List<InetAddress> {
        val choice = SettingsStore.value.value.secureDns
        if (choice == "off" || isIpLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)
        // Auto: the phone's own lookup first; a name it can't find (a provider blocking streaming sites, as
        // with api.themoviedb.org for StreamPlay) is asked of Cloudflare, then Google.
        val endpoint = dohEndpoint(choice) ?: run {
            val system = runCatching { Dns.SYSTEM.lookup(hostname) }
            system.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            return viaHttps(dohEndpoint("cloudflare")!!, hostname) ?: viaHttps(dohEndpoint("google")!!, hostname) ?: throw system.exceptionOrNull() as? java.net.UnknownHostException
                ?: java.net.UnknownHostException(hostname)
        }
        return viaHttps(endpoint, hostname) ?: Dns.SYSTEM.lookup(hostname)
    }

    /** [hostname]'s addresses from [endpoint], kept for their TTL; null when it gave none. */
    private fun viaHttps(endpoint: String, hostname: String): List<InetAddress>? {
        val key = "$endpoint|$hostname"
        cache[key]?.takeIf { it.first > System.currentTimeMillis() }?.let { return it.second }
        val found = runCatching { ask(endpoint, hostname, 1) + ask(endpoint, hostname, 28) }.getOrDefault(emptyList())
        if (found.isEmpty()) return null
        val ttl = found.minOf { it.second }.coerceIn(60, 3600)
        val addresses = found.mapNotNull { (ip, _) -> runCatching { InetAddress.getByName(ip) }.getOrNull() }.distinct()
        if (addresses.isEmpty()) return null
        cache[key] = System.currentTimeMillis() + ttl * 1000L to addresses
        return addresses
    }

    private fun ask(endpoint: String, host: String, type: Int): List<Pair<String, Int>> {
        val request = Request.Builder().url("$endpoint?name=$host&type=$type").header("Accept", "application/dns-json").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            return parseDohAnswers(r.body?.string().orEmpty())
        }
    }
}
