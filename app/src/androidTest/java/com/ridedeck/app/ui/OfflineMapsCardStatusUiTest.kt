package com.ridedeck.app.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.NativeRegionState
import com.ridedeck.app.data.OfflineArea
import com.ridedeck.app.data.OfflineBlock
import com.ridedeck.app.data.OfflineMaps
import com.ridedeck.app.data.OfflinePlan
import com.ridedeck.app.data.ReconciledRegion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Fake-callback card tests: no singleton load, no native region and no deletion. */
class OfflineMapsCardStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val area = OfflineArea("istanbul", GeoPoint(41.0, 29.0), 15, 10L)
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    /** The card lives in the settings scroll column; keep the fixture in landscape independently. */
    @Before fun forceLandscapeForThisTest() {
        originalOrientation = compose.activity.requestedOrientation
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
    }

    @After fun restoreActivityOrientation() {
        compose.activity.requestedOrientation = originalOrientation
        compose.waitForIdle()
    }

    private fun showCard(
        regions: List<ReconciledRegion> = listOf(ReconciledRegion(area, NativeRegionState.PARTIAL)),
        reconcileError: String? = null,
        actionError: String? = null,
        deleted: AtomicReference<OfflineArea?> = AtomicReference(),
        allowStale: AtomicBoolean = AtomicBoolean(false),
        deleteCount: AtomicInteger = AtomicInteger(),
        cancelled: AtomicReference<OfflineArea?> = AtomicReference(),
        cancelCount: AtomicInteger = AtomicInteger(),
        areasState: MutableStateFlow<List<OfflineArea>> = MutableStateFlow(listOf(area)),
        progressState: MutableStateFlow<OfflineMaps.Progress?> = MutableStateFlow<OfflineMaps.Progress?>(null),
    ) {
        val progress = progressState
        val regionState = MutableStateFlow(regions)
        val reconcileState = MutableStateFlow(reconcileError)
        val actionState = MutableStateFlow(actionError)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MotoTheme {
                    // Production mounts the card in the settings LazyColumn, whose item gets an
                    // unbounded vertical measure. A bare root would compress the trailing controls.
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        OfflineMapsCard(
                            here = null,
                            areasFlow = areasState,
                            progressFlow = progress,
                            regionsFlow = regionState,
                            reconcileErrorFlow = reconcileState,
                            actionErrorFlow = actionState,
                            actions = OfflineMapsCardActions(
                                load = {},
                                download = { _, _, _, _ -> },
                                delete = { _, target, stale ->
                                    deleteCount.incrementAndGet()
                                    deleted.set(target)
                                    allowStale.set(stale)
                                },
                                cancel = { _, target ->
                                    cancelCount.incrementAndGet()
                                    cancelled.set(target)
                                },
                                clearError = {},
                            ),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun font2DeleteConfirmationKeeps64TargetsAndFakeCallback() {
        val deleted = AtomicReference<OfflineArea?>()
        val allowStale = AtomicBoolean(false)
        val deleteCount = AtomicInteger()
        showCard(deleted = deleted, allowStale = allowStale, deleteCount = deleteCount)

        val delete = compose.onNodeWithTag("offline-delete")
            .performScrollTo()
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue("delete target is at least 64 dp tall", delete.height >= 64.dp)

        compose.onNodeWithTag("offline-delete").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.offline_delete_confirm_text))
            .performScrollTo().assertIsDisplayed()
        val confirm = compose.onNodeWithTag("offline-delete-confirm")
            .performScrollTo()
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val cancel = compose.onNodeWithTag("offline-delete-cancel")
            .performScrollTo()
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue("confirm target is at least 64 dp tall", confirm.height >= 64.dp)
        assertTrue("cancel target is at least 64 dp tall", cancel.height >= 64.dp)

        compose.onNodeWithTag("offline-delete-confirm").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(area, deleted.get())
        assertTrue("confirmed delete requests stale-entry removal", allowStale.get())
        assertEquals(1, deleteCount.get())
    }

    @Test
    fun inheritedActiveRegionCancelsThroughFakeCallbackAtFont2() {
        val cancelled = AtomicReference<OfflineArea?>()
        val cancelCount = AtomicInteger()
        val deleteCount = AtomicInteger()
        showCard(
            regions = listOf(ReconciledRegion(area, NativeRegionState.DOWNLOADING)),
            cancelled = cancelled,
            cancelCount = cancelCount,
            deleteCount = deleteCount,
        )

        val cancel = compose.onNodeWithTag("offline-cancel")
            .performScrollTo()
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue("cancel target is at least 64 dp tall", cancel.height >= 64.dp)

        compose.onNodeWithTag("offline-cancel").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(area, cancelled.get())
        assertEquals(1, cancelCount.get())
        assertEquals("cancel must not delete the area", 0, deleteCount.get())
    }

    @Test
    fun inactiveRegionHasNoCancelAction() {
        showCard(regions = listOf(ReconciledRegion(area, NativeRegionState.PARTIAL)))

        compose.onNodeWithTag("offline-cancel").assertDoesNotExist()
    }

    @Test
    fun providerBlockMessageStaysIndependentOfPerAreaCancel() {
        val progress = MutableStateFlow<OfflineMaps.Progress?>(
            OfflineMaps.Progress("istanbul", 0f, block = OfflineBlock.ProviderUnverified),
        )
        showCard(
            regions = listOf(ReconciledRegion(area, NativeRegionState.DOWNLOADING)),
            progressState = progress,
        )

        compose.onNodeWithText(
            context.getString(R.string.offline_provider_blocked, OfflinePlan.MAX_CONCURRENT_REQUESTS),
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("offline-cancel").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun doubleConfirmClickCallsDeleteExactlyOnce() {
        val deleteCount = AtomicInteger()
        showCard(deleteCount = deleteCount)
        compose.onNodeWithTag("offline-delete").performScrollTo().performClick()
        compose.waitForIdle()

        val confirm = compose.onNodeWithTag("offline-delete-confirm")
        confirm.performScrollTo()
        compose.mainClock.autoAdvance = false
        try {
            confirm.performClick()
            confirm.performClick()
            compose.mainClock.advanceTimeBy(256)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()

        assertEquals(1, deleteCount.get())
    }

    @Test
    fun removingAreaWhileConfirmationOpenClearsStaleDialog() {
        val areasState = MutableStateFlow(listOf(area))
        showCard(areasState = areasState)
        compose.onNodeWithTag("offline-delete").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.offline_delete_confirm_text))
            .performScrollTo().assertIsDisplayed()

        areasState.value = emptyList()
        compose.waitForIdle()

        compose.onNodeWithText(context.getString(R.string.offline_delete_confirm_text)).assertDoesNotExist()
    }

    @Test
    fun loadFailureShowsErrorWithoutClaimingNativeComplete() {
        showCard(regions = emptyList(), reconcileError = "prefs read failed")

        compose.onNodeWithText(context.getString(R.string.offline_native_error, "prefs read failed"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.offline_state_native_complete)).assertDoesNotExist()
    }

    @Test
    fun nativeAndActionErrorsRemainVisibleAtFont2() {
        showCard(reconcileError = "list failed", actionError = "disk full")

        compose.onNodeWithText(context.getString(R.string.offline_native_error, "list failed"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.offline_action_error, "disk full"))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.offline_state_partial))
            .performScrollTo().assertIsDisplayed()
    }
}
