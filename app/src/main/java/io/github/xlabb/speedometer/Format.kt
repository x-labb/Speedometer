package io.github.xlabb.speedometer

import java.util.Locale
import kotlin.math.roundToInt

/** Speed is always shown as a whole number — sub-unit precision is noise at 1 Hz. */
fun formatSpeed(mps: Float, unit: SpeedUnit): String =
    (mps * unit.speedFromMps).roundToInt().coerceAtLeast(0).toString()

/**
 * Two decimals under 10 units, one above. Formatted with the device locale so a
 * French phone shows "1,25 km" rather than "1.25 km".
 */
fun formatDistance(metres: Double, unit: SpeedUnit): String {
    val value = metres * unit.distanceFromMetres
    val pattern = if (value < 10.0) "%.2f" else "%.1f"
    return String.format(Locale.getDefault(), pattern, value)
}

fun formatAccuracy(metres: Float?): String? =
    metres?.let { "±${it.roundToInt()} m" }
