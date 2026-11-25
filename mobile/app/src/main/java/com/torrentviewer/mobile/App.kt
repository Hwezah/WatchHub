package com.torrentviewer.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.annotation.DrawableRes
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import com.torrentviewer.core.SettingsStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Backup
import com.torrentviewer.core.Avatar
import com.torrentviewer.core.COLOURS
import com.torrentviewer.core.History
import com.torrentviewer.core.Ink
import com.torrentviewer.core.grain
import com.torrentviewer.core.Item
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Phase
import com.torrentviewer.core.Profile
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.Stremio
import com.torrentviewer.core.Kids
import com.torrentviewer.core.Addons
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Net
import com.torrentviewer.core.addonHost
import com.torrentviewer.core.tvDropUrl
import com.torrentviewer.core.SavedStore
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.TitleMark
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes
import com.torrentviewer.core.isTorrentSource
import com.torrentviewer.core.titleOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

/**
 * The tabs, in the order the nav lays them out: two rows of four. Browser, YouTube and Files are announced, not built
 * yet ([ComingSoon]); each gets its own plan with the owner before it does anything.
 */
enum class Tab(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", L.drawable.lucide_ic_house),
    SEARCH("Search", L.drawable.lucide_ic_search),
    YOUTUBE("YouTube", L.drawable.lucide_ic_youtube),
    BROWSER("Browser", L.drawable.lucide_ic_globe),
    LIBRARY("Library", L.drawable.lucide_ic_library),
    FILES("Files", L.drawable.lucide_ic_folder),
    ADDONS("Addons", L.drawable.lucide_ic_puzzle),
    SETTINGS("Settings", L.drawable.lucide_ic_settings),
}

/** A kids profile has no say in where streams come from. */
private fun tabs() = Tab.entries.filter { it != Tab.ADDONS || !Kids.on }

/** What is stacked over the tabs: a title, a whole shelf, or the downloads (reached from Library). */
sealed interface Page {
    data class Detail(val title: Title) : Page
    data class Catalog(val shelf: Shelf) : Page
    data object Downloads : Page
    data object WatchOrders : Page
    data class WatchOrder(val order: com.torrentviewer.core.WatchOrder) : Page
    data class Person(val id: Int, val name: String) : Page
    data class Top(val kind: String) : Page
}

private fun keyOf(index: Int, page: Page) = when (page) {
    is Page.Detail -> "detail:$index:${page.title.imdb}"
    is Page.Catalog -> "shelf:$index:${page.shelf.key}"
    Page.Downloads -> "downloads:$index"
    Page.WatchOrders -> "orders:$index"
    is Page.WatchOrder -> "order:$index:${page.order.id}"
    is Page.Person -> "person:$index:${page.id}"
    is Page.Top -> "top:$index"
}

/**
 * Where the viewer is — the tab, and each tab's open pages — kept outside the screen: by the process, so a new
 * activity (Android rebuilt it, the app was left with Back) opens on the same page; and on disk, so an app Android
 * closed in the background comes back there too. Home's rows and scroll, while the process lives.
 */
internal object Nav {
    var tab by mutableStateOf(Tab.HOME)
    val stacks = androidx.compose.runtime.mutableStateMapOf<Tab, androidx.compose.runtime.snapshots.SnapshotStateList<Page>>()
    val home = HomeState()
    /** The disk's copy has been read into [stacks] (once a process). */
    var restored = false
}

private const val NAV_KEY = "phone:nav"

/**
 * A page as a line on disk: what reopens it. A title by its id (its page loads the rest); a person, a chart. A shelf
 * or a watch order isn't kept: it would need its whole list, and the page under it is a fine place to land.
 */
private fun pageJson(page: Page): org.json.JSONObject? = when (page) {
    is Page.Detail -> org.json.JSONObject().put("t", "detail").put("imdb", page.title.imdb).put("kind", page.title.kind).put("type", page.title.type)
        .put("title", page.title.title).put("year", page.title.year).put("poster", page.title.poster).put("backdrop", page.title.backdrop)
    is Page.Person -> org.json.JSONObject().put("t", "person").put("id", page.id).put("name", page.name)
    is Page.Top -> org.json.JSONObject().put("t", "top").put("kind", page.kind)
    Page.Downloads -> org.json.JSONObject().put("t", "downloads")
    Page.WatchOrders -> org.json.JSONObject().put("t", "orders")
    is Page.Catalog, is Page.WatchOrder -> null
}

private fun pageOf(o: org.json.JSONObject): Page? = runCatching {
    fun str(k: String) = o.optString(k).takeIf { it.isNotEmpty() && !o.isNull(k) }
    when (o.optString("t")) {
        "detail" -> Page.Detail(
            Title(
                o.getString("imdb"), o.getString("kind"), o.optString("title"), year = str("year"), poster = str("poster"), backdrop = str("backdrop"),
                type = str("type") ?: if (o.getString("kind") == "movie") "movie" else "series",
            ),
        )
        "person" -> Page.Person(o.getInt("id"), o.optString("name"))
        "top" -> Page.Top(o.getString("kind"))
        "downloads" -> Page.Downloads
        "orders" -> Page.WatchOrders
        else -> null
    }
}.getOrNull()

private fun saveNav() = runCatching {
    val tabs = org.json.JSONObject()
    Nav.stacks.forEach { (t, pages) -> tabs.put(t.name, org.json.JSONArray(pages.mapNotNull(::pageJson))) }
    Kv.setGlobal(NAV_KEY, org.json.JSONObject().put("tab", Nav.tab.name).put("pages", tabs).toString())
}

private fun loadNav() = runCatching {
    val o = org.json.JSONObject(Kv.global(NAV_KEY) ?: return@runCatching)
    Nav.tab = runCatching { Tab.valueOf(o.getString("tab")) }.getOrDefault(Tab.HOME)
    val tabs = o.optJSONObject("pages") ?: return@runCatching
    tabs.keys().forEach { name ->
        val t = runCatching { Tab.valueOf(name) }.getOrNull() ?: return@forEach
        val a = tabs.getJSONArray(name)
        Nav.stacks[t] = mutableStateListOf<Page>().apply { (0 until a.length()).mapNotNull { pageOf(a.getJSONObject(it)) }.forEach(::add) }
    }
}

/** The profile opened last on this phone, so a phone with one owner never asks. */
private const val LAST_PROFILE = "phone:lastProfile"

/** The first-launch tour has been seen (or skipped). */
private const val TOURED = "phone:toured"
/** The date of the legal text last accepted. */
private const val AGREED = "phone:agreed"

/** Before profiles there were the bare keys; with none made, the phone keeps using them. */
private val OWNER = Profile("", "Me", COLOURS[0])

private fun open(profile: Profile) {
    // Before anything is read: every store call below goes through this profile.
    Kv.useProfile(profile.id, profile.isGuest)
    com.torrentviewer.core.SettingsStore.load()
    History.load()
    // The Tube tab's subscriptions, history and Watch Later are this profile's own.
    Tube.load()
    // Older builds' downloads into the Grabber! folders, once — after History, so Continue Watching follows them.
    Downloads.moveOldDownloads()
    SavedStore.load()
    Stremio.load(profile.id, profile.isGuest)
    com.torrentviewer.core.WATCH_TRACKERS.forEach { it.load(profile.id, profile.isGuest) }
    Kids.use(profile)
    if (!profile.isGuest) Kv.setGlobal(LAST_PROFILE, profile.id)
}

/** Who opens without asking: the owner when there are no profiles, else the last one unless it is locked. */
private fun startProfile(list: List<Profile>): Profile? {
    if (list.isEmpty()) return OWNER
    val last = Kv.global(LAST_PROFILE)
    return (list.find { it.id == last } ?: list.singleOrNull())?.takeIf { it.pin == null }
}

fun darkFor(mode: String, systemDark: Boolean) = when (mode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

/** The page's palette, and status and navigation bar icons that read on it. */
fun ComponentActivity.applyTheme(dark: Boolean) {
    Ink.use(dark)
    val bars = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    enableEdgeToEdge(bars, bars)
}

/** What the download notification opens. */
const val DOWNLOADS_LINK = "watchly://downloads"

@Composable
fun App(watch: Watch, incoming: MutableState<Uri?>) {
    // Tighter margins on an upright phone, followed as it turns.
    val tight = isPortraitPhone()
    androidx.compose.runtime.SideEffect { Room.tight = tight }
    val settings by SettingsStore.value.collectAsState()
    val dark = darkFor(settings.themeMode, isSystemInDarkTheme())
    // The shade picked in Settings → Appearance, live: everything reading Ink.accent redraws with it.
    androidx.compose.runtime.SideEffect { Ink.accent = androidx.compose.ui.graphics.Color(com.torrentviewer.core.accentShade(settings.accent)) }
    // Settings → Appearance → Plain surfaces: the grain off everywhere, live.
    androidx.compose.runtime.SideEffect { Ink.plain = settings.plainSurfaces }
    val activity = LocalActivity.current as? ComponentActivity
    // Live, for the setting and the system's own switch; the player keeps its dark while it is up.
    LaunchedEffect(dark) { if (watch.phase != Phase.PLAYING) activity?.applyTheme(dark) }
    val scheme = if (dark) darkColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.surface) else lightColorScheme(primary = Ink.accent, background = Ink.bg, surface = Ink.surface)
    MaterialTheme(colorScheme = scheme) {
        Box(Modifier.fillMaxSize().background(Ink.bg).grain()) {
            Shell(watch, incoming)
            Toast(watch)
        }
    }
}

@Composable
private fun Shell(watch: Watch, incoming: MutableState<Uri?>) {
    val profiles by Profiles.list.collectAsState()
    var who by remember { mutableStateOf(startProfile(Profiles.list.value)?.also(::open)) }
    // An edit to the profile in use — its name, face or colour — shows at once.
    LaunchedEffect(profiles) {
        who = who?.let { w -> profiles.find { it.id == w.id } ?: w }
        // Kids switched on or off for the profile in use: at once, not at the next switch.
        who?.let(Kids::use)
    }
    var switching by remember { mutableStateOf(false) }
    // Each tab its own stack of pages: going to another tab and back finds the title you had open still there; leaving
    // the app and coming back does too ([Nav]).
    if (!Nav.restored) {
        Nav.restored = true
        if (Nav.stacks.isEmpty()) loadNav()
    }
    var tab by Nav::tab
    val stacks = Nav.stacks
    val pages = stacks.getOrPut(tab) { mutableStateListOf() }
    val holder = rememberSaveableStateHolder()
    val home = Nav.home
    // Written down whenever the tab or a page changes.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { Nav.tab to Nav.stacks.mapValues { it.value.toList() } }.collect { saveNav() }
    }

    fun pop() {
        val i = pages.lastIndex
        if (i < 0) return
        holder.removeState(tab.name + "/" + keyOf(i, pages[i]))
        pages.removeAt(i)
    }

    fun clearPages() {
        while (pages.isNotEmpty()) pop()
    }

    fun enter(profile: Profile) {
        // Rows loaded for the last profile: a kids profile must not inherit a grown-up's, nor the reverse.
        if (who?.kids != profile.kids) home.loaded.clear()
        open(profile)
        who = profile
        switching = false
        clearPages()
        tab = Tab.HOME
    }

    // A download's progress lives in a notification; Android 13+ asks before showing one.
    val context = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val downloading by Downloads.live.collectAsState()
    LaunchedEffect(downloading.isNotEmpty()) {
        if (downloading.isNotEmpty() && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // Engine failures would otherwise be invisible: the player just goes away.
    LaunchedEffect(Unit) { watch.engine.errors.collect { watch.fail(it) } }
    // An addon's YouTube or outside link: whichever app takes it.
    LaunchedEffect(Unit) {
        watch.opens.collect { link ->
            // A web page (a source that insists on one): the app's own Browser, in a tab of its own (the owner's choice) —
            // YouTube and other apps' links still go to their apps.
            val web = link.startsWith("http", true) && !Regex("(?i)(youtube\\.com|youtu\\.be)/").containsMatchIn(link)
            if (web) {
                Browser.newTab()
                Browser.go(link)
                tab = Tab.BROWSER
            } else runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(link))) }
                .onFailure { watch.notes.tryEmit("No app here can open that link") }
        }
    }

    /** The TV page asking for this phone's addons, from its QR, until the viewer says yes or no. */
    var sendTo by remember { mutableStateOf<String?>(null) }
    /** The TV asked for the whole library and settings, not just the addons. */
    var sendAll by remember { mutableStateOf(false) }
    /** A stremio:// link that wants to add an addon, until the viewer says yes or no. */
    var pendingAddon by remember { mutableStateOf<String?>(null) }
    /** A magnet or .torrent another app handed over: streamed or downloaded, as the viewer picks. */
    var captured by remember { mutableStateOf<String?>(null) }

    // Another app handed us a torrent, or an addon page's Install button a stremio:// link,
    // or the TV's QR page its watchly:// link asking for the addons.
    LaunchedEffect(incoming.value, who) {
        val uri = incoming.value ?: return@LaunchedEffect
        if (who == null) return@LaunchedEffect
        incoming.value = null
        if (uri.toString() == DOWNLOADS_LINK) {
            clearPages()
            tab = Tab.LIBRARY
            pages.add(Page.Downloads)
            return@LaunchedEffect
        }
        // A magnet is anything at all: a kids profile plays only what it can browse to.
        if (Kids.on) return@LaunchedEffect watch.fail("A kids profile can't open torrents or addons from other apps.")
        val raw = uri.toString()
        if (raw.startsWith("stremio://", ignoreCase = true)) {
            // Asked first, below: any web page can send one of these links.
            pendingAddon = raw
            return@LaunchedEffect
        }
        if (raw.startsWith("watchly://tv-addons", ignoreCase = true)) {
            sendTo = tvDropUrl(raw) ?: return@LaunchedEffect watch.fail("That isn't a Watchly TV on your home network.")
            sendAll = uri.getQueryParameter("all") == "1"
            return@LaunchedEffect
        }
        if (!isTorrentSource(raw)) return@LaunchedEffect
        // Asked first: stream it now, or keep it with the app's own torrent downloader.
        captured = raw
    }

    // Before anything else, for everyone, whatever they already have here, and again whenever the
    // words change: what was accepted is kept by its date, so a new date in Legal asks again.
    var accepted by remember { mutableStateOf(Kv.global(AGREED)) }
    if (accepted != com.torrentviewer.core.Legal.UPDATED) {
        Agreement(changed = accepted != null) {
            Kv.setGlobal(AGREED, com.torrentviewer.core.Legal.UPDATED)
            accepted = com.torrentviewer.core.Legal.UPDATED
        }
        return
    }

    // After the terms: nothing is asked of GitHub until they are accepted.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // Every push builds a new phone APK: offered here as soon as it's out (see Updates.checkPhoneBuild).
    LaunchedEffect(Unit) { com.torrentviewer.core.Updates.checkPhoneBuild(BuildConfig.VERSION_CODE, "WatchHub-phone-") }
    // And every 5 minutes while the app is in front (the owner's choice, as the Folio app does), so a build that finishes
    // while it's open is fetched and offered without leaving it.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        while (true) {
            kotlinx.coroutines.delay(5 * 60_000L)
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                com.torrentviewer.core.Updates.checkPhoneBuild(BuildConfig.VERSION_CODE, "WatchHub-phone-")
            }
        }
    }

    // The tour, once, on a fresh install: anyone with a library or a profile already knows the app.
    var toured by remember { mutableStateOf(Kv.global(TOURED) != null || Profiles.list.value.isNotEmpty() || History.items.value.isNotEmpty()) }
    if (!toured || tourAgain) {
        Onboarding {
            Kv.setGlobal(TOURED, "1")
            toured = true
            tourAgain = false
        }
        return
    }

    val me = who
    if (me == null || switching) {
        ProfilesScreen(
            profiles = profiles,
            onPick = ::enter,
            onCancel = if (me != null) ({ switching = false }) else null,
            onRemoved = { gone ->
                if (who?.id == gone.id) who = startProfile(Profiles.list.value)?.also(::open)
            },
        )
        return
    }

    if (watch.phase == Phase.PLAYING) {
        PlayerScreen(watch)
        return
    }

    BackHandler(pages.isNotEmpty()) { pop() }
    BackHandler(pages.isEmpty() && tab != Tab.HOME) { tab = Tab.HOME }

    val openTitle: (Title) -> Unit = { pages.add(Page.Detail(it)) }
    val openOrder: (com.torrentviewer.core.WatchOrder) -> Unit = { pages.add(Page.WatchOrder(it)) }
    // Every tab visited stays composed underneath — hidden, not thrown away — and pages open over them, so going
    // back (or to another tab and back) finds it as it was left: its rows loaded, its scroll, what was typed.
    val visited = remember { mutableStateListOf(tab) }
    if (tab !in visited) visited.add(tab)
    val body: @Composable () -> Unit = {
        // Every tab visited, and every page opened on it, stays composed — hidden, not thrown away — so going back
        // (a page, or a tab) finds things as they were left: rows loaded, scroll, season chosen, what was typed.
        Box(Modifier.fillMaxSize()) {
            visited.forEach { t ->
                key(t) {
                    val stack = stacks.getOrPut(t) { mutableStateListOf() }
                    val active = t == tab
                    // The Browser's tab without the fade: a web page drawn half-faded (in a layer of its own) flickers.
                    Layer(shown = active && stack.isEmpty(), z = if (active) 1f else 0f, fade = t != Tab.BROWSER) {
                        CompositionLocalProvider(LocalShown provides (active && stack.isEmpty())) {
                            holder.SaveableStateProvider(t.name) {
                                when (t) {
                                    Tab.HOME -> HomeScreen(home, watch, me, onOpen = openTitle, onShelf = { pages.add(Page.Catalog(it)) }, onTop = { pages.add(Page.Top(it)) }, onOrders = { pages.add(Page.WatchOrders) }, onOrder = openOrder, onProfile = { switching = true })
                                    Tab.SEARCH -> SearchScreen(watch, onOpen = openTitle)
                                    Tab.ADDONS -> AddonsScreen(watch)
                                    Tab.LIBRARY -> LibraryScreen(watch, me, onOpen = openTitle, onDownloads = { pages.add(Page.Downloads) })
                                    Tab.SETTINGS -> SettingsScreen(watch, me, onSwitchProfile = { switching = true })
                                    Tab.FILES -> FilesScreen(watch)
                                    Tab.YOUTUBE -> TubeScreen(watch)
                                    // Behind the code too (the owner's choice): however the tab is reached, a link sent to it included.
                                    Tab.BROWSER -> if (FilesLock.needed()) LockedPage(Tab.BROWSER, "The Browser") else BrowserScreen()
                                }
                            }
                        }
                    }
                    stack.forEachIndexed { i, page ->
                        val k = t.name + "/" + keyOf(i, page)
                        key(k) {
                            val top = active && i == stack.lastIndex
                            Layer(shown = top, z = if (active) 2f + i else 0f, opaque = true) {
                                CompositionLocalProvider(LocalShown provides top) {
                                    holder.SaveableStateProvider(k) {
                                        when (page) {
                                            is Page.Detail -> DetailScreen(page.title, watch, onOrder = openOrder, onOpen = openTitle, onPerson = { pages.add(Page.Person(it.id, it.name)) }, onBack = ::pop)
                                            is Page.Person -> PersonScreen(page.id, page.name, onOpen = openTitle, onBack = ::pop)
                                            is Page.Top -> TopScreen(page.kind, onOpen = openTitle, onBack = ::pop)
                                            is Page.Catalog -> CatalogScreen(page.shelf, onOpen = openTitle, onBack = ::pop)
                                            Page.Downloads -> DownloadsScreen(watch, onBack = ::pop)
                                            Page.WatchOrders -> WatchOrdersScreen(onOpen = openOrder, onBack = ::pop)
                                            is Page.WatchOrder -> WatchOrderScreen(page.order, onOpen = openTitle, onBack = ::pop)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // The tabs' content moved, not rebuilt, when the phone turns (upright: under the floating bar; sideways: beside the
    // rail): a video playing in Tube, a page's scroll and everything typed carry over instead of starting again.
    val host = remember { androidx.compose.runtime.movableContentOf { content: @Composable () -> Unit -> content() } }

    // Another tab: its own stack, as it was left. The tab you're on, tapped again: back to its start.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val choose: (Tab) -> Unit = { t ->
        // Whatever was being typed in (Search, the Browser's address) lets go of the keyboard and its cursor: left
        // focused in a hidden tab, its cursor and "Select all" showed up over Home's picture (the owner caught it).
        focus.clearFocus(force = true)
        keyboard?.hide()
        // Library and Files behind the code (Settings → Lock): its row opens on top of the tab bar first.
        if (FilesLock.locks(t)) FilesLock.ask(t)
        else {
            // A pin pad left open for Files goes with the move elsewhere: it isn't carried onto the next tab.
            FilesLock.dismiss()
            if (t == tab) clearPages() else tab = t
        }
    }
    /** The tab bar's ＋: a link copied elsewhere, to download. */
    // Locked again whenever the app goes to the background.
    val shellLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(shellLifecycle) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) FilesLock.backgrounded() }
        shellLifecycle.addObserver(watcher)
        onDispose { shellLifecycle.removeObserver(watcher) }
    }
    // Any other way the tab changes (a link, a notification): the same.
    LaunchedEffect(tab) { focus.clearFocus(force = true) }
    // Settings → Lock → Immediately: going to another tab or page locks it again (the owner's choice).
    LaunchedEffect(tab, pages.size) { FilesLock.left(tab) }

    Box(Modifier.fillMaxSize()) {
        if (isWide()) {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))) {
                NavRail(tab, me, choose, onProfile = { switching = true })
                Box(Modifier.weight(1f).fillMaxHeight()) { host(body) }
            }
        } else if (SettingsStore.value.collectAsState().value.navStyle == "pill") {
            // The pages run to the bottom edge; the pill floats over them. It slides away while the page is scrolled
            // down — so the last items are never under it — and back the moment it's scrolled up (or a tab changes).
            var pillShown by remember { mutableStateOf(true) }
            // Web pages and YouTube (the owner's choice): the bar stays away — at rest too — and comes only while the
            // page is moved back up, so it never covers what's being read on the way down. A blank Browser tab has
            // nothing to cover: there it shows. Everywhere else, as before: away while moving, back once at rest.
            val reveal = tab == Tab.BROWSER || tab == Tab.YOUTUBE
            val revealNow by androidx.compose.runtime.rememberUpdatedState(reveal)
            LaunchedEffect(tab) { pillShown = !reveal || (tab == Tab.BROWSER && Browser.url.isBlank()) }
            LaunchedEffect(Browser.url) { if (tab == Tab.BROWSER) pillShown = Browser.url.isBlank() }
            // The page's way, read outside composition: read here, each step of a page's scroll recomposed the whole shell.
            val tabNow by androidx.compose.runtime.rememberUpdatedState(tab)
            LaunchedEffect(Unit) {
                launch { androidx.compose.runtime.snapshotFlow { Browser.goingUp }.drop(1).collect { if (tabNow == Tab.BROWSER) pillShown = true } }
                androidx.compose.runtime.snapshotFlow { Browser.goingDown }.drop(1).collect { if (tabNow == Tab.BROWSER && Browser.url.isNotBlank()) pillShown = false }
            }
            // Hidden only while a page is actually moving (either way, a fling included); back as soon as it rests.
            // A page with nothing to scroll never moves, so the bar never leaves.
            var moved by remember { mutableIntStateOf(0) }
            LaunchedEffect(moved) {
                if (moved == 0 || reveal) return@LaunchedEffect
                kotlinx.coroutines.delay(350)
                pillShown = true
            }
            val watchScroll = remember {
                object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPostScroll(
                        consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                    ): androidx.compose.ui.geometry.Offset {
                        if (revealNow) {
                            // Down the page (the finger going up): away. Back up it, or drawn down at its top: here.
                            // YouTube: any scroll, down the list as well, brings the bar back (the owner's choice); the
                            // Browser keeps it away on the way down, over what's being read.
                            if (tabNow == Tab.YOUTUBE && kotlin.math.abs(consumed.y) > 2f) pillShown = true
                            else if (consumed.y < -2f) pillShown = false
                            else if (consumed.y > 2f || available.y > 8f) pillShown = true
                        } else if (kotlin.math.abs(consumed.y) > 2f) {
                            pillShown = false
                            moved++
                        }
                        return androidx.compose.ui.geometry.Offset.Zero
                    }
                }
            }
            // What the bar's "Glass" look blurs: everything drawn under it — except on the Browser's tab. The blur draws the
            // screen a second time, off-screen, every frame, and a web page drawn that way comes out as blank frames on many
            // phones: the Browser's flicker. There the bar is its solid frosted self.
            val haze = dev.chrisbanes.haze.rememberHazeState()
            val glass = NavGlass.glass && tab != Tab.BROWSER
            Box(Modifier.fillMaxSize().nestedScroll(watchScroll)) {
                Box(Modifier.fillMaxSize().then(if (glass) Modifier.hazeSource(haze) else Modifier)) { host(body) }
                // The pin row has no X (the owner's choice): a tap anywhere outside the bar puts it away, keyboard and all.
                if (FilesLock.asking != null) Box(
                    Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                        FilesLock.dismiss()
                        keyboard?.hide()
                    },
                )
                AnimatedVisibility(
                    pillShown && OpenSheets.count == 0, Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                ) { PillBar(tab, choose, haze = haze.takeIf { glass }) }
                // A Tube video dropped down keeps playing here, above the bar; a tap opens it again in Tube.
                if (TubeSession.playing.isEmpty()) TubeMiniPlayer(onOpen = { tab = Tab.YOUTUBE; TubeSession.expand() })
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { host(body) }
                TabBar(tab, choose)
            }
        }

        if (AddLink.open) AddLinkSheet { AddLink.open = false }
        if (FilesLock.asking != null && (isWide() || SettingsStore.value.value.navStyle != "pill")) {
            BottomSheet("Code", onDismiss = { FilesLock.asking = null }) { CodeRow(onOpen = choose) }
        }

        // A slim strip along the very top, its face reaching up behind the status bar (it pads itself below it).
        UpdateCard(watch, Modifier.align(Alignment.TopCenter))

        if (watch.phase == Phase.INSPECTING) Opening(watch)

        watch.choose?.let { (item, meta) ->
            Sheet(
                title = "Which file?",
                options = meta.files.filter { it.isVideo }.map { Option(it.index, it.name, bytes(it.size)) },
                selected = null,
                empty = "This torrent holds no video.",
                onPick = { watch.pick(item, meta, it) },
                onDismiss = { watch.choose = null },
            )
        }

        // An addon decides what the app is offered to play, so one from a link is added only on a yes.
        captured?.let { raw ->
            Sheet(
                title = titleOf(raw).ifBlank { "This torrent" },
                options = listOf(
                    Option("stream", "Stream now", "Starts playing while it downloads", icon = L.drawable.lucide_ic_play),
                    Option("download", "Download", "Kept on this phone — see the Downloads tab", icon = L.drawable.lucide_ic_download, iconTint = Ink.download),
                    Option("", "Cancel", icon = L.drawable.lucide_ic_x),
                ),
                selected = null,
                onPick = { pick ->
                    captured = null
                    AppScope.launch {
                        val path = runCatching { withContext(Dispatchers.IO) { watch.engine.importSource(raw) } }
                            .getOrElse { return@launch watch.fail(it.message ?: "Couldn't read that torrent.") }
                        when (pick) {
                            "stream" -> watch.resume(History.put(Item(path, titleOf(raw), System.currentTimeMillis())))
                            "download" -> {
                                Downloads.add(DownloadJob(uri = path, title = titleOf(raw), titleName = null, poster = null, imdb = null, kind = null, tags = emptyList()))
                                watch.notes.tryEmit("Downloading ${titleOf(raw)} — see Downloads")
                            }
                        }
                    }
                },
                onDismiss = { captured = null },
            )
        }

        pendingAddon?.let { link ->
            Sheet(
                title = "Add this addon?",
                options = listOf(
                    Option(true, "Add ${addonHost(link) ?: "this addon"}", "Only add addons you trust: it chooses what you are offered to play.", icon = L.drawable.lucide_ic_plus),
                    Option(false, "Cancel", icon = L.drawable.lucide_ic_x),
                ),
                selected = null,
                onPick = { yes ->
                    pendingAddon = null
                    if (yes) AppScope.launch {
                        watch.notes.tryEmit(runCatching { Addons.add(link) }.fold({ "Added ${it.name} — see the Addons tab" }, { it.message ?: "Couldn't add that addon" }))
                    }
                },
                onDismiss = { pendingAddon = null },
            )
        }

        // The TV asked for the addons: asked first, since their links can carry the viewer's keys.
        sendTo?.let { to ->
            val all = sendAll
            val n = Addons.list.value.size
            val tv = to.substringAfter("//").substringBefore(':')
            // "" cancels, "*" is everything, "addons" the addon list alone; the rest are Backup.GROUPS' labels.
            Sheet(
                title = if (all) "What should the TV copy?" else "Copy your addons to the TV?",
                options = if (all) {
                    Backup.GROUPS.keys.map { Option(it, it, "Replaces the TV's, for this profile", icon = L.drawable.lucide_ic_upload) } +
                        Option("*", "Everything", "All of the above", icon = L.drawable.lucide_ic_layers) + Option("", "Cancel", icon = L.drawable.lucide_ic_x)
                } else {
                    listOf(
                        Option("addons", if (n == 0) "No addons on this phone yet" else "Send $n addon${if (n == 1) "" else "s"}", tv, icon = L.drawable.lucide_ic_upload),
                        Option("", "Cancel", icon = L.drawable.lucide_ic_x),
                    )
                },
                selected = null,
                onPick = { pick ->
                    sendTo = null
                    sendAll = false
                    val body = when {
                        pick.isEmpty() || pick == "addons" && n == 0 -> null
                        pick == "addons" -> Addons.linksJson()
                        pick == "*" -> Backup.export()
                        else -> Backup.export(only = Backup.GROUPS[pick])
                    }
                    if (body != null) AppScope.launch {
                        watch.notes.tryEmit("Sending to the TV…")
                        // The TV fetches each addon before it answers: give it time.
                        runCatching { Net.postForm(to, mapOf("key" to body), 120_000) }
                            .onSuccess { page ->
                                val said = Regex("<p class=note>(.*?)</p>").find(page)?.groupValues?.get(1)?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
                                watch.notes.tryEmit(said ?: "Sent to the TV")
                            }
                            .onFailure { watch.notes.tryEmit("Couldn't reach the TV — is the phone on the same Wi-Fi?") }
                    }
                },
                onDismiss = { sendTo = null; sendAll = false },
            )
        }
    }
}

/** Portrait: the tabs along the bottom, the one you are on white with the TV rail's red tick. */
@Composable
private fun TabBar(tab: Tab, onTab: (Tab) -> Unit) {
    // Two rows of four, as the floating nav: eight tabs don't fit one row on a phone.
    Column(Modifier.fillMaxWidth().background(Ink.bg).grain().navigationBarsPadding()) {
    tabs().chunked(4).forEach { row ->
    Row(Modifier.fillMaxWidth().height(54.dp)) {
        row.forEach { t ->
            val on = t == tab
            Tap({ onTab(t) }, Modifier.weight(1f).fillMaxHeight(), RoundedCornerShape(0.dp)) {
                Box {
                    if (on) Box(Modifier.align(Alignment.TopCenter).width(22.dp).height(3.dp).clip(RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)).background(Ink.accent))
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Lc(t.icon, tint = if (on) Ink.text else Ink.faint, size = 22.dp)
                        Text(t.label, Modifier.padding(top = 3.dp), style = Type.meta.copy(fontSize = 11.sp, color = if (on) Ink.text else Ink.faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
                    }
                }
            }
        }
        // A short row (a kids profile has no Addons) keeps its cells the width of a full row's.
        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
    }
    }
    }
}

/**
 * The tabs floating over the page, as a music app's nav: a wide rounded panel, two rows of four icons, the chosen one
 * on a capsule of the accent. One flat colour a little lighter than the page's cards, with the grain over it, no rim.
 */
@Composable
private fun PillBar(tab: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier, haze: dev.chrisbanes.haze.HazeState? = null) {
    // Dark: near pitch black, a breath above the page's own black, its parts toned down to match (the owner's choice:
    // dark, muted, nothing that clashes with the pages behind). Light: as it was.
    val face = if (Ink.isDark) NAV_DARK else Ink.surface2
    // A very faint edge in the colour of the line between its rows (the owner's choice).
    val edge = Ink.text.copy(alpha = if (Ink.isDark) 0.05f else 0.08f)
    Column(
        // Just wider than the pages' content (16 dp in), clear of the screen's edges (the owner's choice).
        modifier.navigationBarsPadding().padding(start = 10.dp, end = 10.dp, bottom = 10.dp).fillMaxWidth()
            // A soft shadow, not a border, lifts it off the page in the light theme.
            .then(if (Ink.isDark) Modifier else Modifier.shadow(10.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f)))
            .clip(RoundedCornerShape(20.dp))
            // Dark: a shade lighter than the page. Light: a tint — near-white on the paper page, edged faintly —
            // so the bar stands apart in both (the owner's choice: light plays with tints as dark with shades).
            // Flat, no edge (the owner's choice). Light theme: a deeper paper than the page, so it reads apart from it.
            // Glass: what scrolls behind blurred and tinted with the bar's colour, part see-through (Settings → Appearance).
            .then(
                if (NavGlass.glass && haze != null) Modifier.hazeEffect(
                    haze,
                    dev.chrisbanes.haze.HazeStyle(
                        backgroundColor = Ink.bg, tint = dev.chrisbanes.haze.HazeTint(face.copy(alpha = if (Ink.isDark) 0.82f else 0.55f)),
                        blurRadius = 26.dp, noiseFactor = 0.06f, fallbackTint = dev.chrisbanes.haze.HazeTint(face.copy(alpha = if (Ink.isDark) 0.97f else 0.92f)),
                    ),
                ) else Modifier.background(face).grain(),
            )
            .border(1.dp, edge, RoundedCornerShape(20.dp))
            // A little more room above, below and between the rows (the owner's choice).
            .padding(horizontal = 8.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The code for Library or Files, asked for in a row of its own on top (the owner's design).
        if (FilesLock.asking != null) CodeRow(onOpen = onTab)
        tabs().chunked(4).forEachIndexed { i, row ->
            // Between the two rows: a thin, quiet line, short of the panel's edges (the owner's choice: 90% wide). The ＋ for
            // a copied link moved to the Library's heading (the owner's choice).
            if (i > 0) Box(Modifier.fillMaxWidth(0.9f).height(1.dp).background(edge))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                row.forEach { t ->
                    val on = t == tab
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Tap({ onTab(t) }, shape = RoundedCornerShape(14.dp)) {
                            Box(
                                // The chosen tab's capsule in the accent the viewer picked.
                                // Slim enough to stay clear of the line between the rows and the panel's top and bottom.
                                // Its colour as chosen in Settings → Appearance (frosted grey by default), with the app's grain.
                                Modifier.clip(RoundedCornerShape(11.dp)).background(if (on) NavActive.fill else Color.Transparent)
                                    .then(if (on && NavActive.style != "Accent") Modifier.grain() else Modifier)
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) { Lc(t.icon, tint = if (on) NavActive.tint else if (Ink.isDark) NAV_DARK_ICON else Ink.muted, size = 24.dp) }
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The floating tab bar in the dark theme: near pitch black, just above the page's #0A0A0A so it still reads apart. */
internal val NAV_DARK = Color(0xFF0E0E0E)

/** Its tabs' icons in the dark theme: a muted grey, quieter than the app's usual muted text on the near-black bar. */
private val NAV_DARK_ICON = Color(0xFF6E6E6E)

/** A tab announced but not built yet: its icon, its name, and a line saying it's coming. */
@Composable
private fun ComingSoon(t: Tab) {
    Box(Modifier.fillMaxSize().background(Ink.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 32.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Ink.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Lc(t.icon, tint = Ink.accent, size = 32.dp)
            }
            Text(t.label, Modifier.padding(top = 18.dp), style = Type.display)
            Text("Coming soon.", Modifier.padding(top = 6.dp), style = Type.body.copy(color = Ink.muted), textAlign = TextAlign.Center)
        }
    }
}

/** Landscape: the TV's collapsed rail down the left — avatar on top, the tabs in the middle. */
@Composable
private fun NavRail(tab: Tab, who: Profile, onTab: (Tab) -> Unit, onProfile: () -> Unit) {
    Column(
        // Eight tabs are taller than a phone held sideways: the rail scrolls, centred when they fit.
        Modifier.width(76.dp).fillMaxHeight().background(Ink.bg).grain().statusBarsPadding()
            .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // No avatar here either (the owner's choice): profiles are switched from Settings.
        tabs().forEach { t ->
            val on = t == tab
            Tap({ onTab(t) }, Modifier.fillMaxWidth().height(54.dp), RoundedCornerShape(0.dp)) {
                Box {
                    if (on) Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(Ink.accent))
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Lc(t.icon, tint = if (on) Ink.text else Ink.faint, size = 22.dp)
                        Text(t.label, Modifier.padding(top = 3.dp), style = Type.meta.copy(fontSize = 11.sp, color = if (on) Ink.text else Ink.faint, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
                    }
                }
            }
        }
    }
}

/** Resolving a torrent is a DHT round trip — seconds. Say so, over the title's own art. */
@Composable
private fun Opening(watch: Watch) {
    BackHandler { watch.cancel() }
    val title = watch.title
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        title?.art?.let {
            Artwork(it, title.title, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (title != null) TitleMark(title, Modifier.widthIn(max = 320.dp).padding(bottom = 28.dp), maxHeight = 80.dp)
            Spinner()
            // White on the blacked-out art, whatever the page's palette.
            Text("Opening…", Modifier.padding(top = 18.dp), style = Type.title.copy(color = Color.White))
            Text("Finding peers and reading the file list", Modifier.padding(top = 4.dp), style = Type.meta.copy(color = Color.White.copy(alpha = 0.7f)), textAlign = TextAlign.Center)
        }
        BackButton { watch.cancel() }
    }
}

@Composable
private fun Toast(watch: Watch) {
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        watch.notes.collect {
            note = it
            launch {
                delay(6000)
                if (note == it) note = null
            }
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 72.dp, start = EDGE, end = EDGE), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(note != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            Row(
                Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(6.dp)).background(Ink.surface2).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Lc(L.drawable.lucide_ic_circle_alert, tint = Ink.accent, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                PathText(note.orEmpty(), style = Type.label)
            }
        }
    }
}

/** Whether the screen reading it is the one in view: a hidden tab's Back handling stays out of the way. */
val LocalShown = androidx.compose.runtime.compositionLocalOf { true }

/** A hidden tab under the one in view: no tap or swipe reaches it. */
private fun Modifier.blockTouches() = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

/** One screen in the stack: drawn and touchable only when [shown], kept composed (and its state with it) when not. */
@Composable
private fun Layer(shown: Boolean, z: Float, opaque: Boolean = false, fade: Boolean = true, content: @Composable () -> Unit) {
    val faded by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 220 else 120), label = "layer")
    // [fade] off: shown or not at once. A WebView inside a layer being faded is drawn off-screen first, and on many
    // phones that comes out as blank frames — the flicker the owner saw opening the Browser on a page.
    val alpha = if (fade) faded else if (shown) 1f else 0f
    Box(
        Modifier.fillMaxSize().zIndex(z).graphicsLayer { this.alpha = alpha }
            .then(if (opaque) Modifier.background(Ink.bg).grain() else Modifier)
            .then(if (shown) Modifier else Modifier.blockTouches()),
    ) { content() }
}

