package com.torrentviewer.mobile

import com.torrentviewer.core.Spinner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginWrapper
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Note
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.bytes
import com.torrentviewer.mobile.cs.CsPlugins
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/**
 * The Addons tab's second half: CloudStream repositories and the plugins installed from them.
 * Add a repository by link or shortcode ("phisherrepo"), open it to install plugins, and see each
 * installed plugin's state — running, or why it didn't start.
 */
@Composable
fun PluginsSection(watch: Watch) {
    val repos by CsPlugins.repos.collectAsState()
    val installed by CsPlugins.installed.collectAsState()
    val changes by CsPlugins.changes.collectAsState()
    val starting by CsPlugins.starting.collectAsState()
    var link by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun add() {
        if (adding) return
        if (link.isBlank()) return run { watch.notes.tryEmit("Paste a repository link or shortcode first") }
        adding = true
        scope.launch {
            runCatching { CsPlugins.addRepo(link) }
                .onSuccess { link = ""; watch.notes.tryEmit("Added ${it.name} — open it to install plugins") }
                .onFailure { watch.notes.tryEmit(it.message ?: "Couldn't add that repository") }
            adding = false
        }
    }

    Text(
        "CloudStream plugins search sites from your phone and find streams there. A plugin is code from whoever wrote it, " +
            "running inside this app: only add repositories you trust. WatchHub comes with none.",
        Modifier.padding(bottom = 18.dp), style = Type.meta.copy(color = Ink.faint),
    )
    Text("Add a repository", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp), style = Type.overline.copy(color = Ink.faint))
    Column(Modifier.padding(bottom = 22.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).padding(16.dp)) {
        Field(link, { link = it.trim() }, "Shortcode or https://…/repo.json", Modifier.fillMaxWidth(), onSubmit = ::add, leading = L.drawable.lucide_ic_link)
        Button(if (adding) "Adding…" else "Add repository", ::add, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_plus, primary = true)
        Text(
            "A shortcode is the short name a repository's page gives for CloudStream, such as phisherrepo.",
            Modifier.padding(top = 10.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp),
        )
    }

    if (repos.isEmpty()) {
        Note("No repositories yet. Add one above, then open it to pick plugins.")
    } else {
        Text("Repositories", Modifier.padding(start = 4.dp, top = 6.dp, bottom = 10.dp), style = Type.overline.copy(color = Ink.faint))
        repos.forEach { repo -> RepoCard(repo, installed, changes, watch) }
    }

    if (installed.isNotEmpty()) {
        Text(
            "Installed · ${installed.size}" + if (starting) " · starting…" else "",
            Modifier.padding(start = 4.dp, top = 14.dp, bottom = 10.dp), style = Type.overline.copy(color = Ink.faint),
        )
        installed.sortedBy { it.internalName.lowercase() }.forEach { p -> InstalledRow(p, changes, starting, watch) }
    }
}

/** A repository: its name and what it says, opened to list its plugins with Install / Update beside each. */
@Composable
private fun RepoCard(repo: RepositoryData, installed: List<PluginData>, changes: Int, watch: Watch) {
    var open by rememberSaveable(repo.url) { mutableStateOf(false) }
    var offered by remember(repo.url) { mutableStateOf<List<PluginWrapper>?>(null) }
    var error by remember(repo.url) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(repo.url) { mutableStateOf("") }
    var kind by rememberSaveable(repo.url) { mutableStateOf<String?>(null) }
    val busy = remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) {
        if (open && offered == null) {
            error = null
            runCatching { CsPlugins.offered(repo) }.onSuccess { offered = it }.onFailure { error = it.message }
        }
    }
    // Thin inside padding: the plugin list gets the width.
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.surface).padding(horizontal = 10.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Ink.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Lc(L.drawable.lucide_ic_puzzle, tint = Ink.muted, size = 22.dp)
                repo.iconUrl?.takeIf { it.startsWith("http") }?.let { Artwork(it, repo.name, Modifier.fillMaxSize(), fill = false) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(repo.name, style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    repo.description ?: hostOf(repo.url), Modifier.padding(top = 2.dp),
                    style = Type.meta.copy(color = Ink.muted), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(if (open) "Hide plugins" else "Browse plugins", { open = !open }, Modifier.weight(1f), icon = L.drawable.lucide_ic_search, compact = true)
            Spacer(Modifier.width(8.dp))
            Outline("Remove") {
                scope.launch {
                    CsPlugins.removeRepo(repo)
                    watch.notes.tryEmit("Removed ${repo.name} and its plugins")
                }
            }
        }
        if (!open) return@Column
        when {
            error != null -> Note(error!!, Modifier.padding(top = 14.dp))
            offered == null -> Note("Reading ${repo.name}…", Modifier.padding(top = 14.dp))
            else -> {
                val all = offered!!
                val have = all.count { CsPlugins.installedOf(it) != null }
                Text(
                    "Downloaded: $have  ·  Not downloaded: ${all.size - have}",
                    Modifier.padding(top = 14.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp),
                )
                Field(query, { query = it }, "Search ${all.size} plugins", Modifier.padding(top = 10.dp).fillMaxWidth(), leading = L.drawable.lucide_ic_search)
                // The kinds the plugins say they cover, as CloudStream's chips: Movie, Series, Anime…
                val types = remember(all) { all.flatMap { it.plugin.tvTypes.orEmpty() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
                if (types.isNotEmpty()) Row(
                    Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    types.forEach { t -> Chip(typeName(t), kind == t, onClick = { kind = if (kind == t) null else t }, compact = true) }
                }
                val q = query.trim()
                val shown = all.filter { w ->
                    (kind == null || kind in w.plugin.tvTypes.orEmpty()) &&
                        (q.isEmpty() || listOfNotNull(w.plugin.name, w.plugin.description, w.plugin.language).any { it.contains(q, ignoreCase = true) })
                }.sortedBy { it.plugin.name.lowercase() }
                if (shown.isEmpty()) Note("No plugin matches.", Modifier.padding(top = 12.dp))
                // The installed list changing (or a plugin loading) re-reads each row's state.
                shown.forEach { w -> androidx.compose.runtime.key(w.plugin.url, changes, installed) { OfferedRow(w, busy, watch) } }
            }
        }
    }
}

/** "TvSeries" → "Series", "AsianDrama" → "Asian Drama": CloudStream's type names, read aloud. */
private fun typeName(t: String) = when (t) {
    "TvSeries" -> "Series"
    "AnimeMovie" -> "Anime Movie"
    "AsianDrama" -> "Asian Drama"
    "NSFW" -> "NSFW"
    else -> t.replace(Regex("([a-z])([A-Z])"), "$1 $2")
}

/** A plugin's language as a flag and a name, as CloudStream lists them: "hi" → 🇮🇳 Hindi. */
private fun languageLabel(code: String?): String? {
    val c = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val flag = when (c.substringBefore('-')) {
        "en" -> "🇬🇧"; "hi", "ta", "te", "ml", "kn", "mr", "pa", "gu" -> "🇮🇳"; "bn" -> "🇧🇩"; "es" -> "🇪🇸"; "mx" -> "🇲🇽"
        "fr" -> "🇫🇷"; "de" -> "🇩🇪"; "it" -> "🇮🇹"; "pt" -> "🇧🇷"; "pl" -> "🇵🇱"; "ru" -> "🇷🇺"; "tr" -> "🇹🇷"; "ar" -> "🇸🇦"
        "id" -> "🇮🇩"; "zh" -> "🇨🇳"; "ja" -> "🇯🇵"; "ko" -> "🇰🇷"; "vi" -> "🇻🇳"; "th" -> "🇹🇭"; "tl" -> "🇵🇭"; "ur" -> "🇵🇰"
        else -> "🌐"
    }
    val name = if (c == "mx") "Spanish" else java.util.Locale.forLanguageTag(c).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { c.uppercase() }
    return "$flag $name"
}

/**
 * One plugin a repository offers, as CloudStream lists it: icon, name, language, version and size, what it
 * says it does; on the right Install, or — installed — Update when there's a newer one, its settings, Remove.
 */
@Composable
private fun OfferedRow(w: PluginWrapper, busy: androidx.compose.runtime.MutableState<Set<String>>, watch: Watch) {
    val p = w.plugin
    val have = CsPlugins.installedOf(w)
    val working = p.url in busy.value
    val scope = rememberCoroutineScope()
    fun install() {
        if (working) return
        busy.value = busy.value + p.url
        scope.launch {
            runCatching { CsPlugins.install(w) }
                .onSuccess { watch.notes.tryEmit("${if (have == null) "Installed" else "Updated"} ${p.name}") }
                .onFailure { watch.notes.tryEmit(it.message ?: "Couldn't install ${p.name}") }
            busy.value = busy.value - p.url
        }
    }
    Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Ink.text.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            Lc(L.drawable.lucide_ic_puzzle, tint = Ink.faint, size = 18.dp)
            p.iconUrl?.takeIf { it.startsWith("http") }?.let { Artwork(it, p.name, Modifier.fillMaxSize(), fill = false) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
            Text(p.name, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val facts = listOfNotNull(
                languageLabel(p.language), "v${p.version}", p.fileSize?.let(::bytes),
                when (p.status) { 0 -> "down"; 2 -> "slow"; 3 -> "beta"; else -> null },
            ).joinToString("   ")
            Text(facts, style = Type.meta.copy(color = Ink.muted, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            p.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            working -> Spinner(size = 22.dp, modifier = Modifier.padding(8.dp))
            have == null -> RoundTap(L.drawable.lucide_ic_circle_arrow_down, Ink.download, ::install)
            else -> {
                if (p.version > have.version) RoundTap(L.drawable.lucide_ic_refresh_cw, Ink.download, ::install)
                if (CsPlugins.loaded(have) && CsPlugins.settingsOf(have) != null) {
                    RoundTap(L.drawable.lucide_ic_sliders_horizontal) { CsPlugins.openSettings(have)?.let { watch.notes.tryEmit(it) } }
                }
                RoundTap(L.drawable.lucide_ic_trash_2) {
                    scope.launch {
                        CsPlugins.uninstall(have)
                        watch.notes.tryEmit("Removed ${p.name}")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundTap(icon: Int, tint: androidx.compose.ui.graphics.Color = Ink.text, onClick: () -> Unit) {
    Tap(onClick, shape = androidx.compose.foundation.shape.CircleShape) { Lc(icon, Modifier.padding(8.dp), tint = tint, size = 22.dp) }
}

/** An installed plugin: running (and how many sources it added), or the reason it didn't start; and Remove. */
@Composable
private fun InstalledRow(p: PluginData, changes: Int, starting: Boolean, watch: Watch) {
    val scope = rememberCoroutineScope()
    // Read again whenever a plugin loads or unloads.
    val (state, tint) = remember(p, changes, starting) {
        val failure = CsPlugins.failure(p)
        when {
            CsPlugins.loaded(p) -> {
                val names = CsPlugins.providers().filter { it.sourcePlugin == java.io.File(p.filePath).absolutePath }.map { it.name }
                (if (names.isEmpty()) "Running" else "Running · ${names.joinToString(", ")}") to Ink.muted
            }
            failure != null -> "Didn't start — $failure" to Ink.accent
            starting -> "Starting…" to Ink.faint
            else -> "Not running" to Ink.faint
        }
    }
    Row(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ink.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${p.internalName}  ·  v${p.version}", style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // A failure in full: the missing class it names is what tells what to add.
            Text(state, style = Type.meta.copy(color = tint, fontSize = 12.sp), maxLines = if (tint == Ink.accent) 10 else 3, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        // The plugin's own settings — accounts, which sources it asks, languages — as CloudStream's sliders icon.
        if (CsPlugins.loaded(p) && CsPlugins.settingsOf(p) != null) {
            Tap({ CsPlugins.openSettings(p)?.let { watch.notes.tryEmit(it) } }, shape = androidx.compose.foundation.shape.CircleShape) {
                Lc(L.drawable.lucide_ic_sliders_horizontal, Modifier.padding(8.dp), tint = Ink.text, size = 22.dp)
            }
            Spacer(Modifier.width(4.dp))
        }
        Outline("Remove") {
            scope.launch {
                CsPlugins.uninstall(p)
                watch.notes.tryEmit("Removed ${p.internalName}")
            }
        }
    }
}

/** The outlined secondary button the Addons cards use. */
@Composable
private fun Outline(label: String, onClick: () -> Unit) {
    Tap(onClick, shape = RoundedCornerShape(24.dp)) { pressed ->
        Box(
            Modifier.height(40.dp).border(1.dp, Ink.text.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                .background(Ink.text.copy(alpha = if (pressed) 0.12f else 0f)).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) { Text(label, style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = Ink.muted)) }
    }
}

/** Just the host: a repository link is not a secret, but the host is what reads. */
private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull() ?: url
