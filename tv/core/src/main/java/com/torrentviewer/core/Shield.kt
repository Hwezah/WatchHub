package com.torrentviewer.core

/**
 * The domains a blocklist names — hosts format or AdGuard's DNS-filter format. Hosts format (StevenBlack's, AdGuard's DNS list in hosts form): "0.0.0.0 ads.example.com"
 * or "127.0.0.1 …" lines, comments and the list's own localhost entries left out. Lower-cased, for the Browser's
 * ad blocking.
 */
fun parseHostsList(text: String): Set<String> {
    val skip = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")
    val out = HashSet<String>(100_000)
    val allowed = HashSet<String>()
    // AdGuard's DNS filter writes "||ads.example.com^" (a "$…" option after it), "@@||…^" for a site let through, and
    // "!" before a comment; names with a "*" in them are patterns, which a name list can't hold, and are left out.
    val adblock = Regex("""^(@@)?\|\|([a-z0-9._-]+)\^?(\$.*)?$""")
    text.lineSequence().forEach { raw ->
        if (raw.startsWith("!")) return@forEach
        adblock.matchEntire(raw.trim().lowercase())?.let { m ->
            val host = m.groupValues[2].trimEnd('.')
            if ('.' in host) { if (m.groupValues[1].isNotEmpty()) allowed += host else out += host }
            return@forEach
        }
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty() || line.startsWith("|") || line.startsWith("@")) return@forEach
        val parts = line.split(Regex("""\s+"""))
        val host = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1") -> parts[1]
            // Domain-only lists: one name a line.
            parts.size == 1 && '.' in parts[0] -> parts[0]
            else -> return@forEach
        }.lowercase()
        if (host !in skip && '.' in host) out += host
    }
    return if (allowed.isEmpty()) out else out.apply { removeAll(allowed) }
}

/** Whether [host] or any domain it sits under ("a.b.ads.com" → "b.ads.com", "ads.com") is in [blocked]. */
fun hostBlocked(host: String, blocked: Set<String>): Boolean {
    var h = host.lowercase().trimEnd('.')
    while (true) {
        if (h in blocked) return true
        val dot = h.indexOf('.')
        if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
        h = h.substring(dot + 1)
    }
}

/**
 * AdGuard's public "Default" filtering resolver (ad and tracker domains answered as "not found"). The tunnel is pointed
 * at it when the owner asks to block ads across the phone: there's only one VPN slot on Android, so the ad blocking
 * rides the WireGuard tunnel rather than running a second VPN. Device-wide when the tunnel carries the whole phone,
 * WatchHub-only when it carries only this app.
 */
val AD_FILTER_DNS = listOf("94.140.14.14", "94.140.15.15")

/**
 * A WireGuard config (as Mullvad's or Proton's site hands it out) made for this app: any app list it had taken out of
 * [Interface], and — [onlyApp] — `IncludedApplications = [pkg]`, so only this app's traffic goes through the tunnel
 * and the rest of the phone is untouched. With [blockAds], the config's own `DNS` is replaced by [AD_FILTER_DNS], so
 * everything the tunnel carries resolves through an ad-blocking resolver.
 */
fun wireguardForApp(conf: String, pkg: String, onlyApp: Boolean, blockAds: Boolean = false): String {
    val out = StringBuilder()
    var section = ""
    var addedApps = false
    var addedDns = false
    // Mobile networks drop a connection that's quiet too long: a peer without a keep-alive gets one every 25 s, so
    // the tunnel isn't left "connected" with nothing getting through after the phone sat idle.
    val keepalive = conf.lineSequence().any { it.trim().substringBefore('=').trim().equals("PersistentKeepalive", true) }
    // What's injected into [Interface] as it closes (another section starts, or the file ends).
    fun closeInterface() {
        if (!section.equals("[Interface]", true)) return
        if (onlyApp && !addedApps) { out.append("IncludedApplications = $pkg\n"); addedApps = true }
        if (blockAds && !addedDns) { out.append("DNS = ${AD_FILTER_DNS.joinToString(", ")}\n"); addedDns = true }
    }
    conf.lineSequence().forEach { line ->
        val t = line.trim()
        if (t.startsWith("[") && t.endsWith("]")) {
            closeInterface()
            section = t
        }
        if (!keepalive && section.equals("[Peer]", true) && t.substringBefore('=').trim().equals("Endpoint", true)) {
            out.append(line).append('\n').append("PersistentKeepalive = 25\n")
            return@forEach
        }
        val key = t.substringBefore('=').trim()
        if (section.equals("[Interface]", true) && (key.equals("IncludedApplications", true) || key.equals("ExcludedApplications", true))) return@forEach
        // Blocking ads means we set the DNS ourselves, so the config's own DNS line is dropped.
        if (blockAds && section.equals("[Interface]", true) && key.equals("DNS", true)) return@forEach
        out.append(line).append('\n')
    }
    closeInterface()
    return out.toString().trimEnd() + "\n"
}

/** The server a WireGuard config connects to ("se-got-wg-001.relays.mullvad.net"), from its first Endpoint; null without one. */
fun wireguardServer(conf: String): String? =
    conf.lineSequence().map { it.trim() }.firstOrNull { it.substringBefore('=').trim().equals("Endpoint", true) }
        ?.substringAfter('=')?.trim()?.let { e -> if (e.startsWith("[")) e.substringAfter('[').substringBefore(']') else e.substringBeforeLast(':') }
        ?.takeIf { it.isNotBlank() }
