package com.torrentviewer.core

/*
 * A download's part map, for its card's stripes: how full each slice of the file is, as a download over several
 * connections (or a torrent's pieces) fills it out of order. Pure, tested on the JVM.
 */

/**
 * How full (0–1) each of [slots] equal slices of a file of [total] units is, from the ranges it [has] (inclusive, in
 * the same units: bytes for a link's parts, pieces for a torrent). Overlapping ranges count once per slice only as far
 * as they're given; an empty or unknown file is all empty.
 */
fun partMap(total: Long, has: List<LongRange>, slots: Int): List<Float> {
    if (slots <= 0) return emptyList()
    if (total <= 0) return List(slots) { 0f }
    return List(slots) { i ->
        val from = total * i / slots
        val to = total * (i + 1) / slots - 1
        val span = (to - from + 1).coerceAtLeast(1)
        val got = has.sumOf { r -> (minOf(r.last, to) - maxOf(r.first, from) + 1).coerceAtLeast(0) }
        (got.toFloat() / span).coerceIn(0f, 1f)
    }
}

/** A part map for a download that fills from the start (one connection, a playlist): [ratio] of the way, in order. */
fun inOrderMap(ratio: Float, slots: Int): List<Float> {
    val r = if (ratio.isNaN()) 0f else ratio.coerceIn(0f, 1f)
    val units = 10_000L
    val have = (r * units).toLong()
    return partMap(units, if (have > 0) listOf(0L until have) else emptyList(), slots)
}

/** The runs of set bits among [count] (a torrent's pieces it has), as ranges of their indices. */
fun runsOf(count: Int, has: (Int) -> Boolean): List<LongRange> {
    val out = mutableListOf<LongRange>()
    var start = -1
    for (i in 0 until count) {
        if (has(i)) { if (start < 0) start = i }
        else if (start >= 0) { out += start.toLong() until i.toLong(); start = -1 }
    }
    if (start >= 0) out += start.toLong() until count.toLong()
    return out
}
