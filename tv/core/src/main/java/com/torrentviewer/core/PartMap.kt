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

/**
 * The byte ranges of a file already downloaded, in order and joined where they touch: what a force merge copies out,
 * one after another, as 1DM does with its threads' sections — every section that came in, not only the unbroken start.
 * Empty ranges are dropped.
 */
fun mergedSections(ranges: List<LongRange>): List<LongRange> {
    val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
    val out = mutableListOf<LongRange>()
    for (r in sorted) {
        val prev = out.lastOrNull()
        if (prev != null && r.first <= prev.last + 1) out[out.size - 1] = prev.first..maxOf(prev.last, r.last) else out += r
    }
    return out
}

/**
 * A multi-connection link's downloaded sections, from its parts.json: part i begins at [starts] and has [done] bytes in.
 * See [mergedSections].
 */
fun linkSections(starts: List<Long>, done: List<Long>): List<LongRange> =
    mergedSections(starts.indices.mapNotNull { i -> done.getOrNull(i)?.takeIf { it > 0 }?.let { d -> starts[i] until starts[i] + d } })

/**
 * A torrent file's downloaded sections, in the file's own bytes: the file runs from [offset] for [size] bytes of the
 * torrent, [pieces] are [pieceLength] long, and [has] says whether one is in. A piece shared with the next file counts
 * only for the part inside this one. See [mergedSections].
 */
fun torrentSections(offset: Long, size: Long, pieceLength: Long, pieces: Int, has: (Int) -> Boolean): List<LongRange> {
    if (size <= 0 || pieceLength <= 0) return emptyList()
    val first = (offset / pieceLength).toInt()
    val last = ((offset + size - 1) / pieceLength).toInt().coerceAtMost(pieces - 1)
    if (last < first) return emptyList()
    return mergedSections((first..last).filter(has).map { p -> (p * pieceLength - offset).coerceAtLeast(0) until ((p + 1) * pieceLength - offset).coerceAtMost(size) })
}
