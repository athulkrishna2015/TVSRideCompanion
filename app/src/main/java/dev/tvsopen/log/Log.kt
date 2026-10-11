// Logging for a reverse-engineering tool.
//
// Normal apps log little. This one exists to answer "what did the cluster
// actually send?", so the requirements are different:
//
//   * **Frames are first-class.** Every BLE frame in and out is captured with a
//     timestamp, direction and hex dump. That record is the evidence base for
//     every protocol fix.
//   * **Nothing is lost.** A bounded in-memory ring backs the live UI; an
//     optional rotating file sink survives a crash.
//   * **Cheap.** Logging must never be the reason a GATT write is late.
//     Hex formatting happens once, into a pre-rendered string.

package dev.tvsopen.log

import android.util.Log as AndroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

enum class Level(val short: Char, val androidPriority: Int) {
    VERBOSE('V', AndroidLog.VERBOSE),
    DEBUG('D', AndroidLog.DEBUG),
    INFO('I', AndroidLog.INFO),
    WARN('W', AndroidLog.WARN),
    ERROR('E', AndroidLog.ERROR);

    companion object {
        fun parse(s: String?): Level? =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }
    }
}

/** One captured record. */
data class LogRecord(
    val atMillis: Long,
    val level: Level,
    val tag: String,
    val message: String,
    val throwable: String? = null,
) {
    fun format(withTime: Boolean = true): String {
        val t = if (withTime) "${TIME.get().format(Date(atMillis))} " else ""
        val x = if (throwable != null) "\n$throwable" else ""
        return "$t${level.short}/$tag: $message$x"
    }

    companion object {
        private val TIME = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        }
    }
}

/**
 * Singleton logger.
 *
 * Deliberately a singleton with no DI: the BLE callbacks that need it are
 * constructed by the framework, and threading an instance through every GATT
 * callback adds ceremony for no benefit.
 */
object Log {

    private const val TAG = "TVSRide"

    /** Keep roughly the last few thousand lines in memory for the UI. */
    private const val RING_CAPACITY = 4000

    /** Rotate the file sink once it passes this size. */
    private const val FILE_MAX_BYTES = 2L * 1024 * 1024

    private const val FILE_KEEP = 3

    /**
     * How often the ring is republished to the UI.
     *
     * Emitting on every record would thrash Compose during a frame burst. But a
     * pure modulo is wrong at the start: the first PUBLISH_EVERY-1 records would
     * never be published at all, so a freshly launched app showed "0 records"
     * while logcat plainly had lines. Hence the `ring.size <= PUBLISH_EVERY`
     * term — the head of the log is always current, the tail is sampled.
     */
    private const val PUBLISH_EVERY = 8

    /** Records between explicit flushes, so a crash keeps what was written. */
    private const val FLUSH_EVERY = 16

    @Volatile
    var minLevel: Level = Level.DEBUG

    /** When false, frames are still counted but not recorded. */
    @Volatile
    var captureFrames: Boolean = true

    private val ring = ArrayDeque<LogRecord>(RING_CAPACITY)
    private val lock = Any()

    private val _records = MutableStateFlow<List<LogRecord>>(emptyList())
    val records: StateFlow<List<LogRecord>> = _records.asStateFlow()

    private val _frameCount = MutableStateFlow(0L)
    /** Total frames observed, in + out. Cheap enough to always update. */
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    private var fileDir: File? = null
    private var writer: PrintWriter? = null
    private var fileBytes = 0L
    private var sinceFlush = 0

    /** Point the file sink at a directory. Creates it if needed. */
    fun enableFileSink(dir: File) {
        synchronized(lock) {
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "tvsride.log")
                if (f.exists() && f.length() > FILE_MAX_BYTES) rotate(dir)
                fileDir = dir
                // File.writer takes no `append` flag; append mode comes from the
                // FileWriter(file, true) constructor.
                writer = PrintWriter(java.io.BufferedWriter(java.io.FileWriter(f, true)))
                fileBytes = f.length()
            }.onFailure { v("log", "file sink unavailable: ${it.message}") }
        }
    }

    fun disableFileSink() {
        synchronized(lock) {
            runCatching { writer?.flush(); writer?.close() }
            writer = null
            fileDir = null
        }
    }

    fun logFile(): File? = fileDir?.let { File(it, "tvsride.log") }

    /**
     * Shift the log files down one slot and let the caller start a fresh one.
     *
     * Keeps exactly [FILE_KEEP] files *including* the live one. The obvious
     * `for (i in FILE_KEEP downTo 2)` version quietly produced FILE_KEEP+1 files
     * and never deleted the oldest, because the live file was renamed into slot
     * FILE_KEEP+1 which nothing ever cleaned.
     */
    private fun rotate(dir: File) {
        File(dir, "tvsride.$FILE_KEEP.log").delete()
        for (i in FILE_KEEP - 1 downTo 2) {
            val from = File(dir, "tvsride.$i.log")
            if (from.exists()) from.renameTo(File(dir, "tvsride.${i + 1}.log"))
        }
        File(dir, "tvsride.log").renameTo(File(dir, "tvsride.2.log"))
    }

    /**
     * Flush and report the live file's size.
     *
     * The UI shows this, so it has to flush first — a buffered writer reports
     * 0 bytes for a log that is actually several hundred lines long.
     */
    fun fileSizeBytes(): Long {
        synchronized(lock) {
            writer?.let { runCatching { it.flush() } }
            return fileDir?.let { File(it, "tvsride.log").length() } ?: 0L
        }
    }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            _records.value = emptyList()
            _frameCount.value = 0L
        }
    }

    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    fun log(level: Level, tag: String, message: String, t: Throwable? = null) {
        if (level < minLevel) return
        val sw = t?.let { StringWriter().also { w -> it.printStackTrace(PrintWriter(w)) }.toString() }
        val record = LogRecord(System.currentTimeMillis(), level, tag, message, sw)

        AndroidLog.println(level.androidPriority, "$TAG/$tag", message)

        synchronized(lock) {
            if (ring.size >= RING_CAPACITY) ring.pollFirst()
            ring.addLast(record)
            // Publish a copy for the UI. Throttled, but the head of the ring is
            // always published so a short startup log is not invisible.
            if (level >= Level.WARN ||
                ring.size <= PUBLISH_EVERY ||
                ring.size % PUBLISH_EVERY == 0
            ) {
                _records.value = ring.toList()
            }
            writer?.let { w ->
                try {
                    // Format once: this runs for every BLE frame in a burst.
                    val line = record.format()
                    w.println(line)
                    fileBytes += line.length + 1
                    sinceFlush++
                    if (fileBytes > FILE_MAX_BYTES) {
                        w.flush()
                        rotate(fileDir!!)
                        writer = PrintWriter(
                            java.io.BufferedWriter(java.io.FileWriter(File(fileDir!!, "tvsride.log"), true)),
                        )
                        fileBytes = 0
                        sinceFlush = 0
                    } else if (sinceFlush >= FLUSH_EVERY) {
                        // Without this, a kill -9 loses the buffer and the whole
                        // point of the file sink — surviving a crash — is lost.
                        w.flush()
                        sinceFlush = 0
                    }
                } catch (e: Exception) {
                    AndroidLog.w(TAG, "file write failed: ${e.message}")
                }
            }
        }
    }

    /** True when [level] would actually be recorded. Use to skip hot-path work. */
    fun enabled(level: Level): Boolean = level >= minLevel

    // ---- BLE frames ----

    /**
     * Record one BLE frame.
     *
     * [hex] is passed pre-rendered by [hex] so the formatting cost is paid once
     * and can be skipped entirely when frame capture is off.
     */
    fun frame(direction: String, hex: String, atMillis: Long = System.currentTimeMillis()) {
        _frameCount.value = _frameCount.value + 1
        if (!captureFrames) return
        log(Level.DEBUG, "BLE", "$direction $hex (${hex.length / 2} bytes)")
    }

    /** `5B 4A 03 00 …` — uppercase, space separated, two digits per byte. */
    fun hex(bytes: ByteArray, limit: Int = 64): String {
        val sb = StringBuilder(bytes.size * 3)
        for (i in bytes.indices) {
            if (i >= limit) { sb.append(" …+").append(bytes.size - limit); break }
            if (i > 0) sb.append(' ')
            val b = bytes[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}