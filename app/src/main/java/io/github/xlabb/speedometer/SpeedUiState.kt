package io.github.xlabb.speedometer

/**
 * What the app is currently able to tell the user. The distinction between
 * ACQUIRING, WEAK and LOST matters a lot in practice: showing a confident "0"
 * when the receiver has simply lost the sky is the single most misleading thing
 * a speedometer can do.
 */
enum class GpsState {
    /** Location permission has not been granted (or only coarse was granted). */
    PERMISSION_REQUIRED,

    /** Permission is fine, but Location is switched off system-wide. */
    LOCATION_DISABLED,

    /** Listening, but no usable fix has arrived yet. Cold start is 5-30s outdoors. */
    ACQUIRING,

    /** Fresh fixes with usable accuracy. */
    LIVE,

    /** Fresh fixes, but horizontal accuracy is poor (tree cover, tunnel, urban canyon). */
    WEAK,

    /** We had a fix and then updates stopped arriving. Speed is no longer meaningful. */
    LOST
}

enum class SpeedUnit(
    val speedLabel: String,
    val distanceLabel: String,
    /** Multiply metres-per-second by this to get the displayed speed. */
    val speedFromMps: Float,
    /** Multiply metres by this to get the displayed distance. */
    val distanceFromMetres: Double
) {
    KMH("km/h", "km", 3.6f, 0.001),
    MPH("mph", "mi", 2.2369363f, 1.0 / 1609.344);

    fun next(): SpeedUnit = if (this == KMH) MPH else KMH
}

data class SpeedUiState(
    val gpsState: GpsState = GpsState.PERMISSION_REQUIRED,
    val unit: SpeedUnit = SpeedUnit.KMH,

    /** Current speed in m/s. Already clamped to zero below the standstill threshold. */
    val speedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val distanceMetres: Double = 0.0,
    /** Time actually spent moving, used for a moving average rather than a trip average. */
    val movingMillis: Long = 0L,

    val horizontalAccuracyM: Float? = null,
    val speedAccuracyMps: Float? = null,
    val satellitesUsed: Int? = null,
    val satellitesVisible: Int? = null,

    /** Set when the fix came from a mock provider — worth surfacing on a speedometer. */
    val isMock: Boolean = false,

    /** True when the user granted only "Approximate" location, which cannot give speed. */
    val coarseOnly: Boolean = false,

    /** True once the user has denied permission with "Don't ask again". */
    val permanentlyDenied: Boolean = false
) {
    /** Moving average in m/s: distance travelled divided by time spent actually moving. */
    val averageSpeedMps: Float
        get() = if (movingMillis <= 0L) 0f
        else (distanceMetres / (movingMillis / 1000.0)).toFloat()

    val isTripEmpty: Boolean
        get() = distanceMetres <= 0.0 && maxSpeedMps <= 0f
}
