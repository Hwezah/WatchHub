package com.torrentviewer.mobile

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Secrets
import com.torrentviewer.core.hostBlocked
import com.torrentviewer.core.parseHostsList
import com.torrentviewer.core.wireguardForApp
import com.torrentviewer.core.wireguardServer
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Browser's big blocklist: StevenBlack's unified hosts list (about 100,000 ad, tracker and malware domains), the
 * one most ad blockers start from, fetched once a week and kept on the phone. Read once at launch into memory; a
 * request is checked against it (and its parent domains) before it leaves.
 */
object AdBlock {
    private const val LIST = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val WEEK = 7L * 24 * 3600 * 1000
    private lateinit var file: File

    @Volatile private var hosts: Set<String> = emptySet()

    private val _count = MutableStateFlow(0)
    /** How many sites the list holds: Settings → Browser says it. */
    val count: StateFlow<Int> = _count

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.applicationContext.filesDir, "blocklist.txt")
        AppScope.launch(Dispatchers.IO) {
            runCatching { if (file.exists()) use(file.readText()) }
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > WEEK) runCatching { update() }
        }
    }

    private fun use(text: String) {
        hosts = parseHostsList(text)
        _count.value = hosts.size
    }

    /** Fetched afresh (Settings' "Update Now", and weekly by itself). */
    suspend fun update() = withContext(Dispatchers.IO) {
        val text = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(LIST).build()).execute().use { r ->
            if (!r.isSuccessful) error("The blocklist didn't download (${r.code})")
            r.body?.string().orEmpty()
        }
        if (parseHostsList(text).size < 1000) error("The blocklist came back empty")
        file.writeText(text)
        use(text)
    }

    fun updatedAt(): Long = if (::file.isInitialized && file.exists()) file.lastModified() else 0

    fun blocks(host: String): Boolean = hosts.isNotEmpty() && hostBlocked(host, hosts)

    /**
     * Ad boxes a page still lays out once their contents are blocked, hidden by style: Google's slots, common ad
     * containers, sticky ad bars. Specific names only, so a site's own "ad"-something classes don't vanish.
     */
    const val HIDE_SCRIPT = """
(function(){if(document.getElementById('wh-adhide'))return;var s=document.createElement('style');s.id='wh-adhide';
s.textContent='ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[data-ad-slot],[data-ad-client],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],'+
'iframe[id^="google_ads_iframe"],.adsbox,.ad-container,.ad-wrapper,.ad-banner,.ad-slot,.ad-unit,.advertisement,.advert,.sponsored-ad,'+
'[class*="AdSlot"],[id*="ad-banner"],[id*="adbanner"],[class*="sticky-ad"],[class*="adsense"],[aria-label="Advertisement"],'+
'div[id^="taboola"],div[class*="taboola"],div[id^="outbrain"],div[class*="OUTBRAIN"],.mgbox,[id^="mgid"],.popunder,.ad-overlay'+
'{display:none!important;visibility:hidden!important;height:0!important;}';
(document.head||document.documentElement).appendChild(s);})();
"""
}

/** Where the VPN stands, for the switch and Settings. */
enum class VpnState { OFF, CONNECTING, ON, FAILED }

/**
 * A WireGuard VPN inside the app (the owner's choice): a config file from Mullvad, Proton VPN or any WireGuard
 * provider, imported once and kept encrypted in [Secrets]. By default only this app's traffic — streams, downloads,
 * the Browser — goes through it, the rest of the phone untouched; "Whole phone" sends everything. The official
 * WireGuard library (GoBackend) runs the tunnel; Android asks once for leave to set up a VPN.
 */
object Vpn {
    private const val CONFIG = "wireguardConfig"
    private const val ON = "vpnOn"
    private const val ONLY_APP = "vpnOnlyApp"
    private lateinit var app: Context
    private var backend: GoBackend? = null

    private val _state = MutableStateFlow(VpnState.OFF)
    val state: StateFlow<VpnState> = _state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val tunnel = object : Tunnel {
        override fun getName() = "watchhub"
        override fun onStateChange(newState: Tunnel.State) {
            _state.value = if (newState == Tunnel.State.UP) VpnState.ON else VpnState.OFF
        }
    }

    /** Called at launch: a VPN left on comes back on, if Android still allows it (no prompt needed). */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        if (Kv.global(ON) == "1" && hasConfig() && VpnService.prepare(app) == null) connect()
    }

    fun hasConfig() = !Secrets.get(CONFIG).isNullOrBlank()

    /** The server the config connects to, to show. */
    fun server(): String? = Secrets.get(CONFIG)?.let(::wireguardServer)

    var onlyApp: Boolean
        get() = Kv.global(ONLY_APP) != "0"
        set(v) {
            Kv.setGlobal(ONLY_APP, if (v) "1" else "0")
            if (_state.value == VpnState.ON) connect()
        }

    /** A config file's text, checked before it's kept. Null when it's fine, else why not. */
    fun import(text: String): String? {
        val err = runCatching { Config.parse(text.byteInputStream()) }.exceptionOrNull()
        if (err != null) return "That isn't a WireGuard config (.conf): ${err.message ?: "it couldn't be read"}"
        Secrets.put(CONFIG, text)
        if (_state.value == VpnState.ON) connect()
        return null
    }

    fun forget() {
        disconnect()
        Secrets.put(CONFIG, null)
    }

    /** Android's "allow this VPN" screen, when it hasn't been allowed yet; null when it has. */
    fun permission(): Intent? = VpnService.prepare(app)

    /** Up, with the kept config (rewritten for "only this app"). Allowed by Android already ([permission] null). */
    fun connect() {
        val text = Secrets.get(CONFIG) ?: return
        _state.value = VpnState.CONNECTING
        _error.value = null
        Kv.setGlobal(ON, "1")
        AppScope.launch(Dispatchers.IO) {
            runCatching {
                val b = backend ?: GoBackend(app).also { backend = it }
                val config = Config.parse(wireguardForApp(text, app.packageName, onlyApp).byteInputStream())
                // From the top: a change of mode or config takes effect.
                runCatching { b.setState(tunnel, Tunnel.State.DOWN, null) }
                b.setState(tunnel, Tunnel.State.UP, config)
            }.onSuccess { _state.value = VpnState.ON }.onFailure {
                _state.value = VpnState.FAILED
                _error.value = "Couldn't connect: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    fun disconnect() {
        Kv.setGlobal(ON, null)
        AppScope.launch(Dispatchers.IO) {
            runCatching { backend?.setState(tunnel, Tunnel.State.DOWN, null) }
            _state.value = VpnState.OFF
        }
    }
}
