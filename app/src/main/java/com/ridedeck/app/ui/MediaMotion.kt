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
import androidx.compose.runtime.remember
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
fun TrackCover(title: String, artist: String, art: Bitmap?, playing: Boolean, direction: () -> Int, iconSize: Dp) {
    val key = title to artist
    val arts = remember { LinkedHashMap<Pair<String, String>, Bitmap?>() }
    val known = arts[key]
    // While playing a late picture may replace the placeholder. While paused the player sometimes
    // re-sends a blank or different bitmap; keep the one that was on screen instead of flashing.
    if (key !in arts || (art != null && (playing || known == null))) {
        arts[key] = art
        while (arts.size > KEPT_COVERS) arts.remove(arts.keys.first { it != key })
    }
    AnimatedContent(
        targetState = key,
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

/**
 * The artwork used for the card tint. Some players drop or replace their artwork when playback
 * stops; letting that through pulled the tint towards the neutral fallback and the card visibly
 * darkened for a moment. Keep the artwork seen while playing until the track changes or playback
 * resumes, so pausing only shrinks the cover.
 */
internal class ArtworkMemory<T> {
    private var key: Pair<String, String>? = null
    private var art: T? = null

    fun pick(nowKey: Pair<String, String>, nowArt: T?, playing: Boolean, hasSession: Boolean): T? {
        if (!hasSession) { key = nowKey; art = null; return null }
        if (nowKey != key) { key = nowKey; art = nowArt }
        if (nowArt != null && (playing || art == null)) art = nowArt
        return art
    }
}

@Composable
internal fun rememberMediaTint(state: MediaState, palette: MotoPalette): Color {
    val memory = remember { ArtworkMemory<Bitmap>() }
    val art = memory.pick(state.title to state.artist, state.art, state.playing, state.hasSession)
    val target = remember(art, palette) { mediaTint(art, palette) }
    return animateColorAsState(target, tween(600), label = "tint").value
}
