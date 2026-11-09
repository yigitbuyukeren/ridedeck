package com.ridedeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R

/** One permission the first-launch screen asks for. */
data class PermissionRow(val title: String, val description: String, val granted: Boolean, val onAllow: () -> Unit)

/**
 * Shown on first launch: only the location permission, which the speedometer cannot do without.
 * Every other permission is asked for where its feature is switched on. "Later" skips.
 */
@Composable
fun PermissionIntro(row: PermissionRow, onClose: () -> Unit) {
    val palette = LocalMotoPalette.current
    Box(modifier = Modifier.fillMaxSize().background(palette.wallpaper), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.symmetricSafePadding().padding(horizontal = 32.dp, vertical = 12.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.perm_intro_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = palette.text, textAlign = TextAlign.Center)
            Text(stringResource(R.string.perm_intro_sub), fontSize = 15.sp, color = palette.textSecondary, textAlign = TextAlign.Center)
            PermissionCard(row)
            PillButton(
                stringResource(if (row.granted) R.string.perm_intro_done else R.string.perm_intro_later),
                if (row.granted) MotoColors.Blue else palette.track, onClose, Modifier.widthIn(min = 260.dp),
                textColor = if (row.granted) Color.White else palette.text, minHeight = 64.dp,
            )
        }
    }
}

@Composable
private fun PermissionCard(row: PermissionRow) {
    val palette = LocalMotoPalette.current
    Row(
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(palette.cardHigh).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.text)
            Text(row.description, fontSize = 13.sp, lineHeight = 16.sp, color = palette.textSecondary)
        }
        if (row.granted) Text(stringResource(R.string.perm_granted), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MotoColors.Green)
        else PillButton(stringResource(R.string.action_allow), MotoColors.Blue, row.onAllow, minHeight = 56.dp, fontSize = 16.sp, horizontalPadding = 18.dp, singleLine = true)
    }
}
