package com.ridedeck.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.app.NotificationManagerCompat
import com.ridedeck.app.service.CarNotificationListener
import com.ridedeck.app.util.MUSIC_APPS
import com.ridedeck.app.util.Prefs
import com.ridedeck.app.util.isInstalled
import com.ridedeck.app.util.launchPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MediaState(
    val hasAccess: Boolean = false,
    val hasSession: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val art: Bitmap? = null,
    val playing: Boolean = false,
    val packageName: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** SystemClock.elapsedRealtime() at which [positionMs] was sampled. */
    val positionAt: Long = 0,
    val speed: Float = 1f,
)

class MediaRepository(private val context: Context) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, CarNotificationListener::class.java)

    private val handler = Handler(Looper.getMainLooper())

    /** Apps that count as music players: the well known ones plus anything that declares itself a music app. */
    private val musicPackages: Set<String> by lazy {
        val declared = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
            .map { it.activityInfo.packageName }
        (MUSIC_APPS.map { it.packageName } + declared).toSet()
    }

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state

    private var controller: MediaController? = null
    private var registered = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = pick(activeControllers())
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it.orEmpty()) }

    private fun hasAccess(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private fun activeControllers(): List<MediaController> =
        try {
            sessions.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            emptyList()
        }

    fun start() {
        if (!hasAccess()) {
            // Access was switched off: drop the old listener so it can be registered afresh when it comes back.
            if (registered) {
                try { sessions.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: SecurityException) {}
                controller?.unregisterCallback(callback)
                controller = null
                registered = false
            }
            _state.value = MediaState(hasAccess = false)
            return
        }
        if (!registered) {
            try {
                sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
                registered = true
            } catch (_: SecurityException) {
                return
            }
        }
        pick(activeControllers())
    }

    private fun pick(list: List<MediaController>) {
        // Only music apps count: a video, a browser or a game holding a media session is not what this screen is for.
        val music = list.filter { it.packageName in musicPackages }
        val best = music.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: music.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = MediaState(hasAccess = true)
            return
        }
        if (Prefs.lastMusicApp(context) != c.packageName) Prefs.setLastMusicApp(context, c.packageName)
        val meta = c.metadata
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        _state.value = MediaState(
            hasAccess = true,
            hasSession = true,
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            art = art,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            packageName = c.packageName,
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = c.playbackState?.position ?: 0,
            positionAt = c.playbackState?.lastPositionUpdateTime ?: 0,
            speed = c.playbackState?.playbackSpeed ?: 1f,
        )
    }

    fun togglePlay() {
        val c = controller
        if (c == null) {
            resumeLast()
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun play() {
        controller?.transportControls?.play()
    }

    /** "Play X": hands the search to the music app; false when no music app has a session to ask. */
    fun playSearch(query: String): Boolean {
        val c = controller ?: return false
        return dispatch(c, PlaybackState.ACTION_PLAY_FROM_SEARCH) { it.playFromSearch(query, null) }
    }

    /** Returns true only when the active player advertises the requested operation and accepts the dispatch. */
    fun playForVoice(): Boolean {
        val c = controller ?: run {
            if (!canResumeLast()) return false
            resumeLast()
            return true
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) return true
        return dispatch(c, PlaybackState.ACTION_PLAY) { it.play() }
    }

    fun pauseForVoice(): Boolean {
        val c = controller ?: return false
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return true
        return dispatch(c, PlaybackState.ACTION_PAUSE) { it.pause() }
    }

    private inline fun dispatch(
        target: MediaController,
        action: Long,
        command: (MediaController.TransportControls) -> Unit,
    ): Boolean {
        if (!MediaSessionActionPolicy.supports(target.playbackState?.actions ?: 0L, action)) return false
        return try {
            command(target.transportControls)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Music volume as a percentage of the loudest setting. */
    fun setVolumePercent(percent: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max / 100f).toInt(), AudioManager.FLAG_SHOW_UI)
    }

    fun volumePercent(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).toInt().coerceIn(0, 100)
    }

    fun pause(): Boolean {
        val c = controller ?: return false
        return dispatch(c, PlaybackState.ACTION_PAUSE) { it.pause() }
    }

    val isPlaying: Boolean get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms.coerceAtLeast(0))
    }

    /**
     * Nothing is playing and no music app has a session: start the last one. First a plain "play" media
     * key, which wakes most players where they stopped without opening them; if no session shows up, the
     * app is launched and asked to play.
     */
    fun resumeLast() {
        val pkg = MusicResumePolicy.select(Prefs.lastMusicApp(context), musicPackages.toList(), context::isInstalled) ?: return
        sendPlayKey()
        handler.postDelayed({
            if (controller == null) {
                context.launchPackage(pkg)
                handler.postDelayed(::sendPlayKey, LAUNCH_PLAY_DELAY_MS)
            }
        }, WAKE_WAIT_MS)
    }

    fun canResumeLast(): Boolean = MusicResumePolicy.select(Prefs.lastMusicApp(context), musicPackages.toList(), context::isInstalled) != null

    private fun sendPlayKey() {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
    }

    /** +1 after "next", -1 after "previous": which way the cover should slide when the track changes. */
    @Volatile var skipDirection = 1
        private set

    fun next(): Boolean {
        val c = controller ?: return false
        val sent = dispatch(c, PlaybackState.ACTION_SKIP_TO_NEXT) { it.skipToNext() }
        if (sent) skipDirection = 1
        return sent
    }

    fun previous(): Boolean {
        val c = controller ?: return false
        val sent = dispatch(c, PlaybackState.ACTION_SKIP_TO_PREVIOUS) { it.skipToPrevious() }
        if (sent) skipDirection = -1
        return sent
    }

    fun volumeUp() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
    fun volumeDown() = audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)

    private companion object {
        const val WAKE_WAIT_MS = 2500L
        const val LAUNCH_PLAY_DELAY_MS = 2500L
    }
}
