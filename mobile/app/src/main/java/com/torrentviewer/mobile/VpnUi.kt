package com.torrentviewer.mobile

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Type
import com.composables.icons.lucide.R as L

/**
 * The VPN's on/off, wherever it's offered (the Browser's sheet, Settings): on asks Android for leave the first time,
 * then connects. Without a config yet, [onNoConfig] says where to add one.
 */
@Composable
fun VpnSwitch(label: String = "VPN", onNoConfig: () -> Unit = {}, modifier: Modifier = Modifier) {
    val state by Vpn.state.collectAsState()
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) Vpn.connect()
    }
    val on = state == VpnState.ON || state == VpnState.CONNECTING
    LabeledToggle(if (state == VpnState.CONNECTING) "Connecting…" else label, on, { want ->
        when {
            !want -> Vpn.disconnect()
            !Vpn.hasConfig() -> onNoConfig()
            else -> Vpn.permission()?.let { allow.launch(it) } ?: Vpn.connect()
        }
    }, modifier)
}

/**
 * Settings → VPN: the WireGuard config (imported from a file), whether only this app goes through it, the switch,
 * and where it connects.
 */
@Composable
fun VpnSettings(say: (String) -> Unit) {
    val context = LocalContext.current
    val state by Vpn.state.collectAsState()
    val error by Vpn.error.collectAsState()
    var has by remember { mutableStateOf(Vpn.hasConfig()) }
    var onlyApp by remember { mutableStateOf(Vpn.onlyApp) }
    var blockAds by remember { mutableStateOf(Vpn.blockAds) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
        val problem = Vpn.import(text)
        has = Vpn.hasConfig()
        say(problem ?: "VPN config added")
    }
    // Turning on ad blocking needs the same one-time "allow this VPN" leave WireGuard uses.
    val allowAd = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { blockAds = true; Vpn.blockAds = true }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (state) {
                        VpnState.ON -> "Connected"
                        VpnState.CONNECTING -> "Connecting…"
                        VpnState.FAILED -> "Not connected"
                        VpnState.OFF -> if (has) "Off" else "No config yet"
                    },
                    style = Type.title.copy(fontWeight = FontWeight.Bold, color = if (state == VpnState.ON) Ink.download else Ink.text),
                )
                Vpn.server()?.let { Text("Server: $it", style = Type.meta.copy(color = Ink.faint)) }
            }
            if (has) VpnSwitch("")
        }
        error?.let { Text(it, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.danger)) }
        // The ad-block baseline: on with or without the VPN (one VPN slot, so it rides the tunnel when that's on, and
        // runs on its own when it's off). Shown even with no VPN config, since it stands alone.
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Block ads & trackers", style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    "Filters ad and tracker domains across the whole phone, with or without the VPN. Ads served from a site's own address (some in-app video ads) still get through.",
                    style = Type.meta.copy(color = Ink.faint),
                )
            }
            Toggle(blockAds, { want ->
                if (!want) { blockAds = false; Vpn.blockAds = false }
                else android.net.VpnService.prepare(context)?.let { allowAd.launch(it) } ?: run { blockAds = true; Vpn.blockAds = true }
            })
        }
        if (has) Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Only WatchHub", style = Type.label.copy(fontWeight = FontWeight.SemiBold))
                Text("Streams, downloads and the Browser go through it; the rest of the phone doesn't. Off: the whole phone.", style = Type.meta.copy(color = Ink.faint))
            }
            Toggle(onlyApp, { onlyApp = it; Vpn.onlyApp = it })
        }
        Button(if (has) "Replace Config File" else "Import Config File", { pick.launch(arrayOf("*/*")) }, Modifier.padding(top = 16.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_upload, compact = true)
        // Or the config's text, copied from the provider's page and pasted (the file can't be picked, or won't open).
        var pasting by remember { mutableStateOf(false) }
        var pasted by remember { mutableStateOf("") }
        if (!pasting) Button("Paste Config Text", { pasting = true }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_copy, compact = true)
        else {
            // Focused as it opens (the owner's choice): the cursor blinks where the text goes and the keyboard is up; a
            // line says what to do, and Paste takes what was copied in one tap.
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 140.dp).frosted(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)).padding(12.dp)) {
                if (pasted.isEmpty()) Text("Paste the config here ([Interface] … [Peer] …)", style = Type.meta.copy(color = Ink.faint))
                androidx.compose.foundation.text.BasicTextField(
                    pasted, { pasted = it }, Modifier.fillMaxWidth().heightIn(min = 116.dp).focusRequester(focus),
                    textStyle = Type.meta.copy(color = Ink.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.download),
                )
            }
            Button("Paste", {
                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { pasted = it }
            }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_clipboard_paste, compact = true)
            Button("Use This Config", {
                val problem = Vpn.import(pasted)
                has = Vpn.hasConfig()
                if (problem == null) { pasting = false; pasted = "" }
                say(problem ?: "VPN config added")
            }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_check, compact = true)
        }
        // The saved config, copied out (it holds the private key: paste it only somewhere safe).
        if (has) Button("Copy Config", {
            Vpn.configText()?.let { text ->
                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("WireGuard config", text))
                say("Config copied — it holds your private key, keep it safe")
            }
        }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_copy, compact = true)
        if (has) Button("Remove Config", { Vpn.forget(); has = false; say("VPN config removed") }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_trash_2, compact = true)
        PathText(
            "Use a WireGuard config (.conf) from your VPN's website: Mullvad (Account → WireGuard configuration), " +
                "Proton VPN (Downloads → WireGuard configuration), Windscribe or IVPN. It stays encrypted on this phone.",
            Modifier.padding(top = 12.dp), style = Type.meta.copy(color = Ink.faint),
        )
    }
}
