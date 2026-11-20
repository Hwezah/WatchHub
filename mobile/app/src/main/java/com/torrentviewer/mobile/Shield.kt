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
    /**
     * The lists, together about 300,000 sites: StevenBlack's unified hosts (ads, trackers, malware) and AdGuard's DNS
     * filter (ad networks, video-ad servers, pop-under and tracking hosts the hosts list lacks).
     */
    private val LISTS = listOf(
        "blocklist.txt" to "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        "blocklist-adguard.txt" to "https://raw.githubusercontent.com/AdguardTeam/AdGuardSDNSFilter/gh-pages/Filters/filter.txt",
    )
    private const val WEEK = 7L * 24 * 3600 * 1000
    private lateinit var dir: File

    @Volatile private var hosts: Set<String> = emptySet()

    private val _count = MutableStateFlow(0)
    /** How many sites the lists hold: Settings → Browser says it. */
    val count: StateFlow<Int> = _count

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = context.applicationContext.filesDir
        AppScope.launch(Dispatchers.IO) {
            runCatching { load() }
            val stale = LISTS.any { (name, _) -> File(dir, name).let { !it.exists() || System.currentTimeMillis() - it.lastModified() > WEEK } }
            if (stale) runCatching { update() }
        }
    }

    /** Every list kept on the phone, read into one set. */
    private fun load() {
        val all = HashSet<String>(320_000)
        LISTS.forEach { (name, _) -> File(dir, name).takeIf { it.exists() }?.let { all += parseHostsList(it.readText()) } }
        hosts = all
        _count.value = all.size
    }

    /** Fetched afresh (Settings' "Update Now", and weekly by itself). A list that fails keeps its last good copy. */
    suspend fun update() = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient()
        var got = 0
        LISTS.forEach { (name, url) ->
            runCatching {
                val text = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("${r.code}")
                    r.body?.string().orEmpty()
                }
                if (parseHostsList(text).size < 1000) error("empty")
                File(dir, name).writeText(text)
                got++
            }
        }
        load()
        if (got == 0) error("The blocklists didn't download; the copies already here are still used")
    }

    fun updatedAt(): Long = if (::dir.isInitialized) LISTS.maxOf { (name, _) -> File(dir, name).lastModified() } else 0

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

/**
 * Run in every page before its own scripts (where the phone's WebView allows it, else as early as it can):
 *
 * - YouTube: the ad slots taken out of the player's data before the player reads it (as uBlock's "json-prune"),
 *   so no ad is ever scheduled.
 * - Any player: a Skip button pressed the moment it appears; an ad video (Google IMA, Video.js and JW Player ad
 *   states, YouTube's ad-showing) muted and sent to its end in a split second.
 */
const val SHIELD_SCRIPT = """
(function(){if(window.__whShield)return;window.__whShield=1;
var AD=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams','adBreakParams'];
function strip(o){try{if(o&&typeof o==='object'){AD.forEach(function(k){if(k in o)delete o[k]});if(o.playerResponse)strip(o.playerResponse);}}catch(e){}return o;}
var jp=JSON.parse;JSON.parse=function(){return strip(jp.apply(this,arguments));};
try{var keep;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return keep},set:function(v){keep=strip(v)}});}catch(e){}
if(window.Response&&Response.prototype.json){var rj=Response.prototype.json;Response.prototype.json=function(){return rj.apply(this,arguments).then(strip);};}
var SKIP='.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.videoAdUiSkipButton,.jw-skip,.jw-skiptext,.vjs-ad-skip,'+
'[class*="skip-ad"],[class*="skipAd"],[class*="ad-skip"],[id*="skip-ad"],[id*="skipAd"],button[aria-label^="Skip"]';
var ADVID='.ad-showing video,.ad-interrupting video,.vjs-ad-playing video,.jw-flag-ads video,.ima-ad-container video,'+
'[class*="preroll"] video,[id*="preroll"] video,[class*="ad-player"] video';
setInterval(function(){try{
document.querySelectorAll(SKIP).forEach(function(b){try{b.click()}catch(e){}});
document.querySelectorAll(ADVID).forEach(function(v){v.muted=true;if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.2)v.currentTime=v.duration-0.1;v.playbackRate=16;});
}catch(e){}},300);
})();
"""

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
    fun import(raw: String): String? {
        // As files come: a byte-order mark first, Windows line ends, spaces around lines.
        val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trim() }.trim()
        if (text.isEmpty()) return "That file was empty or couldn't be opened. Pick the .conf again, or paste the config."
        if (!text.contains("[Interface]", true) || !text.contains("PrivateKey", true)) return "That isn't a WireGuard config: it has no [Interface] with a PrivateKey."
        val err = runCatching { Config.parse(text.byteInputStream()) }.exceptionOrNull()
        if (err != null) {
            val why = (err as? com.wireguard.config.BadConfigException)?.let { e -> "${e.reason.name.lowercase().replace('_', ' ')} in ${e.section.name.lowercase()} ${e.location.name.lowercase().replace('_', ' ')}" + (e.text?.let { t -> " (\"$t\")" } ?: "") }
            return "That config couldn't be read: ${why ?: err.message ?: err.javaClass.simpleName}"
        }
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
