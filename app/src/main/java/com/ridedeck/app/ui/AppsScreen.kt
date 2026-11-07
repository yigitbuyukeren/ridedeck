package com.ridedeck.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ridedeck.app.R
import com.ridedeck.app.util.launchPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An installed app the rider can pin. */
private data class AppEntry(val pkg: String, val label: String)

/**
 * The app shelf: big tiles for the apps the rider pinned (messengers, video, anything). Starting one is always
 * allowed; adding, moving and removing are locked while riding, like the other text-heavy screens.
 */
@Composable
fun AppsScreen(
    pinned: List<String>,
    locked: Boolean,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    // Riding while editing or picking: drop back to the plain shelf.
    if (locked && (editing || adding)) { editing = false; adding = false }

    Box(modifier = Modifier.fillMaxSize().background(palette.scrim).clickable(enabled = false) {}) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp, vertical = 16.dp)) {
            val narrow = isNarrowScreen()
            val title: @Composable (Modifier) -> Unit = { m ->
                Text(
                    stringResource(if (adding) R.string.apps_add_title else R.string.apps_title), fontSize = if (narrow) 26.sp else 30.sp,
                    fontWeight = FontWeight.Bold, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m,
                )
            }
            val buttons: @Composable () -> Unit = {
                if (!adding) {
                    PillButton(
                        stringResource(if (editing) R.string.apps_done else R.string.apps_edit),
                        if (editing) MotoColors.Blue else palette.cardHigh,
                        { if (!locked) editing = !editing },
                        textColor = if (editing) Color.White else palette.text,
                        horizontalPadding = 20.dp, singleLine = true,
                    )
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape).background(palette.cardHigh).hapticClickable(onClick = onSettings),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Settings, stringResource(R.string.apps_settings), tint = palette.text, modifier = Modifier.size(32.dp)) }
                }
                Box(
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(palette.cardHigh)
                        .hapticClickable { if (adding) adding = false else onClose() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.message_dismiss), tint = palette.text, modifier = Modifier.size(32.dp)) }
            }
            if (narrow) {
                title(Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) { buttons() }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    title(Modifier.weight(1f))
                    buttons()
                }
            }
            if (locked) Text(stringResource(R.string.apps_locked), fontSize = 14.sp, color = palette.muted, modifier = Modifier.padding(top = 4.dp))

            if (adding) {
                AppPicker(pinned) { onPin(it); adding = false }
            } else {
                Shelf(pinned, editing, onUnpin, onMove, onAdd = { adding = true })
            }
        }
    }
}

@Composable
private fun Shelf(
    pinned: List<String>,
    editing: Boolean,
    onUnpin: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onAdd: () -> Unit,
) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    val pm = context.packageManager
    // Pinned apps that were uninstalled since stay in the list but are skipped.
    val shown = remember(pinned) { pinned.filter { context.packageManager.getLaunchIntentForPackage(it) != null } }
    if (shown.isEmpty() && !editing) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.apps_empty), fontSize = 18.sp, color = palette.muted, textAlign = TextAlign.Center)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        modifier = Modifier.fillMaxSize().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(shown, key = { it }) { pkg ->
            val label = remember(pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            AppTile(
                pkg, label,
                modifier = Modifier.hapticClickable(enabled = !editing, haptic = RideHaptic.Select) { context.launchPackage(pkg) },
            ) {
                if (editing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        EditChip("◀", stringResource(R.string.apps_move_up)) { onMove(pkg, -1) }
                        EditChip("✕", stringResource(R.string.apps_remove), MotoColors.Red) { onUnpin(pkg) }
                        EditChip("▶", stringResource(R.string.apps_move_down)) { onMove(pkg, 1) }
                    }
                }
            }
        }
        if (editing) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(20.dp)).background(MotoColors.Blue).hapticClickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Text("+ " + stringResource(R.string.apps_add), fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun EditChip(text: String, description: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val palette = LocalMotoPalette.current
    Box(
        modifier = Modifier.size(width = 44.dp, height = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(if (color == Color.Unspecified) palette.cardHigh else color)
            .hapticClickable(haptic = RideHaptic.Select, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 18.sp, color = if (color == Color.Unspecified) palette.text else Color.White) }
}

@Composable
private fun AppTile(pkg: String, label: String, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    val palette = LocalMotoPalette.current
    Column(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(pkg, 64)
        Text(
            label, fontSize = 16.sp, color = palette.text, fontWeight = FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
        extra()
    }
}

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

@Composable
private fun AppPicker(pinned: List<String>, onPick: (String) -> Unit) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    val apps by produceState(emptyList<AppEntry>(), pinned) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .distinctBy { it.pkg }
                .filter { it.pkg != context.packageName && it.pkg !in pinned }
                .sortedBy { it.label.lowercase() }
        }
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(apps, key = { it.pkg }) { app ->
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MotoColors.Card)
                    .hapticClickable(haptic = RideHaptic.Select) { onPick(app.pkg) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIcon(app.pkg, 48)
                Text(app.label, fontSize = 20.sp, color = palette.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Dashboard card: the pinned apps in one scrolling row, so a messenger or a video app is one tap away. */
@Composable
fun AppsCard(pinned: List<String>, onOpenShelf: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalMotoPalette.current
    val context = LocalContext.current
    val pm = context.packageManager
    val shown = remember(pinned) { pinned.filter { pm.getLaunchIntentForPackage(it) != null } }
    Box(
        modifier = modifier.clip(RoundedCornerShape(28.dp)).background(MotoColors.Card),
        contentAlignment = Alignment.Center,
    ) {
        if (shown.isEmpty()) {
            PillButton(stringResource(R.string.apps_card_empty), MotoColors.Blue, onOpenShelf, singleLine = true)
        } else {
            androidx.compose.foundation.lazy.LazyRow(
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
                        Text(label, fontSize = 14.sp, color = palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 84.dp))
                    }
                }
            }
        }
    }
}
