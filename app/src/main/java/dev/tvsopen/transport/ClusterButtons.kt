// Cluster buttons, replayed on the phone.
//
// The cluster sends a single command byte and expects the *phone* to act: the
// spec's wording for the stock implementation is that the cluster is a remote
// control, not a player. Both families use the same command map
// (`BluetoothUtil.getMusicCommandType`, dex-verified):
//
//   0 play, 1 pause, 2 toggle, 3 next, 4 previous, 5 volume up, 6 volume down
//
// Dispatch goes through AudioManager rather than a MediaSession, which is what
// the stock app does: it requires no playback session of our own to exist, and
// it works while another app is playing.

package dev.tvsopen.transport

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import dev.tvsopen.log.Log

/** Replays `0x6B` media-key commands through the system audio stack. */
class MediaKeys(context: Context) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    /**
     * Handle one command byte. Returns false for a command this build does not
     * map, so the caller can log it once rather than silently dropping it.
     */
    fun handle(command: Int): Boolean {
        val am = audio ?: return false
        when (command) {
            0 -> return mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY)
            1 -> return mediaKey(am, KeyEvent.KEYCODE_MEDIA_PAUSE)
            2 -> return mediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            3 -> return mediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
            4 -> return mediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            5 -> return adjustVolume(am, AudioManager.ADJUST_RAISE)
            6 -> return adjustVolume(am, AudioManager.ADJUST_LOWER)
            else -> {
                Log.d(TAG, "unknown media command $command")
                return false
            }
        }
    }

    private fun mediaKey(am: AudioManager, code: Int): Boolean {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, code)
        val up = KeyEvent(KeyEvent.ACTION_UP, code)
        return runCatching {
            am.dispatchMediaKeyEvent(down)
            am.dispatchMediaKeyEvent(up)
            true
        }.onFailure { Log.w(TAG, "media key $code failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun adjustVolume(am: AudioManager, direction: Int): Boolean = runCatching {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        true
    }.onFailure { Log.w(TAG, "volume adjust failed: ${it.message}") }
        .getOrDefault(false)

    companion object {
        private const val TAG = "MediaKeys"
    }
}

/**
 * The cluster's call switch, from `0x10` byte 12.
 *
 * Values, dex-verified: 1 accept, 2 reject, 3 reject with SMS, 4 voice assist.
 * The stock app edge-triggers answer/reject on 1 and 2 only.
 *
 * **This is best-effort by design.** Answering a call outright needs
 * `ANSWER_PHONE_CALLS`, which a third-party app can only hold by being the
 * default dialer — see the note in AndroidManifest.xml. So instead of
 * pretending, this dispatches a headset-hook key event, which is exactly the
 * button a wired headset exposes: the dialer answers on the first press and
 * ends on the next. It works on the phones that map that key, and it never
 * claims more than that.
 */
class CallButtons(context: Context) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    fun handle(switchValue: Int): Boolean {
        val am = audio ?: return false
        return when (switchValue) {
            1 -> {
                Log.i(TAG, "cluster call switch: accept")
                hook(am)
            }
            2 -> {
                Log.i(TAG, "cluster call switch: reject/end")
                hook(am)
            }
            3 -> {
                // Reject-with-SMS needs the dialer's own template; a headset
                // button cannot select it. Logged rather than faked.
                Log.i(TAG, "cluster call switch: reject with SMS (not supported without dialer role)")
                false
            }
            4 -> {
                Log.i(TAG, "cluster call switch: voice assist")
                runCatching {
                    am.dispatchMediaKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST),
                    )
                    am.dispatchMediaKeyEvent(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOICE_ASSIST),
                    )
                }.isSuccess
            }
            else -> {
                Log.d(TAG, "call switch value $switchValue is not a command")
                false
            }
        }
    }

    private fun hook(am: AudioManager): Boolean = runCatching {
        am.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK),
        )
        am.dispatchMediaKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK),
        )
        true
    }.onFailure { Log.w(TAG, "headset hook failed: ${it.message}") }
        .getOrDefault(false)

    companion object {
        private const val TAG = "CallButtons"
    }
}