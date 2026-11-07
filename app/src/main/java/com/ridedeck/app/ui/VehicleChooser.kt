package com.ridedeck.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.TwoWheeler
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridedeck.app.R
import com.ridedeck.app.data.VehicleProfile

/**
 * First-launch question: motorcycle, car or bicycle. Big cards for gloves; side by side on a wide screen,
 * stacked on an upright one. A tap only selects a card; "Continue" saves it, so a wrong tap is harmless.
 * Covers everything, including the settings page that opens without permissions.
 */
@Composable
fun VehicleChooser(onChoose: (VehicleProfile) -> Unit) {
    val palette = LocalMotoPalette.current
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    var selected by remember { mutableStateOf<VehicleProfile?>(null) }
    Box(modifier = Modifier.fillMaxSize().background(palette.wallpaper).clickable(enabled = false) {}) {
        Column(
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().symmetricSafePadding()
                .padding(horizontal = 32.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Logo and name sit beside the question so the whole page fits a ~360dp landscape phone.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(painterResource(R.drawable.ic_logo), null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
                Column {
                    Text(stringResource(R.string.app_name), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.textSecondary)
                    Text(stringResource(R.string.vehicle_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = palette.text)
                }
            }
            Text(stringResource(R.string.vehicle_sub), fontSize = 14.sp, color = palette.textSecondary, textAlign = TextAlign.Center)
            val cards = listOf(
                Triple(VehicleProfile.MOTORCYCLE, Icons.Rounded.TwoWheeler, R.string.vehicle_moto to R.string.vehicle_moto_desc),
                Triple(VehicleProfile.CAR, Icons.Rounded.DirectionsCar, R.string.vehicle_car to R.string.vehicle_car_desc),
                Triple(VehicleProfile.BICYCLE, Icons.Rounded.PedalBike, R.string.vehicle_bike to R.string.vehicle_bike_desc),
            )
            if (portrait) {
                cards.forEach { (profile, icon, text) ->
                    VehicleCard(icon, text.first, text.second, selected == profile, { selected = profile }, Modifier.fillMaxWidth())
                }
            } else {
                Row(modifier = Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    cards.forEach { (profile, icon, text) ->
                        VehicleCard(icon, text.first, text.second, selected == profile, { selected = profile }, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            // The row keeps its height before a card is picked, so nothing jumps when the button appears.
            Box(modifier = Modifier.height(64.dp), contentAlignment = Alignment.Center) {
                selected?.let { profile ->
                    PillButton(stringResource(R.string.vehicle_continue), MotoColors.Blue, { onChoose(profile) }, Modifier.widthIn(min = 260.dp), minHeight = 64.dp)
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(icon: ImageVector, title: Int, desc: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val palette = LocalMotoPalette.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier.clip(shape)
            .background(if (selected) MotoColors.Blue.copy(alpha = 0.22f) else palette.cardHigh)
            .border(3.dp, if (selected) MotoColors.Blue else Color.Transparent, shape)
            .hapticClickable(haptic = RideHaptic.Select, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(MotoColors.Blue).padding(10.dp))
        Text(stringResource(title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Text(stringResource(desc), fontSize = 14.sp, color = palette.textSecondary, textAlign = TextAlign.Center)
    }
}
