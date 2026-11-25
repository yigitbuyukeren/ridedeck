package com.ridedeck.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Clears real hardware display cutouts (e.g. a front-camera punch-hole in landscape) without
 * reserving dead empty bars for hidden immersive system bars or mirroring a one-sided cutout
 * onto the opposite edge.
 */
@Composable
fun Modifier.symmetricSafePadding(): Modifier {
    val insets = WindowInsets.displayCutout
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val left = with(density) { insets.getLeft(density, dir).toDp() }
    val right = with(density) { insets.getRight(density, dir).toDp() }
    val top = with(density) { insets.getTop(density).toDp() }
    val bottom = with(density) { insets.getBottom(density).toDp() }
    return padding(
        start = left.coerceAtLeast(0.dp),
        end = right.coerceAtLeast(0.dp),
        top = top.coerceAtLeast(0.dp),
        bottom = bottom.coerceAtLeast(0.dp),
    )
}
