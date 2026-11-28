package com.torrentviewer.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as L
import com.torrentviewer.core.Breakdown
import com.torrentviewer.core.Breakdowns
import com.torrentviewer.core.Episode
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Scene
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type

/**
 * A film's or episode's full story, to read before watching ([Breakdowns]: Wikipedia's plot, or the episode's summary).
 * Everything but the last paragraph shows; the ending waits behind a button, as asked for. Kept once read: a second
 * look is instant.
 */
@Composable
fun StorySheet(title: Title, episode: Episode?, onDismiss: () -> Unit) {
    var story by remember(title.imdb, episode?.id) { mutableStateOf<Breakdown?>(null) }
    var failed by remember(title.imdb, episode?.id) { mutableStateOf<String?>(null) }
    var ending by remember(title.imdb, episode?.id) { mutableStateOf(false) }
    LaunchedEffect(title.imdb, episode?.id) {
        runCatching { Breakdowns.of(title, episode) }
            .onSuccess { story = it }
            .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) failed = e.message ?: "Couldn't get the story" }
    }
    // What it's doing meanwhile ("Reading Wikipedia…").
    val steps by Breakdowns.steps.collectAsState()
    val doing = steps[Breakdowns.keyOf(title, episode)]
    val heading = episode?.let { "S${it.season}:E${it.episode} · ${it.name}" } ?: title.title
    BottomSheet(heading, onDismiss) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val got = story
            when {
                failed != null -> item { Text(failed.orEmpty(), Modifier.padding(vertical = 12.dp), style = Type.body) }
                got == null -> item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spinner(size = 30.dp)
                        Text(doing ?: "Starting…", Modifier.padding(top = 12.dp), style = Type.meta)
                    }
                }
                else -> {
                    items(got.scenes) { SceneText(it) }
                    if (got.ending.isNotEmpty()) {
                        if (ending) items(got.ending) { SceneText(it) }
                        else item {
                            Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("The ending is left out.", style = Type.meta)
                                Button("Reveal the Ending", { ending = true }, Modifier.padding(top = 10.dp), icon = L.drawable.lucide_ic_eye, compact = true)
                            }
                        }
                    }
                    item { Text(got.source, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.faint)) }
                }
            }
        }
    }
}

/** One paragraph; with times (none from Wikipedia), those on the left. */
@Composable
private fun SceneText(scene: Scene) {
    Row {
        if (scene.from.isNotEmpty()) Text(
            if (scene.to.isNotEmpty()) "${scene.from}\n${scene.to}" else scene.from,
            Modifier.width(62.dp), style = Type.meta.copy(color = Ink.accent, fontWeight = FontWeight.SemiBold),
        )
        Text(scene.text, Modifier.weight(1f), style = Type.body.copy(color = Ink.text.copy(alpha = 0.88f)))
    }
}
