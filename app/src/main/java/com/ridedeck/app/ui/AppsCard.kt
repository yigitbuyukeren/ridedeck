package com.ridedeck.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ridedeck.app.R
import com.ridedeck.app.util.launchPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun AppIcon(pkg: String, sizeDp: Int) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.Default) {
            runCatching { context.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).asImageBitmap() }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) Image(b, null, modifier = Modifier.size(sizeDp.dp)) else Box(Modifier.size(sizeDp.dp))
}

/** Dashboard card: the pinned apps in one scrolling row, so a messenger or a video app is one tap away.
 * With nothing pinned it shows a Settings icon instead. */
@Composable
fun AppsCard(
    pinned: List<String>,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    val pm = context.packageManager
    val shown = remember(pinned) { pinned.filter { pm.getLaunchIntentForPackage(it) != null } }
    Box(
        modifier = modifier.clip(RoundedCornerShape(28.dp)).background(MotoColors.Card),
        contentAlignment = Alignment.Center,
    ) {
        if (shown.isEmpty()) {
            Box(
                modifier = Modifier.size(72.dp).clip(CircleShape).background(MotoColors.Blue).hapticClickable(haptic = RideHaptic.Select, onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Settings, stringResource(R.string.apps_settings), tint = Color.White, modifier = Modifier.size(36.dp)) }
        } else {
            LazyRow(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(shown, key = { it }) { pkg ->
                    val label = remember(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
                    Column(
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).pressClickable { context.launchPackage(pkg) }.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AppIcon(pkg, 60)
                        androidx.compose.material3.Text(
                            label, fontSize = 14.sp, color = palette.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 84.dp),
                        )
                    }
                }
            }
        }
    }
}
