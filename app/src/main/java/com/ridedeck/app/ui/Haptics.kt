package com.ridedeck.app.ui

import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import com.ridedeck.app.util.Prefs

/**
 * The short vibration patterns RideDeck uses. Every pattern is deliberately light: a tap confirms a
 * touch, a selection marks a tab, and a toggle has its own on/off accent. Long presses get a single
 * stronger pulse instead of repeating while the finger is down.
 */
enum class RideHaptic {
    /** A normal button or key: one very short tick. */
    Tap,
    /** A tab, route option or list choice: the system's segment tick. */
    Select,
    ToggleOn,
    ToggleOff,
    /** A person or place was confirmed (call, route). */
    Confirm,
    /** An action was refused or a destructive choice needs care. */
    Reject,
    /** The long-press gesture has been recognised. */
    LongPress,
}

/** Pure gate for the three settings that must all allow haptics before a pulse is sent. */
object HapticPolicy {
    fun allowed(userEnabled: Boolean, viewEnabled: Boolean, systemEnabled: Boolean): Boolean =
        userEnabled && viewEnabled && systemEnabled
}

/**
 * Sends Compose haptics through the platform view, so the Android "touch vibration" setting, the
 * view's own haptic flag and the in-app switch are all respected. No VIBRATE permission or direct
 * Vibrator call is used; when the system is in silent/quiet mode the platform simply drops the pulse.
 */
class RideHaptics internal constructor(
    private val context: Context,
    private val view: View,
    private val feedback: HapticFeedback,
) {
    fun perform(kind: RideHaptic) {
        if (!HapticPolicy.allowed(
                userEnabled = Prefs.hapticsEnabled(context),
                viewEnabled = view.isHapticFeedbackEnabled,
                systemEnabled = systemHapticsEnabled(context),
            )
        ) {
            return
        }
        feedback.performHapticFeedback(kind.feedbackType)
    }
}

@Composable
fun rememberRideHaptics(): RideHaptics {
    val context = LocalContext.current
    val view = LocalView.current
    val feedback = LocalHapticFeedback.current
    return remember(context, view, feedback) { RideHaptics(context, view, feedback) }
}

/** Read on each pulse, so changing the system setting takes effect without restarting the app. */
internal fun systemHapticsEnabled(context: Context): Boolean = runCatching {
    Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 1
}.getOrDefault(true)

private val RideHaptic.feedbackType: HapticFeedbackType
    get() = when (this) {
        RideHaptic.Tap -> HapticFeedbackType.VirtualKey
        RideHaptic.Select -> HapticFeedbackType.SegmentTick
        RideHaptic.ToggleOn -> HapticFeedbackType.ToggleOn
        RideHaptic.ToggleOff -> HapticFeedbackType.ToggleOff
        RideHaptic.Confirm -> HapticFeedbackType.Confirm
        RideHaptic.Reject -> HapticFeedbackType.Reject
        RideHaptic.LongPress -> HapticFeedbackType.LongPress
    }
