package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.ConnectivityStatus
import com.ridedeck.app.data.RideReadiness
import com.ridedeck.app.data.RideReadinessAssessment
import com.ridedeck.app.data.RideReadinessInput
import com.ridedeck.app.data.TtsFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Fake readiness rows only: no permissions, radio, SMS, media session or network access. */
class ReadinessPanelUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readiness(): RideReadiness = RideReadinessAssessment.assess(
        RideReadinessInput(
            fineLocationGranted = false,
            coarseLocationGranted = true,
            gpsEnabled = false,
            hasFreshFix = false,
            internet = ConnectivityStatus.OFFLINE,
            offlineAreas = emptyList(),
            tts = TtsFacts(engineInitialized = false),
            notificationsGranted = false,
            mediaAccess = false,
            sosContactsConfigured = true,
            sosSmsGranted = true,
        )
    )

    @Test fun noGlobalReadyClaimAndSosNeverPromisesDelivery() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(640.dp, 360.dp)) {
                    ReadinessContent(readiness(), onClose = {}, onAction = {})
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.readiness_sub)).assertExists()
        compose.onNodeWithText(context.getString(R.string.readiness_detail_location_coarse)).assertExists()
        compose.onNodeWithText(context.getString(R.string.readiness_status_ready)).assertDoesNotExist()
        val sosDetail = context.getString(R.string.readiness_detail_sos_configured_unverified)
        compose.onNodeWithTag("readiness-list", useUnmergedTree = true).performScrollToNode(hasText(sosDetail))
        compose.onNodeWithText(sosDetail, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun converterFont2NarrowGeometryKeepsCloseAndPositiveList() {
        var action: ReadinessAction? = null
        var closed = false
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 2f }
        val density = Density(context.createConfigurationContext(configuration))
        compose.setContent {
            MotoTheme {
                CompositionLocalProvider(LocalDensity provides density) {
                    Box(Modifier.size(300.dp, 240.dp)) {
                        ReadinessContent(readiness(), onClose = { closed = true }, onAction = { action = it })
                    }
                }
            }
        }

        val close = compose.onNodeWithTag("readiness-close", useUnmergedTree = true)
            .assertIsDisplayed()
        val closeBounds = close.getUnclippedBoundsInRoot()
        assertTrue("close width ${closeBounds.right - closeBounds.left}", closeBounds.right - closeBounds.left >= 64.dp)
        assertTrue("close height ${closeBounds.bottom - closeBounds.top}", closeBounds.bottom - closeBounds.top >= 64.dp)

        val firstRow = compose.onNodeWithText(context.getString(R.string.readiness_kind_location), useUnmergedTree = true).assertIsDisplayed()
        val rowBounds = firstRow.getUnclippedBoundsInRoot()
        assertTrue("readiness list has no positive height", rowBounds.bottom - rowBounds.top > 0.dp)

        compose.onNodeWithText(context.getString(R.string.readiness_detail_location_coarse), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("readiness-action-PRECISE_LOCATION", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ReadinessAction.APP_DETAILS, action) }

        compose.onNodeWithTag("readiness-close", useUnmergedTree = true)
            .assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }
}
