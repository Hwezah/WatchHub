package com.torrentviewer.mobile

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type
import com.composables.icons.lucide.R as L

/**
 * How far the picture is scaled inside its 16:9 frame: as published; a little, for 2:1 trailers;
 * enough to take off the bars a 2.39:1 ("scope") trailer has burned into the picture, at the cost of its sides.
 */
private val ZOOMS = listOf(1f to "Fit", 1.125f to "Fill", 1.34f to "Crop bars")

/**
 * The YouTube embed, filling its frame edge to edge: no page margin, the iframe the frame's exact size,
 * black behind it, and scaled from the centre by [zoom]. Loaded with a youtube.com base so the embed has
 * the origin it now asks for (without one it refuses to play).
 */
private fun embedHtml(id: String, zoom: Float) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
html,body{margin:0;padding:0;height:100%;background:#000;overflow:hidden}
#frame{position:fixed;inset:0;overflow:hidden;background:#000}
iframe{position:absolute;top:50%;left:50%;width:100%;height:100%;border:0;
 transform:translate(-50%,-50%) scale($zoom);transform-origin:center center;transition:transform .25s ease}
</style></head><body><div id="frame">
<iframe id="yt" src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&modestbranding=1&iv_load_policy=3&fs=0"
 allow="autoplay; encrypted-media; picture-in-picture" referrerpolicy="strict-origin-when-cross-origin"></iframe>
</div></body></html>
""".trimIndent()

/**
 * A trailer played here rather than in YouTube: a card floating a few dp off the screen's edges with a
 * thin frame around the video, a zoom for bars baked into the picture, and full screen — sideways, the
 * bars of the system hidden — without reloading the video. The card lives in its own window, so full
 * screen covers the tab bar too.
 */
@Composable
fun TrailerSheet(youtubeId: String, title: String, onDismiss: () -> Unit) {
    var full by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(0) }
    val activity = LocalActivity.current
    // A phone: a sheet on the bottom edge. A tablet or wider: a card in the middle.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // Full screen: sideways, and the status and navigation bars out of the way.
        DisposableEffect(full) {
            activity?.requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            window?.let { w ->
                val c = WindowInsetsControllerCompat(w, w.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (full) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(w, false)
            }
            onDispose { }
        }
        DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
        BackHandler { if (full) full = false else onDismiss() }

        Box(
            Modifier.fillMaxSize()
                .background(if (full) Color.Black else Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { if (!full) onDismiss() },
            contentAlignment = if (full || wide) Alignment.Center else Alignment.BottomCenter,
        ) {
            Column(
                when {
                    full -> Modifier.fillMaxSize()
                    // Tablet: centred, rounded all round, a little off the edges.
                    wide -> Modifier.padding(24.dp).widthIn(max = 760.dp).fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp)).background(Ink.surface)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {}
                        .padding(8.dp)
                    // Phone: flush with the bottom edge, top corners rounded; thin padding inside, kept above the gesture bar.
                    else -> Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Ink.surface)
                        .clickable(remember { MutableInteractionSource() }, indication = null) {}
                        .navigationBarsPadding()
                        .padding(8.dp)
                },
            ) {
                if (!full) Row(Modifier.padding(start = 10.dp, bottom = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trailer", style = Type.title.copy(fontWeight = FontWeight.Bold))
                        Text(title, style = Type.meta.copy(color = Ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Round(L.drawable.lucide_ic_zoom_in) { zoom = (zoom + 1) % ZOOMS.size }
                    Round(L.drawable.lucide_ic_maximize) { full = true }
                    Round(L.drawable.lucide_ic_x, onDismiss)
                }
                // The same view in both layouts, so going full screen doesn't restart the video.
                Box(
                    if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    YouTubeView(youtubeId, ZOOMS[zoom].first)
                    if (full) Row(Modifier.padding(12.dp)) {
                        Round(L.drawable.lucide_ic_zoom_in, dark = true) { zoom = (zoom + 1) % ZOOMS.size }
                        Round(L.drawable.lucide_ic_minimize, dark = true) { full = false }
                    }
                }
                if (!full && zoom != 0) Text(
                    "Zoom: ${ZOOMS[zoom].second}", Modifier.padding(start = 10.dp, top = 6.dp),
                    style = Type.meta.copy(color = Ink.faint),
                )
            }
        }
    }
}

@Composable
private fun Round(icon: Int, dark: Boolean = false, onClick: () -> Unit) {
    Tap(onClick, Modifier.padding(start = 4.dp), CircleShape) {
        Box(Modifier.background(if (dark) Color.Black.copy(alpha = 0.45f) else Color.Transparent, CircleShape).padding(9.dp)) {
            Lc(icon, tint = if (dark) Color.White else Ink.text, size = 22.dp)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeView(id: String, zoom: Float) {
    var web by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(AndroidColor.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadDataWithBaseURL("https://www.youtube.com/", embedHtml(id, zoom), "text/html", "utf-8", null)
                web = this
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    // A new zoom scales the playing video in place, rather than reloading it.
    LaunchedEffect(zoom) {
        web?.evaluateJavascript("var f=document.getElementById('yt');if(f)f.style.transform='translate(-50%,-50%) scale($zoom)';", null)
    }
    DisposableEffect(Unit) {
        onDispose {
            web?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
        }
    }
}
