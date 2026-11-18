package com.torrentviewer.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Note
import com.torrentviewer.core.Type
import com.torrentviewer.core.grain
import com.composables.icons.lucide.R as L

/**
 * The Browser tab, laid out as the Grabber app's (same owner): a bar with the tabs' count, the address, what the
 * page has to download (green, with a count) and ⋮; the page under it, on the app's frosted background. Downloads
 * go to the app's own downloader (Grabber!/Video, Audio…) or to the Grabber app, and "Grab this page's video"
 * hands the page to yt-dlp in the Snaptube-style format sheet.
 */
@Composable
fun BrowserScreen() {
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var tabsOpen by remember { mutableStateOf(false) }
    /** "bookmarks" or "history" while that list is open. */
    var places by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { Browser.load() }
    // A VPN config just taken in from a page: the sheet opens, its VPN switch at the top, the note under the title.
    LaunchedEffect(Browser.vpnAdded) { if (Browser.vpnAdded > 0) menu = true }
    LaunchedEffect(Browser.url, typing, Browser.current) { if (!typing) input = Browser.url }
    val shown = LocalShown.current
    BackHandler(shown && Browser.canBack && !Browser.fullScreen) { Browser.back() }
    BackHandler(shown && Browser.fullScreen) { Browser.exitFull?.invoke() }
    val site = Browser.url.isNotBlank() && Media.looksLikeSite(Browser.url)

    Box(Modifier.fillMaxSize().background(Ink.bg).grain()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                // One container for the whole bar, barely rounded, as Tube's; the address has no box, only its green cursor.
                Modifier.statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp).fillMaxWidth().height(58.dp)
                    .frosted(RoundedCornerShape(18.dp)).padding(start = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tabs: a squircle with how many are open.
                Tap({ tabsOpen = true }, Modifier.size(42.dp), RoundedCornerShape(10.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).border(1.6.dp, Ink.text, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                            Text("${Browser.tabs.size}", style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.Bold))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                BareInput(
                    input, { input = it }, "Search or type an address", Modifier.weight(1f).padding(horizontal = 4.dp),
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, onFocus = { typing = it },
                    onSubmit = { Browser.go(input); focus.clearFocus() },
                )
                if (input.isNotEmpty()) BarIcon(L.drawable.lucide_ic_x) { input = "" }
                // What this page has that can be downloaded, with a count.
                Tap({ picking = true }, Modifier.size(width = 44.dp, height = 46.dp), CircleShape) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Lc(L.drawable.lucide_ic_download, tint = if (Browser.found.isNotEmpty() || site) Ink.download else Ink.text, size = 22.dp)
                        if (Browser.found.isNotEmpty()) Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp).size(17.dp).clip(CircleShape).background(Ink.download),
                            contentAlignment = Alignment.Center,
                        ) { Text("${Browser.found.size.coerceAtMost(99)}", style = Type.meta.copy(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)) }
                    }
                }
                Tap({ menu = true }, Modifier.size(width = 32.dp, height = 46.dp), CircleShape) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_ellipsis_vertical, tint = Ink.text, size = 22.dp) }
                }
            }
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (Browser.loading) Box(Modifier.fillMaxWidth(Browser.progress / 100f).height(2.dp).background(Ink.accent))
            }
            // A jump to another site was stopped: say where, with a way through.
            Browser.tab.stopped?.let { to ->
                Row(Modifier.fillMaxWidth().frosted(RoundedCornerShape(0.dp)).padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stopped a jump to ${android.net.Uri.parse(to).host.orEmpty().removePrefix("www.")}", Modifier.weight(1f), style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Tap({ Browser.openStopped() }, Modifier.height(40.dp), RoundedCornerShape(8.dp)) {
                        Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text("Open Anyway", style = Type.meta.copy(color = Ink.accent, fontWeight = FontWeight.SemiBold)) }
                    }
                    BarIcon(L.drawable.lucide_ic_x) { Browser.tab.stopped = null }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // The current tab's page, swapped in when the tab changes.
                AndroidView(
                    factory = { android.widget.FrameLayout(it) },
                    update = { frame ->
                        Browser.rebuilt // a crashed page's tab gets its new view here
                        val w = Browser.view(frame.context, Browser.tab)
                        if (w.parent !== frame) {
                            (w.parent as? android.view.ViewGroup)?.removeView(w)
                            frame.removeAllViews()
                            frame.addView(w, android.view.ViewGroup.LayoutParams(-1, -1))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Nothing open yet: the frosted page, with the bookmarks a tap away.
                if (Browser.url.isEmpty()) Box(Modifier.fillMaxSize().background(Ink.bg).grain()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)) {
                        if (Browser.bookmarks.isEmpty()) EmptyNote("Search or type an address above. Videos a page plays show up under the green download mark.")
                        else {
                            Text("Bookmarks", Modifier.padding(start = 4.dp, bottom = 6.dp), style = Type.title.copy(fontWeight = FontWeight.Bold))
                            Browser.bookmarks.toList().forEach { p -> PlaceRow(p, onOpen = { Browser.go(p.url) }, onRemove = null) }
                        }
                    }
                }
            }
        }
    }

    // The VPN switch beside the title (the owner's choice), then the page's bookmark mark.
    if (menu) BottomSheet("Browser", { menu = false }, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VpnSwitch(onNoConfig = { Browser.note = "Add a VPN config first: Settings → VPN" })
            if (Browser.url.isNotEmpty()) {
                val marked = Browser.isBookmarked(Browser.url)
                Tap({ Browser.toggleBookmark(Browser.url, Browser.title) }, Modifier.size(44.dp), CircleShape) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_bookmark, tint = if (marked) Ink.accent else Ink.text, size = 22.dp) }
                }
            }
        }
    }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Browser.note?.let { Text(it, Modifier.padding(bottom = 10.dp), style = Type.meta.copy(color = Ink.faint)) }
            // Icons only: three words side by side were cut short ("For…", "Relo…").
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    L.drawable.lucide_ic_arrow_left to { Browser.back() },
                    L.drawable.lucide_ic_arrow_right to { Browser.forward() },
                    L.drawable.lucide_ic_rotate_cw to { Browser.reload() },
                ).forEach { (icon, go) ->
                    Tap({ go(); menu = false }, Modifier.weight(1f).height(44.dp), RoundedCornerShape(12.dp)) {
                        Box(Modifier.fillMaxSize().background(QUIET_FILL), contentAlignment = Alignment.Center) { Lc(icon, tint = Ink.text, size = 22.dp) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("New Tab", { Browser.newTab(); menu = false }, Modifier.weight(1f), icon = L.drawable.lucide_ic_plus, compact = true)
                Button("Close Tab", { Browser.close(Browser.current); menu = false }, Modifier.weight(1f), icon = L.drawable.lucide_ic_x, compact = true)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Bookmarks", { menu = false; places = "bookmarks" }, Modifier.weight(1f), icon = L.drawable.lucide_ic_bookmark, compact = true)
                Button("History", { menu = false; places = "history" }, Modifier.weight(1f), icon = L.drawable.lucide_ic_history, compact = true)
            }
            if (Browser.url.isNotBlank()) {
                val here = Browser.siteOf(android.net.Uri.parse(Browser.url).host)
                val allowed = here in Browser.allowJumps
                Spacer(Modifier.height(8.dp))
                Button(if (allowed) "Stop Redirects On $here" else "Allow Redirects On $here", {
                    if (allowed) Browser.allowJumps.remove(here) else Browser.allowJumps.add(here)
                    menu = false
                }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_shield, compact = true)
                Spacer(Modifier.height(8.dp))
                Button("Grab This Page's Video", { menu = false; Browser.offerPage() }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_download, fill = Ink.download)
            }
        }
    }

    places?.let { which ->
        val list = if (which == "bookmarks") Browser.bookmarks else Browser.history
        BottomSheet(if (which == "bookmarks") "Bookmarks" else "History", { places = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                if (list.isEmpty()) EmptyNote(if (which == "bookmarks") "No bookmarks yet. Open ⋮ on a page and tap the bookmark." else "Pages you visit show up here.")
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    list.toList().forEach { p ->
                        PlaceRow(p, onOpen = { Browser.go(p.url); places = null }) { if (which == "bookmarks") Browser.removeBookmark(p) else Browser.removeVisit(p) }
                    }
                }
                if (which == "history" && list.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Button("Clear History", { Browser.clearHistory() }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_trash_2)
                }
            }
        }
    }

    if (tabsOpen) BottomSheet("Tabs", { tabsOpen = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Browser.tabs.forEachIndexed { i, t ->
                    val on = i == Browser.current
                    Tap({ Browser.select(i); tabsOpen = false }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) {
                        Row(Modifier.background(if (on) Ink.text.copy(alpha = 0.07f) else Color.Transparent).padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Lc(L.drawable.lucide_ic_globe, tint = if (on) Ink.accent else Ink.muted, size = 18.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title.ifBlank { if (t.url.isBlank()) "New Tab" else t.url }, style = Type.label.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (t.url.isNotBlank()) Text(t.url, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            BarIcon(L.drawable.lucide_ic_x) { Browser.close(i) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button("New Tab", { Browser.newTab(); tabsOpen = false }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_plus)
        }
    }

    if (picking) BottomSheet("On This Page", { picking = false }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            if (Browser.url.isNotBlank()) {
                Button("Grab This Page's Video", { picking = false; Browser.offerPage() }, Modifier.fillMaxWidth(), icon = L.drawable.lucide_ic_download, fill = Ink.download)
                Spacer(Modifier.height(10.dp))
            }
            if (Browser.found.isEmpty()) EmptyNote("Nothing loaded yet. Start the video playing and it will show up here.")
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Browser.found.toList().forEach { s ->
                    Tap({ picking = false; Browser.offer(s) }, Modifier.fillMaxWidth(), RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            // A bare icon in green, no disc behind it (the owner's choice).
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                Lc(
                                    when {
                                        "Stream" in s.kind || s.kind == "Video" -> L.drawable.lucide_ic_film
                                        s.kind == "Audio" -> L.drawable.lucide_ic_music
                                        s.kind == "Torrent" -> L.drawable.lucide_ic_magnet
                                        else -> L.drawable.lucide_ic_file_text
                                    },
                                    tint = Ink.download, size = 24.dp,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.kind, style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                                Text(s.url.substringBefore('?').substringAfterLast('/').ifBlank { s.url }, style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (Browser.tab.blocked > 0) Text("${Browser.tab.blocked} ads and trackers blocked on this page", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint))
        }
    }

    Browser.offer?.let { o ->
        if (!o.direct) SaveSheet(o.url, o.title, null, o.headers, onDone = { Browser.offer = null })
        else {
            val grabber = remember { Media.hasGrabber(context) }
            val hls = o.kind == "Stream (HLS)"
            Sheet(
                title = o.title.take(70),
                options = listOfNotNull(
                    if (o.kind == "Stream (DASH)") null else Option("here", "Download Here", if (hls) "Joined into one video · Grabber!/Video" else "To Grabber! in this app", icon = L.drawable.lucide_ic_download, iconTint = Ink.download),
                    if (o.kind == "Video" || hls || o.kind == "Stream (DASH)") Option("formats", "More Formats", "MP3, M4A, Opus… or another height", icon = L.drawable.lucide_ic_music) else null,
                    if (grabber) Option("grabber", "Send to Grabber", "The Grabber app downloads it", icon = L.drawable.lucide_ic_external_link) else null,
                    Option("copy", "Copy Link", icon = L.drawable.lucide_ic_copy),
                ),
                selected = null,
                onPick = {
                    when (it) {
                        "here" -> { Downloads.addWeb(o.url, o.title, o.headers, hls); Browser.offer = null }
                        "formats" -> Browser.offer = o.copy(direct = false)
                        "grabber" -> { Media.sendToGrabber(context, o.url, o.title); Browser.offer = null }
                        "copy" -> {
                            runCatching {
                                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .setPrimaryClip(android.content.ClipData.newPlainText("link", o.url))
                            }
                            Browser.offer = null
                        }
                    }
                },
                onDismiss = { Browser.offer = null },
            )
        }
    }
}

/** A small icon button in the bar and the lists. */
@Composable
private fun BarIcon(icon: Int, onClick: () -> Unit) {
    Tap(onClick, Modifier.size(42.dp), CircleShape) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Lc(icon, tint = Ink.text, size = 20.dp) }
    }
}

/** A bookmark or a visited page: its title and address; tap to open, ✕ to remove. */
@Composable
private fun PlaceRow(p: Place, onOpen: () -> Unit, onRemove: (() -> Unit)?) {
    Tap(onOpen, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.url.removePrefix("https://").removePrefix("http://").removePrefix("www."), style = Type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onRemove != null) BarIcon(L.drawable.lucide_ic_x, onRemove)
        }
    }
}
