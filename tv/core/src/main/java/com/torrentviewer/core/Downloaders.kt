package com.torrentviewer.core

/**
 * Download managers known to take links well, best first: 1DM (paid, free, lite), then ADM and FDM. A tap on
 * "Send to a downloader" goes to the first of these installed, when no other app was chosen before.
 */
val KNOWN_DOWNLOADERS = listOf(
    "idm.internet.download.manager.plus",
    "idm.internet.download.manager",
    "idm.internet.download.manager.adm.lite",
    "com.dv.adm.pay",
    "com.dv.adm",
    "org.freedownloadmanager.fdm",
)

/**
 * The app a tap hands links to, from the packages that can take them ([able]): the one picked last in the
 * "Download With" sheet ([remembered]) while it is still installed, else the best known downloader, else none
 * (Android's own picker then). A messenger is never chosen silently: it only comes from [remembered].
 */
fun defaultDownloader(able: List<String>, remembered: String?): String? =
    remembered?.takeIf { it in able } ?: KNOWN_DOWNLOADERS.firstOrNull { it in able }

/** Words in an app's name or package that say it downloads files: a messenger, a calendar or a browser doesn't. */
private val DOWNLOADER_WORDS = Regex("""(?i)download|downloader|\bdm\b|1dm|\badm\b|\bidm|fdm|grab|aria2?|torrent|getter|loader|leech|xdm|gopeed|snaptube|vidmate|tubemate|seal""")

/**
 * Whether the app [pkg] named [label] is a downloader, for the "Download With" sheet (the owner's choice: only apps that
 * catch and download a link, not every app that takes text): a known one ([KNOWN_DOWNLOADERS]), or one whose name says so.
 */
fun looksLikeDownloader(pkg: String, label: String): Boolean =
    pkg in KNOWN_DOWNLOADERS || DOWNLOADER_WORDS.containsMatchIn(label) || DOWNLOADER_WORDS.containsMatchIn(pkg.replace('.', ' '))
