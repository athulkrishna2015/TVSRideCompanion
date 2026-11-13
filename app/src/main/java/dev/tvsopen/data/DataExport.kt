// On-device data export.
//
// The app keeps everything locally (rides, settings, parked spot) and has no
// account or cloud. Exporting produces a single JSON file the rider can keep or
// move to another device. Nothing is uploaded anywhere.

package dev.tvsopen.data

import android.content.Context
import dev.tvsopen.prefs.RidePrefs
import dev.tvsopen.session.Session
import dev.tvsopen.ui.theme.ThemePrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataExport {

    const val FORMAT = "tvsride-export"
    const val VERSION = 1

    /** Build the export document as a JSON string. */
    fun buildJson(context: Context): String {
        val prefs = RidePrefs.load(context)
        val rides = Session.rides.value
        val parked = Session.parked.value

        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())

        val ridesArr = JSONArray()
        rides.forEach { r ->
            val o = JSONObject()
                .put("startedAt", r.startedAt)
                .put("distanceKm", r.distanceKm)
            if (r.endedAt != null) o.put("endedAt", r.endedAt) else o.put("endedAt", JSONObject.NULL)
            ridesArr.put(o)
        }
        root.put("rides", ridesArr)
            .put("rideTotalCount", Session.totalRides.value)
            .put("rideTotalDistanceKm", Session.totalRideDistanceKm.value)

        if (parked != null) {
            root.put(
                "parked",
                JSONObject()
                    .put("atMillis", parked.atMillis)
                    .put("label", parked.label ?: JSONObject.NULL),
            )
        } else {
            root.put("parked", JSONObject.NULL)
        }

        root.put(
            "prefs",
            JSONObject()
                .put("riderName", prefs.riderName ?: JSONObject.NULL)
                .put("navigationMirroring", prefs.navigationMirroring)
                .put("mirrorPhoneCalls", prefs.mirrorPhoneCalls)
                .put("mirrorMessages", prefs.mirrorMessages)
                .put("rideMode", prefs.rideMode)
                .put("theme", ThemePrefs.mode.name),
        )

        return root.toString(2)
    }

    /**
     * Write the export to a file in the app's cache and return it, ready to
     * share. The caller owns presenting the share/send intent.
     */
    fun writeFile(context: Context): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "tvsride-export-$stamp.json")
        file.writeText(buildJson(context))
        return file
    }
}
