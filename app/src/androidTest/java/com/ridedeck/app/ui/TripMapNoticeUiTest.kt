package com.ridedeck.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import org.junit.Rule
import org.junit.Test

/** The base-map failure notice is local state; no provider tiles are requested here. */
class TripMapNoticeUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tileFailureAfterStyleLoadKeepsTheOnlineStyleAndExplainsIt() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(300.dp, 240.dp)) {
                    TripMapNotice(localOnly = false, tilesUnavailable = true)
                }
            }
        }
        compose.onNodeWithTag("trip-map-notice").assertExists()
        compose.onNodeWithText(context.getString(R.string.trip_map_tiles_unavailable)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.trip_map_local_only)).assertCountEquals(0)
    }

    @Test
    fun healthyTilesShowNoNotice() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(300.dp, 240.dp)) {
                    TripMapNotice(localOnly = false, tilesUnavailable = false)
                }
            }
        }
        compose.onAllNodesWithTag("trip-map-notice").assertCountEquals(0)
    }

    @Test
    fun aLocalStyleDoesNotReuseTheTileFailureWording() {
        compose.setContent {
            MotoTheme {
                Box(Modifier.size(300.dp, 240.dp)) {
                    TripMapNotice(localOnly = true, tilesUnavailable = true)
                }
            }
        }
        compose.onAllNodesWithTag("trip-map-notice").assertCountEquals(1)
        compose.onNodeWithText(context.getString(R.string.trip_map_local_only)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.trip_map_tiles_unavailable)).assertCountEquals(0)
    }
}
