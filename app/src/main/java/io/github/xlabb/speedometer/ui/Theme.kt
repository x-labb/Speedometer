package io.github.xlabb.speedometer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The app is always dark. A speedometer lives on a windscreen mount: a dark face
// with a few very bright elements is the readable choice at night and does not
// wash out in daylight the way a light theme does.
val Ink = Color(0xFF07080B)
val Panel = Color(0xFF13161C)
val Track = Color(0xFF1F242D)

val Live = Color(0xFF3DDC97)
val Weak = Color(0xFFF5B841)
val Lost = Color(0xFFE5484D)

val TextPrimary = Color(0xFFF4F6FA)
val TextMuted = Color(0xFF8A93A3)

private val Scheme = darkColorScheme(
    primary = Live,
    onPrimary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = Track,
    onSurfaceVariant = TextMuted,
    error = Lost
)

@Composable
fun SpeedometerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Typography(),
        content = content
    )
}
