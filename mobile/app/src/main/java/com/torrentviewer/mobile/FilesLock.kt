package com.torrentviewer.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type
import com.composables.icons.lucide.R as L

/**
 * A four-digit code before Library and Files (the owner's choice: nobody else into the downloads). Set in Settings →
 * Lock; asked for in a row that opens on top of the tab bar; unlocked until the app goes to the background.
 */
object FilesLock {
    private const val KEY = "files-lock-code"

    /** The tabs behind the code. */
    val LOCKED = setOf(Tab.LIBRARY, Tab.FILES)

    /** Unlocked since the app last came to the front. */
    var unlocked by mutableStateOf(false)

    /** The tab the code is being asked for, its row open over the tab bar; null when none. */
    var asking by mutableStateOf<Tab?>(null)

    /** The code set, or null (no lock). Kept on this phone only. */
    fun code(): String? = Kv.global(KEY)?.takeIf { it.length == 4 }

    fun set(code: String?) = Kv.setGlobal(KEY, code?.takeIf { it.length == 4 && it.all(Char::isDigit) })

    private const val PASS = "files-lock-pass"

    /** A one-way digest: the password itself is never kept. */
    private fun digest(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(("watchhub:" + text).toByteArray())
        .joinToString("") { "%02x".format(it) }

    /**
     * The password for a forgotten pin: kept on this phone only, as a digest (a session-only stand-in for a real
     * account sign-in, the owner's choice for now). Null clears it.
     */
    fun setPassword(password: String?) = Kv.setGlobal(PASS, password?.takeIf { it.length >= 4 }?.let(::digest))

    fun hasPassword() = Kv.global(PASS) != null

    /** The password typed: right, unlocked (for this session); wrong, false. */
    fun tryPassword(typed: String): Boolean = (Kv.global(PASS) != null && digest(typed) == Kv.global(PASS)).also { if (it) unlocked = true }

    /** Whether [tab] needs the code now. */
    fun locks(tab: Tab) = tab in LOCKED && !unlocked && code() != null

    /** The code typed: right, unlocked; wrong, false. */
    fun tryCode(typed: String): Boolean = (typed == code()).also { if (it) unlocked = true }
}

/**
 * The tab bar's top row while the code is asked for (the owner's design): the bar's own face, a lock, and a field of
 * four stars that takes the code; the fourth digit checks it. Right: on to [FilesLock.asking]'s tab. Wrong: cleared.
 */
@Composable
fun CodeRow(onOpen: (Tab) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var typed by remember { mutableStateOf("") }
    /** The pin forgotten: the password instead. */
    var byPassword by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(byPassword) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Lc(L.drawable.lucide_ic_lock, tint = Ink.muted, size = 20.dp)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            if (typed.isEmpty()) Text(if (byPassword) "Auth Password" else "Auth Pin", style = Type.label.copy(color = Ink.faint), textAlign = TextAlign.Center, maxLines = 1)
            if (byPassword) BasicTextField(
                typed, { typed = it.replace("\n", "") },
                Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = TextStyle(color = Ink.text, fontSize = 16.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    val tab = FilesLock.asking
                    if (FilesLock.tryPassword(typed)) { FilesLock.asking = null; tab?.let(onOpen) }
                    else { typed = ""; android.widget.Toast.makeText(context, "Wrong password", android.widget.Toast.LENGTH_SHORT).show() }
                }),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                cursorBrush = SolidColor(Ink.download),
            ) else BasicTextField(
                typed,
                { v ->
                    typed = v.filter(Char::isDigit).take(4)
                    if (typed.length == 4) {
                        val tab = FilesLock.asking
                        if (FilesLock.tryCode(typed)) {
                            FilesLock.asking = null
                            tab?.let(onOpen)
                        } else {
                            typed = ""
                            android.widget.Toast.makeText(context, "Wrong code", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = TextStyle(color = Ink.text, fontSize = 22.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation('*'),
                singleLine = true,
                cursorBrush = SolidColor(Ink.download),
            )
        }
        // The pin forgotten: the password (when one is set).
        if (FilesLock.hasPassword()) Tap({ byPassword = !byPassword; typed = "" }, shape = CircleShape) {
            Lc(if (byPassword) L.drawable.lucide_ic_hash else L.drawable.lucide_ic_key_round, Modifier.padding(6.dp), tint = Ink.muted, size = 20.dp)
        }
        Tap({ FilesLock.asking = null }, shape = CircleShape) { Lc(L.drawable.lucide_ic_x, Modifier.padding(6.dp), tint = Ink.muted, size = 20.dp) }
    }
}

/** The ＋ between the tab bar's two rows: a link copied elsewhere, to download (the owner's design). */
@Composable
fun NavPlus(onClick: () -> Unit, modifier: Modifier = Modifier) {
    // No disc behind it (the owner's choice): the ＋ alone, large.
    Tap(onClick, modifier, CircleShape) {
        Lc(L.drawable.lucide_ic_plus, Modifier.padding(4.dp), tint = Ink.accent, size = 30.dp)
    }
}

/** Settings → Lock: set, change or remove the four-digit code. */
@Composable
fun LockSettings(say: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var has by remember { mutableStateOf(FilesLock.code() != null) }
    Text(
        if (has) "Library and Files ask for the code, once each time the app is opened." else "No code yet: Library and Files open for anyone.",
        Modifier.padding(bottom = 10.dp), style = Type.meta.copy(color = Ink.faint),
    )
    Field(code, { code = it.filter(Char::isDigit).take(4) }, if (has) "New four-digit code" else "Four-digit code", Modifier.fillMaxWidth(), secret = true, digits = true)
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(if (has) "Change Code" else "Set Code", {
            if (code.length != 4) say("Four digits, please") else {
                FilesLock.set(code)
                FilesLock.unlocked = true
                has = true
                code = ""
                say("Code set")
            }
        }, Modifier.weight(1f), icon = L.drawable.lucide_ic_lock, compact = true)
        if (has) Button("Remove", {
            FilesLock.set(null)
            FilesLock.setPassword(null)
            has = false
            say("Code removed")
        }, Modifier.weight(1f), icon = L.drawable.lucide_ic_x, compact = true)
    }
    // For a forgotten pin: a password, kept on this phone only (a real account sign-in comes later).
    if (has) {
        var pass by remember { mutableStateOf("") }
        Field(pass, { pass = it }, if (FilesLock.hasPassword()) "New Auth Password" else "Auth Password (for a forgotten pin)", Modifier.padding(top = 14.dp).fillMaxWidth(), secret = true)
        Button(if (FilesLock.hasPassword()) "Change Password" else "Set Password", {
            if (pass.length < 4) say("Four characters at least") else { FilesLock.setPassword(pass); pass = ""; say("Password set") }
        }, Modifier.padding(top = 10.dp).fillMaxWidth(), icon = L.drawable.lucide_ic_key_round, compact = true)
    }
}
