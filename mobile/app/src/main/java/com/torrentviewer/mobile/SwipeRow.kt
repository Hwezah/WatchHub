package com.torrentviewer.mobile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import kotlinx.coroutines.launch

/**
 * A row you can swipe either way, as the Grabber app does it (same owner).
 *
 * RIGHT: swipe past a third and it runs [onRight] at once (play a finished file, pause/resume a download), over a
 * card-shaped backing in [rightTint] with [rightIcon] at the left.
 *
 * LEFT: a destructive action, so it doesn't run on the swipe — the row hangs open by [revealWidth] showing a full-bleed
 * backing in [leftTint] with [leftIcon] (a delete) at the right, and [onLeft] runs only when that icon is TAPPED. A tap
 * on the open row, or a swipe back, closes it. The left backing has no rounding, running endlessly to the left.
 */
@Composable
fun SwipeRow(
    modifier: Modifier = Modifier,
    onRight: (() -> Unit)? = null,
    rightIcon: Int? = null,
    rightTint: Color = Ink.good,
    onLeft: (() -> Unit)? = null,
    leftIcon: Int? = null,
    leftTint: Color = Ink.danger,
    revealWidth: Dp = 92.dp,
    /** The card's shape and the gap below it, so the right backing matches the row's card exactly. */
    backing: Shape = RoundedCornerShape(14.dp),
    inset: Dp = 10.dp,
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
    fun leftMin() = if (onLeft != null) -revealPx else 0f
    fun rightMax() = if (onRight != null) widthPx else 0f

    Box(modifier.fillMaxWidth().onSizeChanged { if (it.width > 0) widthPx = it.width.toFloat() }) {
        // The right action's backing — the card's own size and shape, brightening as it's drawn right, the icon at the left.
        if (onRight != null && rightIcon != null) {
            val a = (offset.value / (widthPx / 3f)).coerceIn(0f, 1f)
            Box(Modifier.matchParentSize().padding(bottom = inset).clip(backing).background(rightTint.copy(alpha = 0.16f + 0.24f * a)), contentAlignment = Alignment.CenterStart) {
                Lc(rightIcon, Modifier.padding(start = 24.dp), tint = rightTint, size = 24.dp)
            }
        }
        // The left action's backing — full width, no rounding (the owner's choice: endless to the left). Its icon, at the
        // right where the row uncovers it, is the tap target: the delete runs on the tap, not the swipe.
        if (onLeft != null && leftIcon != null) {
            val a = (-offset.value / revealPx).coerceIn(0f, 1f)
            Box(Modifier.matchParentSize().padding(bottom = inset).background(leftTint.copy(alpha = 0.16f + 0.5f * a)), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier.width(revealWidth).fillMaxHeight().clickable(remember { MutableInteractionSource() }, indication = null, enabled = isOpen) { onLeft(); close() },
                    contentAlignment = Alignment.CenterEnd,
                ) { Lc(leftIcon, Modifier.padding(end = 24.dp), tint = leftTint, size = 24.dp) }
            }
        }
        // The row itself, moved by the drag; while it's open a tap on it closes it.
        Box(
            Modifier.graphicsLayer { translationX = offset.value }
                .pointerInput(onRight != null, onLeft != null) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + delta).coerceIn(leftMin(), rightMax())) }
                        },
                        onDragEnd = {
                            val x = offset.value
                            when {
                                onRight != null && x >= widthPx / 3f -> { onRight(); close() }
                                onLeft != null && x <= -revealPx / 2f -> scope.launch { offset.animateTo(-revealPx, back) }
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
