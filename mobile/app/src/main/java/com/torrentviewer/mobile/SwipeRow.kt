package com.torrentviewer.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Type
import kotlinx.coroutines.launch

/**
 * A row you can swipe, as the Grabber app does it (same owner). Swipe RIGHT past a third of the width and it runs
 * [onRight] (pause a running download, open a finished one, resume a paused one) over a coloured, iconed backing that
 * fades in with the drag. Swipe LEFT and, where [leftReveal] is given, the row hangs open by [revealWidth] showing the
 * choices behind its right end (Bin / Delete) — nothing runs until a choice is tapped; where it isn't, a left swipe past
 * a third runs [onRemove] (take a not-yet-saved download off the list, with Undo). Tapping an open row, or swiping it
 * back, closes it.
 */
@Composable
fun SwipeRow(
    modifier: Modifier = Modifier,
    onRight: (() -> Unit)? = null,
    rightIcon: Int? = null,
    rightTint: Color = Ink.good,
    onRemove: (() -> Unit)? = null,
    revealWidth: Dp = 196.dp,
    /** The card's shape and the gap below it, so the backing and the revealed sheet match the row's card exactly. */
    backing: Shape = RoundedCornerShape(14.dp),
    inset: Dp = 10.dp,
    leftReveal: (@Composable (close: () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val revealPx = with(LocalDensity.current) { revealWidth.toPx() }
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val back = remember { spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow) }
    val isOpen by remember { derivedStateOf { offset.value <= -revealPx + 1f } }
    fun close() { scope.launch { offset.animateTo(0f, back) } }

    // Computed fresh in the drag from the live width, so the first drag before layout settled still clamps right.
    fun leftMin() = if (leftReveal != null) -revealPx else if (onRemove != null) -widthPx else 0f
    fun rightMax() = if (onRight != null) widthPx else 0f

    Box(modifier.fillMaxWidth().onSizeChanged { if (it.width > 0) widthPx = it.width.toFloat() }) {
        // The right action's backing — the card's own size and shape (the owner's choice), brightening as it's drawn right.
        if (onRight != null && rightIcon != null) {
            val a = (offset.value / (widthPx / 3f)).coerceIn(0f, 1f)
            Box(Modifier.matchParentSize().padding(bottom = inset).clip(backing).background(rightTint.copy(alpha = 0.16f + 0.24f * a)), contentAlignment = Alignment.CenterStart) {
                Lc(rightIcon, Modifier.padding(start = 24.dp), tint = rightTint, size = 24.dp)
            }
        }
        // The left choices as a card-shaped sheet behind the row's right end (the owner's choice), fading in with the drag.
        if (leftReveal != null) {
            val a = (-offset.value / revealPx).coerceIn(0f, 1f)
            Box(Modifier.matchParentSize().padding(bottom = inset), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.width(revealWidth).fillMaxHeight().clip(backing).background(Ink.surface2).graphicsLayer { alpha = a }) { leftReveal(::close) }
            }
        }
        // The row itself, moved by the drag; while it's open a tap on it just closes it.
        Box(
            Modifier.graphicsLayer { translationX = offset.value }
                .pointerInput(leftReveal != null, onRight != null, onRemove != null) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(leftMin(), rightMax())) }
                        },
                        onDragEnd = {
                            val x = offset.value
                            when {
                                onRight != null && x >= widthPx / 3f -> { onRight(); close() }
                                leftReveal != null && x <= -revealPx / 2f -> scope.launch { offset.animateTo(-revealPx, back) }
                                onRemove != null && leftReveal == null && x <= -widthPx / 3f -> { onRemove(); scope.launch { offset.snapTo(0f) } }
                                else -> close()
                            }
                        },
                        onDragCancel = { close() },
                    )
                },
        ) {
            content()
            if (isOpen) Box(Modifier.matchParentSize().clickable(remember { MutableInteractionSource() }, indication = null) { close() })
        }
    }
}

/** A bare action inside a [SwipeRow]'s revealed sheet: an icon over a small label, no background (the owner's choice). */
@Composable
fun SwipeChoice(label: String, icon: Int, modifier: Modifier = Modifier, tint: Color = Ink.text, onClick: () -> Unit) {
    Column(
        modifier.fillMaxHeight().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Lc(icon, tint = tint, size = 22.dp)
        Text(label, Modifier.padding(top = 5.dp), style = Type.meta.copy(color = tint, fontWeight = FontWeight.Medium))
    }
}

/** The thin pipe between two [SwipeChoice]s on a revealed sheet. */
@Composable
fun SwipePipe() {
    Box(Modifier.width(1.5.dp).height(26.dp).clip(RoundedCornerShape(1.dp)).background(Ink.text.copy(alpha = 0.22f)))
}
