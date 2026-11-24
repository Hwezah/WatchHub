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
 * A four-digit code before what's on the phone (the Library's saved downloads) and Files (the owner's choice: nobody else into the downloads). Set in Settings →
 * Lock; asked for in a row that opens on top of the tab bar; unlocked for [FilesLock.timeout].
 */
object FilesLock {
    private const val KEY = "files-lock-code"

    /**
     * The tabs behind the code. The Library itself opens: what's on the phone, under its "On this phone" chevron, is
     * what asks (the owner's choice), so it isn't asked for twice.
     */
    val LOCKED = setOf(Tab.FILES)

    /** Unlocked: by the code or the password, at [unlockedAt]; how long that lasts is [timeout]. */
    var unlocked by mutableStateOf(false)

    /** When the code was last typed right. */
    private var unlockedAt = 0L

    /** The tab the code is being asked for, its row open over the tab bar; null when none. */
    var asking by mutableStateOf<Tab?>(null)

    /** What opens once the code is right, beside [asking]'s tab: the "On this phone" list. */
    var then: (() -> Unit)? = null

    /** Asks for the code before [tab], and [after] once it's right. */
    fun ask(tab: Tab, after: (() -> Unit)? = null) {
        then = after
        asking = tab
    }

    private const val TIMEOUT = "files-lock-timeout"

    /** How long an unlock lasts, in ms (Settings → Lock): [IMMEDIATELY], a few minutes, or [UNTIL_CLOSED]. */
    const val IMMEDIATELY = 0L
    const val UNTIL_CLOSED = -1L

    /** The choices for [timeout], as Settings → Lock shows them. */
    val TIMEOUTS = listOf(IMMEDIATELY to "Immediately", 60_000L to "1 min", 300_000L to "5 min", 900_000L to "15 min", UNTIL_CLOSED to "App closes")

    /** Kept app-wide; until the app is closed (to the background) unless chosen otherwise. */
    var timeout by mutableStateOf(Kv.global(TIMEOUT)?.toLongOrNull() ?: UNTIL_CLOSED)
        private set

    fun setTimeout(ms: Long) {
        timeout = ms
        Kv.setGlobal(TIMEOUT, ms.toString())
    }

    /** Unlocked now: by the code, and not timed out. */
    private fun open() = unlocked && (timeout <= 0 || System.currentTimeMillis() - unlockedAt < timeout)

    /** Whether what's locked needs the code now: a code set, and not [open]. */
    fun needed() = code() != null && !open()

    /** Another page or tab: locked again at once when the timeout is [IMMEDIATELY] (the owner's choice). */
    fun left() {
        if (timeout == IMMEDIATELY) unlocked = false
    }

    /** The app sent to the background: locked again, unless a few minutes were chosen (the clock decides those). */
    fun backgrounded() {
        if (timeout <= 0) unlocked = false
        asking = null
        then = null
    }

    /**
     * The code was right: what was asked for opens — [then] where there is one (the saved list, on the page already),
     * else the tab, through [onOpen].
     */
    fun passed(tab: Tab?, onOpen: (Tab) -> Unit) {
        val after = then
        asking = null
        then = null
        if (after != null) after() else tab?.let(onOpen)
    }

    private fun opened() {
        unlocked = true
        unlockedAt = System.currentTimeMillis()
    }

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
    fun tryPassword(typed: String): Boolean = (Kv.global(PASS) != null && digest(typed) == Kv.global(PASS)).also { if (it) opened() }

    /** Whether [tab] needs the code now. */
    fun locks(tab: Tab) = tab in LOCKED && needed()

    /** The code typed: right, unlocked; wrong, false. */
    fun tryCode(typed: String): Boolean = (typed == code()).also { if (it) opened() }
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
                    if (FilesLock.tryPassword(typed)) FilesLock.passed(tab, onOpen)
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
                        if (FilesLock.tryCode(typed)) FilesLock.passed(tab, onOpen) else {
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

/** Whether the "Add a link" sheet is up: opened from the Library's heading, shown by the shell over every tab. */
object AddLink {
    /** Set by the Library's ＋, cleared when the sheet is dismissed. */
    var open by mutableStateOf(false)
}

/** The ＋ beside the Library's heading: a link copied elsewhere, to download (the owner's design: big, in the accent). */
@Composable
fun AddLinkPlus(modifier: Modifier = Modifier) {
    Tap({ AddLink.open = true }, modifier, CircleShape) {
        Lc(L.drawable.lucide_ic_plus, Modifier.padding(4.dp), tint = Ink.accent, size = 42.dp)
    }
}

/**
 * Settings → Lock, as steps (the owner found one page of fields confusing). Off: one form — the pin twice and the Auth
 * Password — and one button to turn it on. On: what's set, and three choices, each opening its own short form that asks
 * for the current pin first.
 */
@Composable
fun LockSettings(say: (String) -> Unit) {
    var on by remember { mutableStateOf(FilesLock.code() != null) }
    /** The open form while on: "pin", "pass" or "off"; null for the three choices. */
    var step by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    fun reset() { step = null; current = ""; pin = ""; again = ""; pass = "" }
    val gap = Modifier.padding(top = 10.dp).fillMaxWidth()
    @Composable
    fun Note(text: String) = Text(text, Modifier.padding(bottom = 4.dp), style = Type.meta.copy(color = Ink.faint))
    @Composable
    fun PinField(value: String, set: (String) -> Unit, hint: String) = Field(value, { set(it.filter(Char::isDigit).take(4)) }, hint, gap, secret = true, digits = true)
    /** The current pin, or the Auth Password for a forgotten one: what every change asks for. */
    fun knows() = FilesLock.tryCode(current) || FilesLock.tryPassword(current)
    @Composable
    fun CancelSave(label: String, onSave: () -> Unit) = Row(gap, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button("Cancel", { reset() }, Modifier.weight(1f))
        Button(label, onSave, Modifier.weight(1f), primary = true)
    }

    if (!on) {
        Note("Lock your saved downloads and Files behind a four-digit pin. The Auth Password opens them if you forget the pin.")
        PinField(pin, { pin = it }, "Pin (four digits)")
        PinField(again, { again = it }, "The pin again")
        Field(pass, { pass = it }, "Auth Password", gap, secret = true)
        Button("Turn On Lock", {
            when {
                pin.length != 4 -> say("The pin is four digits")
                again != pin -> say("The two pins don't match")
                pass.length < 4 -> say("The Auth Password needs four characters at least")
                else -> {
                    FilesLock.set(pin)
                    FilesLock.setPassword(pass)
                    FilesLock.tryCode(pin)
                    on = true
                    reset()
                    say("Lock on")
                }
            }
        }, gap, icon = L.drawable.lucide_ic_lock, primary = true)
        return
    }
    when (step) {
        null -> {
            Note("On. Saved downloads and Files ask for the pin" + if (FilesLock.hasPassword()) "; the Auth Password opens them too." else ". No Auth Password yet: set one in case you forget the pin.")
            Text("Lock Again", Modifier.padding(top = 8.dp), style = Type.label.copy(color = Ink.text))
            Text(
                "Immediately: as soon as you go to another page.",
                Modifier.padding(top = 2.dp), style = Type.meta.copy(color = Ink.faint),
            )
            BarChoices(FilesLock.TIMEOUTS.map { it.second }, FilesLock.TIMEOUTS.indexOfFirst { it.first == FilesLock.timeout }.takeIf { it >= 0 }, Modifier.padding(top = 4.dp)) {
                FilesLock.setTimeout(FilesLock.TIMEOUTS[it].first)
            }
            Button("Change Pin", { step = "pin" }, gap, icon = L.drawable.lucide_ic_lock)
            Button(if (FilesLock.hasPassword()) "Change Auth Password" else "Set Auth Password", { step = "pass" }, gap, icon = L.drawable.lucide_ic_key_round)
            Button("Turn Off Lock", { step = "off" }, gap, icon = L.drawable.lucide_ic_lock_open, tint = Ink.accent)
        }
        "pin" -> {
            Note("Change Pin")
            Field(current, { current = it }, "Current pin or Auth Password", gap, secret = true)
            PinField(pin, { pin = it }, "New pin (four digits)")
            PinField(again, { again = it }, "The new pin again")
            CancelSave("Save") {
                when {
                    !knows() -> say("That isn't the current pin or Auth Password")
                    pin.length != 4 -> say("The pin is four digits")
                    again != pin -> say("The two pins don't match")
                    else -> { FilesLock.set(pin); reset(); say("Pin changed") }
                }
            }
        }
        "pass" -> {
            Note(if (FilesLock.hasPassword()) "Change Auth Password" else "Set Auth Password")
            Field(current, { current = it }, "Current pin", gap, secret = true, digits = true)
            Field(pass, { pass = it }, "New Auth Password", gap, secret = true)
            CancelSave("Save") {
                when {
                    !FilesLock.tryCode(current) -> say("That isn't the current pin")
                    pass.length < 4 -> say("Four characters at least")
                    else -> { FilesLock.setPassword(pass); reset(); say("Auth Password saved") }
                }
            }
        }
        "off" -> {
            Note("Turn Off Lock: saved downloads and Files will open for anyone.")
            Field(current, { current = it }, "Current pin or Auth Password", gap, secret = true)
            CancelSave("Turn Off") {
                if (!knows()) say("That isn't the current pin or Auth Password") else {
                    FilesLock.set(null)
                    FilesLock.setPassword(null)
                    on = false
                    reset()
                    say("Lock off")
                }
            }
        }
    }
}
