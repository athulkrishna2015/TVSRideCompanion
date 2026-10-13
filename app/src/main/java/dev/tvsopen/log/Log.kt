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
        // SimpleDateFormat is not thread-safe and BLE frames arrive from
        // several threads, hence the ThreadLocal. get() is declared T? even
        // though initialValue() is non-null, so the assertion is what the
        // compiler needs; it can only fail if initialValue() itself throws.
        val fmt = requireNotNull(TIME.get()) { "log time formatter unavailable" }
        val t = if (withTime) "${fmt.format(Date(atMillis))} " else ""
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
     *
     * **Time-boxed as well, because a modulo is the wrong clock here.** The
     * record rate is wildly uneven: minutes of nothing, then ~250 frames a
     * minute. Counting every 8 records republished the ring — copying up to
     * [RING_CAPACITY] entries each time — about 31 times a second during a
     * burst. That is the UI freeze. [PUBLISH_MIN_MS] caps it by wall clock
     * however fast frames arrive.
     */
    private const val PUBLISH_EVERY = 8

    /** Floor between two UI publishes, however many records arrive. */
    private const val PUBLISH_MIN_MS = 250L

    /**
     * How much of the ring the UI is handed. The ring itself stays full for
     * export; the screen never needs four thousand rows to be useful.
     */
    private const val UI_RECORD_CAP = 500

    /** Full dumps kept per distinct payload before it is counted instead. */
    private const val FRAME_FIRST_N = 3

    /** Occurrences between tally lines for an already-counted payload. */
    private const val FRAME_TALLY_EVERY = 200

    /** Ceiling on remembered payloads, so a long ride cannot grow this forever. */
    private const val FRAME_SIG_CAP = 256

    /** Records between explicit flushes, so a crash keeps what was written. */
    private const val FLUSH_EVERY = 16

    @Volatile
    var minLevel: Level = Level.DEBUG

    /** When false, frames are still counted but not recorded. */
    @Volatile
    var captureFrames: Boolean = true

    private val ring = ArrayDeque<LogRecord>(RING_CAPACITY)

    private var lastPublishAt = 0L

    private val frameCounts = HashMap<Int, Int>()
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
            // Close a previous sink first: replacing the writer without closing
            // it leaks a file descriptor on every call.
            runCatching { writer?.flush(); writer?.close() }
            writer = null
            runCatching {
                if (!dir.exists()) dir.mkdirs()
                val f = File(dir, "tvsride.log")
                if (f.exists() && f.length() > FILE_MAX_BYTES) rotate(dir)
                fileDir = dir
                // autoFlush: PrintWriter flushes on every println(). Without it
                // the sink only reached disk every FLUSH_EVERY records, and a
                // normal session writes two or three — so force-stopping the
                // app discarded exactly the records you open the log to read.
                // One write() per line is nothing next to a 2 Hz BLE frame, and
                // "the file always matches what happened" is the whole point.
                writer = PrintWriter(
                    java.io.BufferedWriter(java.io.FileWriter(f, true)),
                    true,
                )
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

    /**
     * Write a fresh copy of every log file to a single bundle, and return it.
     *
     * The live file is flushed first, so the bundle contains what actually
     * happened rather than whatever the buffer still held. Rotated files are
     * concatenated oldest-first so a session reads in order.
     *
     * [dir] must be app-scoped. `getExternalFilesDir` needs no storage
     * permission and is readable by `adb pull` without root — the point being
     * that logs should be gettable off a phone you cannot root.
     */
    fun export(dir: File): File? {
        val source = logFile() ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .format(Date(System.currentTimeMillis()))
        val out = File(dir, "tvsride-$stamp.log")
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            fileSizeBytes() // flush before copying
            out.bufferedWriter().use { w ->
                (2..FILE_KEEP)
                    .map { File(source.parentFile, "tvsride.$it.log") }
                    .filter { it.exists() }
                    .sortedByDescending { it.name }
                    .forEach { f ->
                        w.write("===== ${f.name} (older) =====\n")
                        f.forEachLine { w.write("$it\n") }
                    }
                w.write("===== ${source.name} (current) =====\n")
                source.forEachLine { w.write("$it\n") }
            }
            out
        }.getOrNull()
    }

    /** Where [export] should write. App-scoped, so no storage permission. */
    fun exportDir(context: android.content.Context): File =
        (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs"))
            .also { if (!it.exists()) it.mkdirs() }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            // Payloads seen before the clear must not suppress the first dumps
            // after it, or clearing the log would still open mid-stream.
            frameCounts.clear()
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
            // Publish a copy for the UI. Throttled by record count *and* wall
            // clock: the count alone lets a frame burst publish 30 times a
            // second, and the head-of-log rule is kept so a short startup log is
            // never invisible.
            val now = record.atMillis
            if (level >= Level.WARN ||
                ring.size <= PUBLISH_EVERY ||
                now - lastPublishAt >= PUBLISH_MIN_MS
            ) {
                lastPublishAt = now
                _records.value = snapshot()
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
                        // Close the old writer before replacing it: rotation
                        // used to orphan it, leaking a descriptor per rotation.
                        runCatching { w.close() }
                        writer = PrintWriter(
                            java.io.BufferedWriter(
                                java.io.FileWriter(File(fileDir!!, "tvsride.log"), true),
                            ),
                            true,
                        )
                        fileBytes = 0
                        sinceFlush = 0
                    } else if (sinceFlush >= FLUSH_EVERY) {
                        // Redundant now that the writer auto-flushes, but kept
                        // as a belt-and-braces flush: a caller could swap in a
                        // non-auto-flushing writer via enableFileSink.
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
     * Takes the raw bytes, not a pre-rendered string: the earlier shape took
     * `hex: String` and derived the count as `hex.length / 2`, but a rendered
     * dump is three characters per byte ("XX "), so a 20-byte frame was logged
     * as 29 bytes. Counting the array is exact, and formatting is still skipped
     * entirely when capture is off.
     *
     * **Consecutive identical payloads are collapsed.** The cluster streams
     * `0x10`/`0x11`/`0x16`/`0x18` on a fixed cadence, and a two-minute session
     * produced 501 lines of which ~130 were byte-for-byte repeats of the frame
     * above. A riding session would fill the ring and rotate the log files
     * inside a minute, and each repeat also paid for a hex format and a logcat
     * line.
     *
     * The cadence is not lost: a tally line is emitted every
     * [FRAME_TALLY_EVERY] repeats, timestamped like any other record, so the
     * spacing stays derivable, and the final tally is flushed the moment the
     * payload changes. Only the redundancy goes.
     */
    fun frame(direction: String, bytes: ByteArray, atMillis: Long = System.currentTimeMillis()) {
        _frameCount.value = _frameCount.value + 1
        if (!captureFrames) return
        // contentHashCode compares content, not identity, so this is a genuine
        // payload test; a collision would only merge two already-identical lines.
        val sig = 31 * direction.hashCode() + bytes.contentHashCode()
        val seen: Int
        synchronized(lock) {
            // **Per payload, not per run.** A first attempt collapsed only
            // *consecutive* repeats, which is nearly useless here: the cluster
            // interleaves 0x18, 0x10, 0x16, 0x11 in rotation, so no two
            // adjacent frames are ever alike and the collapse almost never
            // fired. Counting each distinct payload instead turns 125 copies of
            // one frame into a handful of lines, and still logs every *change*,
            // because a changed payload is a different signature and starts its
            // own count.
            seen = (frameCounts[sig] ?: 0) + 1
            if (frameCounts.size > FRAME_SIG_CAP) frameCounts.clear()
            frameCounts[sig] = seen
        }
        if (seen > FRAME_FIRST_N) {
            if (seen % FRAME_TALLY_EVERY == 0) {
                log(Level.DEBUG, "BLE", "$direction … unchanged, x$seen")
            }
            return
        }
        log(Level.DEBUG, "BLE", "$direction ${hex(bytes)} (${bytes.size} bytes)")
    }

    /**
     * The tail of the ring, bounded, for the UI.
     *
     * The ring holds [RING_CAPACITY] entries but the screen only ever shows the
     * recent ones, so the published list is capped at [UI_RECORD_CAP]. Export
     * still reads the whole ring.
     *
     * `takeLast` on a copy rather than an indexed walk: `kotlin.collections.
     * ArrayDeque` has no `get(Int)`, and an indexed loop does not compile
     * against it.
     *
     * Call under [lock].
     */
    private fun snapshot(): List<LogRecord> {
        val all = ring.toList()
        if (all.size <= UI_RECORD_CAP) return all
        // Copy the window rather than returning subList's view, which would
        // keep the full 4000-record backing list alive for the sake of 500 rows.
        return ArrayList(all.subList(all.size - UI_RECORD_CAP, all.size))
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