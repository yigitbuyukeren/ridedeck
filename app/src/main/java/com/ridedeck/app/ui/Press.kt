package com.ridedeck.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role

/** A click that squeezes the element a little while the finger is down, so a tap with gloves still feels answered. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressClickable(
    enabled: Boolean = true,
    haptic: RideHaptic = RideHaptic.Tap,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = rememberRideHaptics()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
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
    )
}

/** A key that squeezes while pressed, with an optional long-press action. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.pressKey(enabled: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
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
    val haptics = rememberRideHaptics()
    this.clickable(enabled = enabled, role = role) {
        haptics.perform(haptic)
        onClick()
    }
}
