package com.torrentviewer.core

/**
 * The domains a hosts-format blocklist names (StevenBlack's, AdGuard's DNS list in hosts form): "0.0.0.0 ads.example.com"
 * or "127.0.0.1 …" lines, comments and the list's own localhost entries left out. Lower-cased, for the Browser's
 * ad blocking.
 */
fun parseHostsList(text: String): Set<String> {
    val skip = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")
    val out = HashSet<String>(100_000)
    text.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        val parts = line.split(Regex("""\s+"""))
        val host = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::" || parts[0] == "::1") -> parts[1]
            // Domain-only lists: one name a line.
            parts.size == 1 && '.' in parts[0] -> parts[0]
            else -> return@forEach
        }.lowercase()
        if (host !in skip && '.' in host) out += host
    }
    return out
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
 * A WireGuard config (as Mullvad's or Proton's site hands it out) made for this app: any app list it had taken out of
 * [Interface], and — [onlyApp] — `IncludedApplications = [pkg]`, so only this app's traffic goes through the tunnel
 * and the rest of the phone is untouched.
 */
fun wireguardForApp(conf: String, pkg: String, onlyApp: Boolean): String {
    val out = StringBuilder()
    var section = ""
    var added = false
    conf.lineSequence().forEach { line ->
        val t = line.trim()
        if (t.startsWith("[") && t.endsWith("]")) {
            if (section.equals("[Interface]", true) && onlyApp && !added) { out.append("IncludedApplications = $pkg\n"); added = true }
            section = t
        }
        val key = t.substringBefore('=').trim()
        if (section.equals("[Interface]", true) && (key.equals("IncludedApplications", true) || key.equals("ExcludedApplications", true))) return@forEach
        out.append(line).append('\n')
    }
    if (section.equals("[Interface]", true) && onlyApp && !added) out.append("IncludedApplications = $pkg\n")
    return out.toString().trimEnd() + "\n"
}

/** The server a WireGuard config connects to ("se-got-wg-001.relays.mullvad.net"), from its first Endpoint; null without one. */
fun wireguardServer(conf: String): String? =
    conf.lineSequence().map { it.trim() }.firstOrNull { it.substringBefore('=').trim().equals("Endpoint", true) }
        ?.substringAfter('=')?.trim()?.let { e -> if (e.startsWith("[")) e.substringAfter('[').substringBefore(']') else e.substringBeforeLast(':') }
        ?.takeIf { it.isNotBlank() }
