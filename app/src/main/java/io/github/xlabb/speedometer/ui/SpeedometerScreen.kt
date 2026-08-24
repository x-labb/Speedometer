package io.github.xlabb.speedometer.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.DisposableEffect
import io.github.xlabb.speedometer.GpsState
import io.github.xlabb.speedometer.R
import io.github.xlabb.speedometer.SpeedUiState
import io.github.xlabb.speedometer.SpeedUnit
import io.github.xlabb.speedometer.SpeedViewModel
import io.github.xlabb.speedometer.formatAccuracy
import io.github.xlabb.speedometer.formatDistance
import io.github.xlabb.speedometer.formatSpeed

@Composable
fun SpeedometerRoute(viewModel: SpeedViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        viewModel.markAskedForPermission()
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine) {
            viewModel.start()
        } else {
            viewModel.onPermissionDenied(
                coarseOnly = coarse,
                permanently = !context.canAskForFineLocation()
            )
        }
    }

    // Everything is re-evaluated on every foreground transition. The user can leave
    // for system settings and come back having granted the permission, revoked it,
    // or flipped the Location master switch — all three must be picked up here.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                        viewModel.start()
                    } else {
                        viewModel.onPermissionDenied(
                            coarseOnly = context.hasPermission(
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            ),
                            permanently = viewModel.hasAskedForPermission() &&
                                    !context.canAskForFineLocation()
                        )
                    }
                }

                Lifecycle.Event.ON_STOP -> viewModel.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SpeedometerScreen(
        state = state,
        onGrantPermission = {
            if (state.permanentlyDenied) {
                context.openAppSettings()
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        },
        onOpenLocationSettings = { context.openLocationSettings() },
        onToggleUnit = viewModel::toggleUnit,
        onResetTrip = viewModel::resetTrip
    )
}

@Composable
fun SpeedometerScreen(
    state: SpeedUiState,
    onGrantPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onToggleUnit: () -> Unit,
    onResetTrip: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(16.dp)
        ) {
            when (state.gpsState) {
                GpsState.PERMISSION_REQUIRED -> PermissionGate(state, onGrantPermission)
                GpsState.LOCATION_DISABLED -> LocationGate(onOpenLocationSettings)
                else -> LiveContent(state, onToggleUnit, onResetTrip)
            }
        }
    }
}

// ------------------------------------------------------------------ live content

@Composable
private fun LiveContent(
    state: SpeedUiState,
    onToggleUnit: () -> Unit,
    onResetTrip: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val dialSize = if (landscape) maxHeight * 0.92f else maxWidth * 0.92f

        if (landscape) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                SpeedDial(state, modifier = Modifier.size(dialSize))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusRow(state)
                    TripStats(state)
                    Actions(state, onToggleUnit, onResetTrip)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                StatusRow(state)
                SpeedDial(state, modifier = Modifier.size(dialSize))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TripStats(state)
                    Actions(state, onToggleUnit, onResetTrip)
                }
            }
        }
    }
}

private fun stateColor(gpsState: GpsState): Color = when (gpsState) {
    GpsState.LIVE -> Live
    GpsState.WEAK -> Weak
    GpsState.LOST -> Lost
    else -> TextMuted
}

@Composable
private fun SpeedDial(state: SpeedUiState, modifier: Modifier = Modifier) {
    val hasReading = state.gpsState == GpsState.LIVE || state.gpsState == GpsState.WEAK
    val displayed = state.speedMps * state.unit.speedFromMps
    val fullScale = if (state.unit == SpeedUnit.KMH) 180f else 110f
    val colour = stateColor(state.gpsState)

    val sweep by animateFloatAsState(
        targetValue = if (hasReading) (displayed / fullScale).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "dialSweep"
    )

    BoxWithConstraints(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        // Sized to the dial rather than to the user's font-scale preference. This
        // number is really a graphic element; letting a 2x accessibility font scale
        // double it would just clip it against the arc. Divide the scale back out.
        val fontScale = LocalDensity.current.fontScale
        val numberSp = (maxWidth.value * 0.30f / fontScale).coerceIn(40f, 190f).sp
        val unitSp = (maxWidth.value * 0.075f / fontScale).coerceIn(11f, 34f).sp

        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = size.minDimension * 0.072f
            val diameter = size.minDimension - strokeWidth
            val arcSize = Size(diameter, diameter)
            val topLeft = Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f
            )
            drawArc(
                color = Track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (sweep > 0f) {
                drawArc(
                    color = colour,
                    startAngle = 135f,
                    sweepAngle = 270f * sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (hasReading) formatSpeed(state.speedMps, state.unit) else "––",
                fontSize = numberSp,
                fontWeight = FontWeight.Light,
                color = if (hasReading) TextPrimary else TextMuted,
                maxLines = 1
            )
            Text(
                text = state.unit.speedLabel,
                fontSize = unitSp,
                color = TextMuted,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StatusRow(state: SpeedUiState) {
    val label = when (state.gpsState) {
        GpsState.ACQUIRING -> stringResource(R.string.status_acquiring)
        GpsState.LIVE -> stringResource(R.string.status_live)
        GpsState.WEAK -> stringResource(R.string.status_weak)
        GpsState.LOST -> stringResource(R.string.status_lost)
        else -> ""
    }
    val colour = stateColor(state.gpsState)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(colour)
            )
            Text(text = label, color = colour, fontSize = 14.sp, fontWeight = FontWeight.Medium)

            formatAccuracy(state.horizontalAccuracyM)?.let {
                Text(text = it, color = TextMuted, fontSize = 14.sp)
            }
            if (state.satellitesUsed != null && state.satellitesVisible != null) {
                Text(
                    text = stringResource(
                        R.string.satellites_fmt,
                        state.satellitesUsed,
                        state.satellitesVisible
                    ),
                    color = TextMuted,
                    fontSize = 14.sp
                )
            }
        }

        if (state.isMock) {
            Text(
                text = stringResource(R.string.mock_warning),
                color = Weak,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun TripStats(state: SpeedUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatCell(
            label = stringResource(R.string.stat_max),
            value = formatSpeed(state.maxSpeedMps, state.unit),
            unit = state.unit.speedLabel,
            modifier = Modifier.weight(1f)
        )
        StatCell(
            label = stringResource(R.string.stat_avg),
            value = formatSpeed(state.averageSpeedMps, state.unit),
            unit = state.unit.speedLabel,
            modifier = Modifier.weight(1f)
        )
        StatCell(
            label = stringResource(R.string.stat_distance),
            value = formatDistance(state.distanceMetres, state.unit),
            unit = state.unit.distanceLabel,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Panel)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1
        )
        Text(text = unit, color = TextMuted, fontSize = 12.sp)
    }
}

@Composable
private fun Actions(
    state: SpeedUiState,
    onToggleUnit: () -> Unit,
    onResetTrip: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onToggleUnit,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_switch_to, state.unit.next().speedLabel))
        }
        OutlinedButton(
            onClick = onResetTrip,
            enabled = !state.isTripEmpty,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.action_reset))
        }
    }
}

// ------------------------------------------------------------------------ gates

@Composable
private fun PermissionGate(state: SpeedUiState, onGrant: () -> Unit) {
    val body = when {
        state.permanentlyDenied -> stringResource(R.string.permission_body_blocked)
        state.coarseOnly -> stringResource(R.string.permission_body_coarse)
        else -> stringResource(R.string.permission_body)
    }
    val button = if (state.permanentlyDenied) {
        stringResource(R.string.action_open_settings)
    } else {
        stringResource(R.string.action_grant)
    }
    MessageCard(
        title = stringResource(R.string.permission_title),
        body = body,
        buttonLabel = button,
        onClick = onGrant
    )
}

@Composable
private fun LocationGate(onOpenSettings: () -> Unit) {
    MessageCard(
        title = stringResource(R.string.location_off_title),
        body = stringResource(R.string.location_off_body),
        buttonLabel = stringResource(R.string.action_open_location_settings),
        onClick = onOpenSettings
    )
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(Panel)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = body,
                color = TextMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Live,
                    contentColor = Ink
                )
            ) {
                Text(buttonLabel, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ------------------------------------------------------------------- context ext

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/**
 * False once the user has ticked "Don't ask again" — at that point the system
 * silently denies any further request, so we must send them to app settings.
 */
private fun Context.canAskForFineLocation(): Boolean {
    val activity = findActivity() ?: return true
    return ActivityCompat.shouldShowRequestPermissionRationale(
        activity,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun Context.openLocationSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
