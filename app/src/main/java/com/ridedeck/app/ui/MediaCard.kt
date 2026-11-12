package com.ridedeck.app.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.ridedeck.app.R
import com.ridedeck.app.data.MediaRepository
import com.ridedeck.app.data.MediaState

@Composable
fun MediaCard(
    state: MediaState,
    media: MediaRepository,
    onGrantAccess: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onResumeLast: () -> Unit = { media.resumeLast() },
    modifier: Modifier = Modifier,
) {
    val palette = LocalMotoPalette.current
    val tint = rememberMediaTint(state, palette)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(tint, palette.mediaBase)))
            .border(1.dp, palette.border, RoundedCornerShape(28.dp))
    ) {
        when {
            !state.hasAccess -> Prompt(
                text = stringResource(R.string.media_grant_prompt),
                hint = stringResource(R.string.media_access_settings_hint),
                button = stringResource(R.string.grant_access),
                onClick = onGrantAccess,
            )
            // Nothing running: one tap starts the last music app where it stopped.
            !state.hasSession -> Prompt(
                text = stringResource(R.string.media_not_playing),
                hint = stringResource(R.string.media_empty_hint),
                button = stringResource(R.string.play),
                onClick = onResumeLast,
            )
            else -> Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // Top: cover, title and artist; a tap on any of it opens the full music screen.
                Row(
                    modifier = Modifier.fillMaxWidth().pressClickable(onClick = onOpenNowPlaying),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The cover slides like on the music screen; it also breathes a little while music plays.
                    val coverScale by animateFloatAsState(if (state.playing) 1f else 0.92f, spring(stiffness = Spring.StiffnessLow), label = "cover")
                    Box(
                        modifier = Modifier.size(68.dp).graphicsLayer { scaleX = coverScale; scaleY = coverScale }
                            .clip(RoundedCornerShape(16.dp)).background(MotoColors.CardHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        TrackCover(state.title, state.artist, state.art, state.artIsPlaceholder, { media.skipDirection }, 34.dp)
                    }
                    AnimatedContent(
                        targetState = state.title to state.artist,
                        transitionSpec = { trackTransition(media.skipDirection) },
                        modifier = Modifier.weight(1f).padding(start = 14.dp),
                        label = "cardTitle",
                    ) { (title, artist) ->
                        Column {
                            Text(
                                title.ifBlank { stringResource(R.string.unknown_track) },
                                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = palette.text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(artist, fontSize = 16.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (state.playing) Equalizer(Modifier.padding(start = 10.dp))
                }
                // Bottom: volume, previous, play, next, volume across the whole card width.
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Five 64 dp targets need 328 dp of content width. Below that, volume lives on
                    // the full Now Playing screen and the hardware keys; never shrink a target.
                    val compact = maxWidth < CARD_FULL_CONTROLS_WIDTH
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!compact) {
                            ControlButton(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.volume_down), 64, soft = true, onClick = media::volumeDown)
                        }
                        ControlButton(Icons.Rounded.SkipPrevious, stringResource(R.string.media_previous), 64, onClick = { media.previous() })
                        ControlButton(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (state.playing) R.string.media_pause else R.string.play), 72, filled = true,
                            haptic = if (state.playing) RideHaptic.ToggleOff else RideHaptic.ToggleOn, onClick = media::togglePlay,
                        )
                        ControlButton(Icons.Rounded.SkipNext, stringResource(R.string.media_next), 64, onClick = { media.next() })
                        if (!compact) {
                            ControlButton(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.volume_up), 64, soft = true, onClick = media::volumeUp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    size: Int,
    filled: Boolean = false,
    soft: Boolean = false,
    haptic: RideHaptic = RideHaptic.Tap,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .pressClickable(haptic = haptic, onClick = onClick)
            .size(size.dp)
            .clip(CircleShape)
            .background(if (filled) LocalMotoPalette.current.strong else if (soft) LocalMotoPalette.current.track else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(icon, animationSpec = tween(200), label = "icon") { shown ->
            Icon(shown, description, tint = if (filled) LocalMotoPalette.current.onStrong else LocalMotoPalette.current.text, modifier = Modifier.size((size * 0.62f).dp))
        }
    }
}

@Composable
private fun Prompt(text: String, hint: String?, button: String, onClick: () -> Unit) {
    val palette = LocalMotoPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The action keeps a fixed 72 dp target; only the informative text scrolls when a short
        // card (or a large font) leaves less room, so the explanation is never silently dropped.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    color = palette.text, textAlign = TextAlign.Center,
                )
                if (!hint.isNullOrBlank()) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        hint, fontSize = 14.sp, lineHeight = 18.sp, color = palette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Spacer(Modifier.size(8.dp))
        PillButton(
            button, MotoColors.Blue, onClick,
            horizontalPadding = 20.dp, minHeight = 72.dp,
            fontSize = cappedSp(20f, maxScale = 1.25f), singleLine = true,
        )
    }
}

/** Full five-control row needs this much width inside the card padding. */
private val CARD_FULL_CONTROLS_WIDTH = 328.dp

/** Album-art tint for the media cards: a dark swatch at night, a light one by day. */
internal fun mediaTint(art: android.graphics.Bitmap?, palette: MotoPalette): Color {
    val fallback = palette.card
    val bmp = art ?: return fallback
    val swatch = Palette.from(bmp).generate()
    val rgb = if (palette.isDay) swatch.getLightMutedColor(fallback.toArgb()) else swatch.getDarkMutedColor(fallback.toArgb())
    return Color(rgb)
}
