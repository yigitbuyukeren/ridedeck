package com.ridedeck.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.ridedeck.app.data.MediaState
import kotlinx.coroutines.delay

/**
 * The cover changes like a page turn: after "next" the old one leaves to the left and the new one comes
 * in from the right, after "previous" the other way round. [direction] is +1 for next, -1 for previous.
 */
fun trackTransition(direction: Int): ContentTransform =
    // Slides only, both over the same time: a fade that ends sooner leaves a gap between the two covers.
    slideInHorizontally(tween(SLIDE_MS)) { it * direction } togetherWith
        slideOutHorizontally(tween(SLIDE_MS)) { -it * direction }

private const val SLIDE_MS = 380

/**
 * The album cover with its page-turn slide. The slide is keyed on the track (title and artist) only:
 * music apps re-send the metadata, with a fresh copy of the picture each time, several times while a
 * track changes, and keying on the picture made the cover slide back and forth for each of them. A
 * picture that arrives late for the same track just fades in. Each track keeps its own picture, so the
 * cover that is sliding out never shows the new one.
 */
@Composable
fun TrackCover(
    title: String,
    artist: String,
    art: Bitmap?,
    artIsPlaceholder: Boolean,
    direction: () -> Int,
    iconSize: Dp,
) {
    val key = title to artist
    // A launcher icon is not album art: players often send it for a moment while the real cover
    // loads, and on the dark card a transparent icon looks like the cover going black.
    val realArt = if (artIsPlaceholder) null else art
    val arts = remember { LinkedHashMap<Pair<String, String>, Bitmap?>() }
    // Freeze the first real cover of each track: a skip or a seek may re-send metadata, and
    // replacing a known cover with a blank one is what made the card flash on previous/next.
    if (key !in arts || (arts[key] == null && realArt != null)) {
        arts[key] = realArt
        while (arts.size > KEPT_COVERS) arts.remove(arts.keys.first { it != key })
    }
    // The title changes at once, but players often send the new track's artwork a moment later.
    // Hold the previous cover during that gap instead of sliding a dark placeholder in; a track
    // with no artwork at all falls back to the placeholder after a short grace period.
    var shownKey by remember { mutableStateOf(key) }
    LaunchedEffect(key, realArt) {
        if (key == shownKey) return@LaunchedEffect
        if (realArt != null || arts[shownKey] == null) { shownKey = key; return@LaunchedEffect }
        delay(TRACK_ART_GRACE_MS)
        if (key != shownKey) shownKey = key
    }
    AnimatedContent(
        targetState = shownKey,
        transitionSpec = { trackTransition(direction()) },
        label = "cover",
    ) { shown ->
        Crossfade(arts[shown], animationSpec = tween(300), label = "coverArt") { picture ->
            if (picture != null) {
                Image(picture.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = LocalMotoPalette.current.muted, modifier = Modifier.size(iconSize))
                }
            }
        }
    }
}

private const val KEPT_COVERS = 3

/** How long a new track may wait for its artwork before the placeholder is shown. */
private const val TRACK_ART_GRACE_MS = 1_200L

/**
 * The artwork used for the card tint. Players drop or replace their artwork when playback stops,
 * and a new track's cover often arrives a moment after its title. Letting either through pulled
 * the tint towards the neutral fallback and the card visibly darkened. Keep the last real artwork
 * until the new one is in hand; a pause-time replacement is ignored.
 */
internal class ArtworkMemory<T> {
    private var key: Pair<String, String>? = null
    private var art: T? = null

    fun pick(nowKey: Pair<String, String>, nowArt: T?, hasSession: Boolean): T? {
        if (!hasSession) { key = nowKey; art = null; return null }
        // Take the first real artwork of a track and keep it: skip/seek re-sends must not change it.
        if (nowArt != null && (nowKey != key || art == null)) {
            key = nowKey
            art = nowArt
        }
        return art
    }
}

@Composable
internal fun rememberMediaTint(state: MediaState, palette: MotoPalette): Color {
    val memory = remember { ArtworkMemory<Bitmap>() }
    // The app icon is only a placeholder; it must not colour the card while the cover loads.
    val realArt = if (state.artIsPlaceholder) null else state.art
    val art = memory.pick(state.title to state.artist, realArt, state.hasSession)
    val target = remember(art, palette) { mediaTint(art, palette) }
    return animateColorAsState(target, tween(600), label = "tint").value
}
