package com.ridedeck.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.ridedeck.app.util.MUSIC_APPS
import com.ridedeck.app.util.isInstalled
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay

@Composable
fun NowPlayingScreen(
    state: MediaState,
    media: MediaRepository,
    onGrantAccess: () -> Unit,
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
        if (!state.hasAccess) {
            Empty(
                text = stringResource(R.string.media_grant_prompt),
                hint = stringResource(R.string.media_access_settings_hint),
                button = stringResource(R.string.grant_access),
                onClick = onGrantAccess,
            )
            return@Box
        }
        if (!state.hasSession) {
            Empty(
                text = stringResource(R.string.media_not_playing),
                hint = stringResource(R.string.media_empty_hint),
                button = stringResource(R.string.play),
                onClick = onResumeLast,
            )
            return@Box
        }
        val k = 1f
        val controls: @Composable () -> Unit = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // The cover eats into this column on narrow screens. Keep the three transport
                // targets whole and move volume to hardware keys rather than clipping the row.
                val compact = maxWidth < FULL_CONTROLS_WIDTH
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!compact) {
                        RoundButton(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.volume_down), (64 * k).toInt(), palette.track, onClick = media::volumeDown)
                    }
                    RoundButton(Icons.Rounded.SkipPrevious, stringResource(R.string.media_previous), (68 * k).toInt(), Color.Transparent) { media.previous() }
                    RoundButton(
                        if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        stringResource(if (state.playing) R.string.media_pause else R.string.play),
                        (84 * k).toInt(), palette.strong, dark = true,
                        haptic = if (state.playing) RideHaptic.ToggleOff else RideHaptic.ToggleOn, onClick = media::togglePlay,
                    )
                    RoundButton(Icons.Rounded.SkipNext, stringResource(R.string.media_next), (68 * k).toInt(), Color.Transparent) { media.next() }
                    if (!compact) {
                        RoundButton(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.volume_up), (64 * k).toInt(), palette.track, onClick = media::volumeUp)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
            val reflow = maxWidth < FULL_WIDTH_REFLOW_BELOW

            val cover: @Composable () -> Unit = {
                Box(Modifier.fillMaxHeight().aspectRatio(0.88f), contentAlignment = Alignment.Center) {
                    Cover(state, direction = { media.skipDirection }, onNext = { media.next() }, onPrevious = { media.previous() })
                }
            }
            val metadata: @Composable RowScope.(Boolean) -> Unit = { withControls ->
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Title, artist and progress live in a scrollable information region; the
                    // control bar is reserved as a fixed child so a constrained height can never
                    // coerce a target. On narrow layouts the bar moves below the cover instead.
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.padding(end = 56.dp)) { TrackTitle(state) }
                        Spacer(Modifier.height(8.dp))
                        Progress(state, media::seekTo)
                    }
                    if (withControls) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(84.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            controls()
                        }
                    }
                }
            }

            if (reflow) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        cover()
                        Spacer(Modifier.width(28.dp))
                        metadata(false)
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(84.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Full-width bar: the three transport targets keep their width with room
                        // to spare; volume stays available when this width also allows it.
                        controls()
                    }
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    cover()
                    Spacer(Modifier.width(28.dp))
                    metadata(true)
                }
            }
        }
    }
}

/** Album art: swipe it sideways for the next or previous track; it eases back and shrinks a little while paused. */
@Composable
private fun Cover(state: MediaState, direction: () -> Int, onNext: () -> Unit, onPrevious: () -> Unit) {
    val palette = LocalMotoPalette.current
    val haptics = rememberRideHaptics()
    var drag by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(drag, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "swipe")
    val scale by animateFloatAsState(if (state.playing) 1f else 0.93f, spring(stiffness = Spring.StiffnessLow), label = "cover")
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxHeight(0.86f)
            .aspectRatio(1f)
            .graphicsLayer { translationX = offset; scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(24.dp))
            .background(MotoColors.CardHigh)
            .pointerInput(state.hasSession) {
                if (!state.hasSession) return@pointerInput
                var crossed = false
                detectHorizontalDragGestures(
                    onDragStart = { crossed = false },
                    onDragEnd = {
                        when {
                            drag <= -threshold -> onNext()
                            drag >= threshold -> onPrevious()
                        }
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                ) { _, delta ->
                    drag = (drag + delta).coerceIn(-threshold * 1.5f, threshold * 1.5f)
                    // One light tick when the swipe is far enough to change track; no buzzing while dragging.
                    if (!crossed && kotlin.math.abs(drag) >= threshold) {
                        crossed = true
                        haptics.perform(RideHaptic.Select)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        TrackCover(state.title, state.artist, state.art, state.artIsPlaceholder, direction, 72.dp)
    }
}

@Composable
private fun TrackTitle(state: MediaState) {
    val palette = LocalMotoPalette.current
    Row(verticalAlignment = Alignment.Top) {
        AnimatedContent(
            targetState = state.title to state.artist,
            transitionSpec = { (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.weight(1f, fill = false),
            label = "title",
        ) { (title, artist) ->
            Column {
                Text(
                    if (state.hasSession) title.ifBlank { stringResource(R.string.unknown_track) } else stringResource(R.string.media_not_playing),
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = palette.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotBlank()) {
                    Text(artist, fontSize = 19.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (state.playing) Equalizer(modifier = Modifier.padding(start = 14.dp, top = 10.dp))
    }
}

/** Three bars that bounce while music plays. */
@Composable
internal fun Equalizer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    val color = LocalMotoPalette.current.text
    Row(modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        listOf(420, 560, 700).forEachIndexed { i, ms ->
            val h by transition.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse, StartOffset(i * 120)),
                label = "bar$i",
            )
            // Scaled in the draw phase: animating the height asked for a new layout on every frame.
            Box(
                Modifier.width(5.dp).fillMaxHeight()
                    .graphicsLayer { scaleY = h; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }
                    .clip(RoundedCornerShape(3.dp)).background(color),
            )
        }
    }
}

/** A bar you can tap or drag to jump to a point in the track; it grows a handle while touched. */
@Composable
private fun Progress(state: MediaState, onSeek: (Long) -> Unit) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.playing) {
        while (state.playing) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
        now = SystemClock.elapsedRealtime()
    }
    val position = if (state.playing) state.positionMs + ((now - state.positionAt) * state.speed).toLong() else state.positionMs
    val clamped = if (state.durationMs > 0) position.coerceIn(0, state.durationMs) else position.coerceAtLeast(0)
    val playedFraction = if (state.durationMs > 0) clamped.toFloat() / state.durationMs else 0f
    val palette = LocalMotoPalette.current
    var touched by remember { mutableStateOf<Float?>(null) }
    val fraction = touched ?: playedFraction
    val thickness by animateDpAsState(if (touched != null) 14.dp else 8.dp, label = "bar")
    val seekable = state.durationMs > 0
    Column {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(36.dp)
                .pointerInput(seekable, state.durationMs) {
                    if (!seekable) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touched = (down.position.x / size.width).coerceIn(0f, 1f)
                        var last = touched
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            last = (change.position.x / size.width).coerceIn(0f, 1f)
                            touched = last
                            change.consume()
                        } while (event.changes.any { it.pressed })
                        last?.let { onSeek((it * state.durationMs).toLong()) }
                        touched = null
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(modifier = Modifier.fillMaxWidth().height(thickness).clip(CircleShape).background(palette.track)) {
                Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(palette.text))
            }
            if (seekable) {
                val knob = 22.dp
                Box(
                    Modifier.offset(x = (maxWidth - knob) * fraction).size(knob).graphicsLayer { alpha = if (touched != null) 1f else 0f }
                        .clip(CircleShape).background(palette.text),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (touched != null) (touched!! * state.durationMs).toLong() else clamped
            Text(clock(shown), fontSize = 15.sp, color = palette.textSecondary)
            if (state.durationMs > 0) Text(clock(state.durationMs), fontSize = 15.sp, color = palette.textSecondary)
        }
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: Int,
    background: Color,
    dark: Boolean = false,
    enabled: Boolean = true,
    haptic: RideHaptic = RideHaptic.Tap,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .pressClickable(enabled = enabled, haptic = haptic, onClick = onClick)
            .size(size.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(icon, animationSpec = tween(200), label = "icon") { shown ->
            Icon(
                shown, description,
                tint = (if (dark) LocalMotoPalette.current.onStrong else LocalMotoPalette.current.text).copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size((size * 0.55f).dp),
            )
        }
    }
}

@Composable
private fun Empty(text: String, hint: String?, button: String, onClick: () -> Unit) {
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

/** Full five-control row needs this much width inside the text column. */
private val FULL_CONTROLS_WIDTH = 348.dp

/** Below this content width the metadata column is too narrow; put controls in a full-width bar. */
private val FULL_WIDTH_REFLOW_BELOW = 720.dp
