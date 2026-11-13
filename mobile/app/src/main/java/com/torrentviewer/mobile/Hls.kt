package com.torrentviewer.mobile

import java.net.URI

/*
 * Reading HLS playlists (RFC 8216) for downloading: the variants of a master playlist, and the
 * segments, keys and init section of a media playlist. Pure, so it is tested on the JVM.
 */

/** One stream of a master playlist. [audio] is the group of a separate audio rendition, when it has one with its own URI. */
data class HlsVariant(val uri: String, val bandwidth: Long, val height: Int?, val audio: String?)

/** A master playlist: its variants, and the audio groups that live in playlists of their own. */
data class HlsMaster(val variants: List<HlsVariant>, val separateAudio: Set<String>)

/** How a segment is encrypted: AES-128 with the key at [uri]; [iv] as written, or null for the media sequence number. */
data class HlsKey(val method: String, val uri: String?, val iv: String?)

/** One segment, with the key it was encrypted under and, for a byte range, its length and offset. */
data class HlsSegment(val uri: String, val sequence: Long, val key: HlsKey?, val length: Long? = null, val offset: Long? = null)

/** A media playlist: the segments in order, the fMP4 init section if any, and whether it is complete (VOD) rather than live. */
data class HlsMedia(val segments: List<HlsSegment>, val init: String?, val ended: Boolean)

fun resolveUrl(base: String, ref: String): String = runCatching { URI(base).resolve(ref.trim()).toString() }.getOrDefault(ref.trim())

/** `KEY=value,KEY2="quoted, value"` → map, quotes off. */
fun hlsAttributes(s: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(s).forEach { m -> out[m.groupValues[1]] = m.groupValues[2].trim('"') }
    return out
}

fun isMasterPlaylist(text: String) = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

fun parseMaster(text: String, base: String): HlsMaster {
    val lines = text.lines().map { it.trim() }
    val separate = lines.filter { it.startsWith("#EXT-X-MEDIA:") }.map { hlsAttributes(it.substringAfter(':')) }
        .filter { it["TYPE"] == "AUDIO" && !it["URI"].isNullOrBlank() }.mapNotNull { it["GROUP-ID"] }.toSet()
    val variants = mutableListOf<HlsVariant>()
    for ((i, line) in lines.withIndex()) {
        if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
        val a = hlsAttributes(line.substringAfter(':'))
        val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
        variants += HlsVariant(
            uri = resolveUrl(base, uri),
            bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0,
            height = a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull(),
            audio = a["AUDIO"]?.takeIf { it in separate },
        )
    }
    return HlsMaster(variants, separate)
}

/**
 * The variant to save: the one at [height] (the source's resolution) if there is one, else the
 * tallest under it, else the best; among equals, the higher bitrate. Variants whose audio is a
 * separate playlist come last — saving one would be a silent film.
 */
fun pickVariant(variants: List<HlsVariant>, height: Int?): HlsVariant? {
    if (variants.isEmpty()) return null
    val muxed = variants.filter { it.audio == null }.ifEmpty { variants }
    val best = compareBy<HlsVariant>({ it.height ?: 0 }, { it.bandwidth })
    if (height == null) return muxed.maxWithOrNull(best)
    return muxed.filter { it.height == height }.maxByOrNull { it.bandwidth }
        ?: muxed.filter { (it.height ?: 0) in 1 until height }.maxWithOrNull(best)
        ?: muxed.maxWithOrNull(best)
}

fun parseMedia(text: String, base: String): HlsMedia {
    var sequence = 0L
    var key: HlsKey? = null
    var init: String? = null
    var range: Pair<Long, Long?>? = null
    var lastEnd = 0L
    var lastUri: String? = null
    val segments = mutableListOf<HlsSegment>()
    var ended = false
    var index = 0L
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
            line.startsWith("#EXT-X-KEY:") -> {
                val a = hlsAttributes(line.substringAfter(':'))
                val method = a["METHOD"] ?: "NONE"
                key = if (method == "NONE") null else HlsKey(method, a["URI"]?.let { resolveUrl(base, it) }, a["IV"])
            }
            line.startsWith("#EXT-X-MAP:") -> init = hlsAttributes(line.substringAfter(':'))["URI"]?.let { resolveUrl(base, it) }
            line.startsWith("#EXT-X-BYTERANGE:") -> {
                val v = line.substringAfter(':').trim()
                range = (v.substringBefore('@').toLongOrNull() ?: 0L) to v.substringAfter('@', "").toLongOrNull()
            }
            line.startsWith("#EXT-X-ENDLIST") -> ended = true
            line.isEmpty() || line.startsWith("#") -> Unit
            else -> {
                val uri = resolveUrl(base, line)
                val r = range
                val offset = r?.let { it.second ?: if (uri == lastUri) lastEnd else 0L }
                segments += HlsSegment(uri, sequence + index, key, r?.first, offset)
                if (r != null) lastEnd = (offset ?: 0L) + r.first
                lastUri = uri
                range = null
                index++
            }
        }
    }
    return HlsMedia(segments, init, ended)
}

/** The 16-byte IV for a segment: as written (0x…), else the media sequence number, big-endian. */
fun hlsIv(key: HlsKey, sequence: Long): ByteArray {
    key.iv?.removePrefix("0x")?.removePrefix("0X")?.takeIf { it.isNotEmpty() }?.let { hex ->
        val padded = hex.padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
    return ByteArray(16) { i -> if (i < 8) 0 else (sequence ushr ((15 - i) * 8)).toByte() }
}

/** "1080p" → 1080, "4K" → 2160; null when unknown. */
fun heightOf(quality: String?): Int? = when {
    quality == null -> null
    quality == "4K" -> 2160
    else -> quality.removeSuffix("p").toIntOrNull()
}
