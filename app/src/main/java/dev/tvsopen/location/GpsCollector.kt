package dev.tvsopen.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import dev.tvsopen.log.Log

/**
 * Captures GPS fixes into the same log sink as the BLE frames.
 *
 * One line per fix, tagged GPS:
 *   <lat>,<lon>,<alt>,<speed>,<bearing>,<accuracy>,<utc_ms>,<elapsed_ns>
 *
 * Uses the platform LocationManager rather than
 * FusedLocationProviderClient so no Google Play Services dependency is
 * added to a project that currently has none. GPS_PROVIDER gives the raw
 * fixes this tool wants; NETWORK_PROVIDER is a fallback indoors.
 */
class GpsCollector(
    private val context: Context,
) : LocationListener {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun start() {
        val providers = buildList {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            Log.w(TAG, "no location provider enabled")
            return
        }
        for (p in providers) {
            locationManager.requestLocationUpdates(
                p,
                1000L,
                0f,
                this,
                Looper.getMainLooper(),
            )
            Log.i(TAG, "subscribed to $p")
        }
    }

    fun stop() {
        locationManager.removeUpdates(this)
        Log.i(TAG, "location updates removed")
    }

    override fun onLocationChanged(location: Location) {
        Log.i(
            TAG,
            "${location.latitude},${location.longitude}," +
                "${location.altitude},${location.speed},${location.bearing}," +
                "${location.accuracy},${location.time}," +
                System.currentTimeMillis(),
        )
    }

    @Deprecated("Required by the platform LocationListener interface")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    override fun onProviderEnabled(provider: String) {
        Log.i(TAG, "provider enabled: $provider")
    }

    override fun onProviderDisabled(provider: String) {
        Log.w(TAG, "provider disabled: $provider")
    }

    private companion object {
        const val TAG = "GPS"
    }
}