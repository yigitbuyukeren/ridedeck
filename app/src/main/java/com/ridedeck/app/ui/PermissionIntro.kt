package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R

/** One permission the first-launch screen asks for. */
data class PermissionRow(val title: String, val description: String, val granted: Boolean, val onAllow: () -> Unit)

/**
 * Shown on first launch instead of the full settings page: the few permissions the app is
 * built around, each with its own button. "Later" skips; whatever is skipped stays in Settings > Setup.
 */
@Composable
fun PermissionIntro(rows: List<PermissionRow>, onClose: () -> Unit) {
    val palette = LocalMotoPalette.current
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    Box(modifier = Modifier.fillMaxSize().background(palette.wallpaper)) {
        Column(
            modifier = Modifier.fillMaxSize().symmetricSafePadding().padding(horizontal = 32.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.perm_intro_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = palette.text, textAlign = TextAlign.Center)
            Text(stringResource(R.string.perm_intro_sub), fontSize = 14.sp, color = palette.textSecondary, textAlign = TextAlign.Center)
            Text(stringResource(R.string.perm_intro_settings_hint), fontSize = 14.sp, color = palette.textSecondary, textAlign = TextAlign.Center)
            // The rows scroll if they must; "Later" stays pinned below so the page can always be left.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            ) {
                rows.chunked(if (portrait) 1 else 2).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max)) {
                        chunk.forEach { row -> PermissionCard(row, Modifier.weight(1f).fillMaxHeight()) }
                        if (chunk.size == 1 && !portrait) Spacer(Modifier.weight(1f))
                    }
                }
            }
            val done = rows.firstOrNull()?.granted == true
            PillButton(
                stringResource(if (done) R.string.perm_intro_done else R.string.perm_intro_later),
                if (done) MotoColors.Blue else palette.track, onClose, Modifier.widthIn(min = 260.dp),
                textColor = if (done) Color.White else palette.text, minHeight = 64.dp,
            )
        }
    }
}

@Composable
private fun PermissionCard(row: PermissionRow, modifier: Modifier) {
    val palette = LocalMotoPalette.current
    Row(
        modifier = modifier.clip(RoundedCornerShape(20.dp)).background(palette.cardHigh).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
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
