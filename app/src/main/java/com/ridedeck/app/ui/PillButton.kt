package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PillButton(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    horizontalPadding: Dp = 24.dp,
    minHeight: Dp = 72.dp,
    fontSize: TextUnit = 20.sp,
    singleLine: Boolean = false,
    haptic: RideHaptic = RideHaptic.Tap,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(RoundedCornerShape(36.dp))
            .background(color)
            .pressClickable(haptic = haptic, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = fontSize, fontWeight = FontWeight.SemiBold, color = textColor,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE, softWrap = !singleLine, overflow = TextOverflow.Ellipsis,
        )
    }
}
