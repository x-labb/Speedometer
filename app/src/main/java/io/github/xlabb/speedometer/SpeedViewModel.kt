package io.github.xlabb.speedometer

import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SpeedViewModel(app: Application) : AndroidViewModel(app) {

    private val engine = LocationEngine(app)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SpeedUiState(unit = loadUnit()))
    val state: StateFlow<SpeedUiState> = _state.asStateFlow()

    /** Previous accepted fix, used for distance integration and the speed fallback. */
    private var lastFix: Location? = null

    /** Monotonic timestamp of the last accepted fix; 0 when we have never had one. */
    private var lastFixNanos: Long = 0L

    private var collectJob: Job? = null
    private var watchdogJob: Job? = null

    // ---------------------------------------------------------------- lifecycle

    /**
     * Begin listening. Safe to call repeatedly — a second call while already running
     * is a no-op. Must only be called once ACCESS_FINE_LOCATION has been granted.
     */
    fun start() {
        if (collectJob?.isActive == true) return

        if (!engine.hasGpsProvider()) {
            _state.update { it.copy(gpsState = GpsState.LOCATION_DISABLED) }
            return
        }
        if (!engine.isLocationEnabled()) {
            _state.update { it.copy(gpsState = GpsState.LOCATION_DISABLED) }
            return
        }

        _state.update {
            it.copy(
                gpsState = if (lastFixNanos == 0L) GpsState.ACQUIRING else it.gpsState,
                permanentlyDenied = false,
                coarseOnly = false
            )
        }

        collectJob = viewModelScope.launch {
            engine.events()
                .catch {
                    // Permission revoked underneath us, or no GPS hardware.
                    _state.update { s -> s.copy(gpsState = GpsState.PERMISSION_REQUIRED) }
                }
                .collect { event ->
                    when (event) {
                        is LocationEvent.Fix -> onFix(event.location)
                        is LocationEvent.Satellites -> _state.update {
                            it.copy(
                                satellitesUsed = event.used,
                                satellitesVisible = event.visible
                            )
                        }
                        LocationEvent.ProviderDisabled -> _state.update {
                            it.copy(gpsState = GpsState.LOCATION_DISABLED)
                        }
                        LocationEvent.ProviderEnabled -> _state.update {
                            it.copy(gpsState = GpsState.ACQUIRING)
                        }
                    }
                }
        }
        startWatchdog()
    }

    /**
     * Stop listening. Called when the screen is no longer visible so we are not
     * burning the battery on a 1 Hz GPS lock in the background.
     */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
        watchdogJob?.cancel()
        watchdogJob = null

        // Drop the reference fix. Otherwise, resuming after ten minutes in a pocket
        // would integrate one enormous bogus "step" into the trip distance.
        lastFix = null
        lastFixNanos = 0L
        _state.update { it.copy(speedMps = 0f, satellitesUsed = null, satellitesVisible = null) }
    }

    /**
     * Watches for updates simply drying up. Without this, driving into a tunnel
     * would leave the last speed frozen on screen looking perfectly authoritative.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val last = lastFixNanos
                if (last == 0L) continue
                val ageMs = (SystemClock.elapsedRealtimeNanos() - last) / 1_000_000L
                if (ageMs > STALE_FIX_MS) {
                    _state.update {
                        if (it.gpsState == GpsState.LIVE || it.gpsState == GpsState.WEAK) {
                            it.copy(gpsState = GpsState.LOST, speedMps = 0f)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- fix handling

    private fun onFix(loc: Location) {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val ageMs = (nowNanos - loc.elapsedRealtimeNanos) / 1_000_000L

        // elapsedRealtimeNanos is monotonic, unlike Location.getTime() which follows
        // the wall clock and can jump when the network updates the time.
        if (ageMs > STALE_FIX_MS) return

        val prev = lastFix
        val hasAccuracy = loc.hasAccuracy()
        val accuracyM = if (hasAccuracy) loc.accuracy else Float.MAX_VALUE
        val speedAccuracy =
            if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else null

        val rawSpeed = if (loc.hasSpeed()) loc.speed else derivedSpeedMps(prev, loc)

        if (rawSpeed == null || rawSpeed.isNaN() || rawSpeed < 0f || rawSpeed > MAX_PLAUSIBLE_MPS) {
            // Either no speed at all, or a physically impossible one. Keep whatever is
            // on screen and make sure it cannot pollute max / average / distance,
            // but still advance the reference fix.
            lastFix = loc
            lastFixNanos = nowNanos
            return
        }

        // Parked, a GPS receiver will happily report a wandering 1-4 km/h forever.
        val moving = rawSpeed >= STANDSTILL_MPS
        val displaySpeed = if (moving) rawSpeed else 0f

        val trustworthy = accuracyM <= GOOD_ACCURACY_M &&
                (speedAccuracy == null || speedAccuracy <= MAX_SPEED_ACCURACY_MPS)

        _state.update { s ->
            var distance = s.distanceMetres
            var movingMs = s.movingMillis

            if (prev != null && moving && trustworthy) {
                val dtMs = (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000L
                if (dtMs in 1..MAX_INTEGRATION_GAP_MS) {
                    val step = prev.distanceTo(loc).toDouble()
                    // A jump much larger than the reported speed allows is a fix
                    // discontinuity (a re-lock after a tunnel), not distance travelled.
                    val plausibleStep = rawSpeed * (dtMs / 1000.0) * 2.0 + 10.0
                    if (step <= plausibleStep) {
                        distance += step
                        movingMs += dtMs
                    }
                }
            }

            s.copy(
                gpsState = if (accuracyM > WEAK_ACCURACY_M) GpsState.WEAK else GpsState.LIVE,
                speedMps = displaySpeed,
                maxSpeedMps = if (trustworthy && displaySpeed > s.maxSpeedMps) {
                    displaySpeed
                } else {
                    s.maxSpeedMps
                },
                distanceMetres = distance,
                movingMillis = movingMs,
                horizontalAccuracyM = if (hasAccuracy) accuracyM else null,
                speedAccuracyMps = speedAccuracy,
                isMock = isMock(loc)
            )
        }

        lastFix = loc
        lastFixNanos = nowNanos
    }

    /** Fallback when a fix carries no Doppler speed: difference successive positions. */
    private fun derivedSpeedMps(prev: Location?, current: Location): Float? {
        if (prev == null) return null
        val dtSec = (current.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
        if (dtSec < 0.2) return null
        return (prev.distanceTo(current) / dtSec).toFloat()
    }

    private fun isMock(loc: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }

    // ----------------------------------------------------------------- commands

    fun resetTrip() {
        _state.update {
            it.copy(maxSpeedMps = 0f, distanceMetres = 0.0, movingMillis = 0L)
        }
    }

    fun toggleUnit() {
        _state.update { s ->
            val next = s.unit.next()
            prefs.edit { putString(KEY_UNIT, next.name) }
            s.copy(unit = next)
        }
    }

    fun onPermissionDenied(coarseOnly: Boolean, permanently: Boolean) {
        stop()
        _state.update {
            it.copy(
                gpsState = GpsState.PERMISSION_REQUIRED,
                coarseOnly = coarseOnly,
                permanentlyDenied = permanently
            )
        }
    }

    fun onLocationDisabled() {
        stop()
        _state.update { it.copy(gpsState = GpsState.LOCATION_DISABLED) }
    }

    fun hasAskedForPermission(): Boolean = prefs.getBoolean(KEY_ASKED, false)

    fun markAskedForPermission() {
        prefs.edit { putBoolean(KEY_ASKED, true) }
    }

    private fun loadUnit(): SpeedUnit =
        runCatching { SpeedUnit.valueOf(prefs.getString(KEY_UNIT, null) ?: "") }
            .getOrDefault(SpeedUnit.KMH)

    override fun onCleared() {
        super.onCleared()
        stop()
    }

    companion object {
        private const val PREFS = "speedometer"
        private const val KEY_UNIT = "unit"
        private const val KEY_ASKED = "asked_permission"

        /** Below this, display a hard zero. 1.0 m/s is 3.6 km/h. */
        const val STANDSTILL_MPS = 1.0f

        /** 120 m/s is 432 km/h. Anything faster is a receiver glitch. */
        const val MAX_PLAUSIBLE_MPS = 120f

        /** Horizontal accuracy required before a fix may update max/avg/distance. */
        const val GOOD_ACCURACY_M = 25f

        /** Above this we still show speed, but flag the signal as weak. */
        const val WEAK_ACCURACY_M = 50f

        /** Reject a fix whose own speed uncertainty exceeds this (m/s). */
        const val MAX_SPEED_ACCURACY_MPS = 2f

        /** No update for this long means the signal is gone, not that we stopped. */
        const val STALE_FIX_MS = 5_000L

        /** Never integrate distance across a gap longer than this. */
        const val MAX_INTEGRATION_GAP_MS = 10_000L
    }
}
