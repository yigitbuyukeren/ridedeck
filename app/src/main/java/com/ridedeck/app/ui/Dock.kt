package com.ridedeck.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.foundation.border
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import com.ridedeck.app.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ridedeck.app.data.BatteryState

/** Which dock tile belongs to the screen that is open; it gets a light ring. */
enum class DockTab { NONE, NAV, MUSIC, PHONE, SETTINGS }

@Composable
fun Dock(
    battery: BatteryState,
    onNavigation: () -> Unit,
    onMusic: () -> Unit,
    onPhone: () -> Unit,
    /** Short press on the Settings tile opens Settings directly. */
    onSettings: () -> Unit = {},
    active: DockTab = DockTab.NONE,
    /** A bar along the bottom for an upright phone; the default is the column down the left side. */
    horizontal: Boolean = false,
) {
    if (horizontal) {
        // The tiles take what the clock block leaves: 68dp on a normal phone, smaller on a narrow one.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
            val statusWidth = 84.dp
            val tile = ((maxWidth - statusWidth - 8.dp * 4) / 4).coerceIn(48.dp, 68.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockTile(Icons.Rounded.Navigation, MotoColors.Blue, tile, onNavigation, active == DockTab.NAV, label = stringResource(R.string.nav_title))
                DockTile(Icons.Rounded.MusicNote, MotoColors.Red, tile, onMusic, active == DockTab.MUSIC, label = stringResource(R.string.dock_music))
                DockTile(Icons.Rounded.Call, Color(0xFF34C759), tile, onPhone, active == DockTab.PHONE, label = stringResource(R.string.perm_phone))
                DockTile(Icons.Rounded.Settings, MotoColors.Gray, tile, onSettings, active == DockTab.SETTINGS, label = stringResource(R.string.apps_settings))
                Box(Modifier.width(statusWidth), contentAlignment = Alignment.Center) { DockStatus(battery) }
            }
        }
        return
    }
    BoxWithConstraints(modifier = Modifier.fillMaxHeight().width(96.dp).padding(vertical = 8.dp)) {
        // Tiles take whatever height the status block leaves, up to a glove-sized 76dp (never below 62dp).
        val gap = 8.dp
        val tile = ((maxHeight - STATUS_HEIGHT - gap * 4) / 4).coerceIn(62.dp, 76.dp)
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                DockTile(Icons.Rounded.Navigation, MotoColors.Blue, tile, onNavigation, active == DockTab.NAV, label = stringResource(R.string.nav_title))
                DockTile(Icons.Rounded.MusicNote, MotoColors.Red, tile, onMusic, active == DockTab.MUSIC, label = stringResource(R.string.dock_music))
                DockTile(Icons.Rounded.Call, Color(0xFF34C759), tile, onPhone, active == DockTab.PHONE, label = stringResource(R.string.perm_phone))
                DockTile(Icons.Rounded.Settings, MotoColors.Gray, tile, onSettings, active == DockTab.SETTINGS, label = stringResource(R.string.apps_settings))
            }
            DockStatus(battery)
        }
    }
}

/** Height of the clock, battery and temperature block under the tiles. */
private val STATUS_HEIGHT = 92.dp

@Composable
private fun DockTile(
    icon: ImageVector, color: Color, tile: Dp, onClick: () -> Unit, active: Boolean = false,
    label: String? = null,
) {
    val ring by animateColorAsState(if (active) Color.White else Color.Transparent, tween(150), label = "dockRing")
    Box(
        modifier = Modifier
            .pressClickable(haptic = RideHaptic.Select, onClick = onClick)
            .size(tile)
            .clip(RoundedCornerShape(18.dp))
            .border(3.dp, ring, RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(lerp(color, Color.White, 0.28f), color))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = label, tint = Color.White,
            modifier = Modifier.size(tile * 0.55f),
        )
    }
}
