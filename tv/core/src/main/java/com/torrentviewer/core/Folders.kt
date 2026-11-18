package com.torrentviewer.core

/*
 * Where a finished download lives under the shared Grabber! folder (the owner's layout, alongside the Grabber app):
 * Movies/<film>/, Shows/<show>/Season NN/, and folders for what isn't a film or an episode. Pure, tested on the JVM.
 */

/** The root folder in the phone's Download folder, shared with the Grabber app. */
const val GRABBER_ROOT = "Grabber!"

/** The folders under [GRABBER_ROOT], made up front so the layout is there from the start. Video and Audio as the Grabber app names them. */
val GRABBER_FOLDERS = listOf("Movies", "Shows", "YouTube", "Video", "Audio", "Documents", "Images", "Other")

/** A name a folder can take: no path separators or characters filesystems refuse. */
fun folderSafe(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]+"), "_").trim().trimEnd('.').ifBlank { "Download" }

/**
 * The folders (under [GRABBER_ROOT]) a downloaded [fileName] goes in: a series' episode (its [kind] "tv", a [season]
 * known, or S01E02 in the name) under Shows/<show>/Season NN, anything else under Movies/<film>. [title] is the name
 * the download carries ("1923 · S01E04", "Interstellar"); what follows " · " is dropped.
 */
fun libraryFolders(title: String, fileName: String, kind: String?, season: Int?): List<String> {
    val name = folderSafe(title.substringBefore(" · "))
    val ep = parseEpisode(fileName) ?: parseEpisode(title)
    val inSeason = season ?: ep?.season
    return if (kind == "tv" || inSeason != null) listOf("Shows", name) + listOfNotNull(inSeason?.let { "Season %02d".format(java.util.Locale.ROOT, it) })
    else listOf("Movies", name)
}
