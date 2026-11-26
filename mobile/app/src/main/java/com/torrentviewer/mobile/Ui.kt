@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.keyframes
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.takeOrElse
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ink
import com.torrentviewer.core.accentGlow
import com.torrentviewer.core.glow
import com.torrentviewer.core.grain
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Motion
import com.torrentviewer.core.ImdbMark
import com.torrentviewer.core.ProgressLine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.composables.icons.lucide.R as L

// The TV's look (Ink, Type, artwork, title marks — all from :core) on touch: what the
// remote's focus ring did is a press that dips the control a little instead.

/** Whether the phone is held upright, where margins and padding are tighter. Kept by [App] (and MainActivity at start). */
object Room {
    var tight by mutableStateOf(false)
}

/** Side margin of every page: 6 dp on an upright phone, where width is scarce; 10 dp otherwise. */
val EDGE: Dp get() = if (Room.tight) 6.dp else 10.dp

/** Padding inside cards, panels and dialogs: 12 dp on an upright phone, 16 dp otherwise. */
val CARD: Dp get() = if (Room.tight) 12.dp else 16.dp

/** Landscape, or a tablet held either way: the TV's side-by-side layouts fit. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

/**
 * Anything tappable: dips while pressed, no ripple. [propagateMinConstraints] so a
 * caller's fillMaxWidth reaches the content and a bare one wraps it.
 */
@Composable
fun Tap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(5.dp),
    onLongClick: (() -> Unit)? = null,
    /** What a hold does, in words, for TalkBack ("Options"): read out with "double-tap and hold to…". */
    longClickLabel: String? = null,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    Box(
        modifier
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .combinedClickable(source, indication = null, onLongClickLabel = longClickLabel, onLongClick = onLongClick, onClick = onClick),
        propagateMinConstraints = true,
    ) { content(pressed) }
}

/** The near-white of a frosted button that isn't the main one, its words black: the same on either theme, since it sits on artwork. */
private val GLASS_LIGHT = Color(0xFFF1F1F3)

/** The TV's buttons, on touch: red for the main action, translucent grey for the rest. */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    /** A toggle (My List): switched on, the icon gets [Action]'s disc and pop. */
    on: Boolean? = null,
    onColor: Color = Ink.good,
    /** The word's colour, and the flat fill's tint: for a button on something other than the page. */
    tint: Color = Ink.text,
    /** Beside a line of text in a row, where the full height towers over it. */
    compact: Boolean = false,
    /** A solid fill with white words instead of [primary]'s red glow: the download buttons' green ([Ink.download]). */
    fill: Color? = null,
    /** The words in the very middle, the icon beside them: as much space after them as the icon takes before. */
    balanced: Boolean = false,
    /** Held instead of tapped: a second choice, such as "Send to 1DM" offering the phone's other apps. */
    onLongClick: (() -> Unit)? = null,
    /**
     * Frosted glass, for the buttons over artwork (the hero's Watch and My List): one flat, opaque colour under the
     * app's grain — red with white words when [primary], near-white with black words otherwise — no gradient glow, no rim.
     */
    glass: Boolean = false,
    /** A notch taller than the usual button, for a hero action like Play (the owner's choice). */
    tall: Boolean = false,
    /** Override the icon's size, for a glyph meant to read larger than the usual 18 dp (e.g. Resume's time-left play). */
    iconSize: Dp? = null,
    /** Barely there: a background that all but matches the page, for a quiet action like "Manage". */
    faint: Boolean = false,
    /** Override the label's size, for a word meant to read a touch larger than the usual. */
    textSize: androidx.compose.ui.unit.TextUnit? = null,
) {
    val filled = on == true
    val disc by animateFloatAsState(if (filled) 1f else 0f, if (filled) spring(dampingRatio = 0.55f, stiffness = 420f) else tween(180), label = "disc")
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on == null || on == seen) return@LaunchedEffect
        seen = on
        pop.animateTo(1f, keyframes { durationMillis = 360; 0.72f at 100; 1.15f at 230; 1f at 360 })
    }
    // The download buttons (filled with the download green): dark grey like the rest, the green kept on the icon alone
    // (the owner's choice).
    val greyDownload = fill != null && fill == Ink.download
    val fill = fill.takeUnless { greyDownload }
    // The same corners as the chips and inputs ([PILL]): 5 dp read too square beside them (the owner's choice).
    Tap(onClick, modifier, PILL, onLongClick = onLongClick) { pressed ->
        // [compact] only where the button is short: one stretched across a row, alone or as one of two, keeps the full
        // height (the owner's choice: wide, thin buttons look wrong). A stretched button's least width is its width.
        androidx.compose.foundation.layout.BoxWithConstraints(propagateMinConstraints = true) {
            val thin = compact && minWidth < 140.dp
            val bg by animateColorAsState(
                when {
                    fill != null -> if (pressed) fill.copy(alpha = 0.8f) else fill
                    primary -> if (pressed) Ink.accent.copy(alpha = 0.8f) else Ink.accent
                    // A tint of the text colour: grey on either page; [faint] all but blends it into the page.
                    else -> tint.copy(alpha = if (faint) (if (pressed) 0.08f else 0.025f) else (if (pressed) 0.18f else 0.09f))
                },
                Motion.effects(), label = "bg",
            )
            // The second glass button (My Listing): near-white with black words on the dark theme, near-black with white
            // words on the light one (the owner's choice).
            val glassFill = if (Ink.isDark) GLASS_LIGHT else Color(0xFF161616)
            val fg = if (glass && !primary) (if (Ink.isDark) Color.Black else Color.White) else if (primary && fill == null) Ink.onAccent else if (fill != null) Color.White else tint
            Row(
                // The primary one — Play, Watch — a flat grainy red, a shade deeper while pressed; the rest a flat tint.
                (when {
                    // Opaque, one flat colour under the app's grain, no rim: red for the main one, near-white for the other.
                    // A notch quieter than full strength (the owner's choice): a little black in every fill.
                    // The second (My Listing) stays at full strength: toned down, the near-white read as a faint grey.
                    glass -> Modifier.background(lerp(if (primary) Ink.accent else glassFill, Color.Black, if (pressed) 0.28f else if (primary) 0.12f else 0f)).grain()
                    // No button carries a gradient (the owner's choice): the main action is one flat red under the grain.
                    primary && fill == null -> Modifier.background(lerp(Ink.accent, Color.Black, if (pressed) 0.28f else 0.12f)).grain()
                    // The download buttons' green as frosted glass: a deep, opaque green under the grain (the owner's choice).
                    fill != null -> Modifier.background(lerp(fill, Color.Black, if (pressed) 0.56f else 0.46f)).grain()
                    else -> Modifier.background(bg)
                })
                    .height(if (thin) 34.dp else if (tall) 50.dp else 44.dp).padding(horizontal = if (thin) 14.dp else 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    // The hero's My Listing switched on: a smaller tick in the words' own colour — black on the dark theme's
                    // near-white button, white on the light theme's near-black one — not the green disc (the owner's choice).
                    val quietTick = glass && !primary && on != null
                    Box(Modifier.size(iconSize ?: 24.dp), contentAlignment = Alignment.Center) {
                        if (on != null) Box(
                            Modifier.size(if (quietTick) 18.dp else 24.dp).graphicsLayer {
                                scaleX = disc
                                scaleY = disc
                                alpha = disc.coerceIn(0f, 1f)
                            }.clip(CircleShape).background(if (quietTick) fg else onColor),
                        )
                        Lc(icon, Modifier.graphicsLayer {
                            scaleX = pop.value
                            scaleY = pop.value
                        }, tint = lerp(if (greyDownload) Ink.download else fg, if (quietTick) glassFill else Color.White, disc.coerceIn(0f, 1f)),
                            // The hero's plus a notch bigger (the owner's choice); its tick, once added, stays small.
                            size = iconSize ?: (if (quietTick) (if (on == true) 12.dp else 20.dp) else if (on != null) 16.dp else 18.dp))
                    }
                    // The hero's pair keep their icon close to the word.
                    Spacer(Modifier.width(if (glass) 2.dp else 8.dp))
                }
                Text(label, style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = fg, fontSize = textSize ?: Type.label.fontSize), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (balanced && icon != null) Spacer(Modifier.width(if (glass) 26.dp else 32.dp))
            }
        }
    }
}

/**
 * A pill's shape: a rounded rectangle, not a full half-circle at each end (the owner's choice: everything squared off a
 * notch). Chips, the update strip's button, the player's notes.
 */
val PILL = RoundedCornerShape(10.dp)

/** A pill of choices — seasons, qualities, genres, sorts. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, @DrawableRes trailing: Int? = null, compact: Boolean = false, trailingTint: Color? = null,
    /** A pill standing alone as a button ([Solo]: "Ask Every Source Again"), with room above and below its words. */
    tall: Boolean = false,
) {
    Tap(onClick, modifier, PILL) { pressed ->
        val bg = when {
            on -> Ink.accent
            pressed -> Ink.text.copy(alpha = 0.18f)
            else -> QUIET_FILL
        }
        val fg = if (on) Ink.onAccent else Ink.muted
        Row(
            // With an icon (Pause All…), a notch more room after the words and less between icon and words (the owner's choice).
            Modifier.background(bg).height(if (tall) 50.dp else if (compact) 32.dp else 36.dp).padding(
                start = if (tall) 20.dp else if (compact) 11.dp else 16.dp,
                end = (if (tall) 20.dp else if (compact) 11.dp else 16.dp) + if (icon != null) 3.dp else 0.dp,
            ),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 16.dp)
                Spacer(Modifier.width(if (compact) 3.dp else 6.dp))
            }
            Text(label, style = Type.label.copy(fontSize = if (compact) 13.sp else 14.sp, color = fg, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (trailing != null) {
                Spacer(Modifier.width(if (compact) 3.dp else 6.dp))
                Lc(trailing, tint = trailingTint ?: fg, size = if (compact) 14.dp else 16.dp)
            }
        }
    }
}

/** A pill naming the current choice; a tap drops the [options] under it. */
@Composable
fun Dropdown(label: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(label, false, { open = true }, trailing = L.drawable.lucide_ic_chevron_down, compact = true)
        // Capped: the genre list is long, and an uncapped menu ran off the bottom of the screen.
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 320.dp), containerColor = Ink.surface) {
            options.forEach { option ->
                val here = option == label
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(option, style = Type.label.copy(fontSize = 14.sp, color = if (here) Ink.accent else Ink.text, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Medium)) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** The app's one filled green: the downloads' green a long way toward black, under the grain (the owner's choice). */
val FROSTED_GREEN: Color
    get() = lerp(Ink.download, Color.Black, 0.46f)

/** Netflix's phone actions under the Play button: an icon over a word. */
@Composable
fun Action(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    tint: Color = Ink.text,
    modifier: Modifier = Modifier.width(84.dp),
    /** A toggle (My List, Like): switched on it fills with [onColor] — see below. */
    on: Boolean? = null,
    // The app's frosted dark green (the owner's choice): the download buttons' deep green, not a bright one.
    onColor: Color = FROSTED_GREEN,
) {
    // Switched on: a disc of onColor springs in behind the icon, which dips and pops back
    // white. Switched off, the disc shrinks away. Every value starts where the last frame
    // left it — nothing snaps, so the first frame after a tap matches the one before.
    val filled = on == true
    val disc by animateFloatAsState(if (filled) 1f else 0f, if (filled) spring(dampingRatio = 0.55f, stiffness = 420f) else tween(180), label = "disc")
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        // A title loading in sets the state too; only the viewer's own tap pops.
        if (on == null || on == seen) return@LaunchedEffect
        seen = on
        pop.animateTo(1f, keyframes { durationMillis = 360; 0.72f at 100; 1.15f at 230; 1f at 360 })
    }
    Tap(onClick, modifier) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(36.dp).graphicsLayer {
                        scaleX = disc
                        scaleY = disc
                        alpha = disc.coerceIn(0f, 1f)
                    }.clip(CircleShape).background(onColor),
                )
                Lc(icon, Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }, tint = lerp(tint, Color.White, disc.coerceIn(0f, 1f)), size = 22.dp)
            }
            Text(label, Modifier.padding(top = 4.dp), style = Type.meta.copy(fontSize = 12.sp, color = if (filled) (if (onColor == FROSTED_GREEN) Ink.download else onColor) else Ink.muted, fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
        }
    }
}

/** A round icon button floating over artwork — back, close. */
@Composable
fun RoundIcon(@DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Tap(onClick, modifier.size(size), CircleShape) {
        Box(Modifier.background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Lc(icon, tint = Color.White, size = size * 0.5f) }
    }
}

/** Back, top-left under the status bar. */
@Composable
fun BoxScope.BackButton(onBack: () -> Unit) {
    RoundIcon(L.drawable.lucide_ic_arrow_left, onBack, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 12.dp, top = 18.dp))
}

/** A text box; the ring shows where the keyboard is typing. */
@Composable
fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    digits: Boolean = false,
    onSubmit: () -> Unit = {},
    leading: Int? = null,
    trailing: @Composable () -> Unit = {},
    /** To put the keyboard on it as it opens (a name to type in a sheet). */
    focus: androidx.compose.ui.focus.FocusRequester? = null,
    /** No box behind it, and the masked digits spread wider: the lock's pad on the page itself. */
    bare: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    // Very faint (the owner's choice): enough to show where the typing goes, not a frame.
    val ring by animateColorAsState(if (focused) Ink.text.copy(alpha = 0.16f) else Color.Transparent, Motion.effects(), label = "ring")
    Row(
        (if (bare) modifier.height(50.dp) else modifier.height(50.dp).clip(PILL).background(if (focused) Ink.text.copy(alpha = 0.12f) else QUIET_FILL).border(1.dp, ring, PILL)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Lc(it, tint = if (focused) Ink.text else Ink.faint, size = 20.dp)
            Spacer(Modifier.width(12.dp))
        }
        // A PIN sits in the middle of its box, placeholder and dots alike.
        val align = if (digits) TextAlign.Center else TextAlign.Start
        Box(Modifier.weight(1f), contentAlignment = if (digits) Alignment.Center else Alignment.CenterStart) {
            // An asterisk sits up at the letters' top, so "* * * *" is nudged down to the box's middle (the owner caught it).
            val stars = placeholder.isNotBlank() && placeholder.all { it == '*' || it == ' ' }
            if (value.isEmpty()) Text(
                placeholder, if (stars) Modifier.offset(y = if (bare) 9.dp else 4.dp) else Modifier,
                style = Type.label.copy(color = Ink.faint, fontSize = if (bare && stars) 36.sp else if (stars) 18.sp else 16.sp, letterSpacing = if (bare && stars) 14.sp else 0.sp, textAlign = align), maxLines = 1,
            )
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = Type.label.copy(fontSize = if (bare) 34.sp else 16.sp, letterSpacing = if (secret) (if (bare) 24.sp else 8.sp) else 0.sp, textAlign = align),
                // Green and blinking, as the other boxes (the owner's choice).
                cursorBrush = SolidColor(Ink.download),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Text, imeAction = if (digits) ImeAction.Done else ImeAction.Search),
                keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                // The lock's bare field wraps its content so its Box centres it — an empty centred field otherwise parks
                // the caret at the left on some builds, and the green cursor must sit in the middle of the stars.
                modifier = (if (bare) Modifier else Modifier.fillMaxWidth()).then(if (focus != null) Modifier.focusRequester(focus) else Modifier).onFocusChanged { focused = it.isFocused },
            )
        }
        trailing()
    }
}

/**
 * [image]: a picture to lead the row with — a site's logo — in place of the tick column. [tag]: a badge before the label, "4K".
 * [icon]: an icon there instead, in [iconTint] (the label's colour when null) — a trash can, a download arrow.
 */
data class Option<T>(
    val value: T, val label: String, val hint: String? = null, val image: String? = null, val tag: String? = null,
    @androidx.annotation.DrawableRes val icon: Int? = null, val iconTint: Color? = null,
)

/** A choice, up from the bottom edge over a dimmed page; a tap outside or Back closes it. */
@Composable
fun <T> Sheet(
    title: String,
    options: List<Option<T>>,
    selected: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    empty: String? = null,
    busy: Boolean = false,
) {
    BottomSheet(title, onDismiss, tight = true) {
        when {
            busy && options.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { Spinner(size = 40.dp) }
            options.isEmpty() -> EmptyNote(empty ?: "Nothing to choose from.", Modifier.padding(horizontal = 11.dp, vertical = 12.dp))
            else -> LazyColumn {
                itemsIndexed(options) { _, option ->
                    val on = option.value == selected
                    Tap({ onPick(option.value) }, Modifier.fillMaxWidth(), RoundedCornerShape(0.dp)) { pressed ->
                        Row(
                            Modifier.background(if (pressed) Ink.text.copy(alpha = 0.08f) else Color.Transparent).padding(horizontal = 11.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (option.image != null) {
                                coil3.compose.AsyncImage(option.image, option.label, Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)))
                            } else if (option.icon != null) {
                                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Lc(option.icon, tint = option.iconTint ?: Ink.muted, size = 20.dp) }
                            } else {
                                // The chosen row: a small accent dot, not a tick (the owner's choice).
                                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(Ink.accent)) }
                            }
                            Spacer(Modifier.width(12.dp))
                            option.tag?.let {
                                Text(
                                    it,
                                    Modifier.background(Ink.text.copy(alpha = 0.1f), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = Type.overline.copy(color = Ink.text, letterSpacing = 0.5.sp),
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(option.label, style = Type.label.copy(color = if (on) Ink.text else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                option.hint?.let { Text(it, style = Type.meta.copy(color = Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                if (busy) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(size = 28.dp) } }
            }
        }
    }
}

/**
 * One tappable action row inside a sheet — an icon, a label and an optional line under it — in the same look as a
 * [Sheet] option (the owner's choice: sheets list their actions as rows, not buttons). [tint] colours the icon and
 * label (a green download, a red delete); [trailing] puts something at the end (a chevron, a count).
 */
@Composable
fun SheetAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.DrawableRes icon: Int? = null,
    hint: String? = null,
    tint: Color? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Tap(onClick, modifier.fillMaxWidth(), RoundedCornerShape(0.dp)) { pressed ->
        Row(
            Modifier.background(if (pressed) Ink.text.copy(alpha = 0.08f) else Color.Transparent).padding(horizontal = 20.dp, vertical = 13.dp).then(if (enabled) Modifier else Modifier.graphicsLayer { alpha = 0.4f }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { if (icon != null) Lc(icon, tint = tint ?: Ink.muted, size = 20.dp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.label.copy(color = tint ?: Ink.text, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                hint?.let { Text(it, style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            trailing?.let { Spacer(Modifier.width(10.dp)); it() }
        }
    }
}

/**
 * A sheet/settings row whose action is on/off: an icon, a label (and an optional line under it) on the left, a [Toggle]
 * on the right, the whole row tappable (the owner's choice: an on/off choice is a toggle, not a button). Same look as
 * [SheetAction].
 */
@Composable
fun SheetToggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @androidx.annotation.DrawableRes icon: Int? = null,
    hint: String? = null,
) {
    Row(
        modifier.fillMaxWidth().toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { if (icon != null) Lc(icon, tint = Ink.muted, size = 20.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.label.copy(color = Ink.text, fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, style = Type.meta.copy(color = Ink.faint), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(10.dp))
        Toggle(checked, onChange)
    }
}

/** The frame every sheet shares: scrim, rounded top, a grab handle and a heading. */
@Composable
fun BottomSheet(
    title: String, onDismiss: () -> Unit, trailing: (@Composable () -> Unit)? = null,
    /** An icon (or anything) heading the sheet in place of the title's words, when [title] is empty. */
    leading: (@Composable () -> Unit)? = null,
    /** A tighter side gutter for the title, to match a list that uses one (the [Sheet] of options). */
    tight: Boolean = false,
    content: @Composable () -> Unit,
) {
    BackHandler(LocalShown.current, onDismiss)
    androidx.compose.runtime.DisposableEffect(Unit) {
        OpenSheets.count++
        onDispose { OpenSheets.count-- }
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp

    // Pulled down — by the handle, or by a list already at its top — it follows the finger,
    // and let go far enough down it closes; short of that it springs back.
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    val closeAt = with(LocalDensity.current) { 110.dp.toPx() }
    fun settle() {
        if (offset > closeAt) onDismiss() else scope.launch { animate(offset, 0f) { v, _ -> offset = v } }
    }
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0 || offset <= 0f) return Offset.Zero
                val used = maxOf(available.y, -offset)
                offset += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                offset += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0f) return Velocity.Zero
                settle()
                return available
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically(Motion.spatial()) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .graphicsLayer { translationY = offset }
                    .nestedScroll(pull)
                    // Frosted, as the tab bar: its colour with the app's grain (the owner's choice).
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(SHEET_FACE).grain()
                    // Taps on the sheet stay on the sheet.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().padding(bottom = 10.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().draggable(
                        rememberDraggableState { d -> offset = (offset + d).coerceAtLeast(0f) },
                        Orientation.Vertical,
                        onDragStopped = { settle() },
                    ),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(Ink.stroke))
                    // The title, and beside it at the far right whatever the sheet puts there (a Reload…).
                    // No title (a menu that heads itself, as Tube's): just room under the handle.
                    if (title.isEmpty() && trailing == null && leading == null) Spacer(Modifier.height(14.dp))
                    else Row(Modifier.fillMaxWidth().padding(start = if (tight) 11.dp else 20.dp, end = if (tight) 11.dp else 14.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        leading?.invoke()
                        // One line, never wrapped: a long title scrolls slowly across (the owner's choice). Alone in its row on
                        // an upright phone, in the middle. Beside a Reload, from the start, cut short with "…" and a clear gap
                        // before it (the owner's choice: scrolling, it ran right up to the button).
                        val alone = trailing == null && leading == null && isPortraitPhone()
                        Text(
                            title, Modifier.weight(1f).then(if (trailing != null) Modifier.padding(end = 16.dp) else Modifier.basicMarquee()),
                            style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1,
                            overflow = if (trailing != null) TextOverflow.Ellipsis else TextOverflow.Clip,
                            textAlign = if (alone) TextAlign.Center else TextAlign.Start,
                        )
                        trailing?.invoke()
                    }
                }
                content()
            }
        }
    }
}

/**
 * Fades artwork into the page at the bottom, and darkens the top for the status bar.
 * The art itself fades out, eased, rather than a flat page colour being painted over it:
 * that colour has none of the page's grain, and showed as a band where the hero ended.
 */
fun Modifier.bottomScrim(from: Float = 0.45f): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val top = size.height * 0.28f
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.5f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent, endY = top), size = Size(size.width, top))
    val start = size.height * from
    drawRect(
        Brush.verticalGradient(
            0f to Color.Black, 0.3f to Color.Black.copy(alpha = 0.85f), 0.55f to Color.Black.copy(alpha = 0.5f),
            0.8f to Color.Black.copy(alpha = 0.15f), 1f to Color.Transparent,
            startY = start, endY = size.height,
        ),
        topLeft = Offset(0f, start),
        size = Size(size.width, size.height - start),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * Status bar icons light while dark artwork sits under them — the light page's dark icons
 * were lost on the hero, and some phones washed a pale scrim over it for contrast — and the
 * page's own again once the art has scrolled away or the screen goes.
 */
@Composable
fun StatusIconsOverArt(overArt: Boolean, navigationToo: Boolean = false) {
    val view = LocalView.current
    val dark = Ink.isDark
    LaunchedEffect(overArt, dark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced = false
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightStatusBars = !dark && !overArt
        if (navigationToo) bars.isAppearanceLightNavigationBars = !dark && !overArt
    }
    DisposableEffect(view) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !Ink.isDark
            if (navigationToo) bars.isAppearanceLightNavigationBars = !Ink.isDark
        }
    }
}

private val POSTER_SHAPE = RoundedCornerShape(6.dp)

/**
 * A poster with its name and a line of facts under it, as the streaming apps set them:
 * a hairline so a pale poster keeps its edge on the light page, a [badge] in the corner,
 * and how far it was watched along the foot.
 */
@Composable
fun TitleCard(
    art: String?, name: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    info: String? = null, badge: String? = null, progress: Float? = null, onLongClick: (() -> Unit)? = null, rating: String? = null,
) {
    Column(modifier) {
        Tap(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), POSTER_SHAPE, onLongClick) {
            // No outline round the art (the owner's choice): the poster's own edge is the card's.
            Artwork(art, name, Modifier.fillMaxSize())
            rating?.takeIf { it.isNotBlank() }?.let { ImdbMark(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
            badge?.let {
                Text(
                    it, Modifier.align(Alignment.TopStart).padding(6.dp).wrapContentSize(Alignment.TopStart).background(Ink.accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = Type.meta.copy(fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1,
                )
            }
            progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomStart)) }
        }
        Text(name, Modifier.padding(top = 7.dp), style = Type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Always a line, so cards with no facts keep the row's height.
        Text(info.orEmpty(), Modifier.padding(top = 1.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Posters, for search results, lists and whole shelves. */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null, onLongClick: (() -> Unit)? = null) =
    TitleCard(title.poster ?: title.wide, title.title, onClick, modifier, info = sub, onLongClick = onLongClick, rating = title.rating)

/** Cards still on their way: the same shape, gently pulsing. */
@Composable
fun CardPlaceholder(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(modifier.graphicsLayer { alpha = pulse }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Ink.surface, POSTER_SHAPE))
        Box(Modifier.padding(top = 9.dp).fillMaxWidth(0.8f).height(11.dp).background(Ink.surface, RoundedCornerShape(3.dp)))
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(9.dp).background(Ink.surface, RoundedCornerShape(3.dp)))
    }
}

/** Continue watching's art: the time left and how far over a fade at its foot, no outline (as [TitleCard]); the name sits under the card. */
@Composable
fun LandscapeCard(art: String?, name: String, stamp: String? = null, progress: Float? = null) {
    Box(Modifier.fillMaxSize()) {
        Artwork(art, name, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.6f).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = if (progress != null) 6.dp else 7.dp)) {
            // Over the artwork's dark fade: white on either page.
            stamp?.let { Text(it, style = Type.meta.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1) }
            if (progress != null) {
                Spacer(Modifier.height(5.dp))
                ProgressLine(progress)
            }
        }
    }
}

/** A page heading under the status bar. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, centred: Boolean = false, trailing: @Composable () -> Unit = {}) {
    // Clear of the screen's top and sides, as Home's wordmark is: a little down and in.
    Row(modifier.statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 24.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        // [centred]: a page with a back button, on an upright phone, has its title in the middle (the owner's choice).
        if (centred) {
            // One line on an upright phone (the owner's choice): a long title steps its size down until it fits.
            var size by remember(text) { mutableStateOf(Type.display.fontSize) }
            Text(
                text, Modifier.weight(1f), style = Type.display.copy(fontSize = size, lineHeight = size * 1.15f), textAlign = TextAlign.Center,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (it.hasVisualOverflow && size.value > 16f) size = (size.value * 0.92f).sp },
            )
        } else Text(text, Modifier.weight(1f), style = Type.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

/**
 * Poster grids: always three across a phone held upright, however large its display size is set; turned
 * sideways (or on a tablet), as many as fit at about 120 dp each, never fewer than four.
 */
val PosterGrid = object : androidx.compose.foundation.lazy.grid.GridCells {
    override fun androidx.compose.ui.unit.Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = if (availableSize < 600.dp.roundToPx()) 3
        else maxOf(4, (availableSize + spacing) / (120.dp.roundToPx() + spacing))
        val cells = availableSize - spacing * (count - 1)
        return List(count) { cells / count + if (it < cells % count) 1 else 0 }
    }
}

/**
 * An on/off setting: a flat pill with a round thumb and no outline — red with a dark thumb on the right
 * when on, a quiet grey with a grey thumb on the left when off. A little smaller than Material's switch.
 */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    // Smaller again, as the Immersive app's (the owner's choice): 34 × 20, a 14 dp thumb.
    val travel by androidx.compose.animation.core.animateDpAsState(if (checked) 14.dp else 0.dp, tween(160), label = "toggle")
    // A white thumb, on and off, in both themes (the owner's choice); off, the track a touch darker so it still shows.
    val track by animateColorAsState(if (checked) Ink.accent else Ink.text.copy(alpha = if (Ink.isDark) 0.18f else 0.24f), tween(160), label = "track")
    val thumb by animateColorAsState(Color.White, tween(160), label = "thumb")
    Box(
        modifier.size(width = 34.dp, height = 20.dp).clip(RoundedCornerShape(50)).background(track)
            .toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(3.dp),
    ) {
        Box(Modifier.offset(x = travel).size(14.dp).clip(CircleShape).background(thumb))
    }
}

/**
 * Space-between with a floor: items spread across the row, first and last at its ends, until there are
 * too many to fit — then [min] apart, and the row (scrolling, or lazy) runs past the screen.
 */
fun spreadOut(min: Dp = 8.dp): Arrangement.Horizontal = object : Arrangement.Horizontal {
    override val spacing = min

    override fun androidx.compose.ui.unit.Density.arrange(totalSize: Int, sizes: IntArray, layoutDirection: androidx.compose.ui.unit.LayoutDirection, outPositions: IntArray) {
        val floor = spacing.roundToPx()
        // Two (or one) aren't spread: they sit from the start, [spacing] apart. Three or more share the width.
        val gap = if (sizes.size > 2) maxOf(floor, (totalSize - sizes.sum()) / (sizes.size - 1)) else floor
        var x = 0
        sizes.forEachIndexed { i, w ->
            outPositions[i] = x
            x += w + gap
        }
    }
}

/** The pill rows' arrangement: [spreadOut] with an 8 dp floor. */
val SpreadOut: Arrangement.Horizontal = spreadOut()

/** A phone held upright: where rows of buttons spread end to end and a lone one is centred. Not sideways, not a tablet. */
@Composable
fun isPortraitPhone(): Boolean {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return c.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT && c.smallestScreenWidthDp < 600
}

/** [spreadOut] on an upright phone; elsewhere plain [min] gaps from the start. For lazy rows. */
@Composable
fun spreadArrangement(min: Dp = 8.dp): Arrangement.Horizontal = if (isPortraitPhone()) spreadOut(min) else Arrangement.spacedBy(min)

/**
 * A row of buttons or pills: on an upright phone spread end to end, gaps closing to [min] and then
 * scrolling sideways; elsewhere [min] apart from the start, scrolling if they don't fit.
 */
@Composable
fun SpreadRow(modifier: Modifier = Modifier, min: Dp = 8.dp, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    if (isPortraitPhone()) {
        androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
            val room = maxWidth
            Row(
                Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).widthIn(min = room),
                horizontalArrangement = spreadOut(min), verticalAlignment = Alignment.CenterVertically, content = content,
            )
        }
    } else {
        Row(
            modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(min), verticalAlignment = Alignment.CenterVertically, content = content,
        )
    }
}

/**
 * A button on its own: on an upright phone centred and at least 70% of the screen wide (the button takes
 * the modifier it's handed); elsewhere where it stands.
 */
@Composable
fun Solo(modifier: Modifier = Modifier, button: @Composable (Modifier) -> Unit) {
    if (isPortraitPhone()) {
        val min = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.7f).dp
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { button(Modifier.widthIn(min = min)) }
    } else {
        Box(modifier) { button(Modifier) }
    }
}

/** The reload mark: one circular arrow; while [busy] (links still coming in), the app's green spinner in its place. */
@Composable
fun ReloadArrow(busy: Boolean, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    if (busy) Spinner(modifier, size = size)
    else Lc(com.composables.icons.lucide.R.drawable.lucide_ic_rotate_cw, modifier, tint = tint, size = size)
}

/**
 * A card's face, frosted as Grabber's are (the owner's choice, for every card of a list — downloads, addons, settings,
 * plugins): the surface with the app's grain over it. [shape] is kept for the caller's clip.
 */
fun Modifier.frosted(shape: androidx.compose.ui.graphics.Shape, color: Color = SECTION_FACE): Modifier =
    // Bare: no rim round it (the owner's choice). Clipped to its shape, so the grain keeps the rounded corners too.
    clip(shape).background(color).grain()

/**
 * Choices as words in a row, Grabber's way: no pills, a short coloured bar between each, the chosen one in the accent
 * colour with a dot before it. In plain flow from the start, a little apart — not spread across the width (the owner's choice) — scrolling
 * sideways when there are more than fit. [leading] goes first, as it is (a reload button). [spread] lays one row end to end
 * instead, as a [SpreadRow] does (the owner's choice for the season sheet's resolutions only).
 */
@Composable
fun BarChoices(labels: List<String>, chosen: Int?, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, spread: Boolean = false, onPick: (Int) -> Unit) {
    val bars = listOf(Ink.accent, Ink.download, Ink.muted, Ink.good)
    // In plain flow from the start, a little apart, never spread across the width (the owner's choice, everywhere these
    // rows are); a row longer than the screen scrolls sideways rather than being cut off.
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        leading?.invoke()
        labels.forEachIndexed { i, label ->
            if (i > 0) Box(Modifier.size(width = 2.dp, height = 18.dp).clip(RoundedCornerShape(1.dp)).background(bars[(i - 1) % bars.size].copy(alpha = 0.8f)))
            val on = i == chosen
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).clickable { onPick(i) }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) Box(Modifier.padding(end = 6.dp).size(6.dp).clip(CircleShape).background(Ink.accent))
                Text(label, style = Type.label.copy(color = if (on) Ink.accent else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium))
            }
        }
    }
    if (spread) SpreadRow(modifier, content = content) else Row(
        modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/** A list card's colour (downloads, sources): nearly the page's own, a breath lighter, grain over it (the owner's choice). */
val CARD_FACE: Color
    // The settings sections' own colour (the owner's choice): one card colour across the app.
    get() = SECTION_FACE

/** Settings' sections, addon and plugin cards: the surface toned down a notch toward the page (the owner's choice). */
/**
 * A quiet fill for pills and fields: in the dark theme a shade (white, faint), in the light theme a tint (white,
 * stronger) — lighter than the paper page rather than greyer, as the owner asked.
 */
val QUIET_FILL: Color
    get() = if (Ink.isDark) Ink.text.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.72f)

/** Sheets' face: the tab bar's colour — a shade lighter than the page in the dark theme, a near-white tint in the light. */
val SHEET_FACE: Color
    // A shade apart from the tab bar's (the owner's choice: the two must read apart), still frosted.
    get() = if (Ink.isDark) Ink.surface else androidx.compose.ui.graphics.lerp(Ink.bg, Color.White, 0.9f)

/** How many sheets are up: the floating tab bar steps aside while any is, so it never sits on one. */
object OpenSheets {
    var count by androidx.compose.runtime.mutableIntStateOf(0)
}

val SECTION_FACE: Color
    get() = androidx.compose.ui.graphics.lerp(Ink.bg, Ink.surface, 0.75f)

/**
 * How the big pictures at the top of Home and of a title's page end (Settings → Appearance → Header edge): fading
 * into the page, or a clean straight line. App-wide; a clean line unless the viewer picks the fade.
 */
/**
 * The bottom nav's chosen tab: its capsule's colour (Settings → Appearance). Frosted grey by default (the owner's
 * choice), the accent, or a faint tint; each drawn from the palette, so it suits light and dark alike.
 */
object NavActive {
    private const val KEY = "navActiveStyle"
    val CHOICES = listOf("Frosted Grey", "Subtle", "Accent")
    var style by androidx.compose.runtime.mutableStateOf(com.torrentviewer.core.Kv.global(KEY)?.takeIf { it in CHOICES } ?: "Frosted Grey")
        private set

    fun choose(s: String) {
        style = s
        com.torrentviewer.core.Kv.setGlobal(KEY, s)
    }

    /** The capsule's colour. */
    val fill: Color
        get() = when (style) {
            "Accent" -> Ink.accent
            // Dark: toned down with the near-black bar, a soft lift rather than a bright patch.
            "Subtle" -> Ink.text.copy(alpha = if (Ink.isDark) 0.05f else 0.07f)
            else -> Ink.text.copy(alpha = if (Ink.isDark) 0.09f else 0.11f)
        }

    private const val ICON_KEY = "navActiveIcon"
    val ICONS = listOf("Accent", "White")

    /** The chosen tab's icon strokes: the accent (default) or plain white (black in the light theme). */
    var icon by androidx.compose.runtime.mutableStateOf(com.torrentviewer.core.Kv.global(ICON_KEY)?.takeIf { it in ICONS } ?: "Accent")
        private set

    fun chooseIcon(s: String) {
        icon = s
        com.torrentviewer.core.Kv.setGlobal(ICON_KEY, s)
    }

    /** The icon on it: on the accent's own capsule always white; otherwise as chosen in [icon]. */
    val tint: Color get() = when {
        style == "Accent" -> Ink.onAccent
        icon == "Accent" -> Ink.accent
        else -> Ink.text
    }
}

/**
 * The floating tab bar's surface (Settings → Appearance): "Frosted" (the app's grained colour, as it was) or "Glass"
 * — what scrolls behind it blurred live and tinted, as Nuvio's bar. The bar's shape stays the same either way.
 */
object NavGlass {
    private const val KEY = "navGlass"
    val CHOICES = listOf("Frosted", "Glass")
    var style by androidx.compose.runtime.mutableStateOf(com.torrentviewer.core.Kv.global(KEY)?.takeIf { it in CHOICES } ?: "Frosted")
        private set

    fun choose(s: String) {
        style = s
        com.torrentviewer.core.Kv.setGlobal(KEY, s)
    }

    val glass get() = style == "Glass"
}

object HeaderEdge {
    private const val KEY = "headerEdgeFade"
    var fade by androidx.compose.runtime.mutableStateOf(com.torrentviewer.core.Kv.global(KEY) == "1")
        private set

    fun choose(fade: Boolean) {
        this.fade = fade
        com.torrentviewer.core.Kv.setGlobal(KEY, if (fade) "1" else null)
    }
}


/**
 * A bare text input for a bar that is all one container (Tube's and the Browser's headers): no box of its own; where
 * it starts is shown by a blinking green cursor, before the placeholder until it's tapped, then the field's own.
 */
@Composable
fun BareInput(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Search,
    onFocus: (Boolean) -> Unit = {},
    onSubmit: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val blink = androidx.compose.animation.core.rememberInfiniteTransition(label = "cursor")
    val on by blink.animateFloat(
        1f, 0f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(530, easing = { if (it < 0.5f) 0f else 1f }), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            // A taller cursor (the owner's choice, as Grabber's), so it reads clearly in the address bar.
            if (!focused) Box(Modifier.width(2.dp).height(26.dp).graphicsLayer { alpha = on }.background(Ink.download))
            Text(placeholder, Modifier.padding(start = if (focused) 2.dp else 6.dp), style = Type.label.copy(color = Ink.faint, fontSize = 16.sp), maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            // The live cursor follows the line height: a taller line makes a taller cursor (the owner's choice), centred.
            textStyle = Type.label.copy(fontSize = 16.sp, lineHeight = 28.sp, lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None)),
            cursorBrush = SolidColor(Ink.download),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onSubmit() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) },
        )
    }
}

/**
 * A switch with its word before it ("Installed", "Subscribed"): the owner's choice over Remove / Uninstall / Subscribe
 * buttons — on is having it, off takes it away.
 */
@Composable
fun LabeledToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(15.dp)).toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 10.dp), style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = if (checked) Ink.text else Ink.muted))
        Toggle(checked, onChange)
    }
}


/**
 * A page's "nothing here" line: centred on an upright phone (the owner's choice), as [Note] elsewhere.
 */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    val centred = isPortraitPhone()
    Text(
        text, (if (centred) modifier.fillMaxWidth() else modifier),
        style = Type.body.copy(color = Ink.faint), textAlign = if (centred) TextAlign.Center else TextAlign.Start,
    )
}

/**
 * A spinner and what is being waited on, on one line: in the middle on an upright phone (the owner's rule for such
 * lines), from the start elsewhere. The season sheet's "Checking episodes", the packs' "Asking your addons…".
 */
@Composable
fun LoadingLine(text: String, modifier: Modifier = Modifier, spinner: Dp = 20.dp, style: androidx.compose.ui.text.TextStyle = Type.meta.copy(color = Ink.muted)) {
    Row(
        modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isPortraitPhone()) Arrangement.Center else Arrangement.Start,
    ) {
        com.torrentviewer.core.Spinner(size = spinner)
        Text(text, Modifier.padding(start = 10.dp), style = style)
    }
}

/**
 * A page's top with a back button: the button on the left, the [title] (and a [subtitle] under it) beside it — in the
 * middle of the screen on an upright phone (the owner's rule for every titled page), from the left elsewhere. Space
 * the button's width is kept on the right so "the middle" is the screen's, unless [trailing] fills it.
 */
@Composable
fun BackTitle(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    val centred = isPortraitPhone()
    val align = if (centred) TextAlign.Center else TextAlign.Start
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
            Text(title, style = Type.title.copy(fontWeight = FontWeight.Bold), textAlign = align, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = Type.meta.copy(color = Ink.faint), textAlign = align, maxLines = 2) }
        }
        if (trailing != null) trailing() else if (centred) Spacer(Modifier.width(40.dp))
    }
}


/**
 * Room under the last thing on a page: enough for it to scroll up clear of the floating tab bar (two rows and their
 * margin, above the system's own bar), whether or not the bar is showing at the time (the owner's choice).
 */
val NAV_CLEARANCE = 180.dp

/**
 * Text whose "→" — a way through the app, "Settings → Leave out" — is drawn as Lucide's chevron at the words' size and
 * colour, in place of the font's arrow (the owner's choice: the arrow looked gimmicky). Plain [Text] otherwise.
 */
@Composable
fun PathText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = Type.body,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if ('→' !in text) return Text(text, modifier, style = style, textAlign = textAlign, maxLines = maxLines, overflow = overflow)
    val parts = text.split('→')
    val built = androidx.compose.ui.text.buildAnnotatedString {
        parts.forEachIndexed { i, part ->
            val piece = (if (i > 0) part.trimStart() else part).let { if (i < parts.lastIndex) it.trimEnd() else it }
            if (i > 0) appendInlineContent(CHEVRON, "›")
            append(piece)
        }
    }
    val size = style.fontSize.takeIf { it.isSp } ?: 14.sp
    val tint = style.color.takeOrElse { Ink.text }
    val inline = mapOf(
        CHEVRON to androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(size * 1.3f, size, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(L.drawable.lucide_ic_chevron_right), null,
                Modifier.fillMaxSize().padding(horizontal = 1.dp), tint = tint,
            )
        },
    )
    Text(built, modifier, style = style, textAlign = textAlign, maxLines = maxLines, overflow = overflow, inlineContent = inline)
}

private const val CHEVRON = "chevron"
