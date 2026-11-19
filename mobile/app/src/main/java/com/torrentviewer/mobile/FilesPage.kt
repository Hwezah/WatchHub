package com.torrentviewer.mobile

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.GRABBER_ROOT
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.NaturalOrder
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.ago
import com.torrentviewer.core.bytes
import com.torrentviewer.core.entryName
import com.torrentviewer.core.freeName
import com.torrentviewer.core.grain
import com.torrentviewer.core.isFixedFolder
import com.torrentviewer.core.isWithin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.composables.icons.lucide.R as L

/** One thing in a folder, read once per visit so scrolling never touches the disk. */
private data class FileEntry(val file: File, val folder: Boolean, val size: Long, val at: Long, val count: Int)

/** What a file is, from its extension: picks its badge, and whether our player opens it. */
private enum class Kind(val icon: Int) { VIDEO(L.drawable.lucide_ic_film), AUDIO(L.drawable.lucide_ic_music), IMAGE(L.drawable.lucide_ic_image), OTHER(L.drawable.lucide_ic_file_text) }

private val VIDEO_EXT = setOf("mkv", "mp4", "avi", "mov", "m4v", "webm", "ts", "wmv", "flv", "mpg", "mpeg", "3gp")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "bmp")

private fun kindOf(f: File): Kind = when (f.extension.lowercase()) {
    in VIDEO_EXT -> Kind.VIDEO
    in AUDIO_EXT -> Kind.AUDIO
    in IMAGE_EXT -> Kind.IMAGE
    else -> Kind.OTHER
}

/** What can be done to a file or folder, from its ⋯, a hold, or TalkBack's actions. */
private enum class Act(val label: String) { MOVE("Move"), COPY("Copy"), RENAME("Rename"), DELETE("Delete") }

/** The same rounded card the Downloads list uses, so the two pages read as one. */
private val FILE_CARD = RoundedCornerShape(18.dp)

/** A folder's contents: folders first (A–Z, "Season 2" before "Season 10"), then files newest first; hidden and half-written ones left out. */
private fun listEntries(dir: File): List<FileEntry> {
    val all = dir.listFiles().orEmpty().filter { !it.name.startsWith(".") && !it.name.endsWith(".part") }
        .map { FileEntry(it, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified(), if (it.isDirectory) it.list()?.count { n -> !n.startsWith(".") } ?: 0 else 0) }
    val (folders, files) = all.partition { it.folder }
    return folders.sortedWith(compareBy(NaturalOrder) { it.file.name }) + files.sortedByDescending { it.at }
}

/** A copy or a move under way: what, and how far, for the bar over the list. */
private data class Work(val copy: Boolean, val name: String, val done: Long, val total: Long)

/**
 * Copies and moves from the Files tab: one at a time, off the main thread and outliving the tab, since a film takes
 * a while to copy. A move within the same storage is a rename, done at once; to the other (SD card ↔ phone) it's a
 * copy and then the original deleted. The downloads list and Continue Watching follow a moved file.
 */
private object FileWork {
    /** The job running, if one is. */
    val now = MutableStateFlow<Work?>(null)

    /** Counts finished jobs, so an open folder lists itself again. */
    val ended = MutableStateFlow(0)

    /** [src] copied or moved into the folder [into] (shown to the owner as [place]); [say] gets the outcome. */
    fun start(src: File, into: File, copy: Boolean, place: String, say: (String) -> Unit) {
        now.value?.let { return say("One at a time: wait for the ${if (it.copy) "copy" else "move"} to finish.") }
        now.value = Work(copy, src.name, 0, 0)
        AppScope.launch {
            val note = withContext(Dispatchers.IO) {
                runCatching { if (copy) copyInto(src, into, place) else moveInto(src, into, place) }
                    .getOrElse { "Couldn't ${if (copy) "copy" else "move"} ${src.name}${it.message?.let { m -> ": $m" } ?: "."}" }
            }
            now.value = null
            ended.value++
            say(note)
        }
    }

    /** [src] copied into [into], under a numbered name if one is taken there. */
    private fun copyInto(src: File, into: File, place: String): String {
        val dest = File(into, freeName(src.name, into.list().orEmpty().toList(), src.isDirectory))
        copyAcross(src, dest)
        return "Copied to $place"
    }

    /** [src] moved into [into]: renamed where it can be, else copied across and the original deleted. */
    private fun moveInto(src: File, into: File, place: String): String {
        if (src.isDirectory && src.walkTopDown().any { it.name.endsWith(".part") }) return "A download is still writing into ${src.name}: move it once that's done."
        val dest = File(into, freeName(src.name, into.list().orEmpty().toList(), src.isDirectory))
        if (!src.renameTo(dest)) {
            copyAcross(src, dest)
            src.deleteRecursively()
        }
        Downloads.moved(src, dest)
        return "Moved to $place"
    }

    /** [src] (a file, or a folder and all in it) copied to [dest] with its progress shown; a half-made copy is removed on failure. */
    private fun copyAcross(src: File, dest: File) {
        val total = src.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val free = dest.parentFile?.usableSpace ?: 0L
        if (free in 1 until total) throw IllegalStateException("not enough room (it needs ${bytes(total)}, ${bytes(free)} free)")
        now.value = now.value?.copy(total = total)
        val done = longArrayOf(0L)
        runCatching { copyTree(src, dest, done) }.onFailure {
            dest.deleteRecursively()
            throw it
        }
    }

    /** [copyAcross]'s walk: folders made, files streamed, [done] counting bytes for the bar. */
    private fun copyTree(src: File, dest: File, done: LongArray) {
        if (src.isDirectory) {
            if (!dest.mkdirs() && !dest.isDirectory) throw IllegalStateException("storage refused the folder")
            src.listFiles().orEmpty().filter { !it.name.endsWith(".part") }.forEach { copyTree(it, File(dest, it.name), done) }
            return
        }
        var shown = 0L
        src.inputStream().use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done[0] += n
                    // A step of the bar each 4 MB, not each read.
                    if (done[0] - shown >= 4L shl 20) {
                        shown = done[0]
                        now.value = now.value?.copy(done = done[0])
                    }
                }
            }
        }
        dest.setLastModified(src.lastModified())
        now.value = now.value?.copy(done = done[0])
    }
}

/**
 * The Files tab: everything finished downloads left on the phone, as the folders they sit in — Grabber! at the root,
 * then Video (Movies, Shows with each show and season, YouTube), Audio and Browser. Videos play in our player with the
 * title they were saved for (so progress and Continue Watching follow them); anything else opens in another app.
 * Each entry's ⋯ (or a hold, or TalkBack's actions) moves, copies, renames or deletes it; a move or copy is placed by
 * opening the folder it goes in and tapping "Move Here". The header's folder-plus makes a new folder where you are.
 *
 * ponytail: on Android 11 and later the system shows us only the files we wrote ourselves (plus pictures, videos and
 * music other apps put there, if the media permission is granted) — another app's documents in Grabber! stay hidden.
 */
@Composable
fun FilesScreen(watch: Watch) {
    val context = LocalContext.current
    val root = remember { Downloads.keepDir() }
    // The folders walked into, below the root; kept as paths so a turn of the phone keeps the place.
    var trail by rememberSaveable { mutableStateOf(listOf<String>()) }
    val dir = trail.fold(root) { d, name -> File(d, name) }
    val finished by Downloads.finished.collectAsState()
    val work by FileWork.now.collectAsState()
    val ended by FileWork.ended.collectAsState()
    var bump by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(dir.path, finished, bump, ended) { entries = withContext(Dispatchers.IO) { runCatching { listEntries(dir) }.getOrDefault(emptyList()) } }
    var menu by remember { mutableStateOf<FileEntry?>(null) }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    // Naming: a new folder (no entry) or a rename (the entry).
    var naming by remember { mutableStateOf<Pair<Boolean, FileEntry?>?>(null) }
    // What's being moved or copied, while the folder it goes in is picked: its path, and whether it's a copy.
    var carry by rememberSaveable { mutableStateOf<String?>(null) }
    var carryCopy by rememberSaveable { mutableStateOf(false) }
    val carried = carry?.let { File(it) }?.takeIf { it.exists() }
    val place = (listOf(GRABBER_ROOT) + trail).joinToString(" › ")
    fun say(note: String) { watch.notes.tryEmit(note) }

    BackHandler(LocalShown.current && (trail.isNotEmpty() || carried != null)) { if (trail.isNotEmpty()) trail = trail.dropLast(1) else carry = null }

    fun open(e: FileEntry) {
        if (e.folder) { trail = trail + e.file.name; return }
        val saved = finished.firstOrNull { it.file.path == e.file.path }
        if (kindOf(e.file) == Kind.VIDEO) {
            val from = saved?.imdb?.let { Title(it, saved.kind ?: "movie", saved.label.substringBefore(" · "), poster = saved.poster) }
            watch.playFile(e.file.path, saved?.label ?: e.file.nameWithoutExtension, from, saved?.poster)
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", e.file)
            val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e.file.extension.lowercase()) ?: "*/*"
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), e.file.name))
        }.onFailure { say("No app here opens ${e.file.extension.uppercase().ifBlank { "this file" }}.") }
    }

    /** Whether [e] is one of the layout's own folders (Video, Movies, Browser…): emptied, never moved or renamed. */
    fun fixed(e: FileEntry) = e.folder && isFixedFolder(trail + e.file.name)

    fun act(e: FileEntry, a: Act) {
        when (a) {
            Act.MOVE, Act.COPY -> { carry = e.file.path; carryCopy = a == Act.COPY }
            Act.RENAME -> naming = false to e
            Act.DELETE -> deleting = e
        }
    }

    fun actsOf(e: FileEntry) = Act.entries.filter { !(fixed(e) && (it == Act.MOVE || it == Act.RENAME)) }

    fun putHere() {
        val src = carried ?: return
        when {
            src.isDirectory && isWithin(dir.path, src.path) -> say("A folder can't go inside itself.")
            !carryCopy && src.parentFile?.path == dir.path -> say("It's already in this folder.")
            else -> {
                FileWork.start(src, dir, carryCopy, place, ::say)
                carry = null
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink.bg).grain()) {
        Heading("Files", trailing = {
            Tap({ naming = true to null }, Modifier.semantics { contentDescription = "New Folder"; role = Role.Button }, CircleShape) {
                Lc(L.drawable.lucide_ic_folder_plus, Modifier.padding(8.dp), tint = Ink.text, size = 24.dp)
            }
        })
        // Where we are: Grabber! › Video › Shows › 1923, each part a step back up.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trail.isNotEmpty()) Tap({ trail = trail.dropLast(1) }, Modifier.semantics { contentDescription = "Up a folder" }, shape = CircleShape) {
                Lc(L.drawable.lucide_ic_arrow_left, Modifier.padding(8.dp), tint = Ink.text, size = 24.dp)
            }
            // Spread across the width (the owner's choice), each part a tap back to that folder; the one here in bold.
            val parts = listOf(GRABBER_ROOT) + trail
            Row(Modifier.weight(1f).padding(start = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                parts.forEachIndexed { i, name ->
                    if (i > 0) Text("›", style = Type.meta.copy(color = Ink.faint, fontSize = 20.sp))
                    val here = i == parts.lastIndex
                    Text(
                        name,
                        Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).clickable(enabled = !here) { trail = trail.take(i) }.padding(horizontal = 4.dp, vertical = 6.dp),
                        style = Type.meta.copy(color = if (here) Ink.text else Ink.muted, fontSize = 16.sp, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        work?.let { WorkBar(it, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) }
        carried?.let { CarryBar(it.name, carryCopy, { carry = null }, ::putHere, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) }
        val list = entries
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyNote(if (trail.isEmpty()) "Finished downloads land here, sorted into folders." else "This folder is empty.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = NAV_CLEARANCE)) {
                items(list, key = { it.file.path }) { e ->
                    // Picking where a move or copy goes: only folders open, and not the one being moved itself.
                    val picking = carried != null
                    val blocked = picking && (!e.folder || e.file.path == carried?.path)
                    EntryRow(
                        e, picking, blocked, if (picking) emptyList() else actsOf(e),
                        onOpen = { if (!blocked) open(e) },
                        onMenu = { menu = e },
                        onAct = { act(e, it) },
                    )
                }
            }
        }
    }

    menu?.let { e ->
        val isFixed = fixed(e)
        Sheet(
            title = e.file.name,
            options = actsOf(e).map { a ->
                when (a) {
                    Act.MOVE -> Option(a, "Move", "Pick the folder it goes in", icon = L.drawable.lucide_ic_folder_input)
                    Act.COPY -> Option(a, "Copy", "Keep this one, put a copy in another folder", icon = L.drawable.lucide_ic_copy)
                    Act.RENAME -> Option(a, "Rename", icon = L.drawable.lucide_ic_pencil)
                    Act.DELETE -> Option(a, if (isFixed) "Empty" else "Delete", if (e.folder) "${e.count} items" else bytes(e.size), icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger)
                }
            },
            selected = null,
            onPick = {
                menu = null
                act(e, it)
            },
            onDismiss = { menu = null },
        )
    }

    deleting?.let { e ->
        val isFixed = fixed(e)
        val what = if (e.folder) "the ${e.file.name} folder and everything in it" else e.file.name
        Sheet(
            title = if (isFixed) "Empty the ${e.file.name} folder?" else "Delete $what?",
            options = listOf(
                Option(true, if (isFixed) "Delete everything in it" else "Delete from this phone", if (e.folder) "${e.count} items" else bytes(e.size), icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger),
                Option(false, "Keep it", icon = L.drawable.lucide_ic_check),
            ),
            selected = null,
            onPick = {
                if (it) remove(e.file, isFixed)
                deleting = null
                bump++
            },
            onDismiss = { deleting = null },
        )
    }

    naming?.let { (_, e) ->
        val ext = e?.takeIf { !it.folder }?.file?.extension?.takeIf { it.isNotEmpty() }
        NameSheet(
            title = if (e == null) "New Folder" else "Rename",
            start = e?.let { if (ext != null) it.file.nameWithoutExtension else it.file.name } ?: "",
            verb = if (e == null) "Create" else "Rename",
            onName = { typed ->
                val name = entryName(typed, ext)
                val taken = dir.list().orEmpty().filter { it != e?.file?.name }
                when {
                    e != null && name == e.file.name -> null
                    taken.any { it.equals(name, ignoreCase = true) } -> "There's already a $name here."
                    e == null -> if (File(dir, name).mkdirs()) null else "Couldn't make a folder here."
                    else -> {
                        val dest = File(dir, name)
                        // A change of case only goes through a hidden name: the phone's storage sees "a" and "A" as one.
                        val ok = if (name.equals(e.file.name, ignoreCase = true)) {
                            val step = File(dir, ".rename-${System.nanoTime()}")
                            e.file.renameTo(step) && (step.renameTo(dest) || run { step.renameTo(e.file); false })
                        } else e.file.renameTo(dest)
                        if (ok) { Downloads.moved(e.file, dest); null } else "Couldn't rename it here."
                    }
                }.also { if (it == null) bump++ }
            },
            onDismiss = { naming = null },
        )
    }
}

/**
 * Deletes a file (or a folder, everything in it) and forgets the downloads it held, so the Downloads list and
 * Continue Watching don't point at nothing. One of the layout's own folders ([fixed]: Video, Movies…) is emptied, never removed.
 */
private fun remove(file: File, fixed: Boolean) {
    val gone = Downloads.finished.value.filter { it.file.path == file.path || it.file.path.startsWith(file.path + File.separator) }
    // The folder stays even when emptied: the owner may have made it, and may be looking at it.
    gone.forEach { Downloads.delete(it, prune = false) }
    if (!fixed) runCatching { file.deleteRecursively() }
    else file.listFiles().orEmpty().forEach { runCatching { it.deleteRecursively() } }
}

/**
 * One row: a folder (badge, name, how many inside) or a file (type badge, name, size and age), and its ⋯ for [acts].
 * [picking]: a move or copy is being placed, so no ⋯ or hold; [blocked] rows (files, the folder being moved) are dimmed.
 */
@Composable
private fun EntryRow(e: FileEntry, picking: Boolean, blocked: Boolean, acts: List<Act>, onOpen: () -> Unit, onMenu: () -> Unit, onAct: (Act) -> Unit) {
    Tap(
        onOpen,
        Modifier.fillMaxWidth().padding(bottom = 10.dp).alpha(if (blocked) 0.4f else 1f)
            // TalkBack's actions menu: the same Move, Copy, Rename and Delete as the ⋯.
            .semantics { if (acts.isNotEmpty()) customActions = acts.map { a -> CustomAccessibilityAction(a.label) { onAct(a); true } } },
        FILE_CARD,
        onLongClick = if (picking) null else onMenu,
        longClickLabel = if (picking) null else "Options",
    ) {
        Row(Modifier.frosted(FILE_CARD, CARD_FACE).padding(start = 16.dp, end = if (picking) 12.dp else 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                e.folder && e.file.name == "YouTube" -> L.drawable.lucide_ic_youtube
                e.folder && e.file.name == "Browser" -> L.drawable.lucide_ic_globe
                e.folder -> L.drawable.lucide_ic_folder
                else -> kindOf(e.file).icon
            }
            // A coloured outline icon alone, no disc behind it (the owner's choice).
            Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Lc(icon, tint = Ink.accent, size = 26.dp) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(e.file.name, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                val meta = if (e.folder) (if (e.count == 1) "1 item" else "${e.count} items") else "${bytes(e.size)}  ·  ${ago(e.at)}"
                Text(meta, Modifier.padding(top = 2.dp), style = Type.meta.copy(fontSize = 12.sp))
            }
            Lc(if (e.folder) L.drawable.lucide_ic_chevron_right else if (kindOf(e.file) == Kind.VIDEO) L.drawable.lucide_ic_play else L.drawable.lucide_ic_external_link, tint = Ink.muted, size = 20.dp)
            if (!picking) Tap(onMenu, Modifier.size(40.dp).semantics { contentDescription = "Options for ${e.file.name}"; role = Role.Button }, CircleShape) {
                Box(contentAlignment = Alignment.Center) { Lc(L.drawable.lucide_ic_ellipsis_vertical, tint = Ink.muted, size = 20.dp) }
            }
        }
    }
}

/** While a move or copy is being placed: what's carried, and Cancel / Move Here side by side. */
@Composable
private fun CarryBar(name: String, copy: Boolean, onCancel: () -> Unit, onHere: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().frosted(FILE_CARD, CARD_FACE).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lc(if (copy) L.drawable.lucide_ic_copy else L.drawable.lucide_ic_folder_input, tint = Ink.accent, size = 22.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (copy) "Copying" else "Moving", style = Type.meta.copy(fontSize = 12.sp))
                Text(name, Modifier.basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            }
        }
        Text("Open the folder it goes in, then tap ${if (copy) "Copy" else "Move"} Here.", Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Cancel", onCancel, Modifier.weight(1f), compact = true)
            Button(if (copy) "Copy Here" else "Move Here", onHere, Modifier.weight(1f), primary = true, compact = true)
        }
    }
}

/** A copy or move running: its name and a thin bar of how far it has got. */
@Composable
private fun WorkBar(w: Work, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().frosted(FILE_CARD, CARD_FACE).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Lc(if (w.copy) L.drawable.lucide_ic_copy else L.drawable.lucide_ic_folder_input, tint = Ink.accent, size = 22.dp)
            Text("${if (w.copy) "Copying" else "Moving"} ${w.name}", Modifier.weight(1f).padding(horizontal = 12.dp).basicMarquee(), style = Type.label.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            if (w.total > 0) Text("${(w.done * 100 / w.total).coerceIn(0, 100)}%", style = Type.meta)
        }
        val part = if (w.total > 0) (w.done.toFloat() / w.total).coerceIn(0f, 1f) else 0f
        Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Ink.text.copy(alpha = 0.1f))) {
            Box(Modifier.fillMaxWidth(part).fillMaxHeight().background(Ink.accent))
        }
    }
}

/** A name to type (a new folder's, or a rename): [onName] makes it and says what's wrong, or null when done. */
@Composable
private fun NameSheet(title: String, start: String, verb: String, onName: (String) -> String?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(start) }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        if (text.isBlank()) return
        problem = onName(text)
        if (problem == null) onDismiss()
    }
    BottomSheet(title, onDismiss) {
        Column(Modifier.imePadding().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp)) {
            Field(text, { text = it; problem = null }, "Name", Modifier.fillMaxWidth(), onSubmit = ::submit, focus = focus)
            problem?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.meta.copy(color = Ink.danger)) }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Cancel", onDismiss, Modifier.weight(1f))
                Button(verb, ::submit, Modifier.weight(1f), primary = true)
            }
        }
    }
}
