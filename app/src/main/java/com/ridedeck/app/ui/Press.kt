package com.ridedeck.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A click that squeezes the element while pressed; large cards can share a lighter press state. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressClickable(
    enabled: Boolean = true,
    haptic: RideHaptic = RideHaptic.Tap,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    role: Role? = Role.Button,
    pressedScale: Float = 0.92f,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember(interactionSource) { interactionSource ?: MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val haptics = rememberRideHaptics()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    // hapticFeedbackEnabled=false: Compose's own long-press pulse ignores the in-app switch; the
    // single RideHaptic.LongPress below already respects Prefs plus system and view haptics.
    this.graphicsLayer { scaleX = scale; scaleY = scale }.combinedClickable(
        hapticFeedbackEnabled = false,
        interactionSource = source, indication = null, enabled = enabled, role = role,
        onClick = {
            haptics.perform(haptic)
            onClick()
        },
        onLongClick = if (onLongClick != null) {
            {
                haptics.perform(RideHaptic.LongPress)
                onLongClick()
            }
        } else null,
        onLongClickLabel = onLongClickLabel,
    ).focusRing(enabled && focused, LocalMotoPalette.current.text)
}

/** A key that squeezes while pressed, with an optional long-press action. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.pressKey(enabled: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val haptics = rememberRideHaptics()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "key")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(
            hapticFeedbackEnabled = false,
            interactionSource = source, indication = null, enabled = enabled,
            onClick = {
                haptics.perform(RideHaptic.Tap)
                onClick()
            },
            onLongClick = if (onLongClick != null) {
                {
                    haptics.perform(RideHaptic.LongPress)
                    onLongClick()
                }
            } else null,
        )
        .focusRing(enabled && focused, LocalMotoPalette.current.text)
}

/**
 * A static, palette-coloured focus ring. It changes no layout, semantics, pointer input or haptic
 * path: the existing `combinedClickable` remains the only focus/click node.
 */
private fun Modifier.focusRing(visible: Boolean, color: Color): Modifier =
    if (!visible) this else drawWithContent {
        drawContent()
        val stroke = 2.dp.toPx()
        if (size.width <= stroke || size.height <= stroke) return@drawWithContent
        val inset = stroke / 2f
        // Conservative inset indicator: deliberately not shape-aware. A fixed small radius stays
        // visible on rounded rows, pills and cards without pretending to follow their exact clip.
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(stroke),
        )
    }

/**
 * A plain [clickable] with the same ripple as before, plus a short haptic. Use it for rows and cards
 * where the squeeze animation of [pressClickable] would move too much content.
 */
fun Modifier.hapticClickable(
    enabled: Boolean = true,
    role: Role? = null,
    haptic: RideHaptic = RideHaptic.Tap,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val haptics = rememberRideHaptics()
    this.focusRing(enabled && focused, LocalMotoPalette.current.text)
        .clickable(
            interactionSource = source,
            indication = LocalIndication.current,
            enabled = enabled,
            role = role,
        ) {
            haptics.perform(haptic)
            onClick()
        }
}
