package com.torrentviewer.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.defaultDownloader

/** Where links handed out of the app go: a tap's default, one app, Android's full picker, or the clipboard. */
sealed interface Downloader {
    /** What a plain tap does: the app picked last, else 1DM / ADM if installed, else Android's picker. */
    data object Default : Downloader
    /** That app, by package; picking it in the sheet makes it the default from then on. */
    data class App(val pkg: String) : Downloader
    /** Android's own app picker, every app that takes the link. */
    data object Picker : Downloader
    /** No app: the link (or links, one a line) copied, to paste anywhere. */
    data object Copy : Downloader
}

/** What happened to the links, so the caller can say so. */
enum class Handed { Opened, Copied, Failed }

/**
 * The phone's apps that take links, and the one a tap goes to. Kept app-wide: it is the phone's downloader,
 * not a profile's taste.
 */
object ExternalDownloaders {
    private const val KEY = "extDownloader"

    /** The app picked last in the "Download With" sheet, by package. */
    fun remembered(): String? = Kv.global(KEY)

    /** A stand-in for one file's link: what a single episode or source hands out (ACTION_VIEW). */
    fun viewProbe() = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/video.mkv"), "video/*")

    /** A stand-in for a season's links as text (ACTION_SEND). */
    fun sendProbe() = Intent(Intent.ACTION_SEND).setType("text/plain")

    /** The apps that take [intent], by package and name, this app left out; known downloaders first. */
    fun apps(context: Context, intent: Intent): List<Pair<String, String>> {
        val pm = context.packageManager
        val found = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
            // The app's own name ("1DM+"), not the screen's ("1DM+ Browser").
            .map { it.activityInfo.packageName to pm.getApplicationLabel(it.activityInfo.applicationInfo).toString() }
            .filter { it.first != context.packageName }
            .distinctBy { it.first }
        val known = com.torrentviewer.core.KNOWN_DOWNLOADERS
        return found.sortedBy { known.indexOf(it.first).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    /** The app a tap would use for [intent], by package and name; null when Android's picker would open. */
    fun default(context: Context, intent: Intent): Pair<String, String>? {
        val able = apps(context, intent)
        val pkg = defaultDownloader(able.map { it.first }, remembered()) ?: return null
        return able.firstOrNull { it.first == pkg }
    }

    /**
     * Hands [intent] to [to]. An app that can't take this particular link (a magnet, say) falls back to
     * Android's picker rather than failing. [text] is what Copy puts on the clipboard.
     */
    fun hand(context: Context, intent: Intent, to: Downloader, pickerTitle: String, text: String): Handed {
        fun picker() = runCatching { context.startActivity(Intent.createChooser(intent, pickerTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .fold({ Handed.Opened }, { Handed.Failed })
        fun app(pkg: String): Handed {
            // One screen of that app, named outright: an app with several that take links (1DM+'s downloader and its
            // browser) otherwise brings up Android's "Open with … Just once / Always" for them. Its downloader, not a browser.
            val screens = runCatching { context.packageManager.queryIntentActivities(Intent(intent).setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY) }
                .getOrDefault(emptyList())
            val screen = screens.firstOrNull { !it.activityInfo.name.contains("browser", true) && !it.loadLabel(context.packageManager).toString().contains("browser", true) }
                ?: screens.firstOrNull() ?: return picker()
            val direct = Intent(intent).setComponent(android.content.ComponentName(pkg, screen.activityInfo.name)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { context.startActivity(direct) }.fold({ Handed.Opened }, { picker() })
        }
        return when (to) {
            Downloader.Copy -> runCatching {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(pickerTitle, text))
            }.fold({ Handed.Copied }, { Handed.Failed })
            Downloader.Picker -> picker()
            is Downloader.App -> {
                Kv.setGlobal(KEY, to.pkg)
                app(to.pkg)
            }
            Downloader.Default -> defaultDownloader(apps(context, intent).map { it.first }, remembered())?.let(::app) ?: picker()
        }
    }

    /** What a caller says after [hand]. */
    fun note(handed: Handed, many: Boolean): String? = when (handed) {
        Handed.Opened -> null
        Handed.Copied -> if (many) "Links copied — paste them into your downloader" else "Link copied"
        Handed.Failed -> if (many) "No app on this phone can take those links." else "No app on this phone can take that link."
    }
}

/**
 * A long press on "Send to a downloader": every app that takes the link, the current default marked, then
 * Android's full picker and Copy. The app picked becomes what a plain tap uses.
 */
@Composable
fun DownloaderSheet(probe: Intent, many: Boolean, onPick: (Downloader) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val apps = remember(probe.action) { ExternalDownloaders.apps(context, probe) }
    val current = remember(probe.action) { ExternalDownloaders.default(context, probe)?.first }
    val known = com.torrentviewer.core.KNOWN_DOWNLOADERS
    val options = apps.map { (pkg, name) ->
        Option<Downloader>(
            Downloader.App(pkg), name,
            hint = if (pkg == current) "Default · a tap sends here" else if (pkg in known) "Downloader" else null,
            icon = if (pkg in known) L.drawable.lucide_ic_download else L.drawable.lucide_ic_external_link,
            iconTint = if (pkg == current) Ink.download else null,
        )
    } + Option<Downloader>(Downloader.Picker, "Other Apps…", "Android's own list", icon = L.drawable.lucide_ic_share_2) +
        Option<Downloader>(Downloader.Copy, if (many) "Copy Links" else "Copy Link", if (many) "One a line, to paste anywhere" else "To paste anywhere", icon = L.drawable.lucide_ic_copy)
    Sheet("Download With", options, current?.let { Downloader.App(it) }, onPick = { onDismiss(); onPick(it) }, onDismiss = onDismiss)
}
