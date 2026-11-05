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
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }.combinedClickable(
        interactionSource = source, indication = null, enabled = enabled, role = role,
        onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel,
    )
}

/** A key that squeezes while pressed, with an optional long-press action. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.pressKey(enabled: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "key")
    this.graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick, onLongClick = onLongClick)
}

