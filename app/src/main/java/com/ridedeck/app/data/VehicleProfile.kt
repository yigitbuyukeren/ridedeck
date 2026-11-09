package com.ridedeck.app.data

/** RideDeck is motorcycle-only. The type stays so route, crash and gauge code share one profile. */
enum class VehicleProfile {
    MOTORCYCLE;

    val config: ProfileConfig get() = ProfileConfig.of(this)
}

/** What a rider might stop for. RideDeck only looks for fuel. */
enum class StopKind { FUEL }

/** One OSM tag filter, e.g. `amenity=fuel`, and the Nominatim search that finds the same kind of place. */
data class StopSource(val kind: StopKind, val key: String, val value: String, val nominatimQuery: String, val nominatimType: String)

/**
 * Everything the motorcycle profile needs in one place, so the rest of the app asks the profile
 * instead of branching on it. Pure data: unit tested without Android.
 */
data class ProfileConfig(
    /** Valhalla `costing` name; also the key of `costing_options`. */
    val costing: String,
    /** Speed gauge full-scale, km/h. */
    val gaugeMaxKmh: Float,
    /** Accelerometer magnitude (g) that counts as a crash impact. */
    val crashImpactG: Float,
    /** Speed above which the vehicle counts as "moving" for crash detection, km/h. */
    val crashMovingKmh: Int,
    /** A fall tips the phone over: the alarm also needs the orientation to have changed. */
    val crashNeedsTilt: Boolean,
    /** The stop button (⛽ on the map) looks for these. */
    val stops: List<StopSource>,
) {
    companion object {
        private val FUEL = StopSource(StopKind.FUEL, "amenity", "fuel", "fuel", "fuel")

        private val MOTORCYCLE = ProfileConfig(
            costing = "motorcycle", gaugeMaxKmh = 200f, crashImpactG = 4.0f, crashMovingKmh = 25, crashNeedsTilt = true,
            stops = listOf(FUEL),
        )

        fun of(profile: VehicleProfile): ProfileConfig = MOTORCYCLE
    }
}
