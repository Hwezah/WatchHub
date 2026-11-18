package com.torrentviewer.mobile

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.torrentviewer.core.GRABBER_ROOT
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Note
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.torrentviewer.core.Watch
import com.torrentviewer.core.grain
import com.torrentviewer.core.ago
import com.torrentviewer.core.bytes
import kotlinx.coroutines.Dispatchers
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

/** The same rounded card the Downloads list uses, so the two pages read as one. */
private val FILE_CARD = RoundedCornerShape(18.dp)

/** A folder's contents: folders first (A–Z), then files newest first, hidden files and half-written ones left out. */
private fun listEntries(dir: File): List<FileEntry> =
    dir.listFiles().orEmpty().filter { !it.name.startsWith(".") && !it.name.endsWith(".part") }
        .map { FileEntry(it, it.isDirectory, if (it.isDirectory) 0L else it.length(), it.lastModified(), if (it.isDirectory) it.list()?.count { n -> !n.startsWith(".") } ?: 0 else 0) }
        .sortedWith(compareByDescending<FileEntry> { it.folder }.thenBy { if (it.folder) it.file.name.lowercase() else "" }.thenByDescending { if (it.folder) 0L else it.at })

/**
 * The Files tab: everything finished downloads left on the phone, as the folders they sit in — Grabber! at the
 * root, then Movies, Shows (each show, each season), YouTube, Audio, Documents… Videos play in our player with the
 * title they were saved for (so progress and Continue Watching follow them); anything else opens in another app.
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
    var bump by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(dir.path, finished, bump) { entries = withContext(Dispatchers.IO) { runCatching { listEntries(dir) }.getOrDefault(emptyList()) } }
    var deleting by remember { mutableStateOf<FileEntry?>(null) }

    BackHandler(LocalShown.current && trail.isNotEmpty()) { trail = trail.dropLast(1) }

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
        }.onFailure { watch.notes.tryEmit("No app here opens ${e.file.extension.uppercase().ifBlank { "this file" }}.") }
    }

    Column(Modifier.fillMaxSize().background(Ink.bg).grain()) {
        Heading("Files")
        // Where we are: Grabber! › Shows › 1923, each part a step back up.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trail.isNotEmpty()) Tap({ trail = trail.dropLast(1) }, shape = CircleShape) { Lc(L.drawable.lucide_ic_arrow_left, Modifier.padding(8.dp), tint = Ink.text, size = 20.dp) }
            // Spread across the width (the owner's choice), each part a tap back to that folder; the one here in bold.
            val parts = listOf(GRABBER_ROOT) + trail
            Row(Modifier.weight(1f).padding(start = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                parts.forEachIndexed { i, name ->
                    if (i > 0) Text("›", style = Type.meta.copy(color = Ink.faint, fontSize = 13.sp))
                    val here = i == parts.lastIndex
                    Text(
                        name,
                        Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).clickable(enabled = !here) { trail = trail.take(i) }.padding(horizontal = 4.dp, vertical = 6.dp),
                        style = Type.meta.copy(color = if (here) Ink.text else Ink.muted, fontSize = 13.sp, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val list = entries
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyNote(if (trail.isEmpty()) "Finished downloads land here, sorted into folders." else "This folder is empty.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 180.dp)) {
                items(list, key = { it.file.path }) { e -> EntryRow(e, { open(e) }, { deleting = e }) }
            }
        }
    }

    deleting?.let { e ->
        val what = if (e.folder) "the ${e.file.name} folder and everything in it" else e.file.name
        Sheet(
            title = "Delete $what?",
            options = listOf(
                Option(true, "Delete from this phone", if (e.folder) "${e.count} items" else bytes(e.size), icon = L.drawable.lucide_ic_trash_2, iconTint = Ink.danger),
                Option(false, "Keep it", icon = L.drawable.lucide_ic_check),
            ),
            selected = null,
            onPick = {
                if (it) remove(e.file)
                deleting = null
                bump++
            },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * Deletes a file (or a folder, everything in it) and forgets the downloads it held, so the Downloads list and
 * Continue Watching don't point at nothing. The root's own folders (Movies, Shows…) are emptied, never removed.
 */
private fun remove(file: File) {
    val gone = Downloads.finished.value.filter { it.file.path == file.path || it.file.path.startsWith(file.path + File.separator) }
    gone.forEach { Downloads.delete(it) }
    if (file.exists() && file.name !in com.torrentviewer.core.GRABBER_FOLDERS) runCatching { file.deleteRecursively() }
    else if (file.isDirectory) file.listFiles().orEmpty().forEach { runCatching { it.deleteRecursively() } }
}

/** One row: a folder (badge, name, how many inside) or a file (type badge, name, size and age). */
@Composable
private fun EntryRow(e: FileEntry, onOpen: () -> Unit, onDelete: () -> Unit) {
    Tap(onOpen, Modifier.fillMaxWidth().padding(bottom = 10.dp), FILE_CARD, onLongClick = onDelete) {
        Row(Modifier.frosted(FILE_CARD, CARD_FACE).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                e.folder && e.file.name == "YouTube" -> L.drawable.lucide_ic_youtube
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
        }
    }
}
