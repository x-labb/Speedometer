package io.github.xlabb.speedometer

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface LocationEvent {
    data class Fix(val location: Location) : LocationEvent
    data class Satellites(val used: Int, val visible: Int) : LocationEvent
    data object ProviderEnabled : LocationEvent
    data object ProviderDisabled : LocationEvent
}

/**
 * Thin wrapper over the platform LocationManager.
 *
 * We deliberately use the raw GPS_PROVIDER rather than the fused provider from Play
 * Services. For a speedometer that is the better source: on essentially every modern
 * chipset `Location.getSpeed()` is derived from the Doppler shift of the satellite
 * carrier signal, not from differencing successive positions, which makes it both
 * smoother and far more responsive. It also keeps the app free of any Google Play
 * Services dependency.
 */
class LocationEngine(context: Context) {

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)

    fun hasGpsProvider(): Boolean =
        manager.allProviders.contains(LocationManager.GPS_PROVIDER)

    /**
     * Emits fixes until the collector is cancelled. The caller is responsible for only
     * collecting this while ACCESS_FINE_LOCATION is held; a SecurityException is
     * surfaced by closing the flow rather than crashing.
     */
    @SuppressLint("MissingPermission")
    fun events(): Flow<LocationEvent> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(LocationEvent.Fix(location))
            }

            // These three carry default implementations only from API 30 onwards. We
            // compile against API 36 where they are defaults, but on an API 26-29
            // device omitting them would raise AbstractMethodError the moment the
            // system called one. They must stay explicitly overridden.
            @Deprecated("Deprecated in API 29, but abstract below API 30, so it must stay.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) {
                trySend(LocationEvent.ProviderEnabled)
            }

            override fun onProviderDisabled(provider: String) {
                trySend(LocationEvent.ProviderDisabled)
            }
        }

        val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                trySend(LocationEvent.Satellites(used, status.satelliteCount))
            }
        }

        val handler = Handler(Looper.getMainLooper())
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                0f,
                listener,
                Looper.getMainLooper()
            )
            manager.registerGnssStatusCallback(gnssCallback, handler)

            // Seed with the last known fix so a warm start shows something immediately.
            // The ViewModel rejects it if it turns out to be stale.
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                trySend(LocationEvent.Fix(it))
            }
        } catch (e: SecurityException) {
            close(e)
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            // Device genuinely has no GPS provider.
            close(e)
            return@callbackFlow
        }

        awaitClose {
            runCatching { manager.removeUpdates(listener) }
            runCatching { manager.unregisterGnssStatusCallback(gnssCallback) }
        }
    }

    companion object {
        /** Ask for a fix every second; 0 m displacement filter so we keep updating at rest. */
        const val MIN_INTERVAL_MS = 1000L
    }
}
