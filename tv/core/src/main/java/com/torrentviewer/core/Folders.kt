package com.torrentviewer.core

/*
 * Where a finished download lives under the shared Grabber! folder (the owner's layout, alongside the Grabber app):
 * Video/Movies/, Video/Shows/<show>/Season N/, Video/YouTube/, Audio/, and Browser/<Video, Audio…>/ for what the
 * Browser saved. Plus the Files tab's helpers for moving, copying and naming. Pure, tested on the JVM.
 */

/** The root folder in the phone's Download folder, shared with the Grabber app. */
const val GRABBER_ROOT = "Grabber!"

/**
 * The folders made up front (paths under [GRABBER_ROOT]), so the layout is there from the start, and kept when emptied.
 * Films, shows and YouTube inside Video, as the Grabber app names it (the owner's layout); Browser with its own Video,
 * Audio, Documents… for anything the Browser saved.
 */
val GRABBER_FOLDERS = listOf(
    "Video", "Video/Movies", "Video/Shows", "Video/YouTube", "Audio",
    "Browser", "Browser/Video", "Browser/Audio", "Browser/Documents", "Browser/Images", "Browser/Other",
)

/** Whether the folder at [path] (its names under [GRABBER_ROOT]) is one of [GRABBER_FOLDERS]: emptied, never moved or renamed. */
fun isFixedFolder(path: List<String>): Boolean = path.joinToString("/") in GRABBER_FOLDERS

/** A name a folder can take: no path separators or characters filesystems refuse. */
fun folderSafe(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().trimEnd('.').ifBlank { "Download" }

/** A season's folder: "Season 1", "Season 12" (the owner's naming, no leading zero). */
fun seasonFolder(season: Int): String = "Season $season"

/**
 * The folders (under [GRABBER_ROOT]) a downloaded [fileName] goes in: a series' episode (its [kind] "tv", a [season]
 * known, or S01E02 in the name) under Video/Shows/<show>/Season N, anything else under Video/Movies. [title] is the
 * name the download carries ("1923 · S01E04", "Interstellar"); what follows " · " is dropped.
 */
fun libraryFolders(title: String, fileName: String, kind: String?, season: Int?): List<String> {
    val name = folderSafe(title.substringBefore(" · "))
    val ep = parseEpisode(fileName) ?: parseEpisode(title)
    val inSeason = season ?: ep?.season
    return if (kind == "tv" || inSeason != null) listOf("Video", "Shows", name) + listOfNotNull(inSeason?.let(::seasonFolder))
    // A film sits straight in Movies (the owner's choice): only shows get folders of their own.
    else listOf("Video", "Movies")
}

/** Which Browser folder a file the Browser saved goes in, by its extension: Browser/Video, Browser/Audio, Browser/Documents… */
fun webFolder(fileName: String): String = "Browser/" + when (fileName.substringAfterLast('.', "").lowercase()) {
    "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus" -> "Audio"
    "pdf", "doc", "docx", "epub", "txt", "xls", "xlsx", "ppt", "pptx" -> "Documents"
    "jpg", "jpeg", "png", "webp", "gif" -> "Images"
    "mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "flv", "wmv", "mpg", "mpeg", "3gp", "m3u8" -> "Video"
    // Anything else (an archive, an app, a VPN's .conf…): Other, and not checked as a video.
    else -> "Other"
}

/**
 * Where a folder of the old layout (its names under [GRABBER_ROOT]) moves to in the new one, or null when it stays:
 * Movies, Shows and YouTube into Video, "Season 01" folders renamed "Season 1", and — when it holds what the Browser
 * saved ([web]) — Video, Audio, Documents, Images and Other into Browser.
 */
fun relaid(path: List<String>, web: Boolean): List<String>? {
    val top = path.firstOrNull() ?: return null
    val moved = when {
        top in setOf("Movies", "Shows", "YouTube") -> listOf("Video") + path
        web && top in setOf("Video", "Audio", "Documents", "Images", "Other") -> listOf("Browser") + path
        else -> path
    }
    // A show's season folder, two levels under Shows.
    val renamed = moved.mapIndexed { i, name -> if (i == 3 && moved[0] == "Video" && moved[1] == "Shows") renumbered(name) ?: name else name }
    return renamed.takeIf { it != path }
}

/** "Season 01" → "Season 1"; null for any other name. */
fun renumbered(name: String): String? = Regex("""Season 0+(\d+)""").matchEntire(name)?.let { seasonFolder(it.groupValues[1].toInt()) }

/**
 * A name for [name] that none of [taken] has (compared ignoring case, as the phone's storage does): "Film.mkv" →
 * "Film (2).mkv", then "Film (3).mkv"; a [folder]'s whole name counts ("Mr. Robot" → "Mr. Robot (2)").
 */
fun freeName(name: String, taken: Collection<String>, folder: Boolean): String {
    val used = taken.map { it.lowercase() }.toSet()
    if (name.lowercase() !in used) return name
    val dot = if (folder) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    val numbered = Regex("""^(.*) \((\d+)\)$""").matchEntire(stem)
    val base = numbered?.groupValues?.get(1) ?: stem
    var n = (numbered?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
    while ("$base ($n)$ext".lowercase() in used) n++
    return "$base ($n)$ext"
}

/** Whether [path] is [folder] itself or somewhere inside it: a folder can't be moved or copied into itself. */
fun isWithin(path: String, folder: String): Boolean = path == folder || path.startsWith(folder.trimEnd('/') + "/")

/** [path] after [from] moved to [to]: the same place inside it (or [to] itself), or null when [path] wasn't in it. */
fun rebased(path: String, from: String, to: String): String? = when {
    path == from -> to
    isWithin(path, from) -> to.trimEnd('/') + path.substring(from.trimEnd('/').length)
    else -> null
}

/**
 * The name a typed one ([typed]) becomes, for a new folder or a renamed entry: made [folderSafe], no leading dot (it
 * would hide it), and a file's extension [ext] kept on the end whatever was typed, so a rename can't change what it is.
 */
fun entryName(typed: String, ext: String?): String {
    val word = typed.trim().let { if (!ext.isNullOrEmpty() && it.endsWith(".$ext", ignoreCase = true)) it.dropLast(ext.length + 1) else it }
    val base = folderSafe(word.trimStart('.'))
    return if (ext.isNullOrEmpty()) base else "$base.$ext"
}

/** A–Z ignoring case, with runs of digits compared as numbers: "Season 2" before "Season 10". */
val NaturalOrder: Comparator<String> = Comparator { a, b ->
    val x = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
    val y = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]
        val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
        else p.compareTo(q)
        if (c != 0) return@Comparator c
    }
    x.size.compareTo(y.size)
}
