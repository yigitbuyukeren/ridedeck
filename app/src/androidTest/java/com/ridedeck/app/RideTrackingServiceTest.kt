package com.ridedeck.app

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.core.app.NotificationManagerCompat
import androidx.activity.compose.setContent
import com.ridedeck.app.ui.MotoTheme
import com.ridedeck.app.ui.TripsScreen
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.data.*
import com.ridedeck.app.service.RideTrackingService
import com.ridedeck.app.util.hasPermission
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.FileInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Uses a mock GPS provider and shell permission changes; intentionally limited to a disposable emulator. */
@Suppress("DEPRECATION")
class RideTrackingServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val tracking get() = (context.applicationContext as RideDeckApplication).rideTracking
    private val manager get() = context.getSystemService(LocationManager::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var oldRecording = true
    private var ready = false
    /** Revoke fine+coarse location before starting this instrumentation; the default run grants them. */
    private val locationDenied
        get() = InstrumentationRegistry.getArguments().getString("locationPermissionDenied") == "true"

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd -> FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun findText(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): android.view.accessibility.AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) findText(node.getChild(i), text)?.let { return it }
        return null
    }

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("Condition timed out", check())
    }

    @Before fun setup() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        shell("settings put secure immersive_mode_confirmations confirmed")
        if (!locationDenied) {
            shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
            shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        }
        if (Build.VERSION.SDK_INT >= 33 && InstrumentationRegistry.getArguments().getString("notificationPermissionDenied") != "true") {
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
        shell("appops set ${context.packageName} android:mock_location allow")
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true, Criteria.POWER_HIGH, Criteria.ACCURACY_FINE)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        main { oldRecording = tracking.recordTrips.value; tracking.setRecording(true) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ready = true
    }

    @After fun cleanup() {
        if (!ready) return
        main { tracking.session.stop() }
        await { tracking.session.state.value.phase == RidePhase.IDLE }
        scenario?.close()
        scenario = null
        manager.removeTestProvider(LocationManager.GPS_PROVIDER)
        main { tracking.setRecording(oldRecording) }
        wakeScreen()
        shell("appops set ${context.packageName} android:mock_location default")
        shell("appops set --uid ${context.packageName} POST_NOTIFICATION default")
    }

    private fun start() {
        scenario!!.onActivity { assertTrue(tracking.requestStart(true)) }
        await { tracking.session.state.value.phase == RidePhase.ACTIVE }
        assertTrue(isForeground())
    }

    private fun isForeground(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == RideTrackingService::class.java.name && it.foreground }

    private fun serviceCount(): Int = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).count { it.service.className == RideTrackingService::class.java.name }

    /** Delivers a command exactly like a startForegroundService() caller (notification action or app code). */
    private fun command(action: String, token: Long = 0L) {
        context.startForegroundService(Intent(context, RideTrackingService::class.java)
            .setAction(action).putExtra(RideTrackingService.EXTRA_REQUEST, token))
    }

    private fun fix(index: Int) {
        val measured = SystemClock.elapsedRealtimeNanos()
        manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 41.0 + index * 0.00009
            longitude = 29.0
            speed = 10f
            bearing = 0f
            accuracy = 2f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = measured
        })
        await { tracking.location.state.value.elapsedRealtimeMs >= measured / 1_000_000 }
        SystemClock.sleep(1_050) // Respect the production listener's one-second minimum interval.
    }

    private fun isInteractive(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive

    /**
     * The power keys only queue an asynchronous transition. Waiting here keeps the following test
     * from launching an activity while the display is still switching and its window is not yet
     * available to UiAutomation on a slow emulator.
     */
    private fun sleepScreen() {
        shell("input keyevent KEYCODE_SLEEP")
        await { !isInteractive() }
    }

    private fun wakeScreen() {
        shell("input keyevent KEYCODE_WAKEUP")
        await { isInteractive() }
        shell("wm dismiss-keyguard")
    }

    /** A first-run full-screen confirmation can appear after the one-shot click on a slow emulator. */
    private fun awaitStartButton(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (findText(root, label) != null) return
            findText(root, "Got it")?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(30)
        }
        throw AssertionError("Start button missing (phase=${tracking.session.state.value.phase}); texts=${visibleTexts()}")
    }

    private fun visibleTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            node ?: return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(instrumentation.uiAutomation.rootInActiveWindow)
        return out.take(12)
    }

    @Test fun screenOffAndActivityDestructionKeepOneGpsRecorderUntilNotificationStop() {
        val store = TripStore(context)
        try {
            val before = store.unfinishedIds().toSet()
            scenario!!.onActivity { activity ->
                activity.setContent { MotoTheme { TripsScreen(tracking.trips, tracking, onClose = {}) } }
            }
            instrumentation.waitForIdleSync()
            findText(instrumentation.uiAutomation.rootInActiveWindow, "Got it")?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            val startLabel = context.getString(R.string.ride_start)
            awaitStartButton(startLabel)
            val button = findText(instrumentation.uiAutomation.rootInActiveWindow, startLabel)!!
            val bounds = android.graphics.Rect()
            button.getBoundsInScreen(bounds)
            val touchAt = SystemClock.uptimeMillis()
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(touchAt, touchAt, android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0))
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(touchAt, touchAt + 50, android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0))
            await { tracking.session.state.value.phase == RidePhase.ACTIVE }
            assertTrue(isForeground())
            SystemClock.sleep(500)
            shell("screencap -p /data/local/tmp/ride-controls-test.png")
            repeat(5, ::fix)
            await { tracking.trips.recording.value }
            val id = store.unfinishedIds().single { it !in before }
            val count = store.points(id).size
            scenario!!.recreate()
            scenario!!.onActivity { activity ->
                assertSame(tracking.location.state, ViewModelProvider(activity)[DashboardViewModel::class.java].locationState)
                assertSame(tracking.trips, ViewModelProvider(activity)[DashboardViewModel::class.java].trips)
            }
            scenario!!.close() // ViewModel.onCleared cannot own or stop this session.
            scenario = null
            sleepScreen()
            fix(5)
            fix(6)
            assertTrue(store.points(id).size > count)
            assertNotNull(tracking.location.displayKmh())
            assertTrue(isForeground())
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == RideTrackingService.NOTIFICATION_ID }
            notification.notification.actions.single().actionIntent.send()
            await { tracking.session.state.value.phase == RidePhase.IDLE }
            await { !isForeground() }
            assertFalse(tracking.trips.recording.value)
            assertFalse(store.unfinishedIds().contains(id)) // Short test rides still obey the existing discard thresholds.
            assertEquals(0L, tracking.location.state.value.fixAtMs)
        } finally { store.close() }
    }

    @Test fun disabledNotificationsDoNotPreventLocationForegroundService() {
        if (InstrumentationRegistry.getArguments().getString("notificationPermissionDenied") == "true") {
            assertFalse(context.hasPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        // Runtime permission revocation kills the target UID, including instrumentation. Block delivery
        // through the same notification app-op here; cold-start with denied permission is checked separately.
        shell("appops set --uid ${context.packageName} POST_NOTIFICATION deny")
        val mode = context.getSystemService(android.app.AppOpsManager::class.java)
            .checkOpNoThrow("android:post_notification", android.os.Process.myUid(), context.packageName)
        assertTrue(mode == android.app.AppOpsManager.MODE_ERRORED || mode == android.app.AppOpsManager.MODE_IGNORED)
        start()
        fix(0)
        assertTrue(tracking.location.state.value.fixAtMs > 0)
        main { tracking.session.stop() }
        await { !isForeground() }
    }

    @Test fun gpsDisabledEndsRideAndServiceInsteadOfWaitingIndefinitely() {
        start()
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        await { tracking.session.state.value.phase == RidePhase.IDLE }
        assertEquals(RideFailure.GPS_DISABLED, tracking.session.state.value.failure)
        await { !isForeground() }
    }

    @Test fun destroyedServiceDoesNotRestartAndRequiresAnotherVisibleStart() {
        start()
        context.stopService(Intent(context, RideTrackingService::class.java))
        await { tracking.session.state.value.phase == RidePhase.IDLE }
        assertEquals(RideFailure.INTERRUPTED, tracking.session.state.value.failure)
        assertNull(tracking.closeService) // A destroyed service cannot close a newer ride later.
        scenario!!.close()
        scenario = null
        main { assertFalse(tracking.requestStart(false)) }
        assertFalse(isForeground())
        scenario = ActivityScenario.launch(MainActivity::class.java)
        start()
    }

    @Test fun navigationAndAudioOwnersSurviveActivityAndViewModelDestruction() {
        lateinit var old: DashboardViewModel
        scenario!!.onActivity { old = ViewModelProvider(it)[DashboardViewModel::class.java] }
        main { old.nav.onQueryChange("Lifecycle preview") }
        scenario!!.recreate()
        scenario!!.onActivity { assertSame(old.nav, ViewModelProvider(it)[DashboardViewModel::class.java].nav) }
        scenario!!.close()
        scenario = null
        sleepScreen()
        main { old.nav.onQueryChange("Changed without a screen") }
        wakeScreen()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity {
            val next = ViewModelProvider(it)[DashboardViewModel::class.java]
            assertNotSame(old, next)
            assertSame(old.nav, next.nav)
            assertSame(old.media, next.media)
            assertSame(old.call, next.call)
            assertSame(old.voiceTtsNames, next.voiceTtsNames)
            assertSame(old.recents, next.recents)
            assertEquals("Changed without a screen", next.nav.ui.value.query)
            next.nav.stopNavigation()
        }
    }

    @Test fun guidanceWithRealServiceGpsContinuesWithoutActivityAndNotificationCancelsLateRoute() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val said = mutableListOf<String>()
        var pending: CompletableDeferred<Result<Route>>? = null
        val routing = object : Routing {
            override suspend fun search(query: String, near: GeoPoint?) = Result.success(emptyList<Place>())
            override suspend fun route(from: GeoPoint, to: GeoPoint, options: RouteOptions): Result<Route> {
                pending?.let { return it.await() }
                val shape = (0..30).map { GeoPoint(from.lat + it * 0.00009, from.lon) }
                return Result.success(Route(shape, listOf(
                    Maneuver(1, "Depart", "", "Head north", 300.0, 0),
                    Maneuver(4, "Arrive", "", "Approaching destination", 0.0, 30),
                ), Geo.distance(shape.first(), shape.last()), 30.0))
            }
        }
        val speaker = object : Speaker {
            override fun speak(text: String) { said += text }
            override fun stop() {}
            override fun shutdown() {}
        }
        lateinit var nav: NavigationController
        var created = false
        try {
            start()
            fix(0)
            main {
                nav = NavigationController(context::getString, scope, tracking.location.state, speaker, repo = routing,
                    beforeStart = { tracking.requestStart(false) })
                created = true
                NavigationRideLifecycle(scope, nav, tracking.session) {
                    tracking.navigating = it
                    (context.applicationContext as RideDeckApplication).rideNavigation.refreshAudio()
                }
                nav.selectPlace(Place("Synthetic destination", "", GeoPoint(41.0027, 29.0)))
                nav.startNavigation()
            }
            await { nav.ui.value.navigating }
            val before = nav.ui.value.remainingM
            scenario!!.close()
            scenario = null
            sleepScreen()
            fix(5)
            assertTrue(nav.ui.value.navigating)
            assertTrue(nav.ui.value.remainingM < before)
            assertTrue(isForeground())
            main {
                assertTrue(said.isNotEmpty())
                pending = CompletableDeferred()
                // A detour reroute is now suspended in the fake routing backend.
                nav.detourVia(Place("Synthetic stop", "", GeoPoint(41.0026, 29.0001)))
            }
            assertTrue(nav.ui.value.rerouting)
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == RideTrackingService.NOTIFICATION_ID }
            notification.notification.actions.single().actionIntent.send()
            await { tracking.session.state.value.phase == RidePhase.IDLE }
            await { !isForeground() }
            main {
                val announcements = said.toList()
                pending!!.complete(Result.success(Route(listOf(GeoPoint(41.0, 29.0), GeoPoint(41.01, 29.0)),
                    listOf(Maneuver(1, "Late", "", "Late instruction", 1000.0, 0)), 1000.0, 100.0)))
                assertFalse(nav.ui.value.navigating)
                assertFalse(nav.ui.value.rerouting)
                assertEquals(announcements, said)
                assertFalse(tracking.navigating)
            }
        } finally {
            main { if (created) nav.shutdown(); scope.cancel() }
        }
    }

    @Test fun stopBeforeAnyStartNeverLeavesAWaitingForegroundService() {
        command(RideTrackingService.ACTION_STOP)
        await { tracking.session.state.value.phase == RidePhase.IDLE && !isForeground() && serviceCount() == 0 }
    }

    @Test fun unacceptedStartWithoutSessionDoesNotLeakANonForegroundService() {
        command(RideTrackingService.ACTION_START, token = 99L) // No STARTING session accepts this token.
        await { tracking.session.state.value.phase == RidePhase.IDLE && !isForeground() && serviceCount() == 0 }
    }

    @Test fun unknownIntentDoesNotLeaveANonForegroundService() {
        command("com.ridedeck.app.UNKNOWN")
        await { tracking.session.state.value.phase == RidePhase.IDLE && !isForeground() && serviceCount() == 0 }
    }

    @Test fun duplicateAndStaleStartsKeepTheActiveRideAndOneService() {
        start()
        command(RideTrackingService.ACTION_START, token = 99L)
        fix(0)
        await { tracking.location.state.value.fixAtMs > 0 }
        assertEquals(RidePhase.ACTIVE, tracking.session.state.value.phase)
        assertTrue(isForeground())
        assertEquals(1, serviceCount())
        main { tracking.session.stop() }
        await { !isForeground() }
    }

    @Test fun startStopStartIgnoresAnOldRidesStopCommand() {
        start()
        main { tracking.session.stop() }
        await { tracking.session.state.value.phase == RidePhase.IDLE }
        await { !isForeground() }
        start()
        // A stop from any ended ride carries a different token; it must not end the new one.
        command(RideTrackingService.ACTION_STOP, token = Long.MAX_VALUE)
        SystemClock.sleep(1_500)
        assertEquals(RidePhase.ACTIVE, tracking.session.state.value.phase)
        assertTrue(isForeground())
        fix(0)
        await { tracking.location.state.value.fixAtMs > 0 }
        main { tracking.session.stop() }
        await { !isForeground() }
    }

    @Test fun prerequisiteFailureAfterPromotionStopsTheServiceInsteadOfLeakingIt() {
        scenario!!.onActivity {
            assertTrue(tracking.requestStart(true))
            // Race the service: the session accepted, then GPS disappears before its command arrives.
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
        }
        await { tracking.session.state.value.phase == RidePhase.IDLE }
        assertEquals(RideFailure.GPS_DISABLED, tracking.session.state.value.failure)
        await { !isForeground() }
    }

    /**
     * Android 14+ refuses a location foreground service without while-in-use location. revoke the
     * permissions before the instrumentation starts (revoking from inside kills the test process):
     * `adb shell pm revoke com.ridedeck.app android.permission.ACCESS_FINE_LOCATION`
     * `adb shell pm revoke com.ridedeck.app android.permission.ACCESS_COARSE_LOCATION`
     * then run with `-e locationPermissionDenied true`.
     */
    @Test fun permissionDeniedStartStillPromotesAndStopsInsteadOfCrashing() {
        assumeTrue(locationDenied)
        assertFalse(context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        command(RideTrackingService.ACTION_START, token = 77L)
        await { tracking.session.state.value.phase == RidePhase.IDLE && !isForeground() && serviceCount() == 0 }
    }
}
