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
