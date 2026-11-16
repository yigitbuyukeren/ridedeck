package com.ridedeck.app.ui

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.ridedeck.app.R
import com.ridedeck.app.data.CallPhase
import com.ridedeck.app.data.CallState
import com.ridedeck.app.data.FuelStation
import com.ridedeck.app.data.FuelUi
import com.ridedeck.app.data.GeoPoint
import com.ridedeck.app.data.Maneuver
import com.ridedeck.app.data.MediaRepository
import com.ridedeck.app.data.MediaState
import com.ridedeck.app.data.NavUi
import com.ridedeck.app.data.Place
import com.ridedeck.app.data.Route
import com.ridedeck.app.data.RouteOptions
import java.io.File
import java.io.FileOutputStream
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * QA43 fixture capture candidate. It renders real current-main composables with fake state and a
 * test-local static map backdrop. Nothing here is a navigation, call, media or permission journey:
 * screenshots are evidence for the named UI state only and are visibly labelled FAKE_UI.
 *
 * ID 26 (empty trips) is deliberately not captured. TripsScreen requires a concrete RideTracking,
 * which constructs a real TripStore and starts a process-lifetime coroutine in its constructor;
 * neither can be replaced or closed test-only, so there is no honest way to prove the real user
 * trips database and scope are untouched without an injection/close hook in main code.
 */
class Qa43FixtureCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = requireNotNull(context.getExternalFilesDir("qa43fixture")).apply { mkdirs() }

    private enum class Fixture(val file: String, val day: Boolean = true) {
        MEDIA_DENIED("16-muzik-izin.png"),
        CALL_RINGING("23-gelen-arama.png"),
        CALL_ACTIVE("24-aktif-arama.png"),
        PERMISSION("43-ilk-izin.png"),
        SEARCH_EMPTY("04-navigasyon-arama.png"),
        SEARCH_KEYBOARD("05-rota-arama-klavye.png"),
        SEARCH_TEXT("06-rota-arama-metin.png"),
        SEARCH_RESULTS("07-arama-sonuclari.png"),
        ROUTE_NIGHT("08-rota-onizleme-gece.png", day = false),
        ROUTE_DAY("09-rota-onizleme-gunduz.png"),
        GUIDANCE_NIGHT("11-aktif-navigasyon-gece.png", day = false),
        GUIDANCE_DAY("12-aktif-navigasyon-gunduz.png"),
        FUEL_ALONG("13-benzinlikler-rota.png"),
        FUEL_NEAR("14-benzinlikler-yakin.png"),
        ARRIVED("15-hedefe-ulastiniz.png"),
    }

    @Before
    fun landscape() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitForIdle()
    }

    private fun save(file: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(output, file)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun FixtureFrame(day: Boolean, content: @Composable () -> Unit) {
        MotoTheme(isDay = day) {
            Box(Modifier.fillMaxSize()) {
                content()
                Text(
                    text = "FAKE_UI — fixture only; not a native/system journey",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    @Test
    fun captureMediaCallPermissionFixtures() {
        var fixture by mutableStateOf(Fixture.MEDIA_DENIED)
        val media = MediaRepository(context)
        val callStarted = System.currentTimeMillis() - 60_000

        compose.setContent {
            FixtureFrame(day = fixture.day) {
                when (fixture) {
                    Fixture.MEDIA_DENIED -> Box(Modifier.fillMaxSize()) {
                        NowPlayingScreen(
                            state = MediaState(hasAccess = false),
                            media = media,
                            onGrantAccess = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Fixture.CALL_RINGING -> CallScreen(
                        CallState(CallPhase.RINGING), "Test Kişi", null, NavUi(), {}, {},
                    )
                    Fixture.CALL_ACTIVE -> CallScreen(
                        CallState(CallPhase.ACTIVE, startedAtMs = callStarted, managed = true),
                        "Test Kişi", null, NavUi(), {}, {},
                    )
                    Fixture.PERMISSION -> PermissionIntro(
                        PermissionRow(
                            title = context.getString(R.string.perm_location),
                            description = context.getString(R.string.perm_intro_location_why),
                            granted = false,
                            actionLabel = context.getString(R.string.action_allow),
                            onAction = {},
                        ),
                        onClose = {},
                    )
                    else -> Box(Modifier.fillMaxSize())
                }
            }
        }

        save(Fixture.MEDIA_DENIED.file)
        compose.runOnIdle { fixture = Fixture.CALL_RINGING }; save(Fixture.CALL_RINGING.file)
        compose.runOnIdle { fixture = Fixture.CALL_ACTIVE }; save(Fixture.CALL_ACTIVE.file)
        compose.onNodeWithText(context.getString(R.string.call_keys)).performClick()
        save("25-gorusme-tus-takimi.png")
        compose.runOnIdle { fixture = Fixture.PERMISSION }; save(Fixture.PERMISSION.file)
    }

    @Test
    fun captureNavigationPanelFixturesWithFakeStaticMap() {
        var fixture by mutableStateOf(Fixture.SEARCH_EMPTY)
        val map = fakeMap()

        compose.setContent {
            FixtureFrame(day = fixture.day) {
                FakeMapScaffold {
                    when (fixture) {
                        Fixture.SEARCH_EMPTY -> SearchPanel(
                            ui = NavUi(), locked = false, typing = false, onFocus = {}, onSearch = {},
                            places = emptyList(), onSelect = {}, onSave = {}, onRemovePlace = {},
                            recents = emptyList(), onClearRecents = {}, onSuggestion = {},
                        )
                        Fixture.SEARCH_KEYBOARD -> Column {
                            SearchPanel(
                                ui = NavUi(query = "Kad"), locked = false, typing = true, onFocus = {}, onSearch = {},
                                places = emptyList(), onSelect = {}, onSave = {}, onRemovePlace = {},
                                recents = emptyList(), onClearRecents = {}, onSuggestion = {},
                            )
                            SearchKeyboard(query = "Kad", onQuery = {}, onSearch = {}, onClose = {})
                        }
                        Fixture.SEARCH_TEXT -> SearchPanel(
                            ui = NavUi(query = "Kadıköy"), locked = false, typing = false, onFocus = {}, onSearch = {},
                            places = emptyList(), onSelect = {}, onSave = {}, onRemovePlace = {},
                            recents = emptyList(), onClearRecents = {}, onSuggestion = {},
                        )
                        Fixture.SEARCH_RESULTS -> SearchPanel(
                            ui = NavUi(query = "Kadıköy", results = map.places),
                            locked = false, typing = false, onFocus = {}, onSearch = {},
                            places = emptyList(), onSelect = {}, onSave = {}, onRemovePlace = {},
                            recents = emptyList(), onClearRecents = {}, onSuggestion = {},
                            here = GeoPoint(41.0, 29.0),
                        )
                        Fixture.ROUTE_NIGHT, Fixture.ROUTE_DAY -> RouteCard(
                            ui = NavUi(destination = map.dest, route = map.route, choices = listOf(map.route)),
                            options = RouteOptions(), onOptions = {}, canOpenExternal = false, saved = false,
                            onSave = {}, onStart = {}, onCancel = {}, onChoose = {}, onAddStop = {}, onRemoveStop = {},
                        )
                        Fixture.GUIDANCE_NIGHT, Fixture.GUIDANCE_DAY -> ManeuverBanner(
                            NavUi(
                                destination = map.dest, route = map.route, navigating = true,
                                distToNextM = 140, remainingM = 270, remainingS = 60,
                            )
                        )
                        Fixture.FUEL_ALONG -> FuelPanel(
                            FuelUi(visible = true, alongRoute = true, stations = map.fuel),
                            guiding = true, onGo = {}, onAddStop = {}, onRetry = {}, onClose = {},
                        )
                        Fixture.FUEL_NEAR -> FuelPanel(
                            FuelUi(visible = true, alongRoute = false, stations = map.fuel),
                            guiding = false, onGo = {}, onAddStop = {}, onRetry = {}, onClose = {},
                        )
                        Fixture.ARRIVED -> ArrivedCard(null, onContinue = {}, onDone = {})
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }

        for (next in listOf(
            Fixture.SEARCH_EMPTY, Fixture.SEARCH_KEYBOARD, Fixture.SEARCH_TEXT, Fixture.SEARCH_RESULTS,
            Fixture.ROUTE_NIGHT, Fixture.ROUTE_DAY, Fixture.GUIDANCE_NIGHT, Fixture.GUIDANCE_DAY,
            Fixture.FUEL_ALONG, Fixture.FUEL_NEAR, Fixture.ARRIVED,
        )) {
            compose.runOnIdle { fixture = next }
            save(next.file)
        }
    }

    private fun fakeMap(): FakeMap = FakeMap(
        dest = Place("Kadıköy", "İstanbul", GeoPoint(41.010, 29.020)),
        route = Route(
            shape = listOf(
                GeoPoint(41.000, 29.000), GeoPoint(41.002, 29.005),
                GeoPoint(41.005, 29.010), GeoPoint(41.008, 29.015), GeoPoint(41.010, 29.020),
            ),
            maneuvers = listOf(
                Maneuver(1, "Kuzeye git", "Kuzeye git", "Kuzeye git", 400.0, 0, timeS = 30.0),
                Maneuver(10, "Sağa dön", "Sağa dön", "Şimdi sağa dön", 300.0, 2, timeS = 30.0),
                Maneuver(4, "Varış", "Varış", "Varış", 0.0, 4, timeS = 0.0),
            ),
            distanceM = 700.0,
            durationS = 60.0,
        ),
        places = listOf(
            Place("Kadıköy", "İstanbul", GeoPoint(41.010, 29.020)),
            Place("Moda", "İstanbul", GeoPoint(41.012, 29.025)),
        ),
        fuel = listOf(
            FuelStation(Place("Petrol", "Rota üzerinde", GeoPoint(41.006, 29.012)), 800),
            FuelStation(Place("Benzin", "Yakında", GeoPoint(41.004, 29.008)), 1_200),
        ),
    )

    private data class FakeMap(
        val dest: Place,
        val route: Route,
        val places: List<Place>,
        val fuel: List<FuelStation>,
    )

    @Composable
    private fun FakeMapScaffold(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(Color(0xFF101820))) {
            Canvas(Modifier.fillMaxSize()) {
                val step = size.width / 12f
                var x = 0f
                while (x <= size.width) {
                    drawLine(Color(0x334FC3F7), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(Color(0x334FC3F7), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f)
                    y += step
                }
                drawLine(
                    Color(0xFF0A84FF),
                    androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height * 0.65f),
                    androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height * 0.35f),
                    10f,
                )
            }
            Box(Modifier.align(Alignment.CenterStart).padding(20.dp).width(640.dp)) { content() }
        }
    }
}
