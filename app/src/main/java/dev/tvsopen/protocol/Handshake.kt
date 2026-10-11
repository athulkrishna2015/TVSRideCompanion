// Handshake and encryption, as observed in the shipped apps.
//
// Three independent sources agree that this "authentication" is not a security
// boundary:
//
//   1. The official TVS Connect APK hardcodes a 16-byte AES key
//      (`KeylessEntryUtils.AES_KEY_BYTE_ARRAY`) and calls `AES/CTR/NoPadding`.
//   2. A third-party Jupiter companion app ships the same key, differing in a
//      single byte — and works on the owner's hardware.
//   3. The TechEnclave reverse-engineering participants report no real key is
//      required.
//
// This is the concrete, client-side half of CVE-2024-35537 (FEV LTD's
// "Broken Cryptography: Leaked secrets on Android and iOS application
// platforms"). We reproduce it only because the cluster rejects frames without
// a well-formed response — it is protocol fidelity, not authentication.

package dev.tvsopen.protocol

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The key shipped in the official APK.
 *
 * Transcribed from `com.tvs.bluetooth.core.utils.KeylessEntryUtils`:
 * `{122,-93,32,77,22,29,-75,RESPONSE_VALID_BYTE,-12,-21,32,79,-68,-41,61,-44}`
 * where `RESPONSE_VALID_BYTE == 37 (0x25)`.
 *
 * The third-party Jupiter companion uses `...,-75,0x33,...` for index 7 —
 * the same key with one byte changed. Neither variant is privileged; the
 * cluster accepts what it accepts.
 */
object HandshakeKey {
    val OFFICIAL: ByteArray = byteArrayOf(
        0x7A, 0xA3.toByte(), 0x20, 0x4D, 0x16, 0x1D, 0xB5.toByte(), 0x25,
        0xF4.toByte(), 0xEB.toByte(), 0x20, 0x4F, 0xBC.toByte(), 0xD7.toByte(), 0x3D, 0xD4.toByte(),
    )

    /** The variant observed in the working third-party Jupiter app. */
    val JUPITER_COMPANION: ByteArray = OFFICIAL.copyOf().also { it[7] = 0x33 }

    /**
     * Official implementation detail, transcribed from `BluetoothUtil`:
     * `AES/CTR/NoPadding` with the **challenge itself as the IV**.
     *
     * That is a weak construction — the IV is attacker-visible on the wire —
     * but it is what the cluster expects.
     */
    fun aesCtrNoPadding(key: ByteArray, iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
}

object Challenge {

    const val START_9A = 0x9A
    const val START_9B = 0x9B
    const val F1_RESPONSE = 0xF1
    const val F2_CHALLENGE = 0xF2

    /** `KeylessEntryUtils.INITIATE_REQUEST`. */
    const val INITIATE_REQUEST = 0x40

    /** `KeylessEntryUtils.SEND_RESPONSE`. */
    const val SEND_RESPONSE = 0x50

    /** `KeylessEntryUtils.POSTFIX == -1`, i.e. the `0xFF` terminator. */
    const val POSTFIX: Byte = 0xFF.toByte()

    /**
     * Recognise an inbound challenge.
     *
     * The official app's own validity check (`BtData.isValidFrame`) accepts
     * start bytes `0x9B` and `0x5A` only, which is how the challenge frame is
     * distinguished from telemetry.
     */
    fun isChallenge(frame: ByteArray): Boolean {
        if (frame.size < 2) return false
        val start = frame[0].toInt() and 0xFF
        val id = frame[1].toInt() and 0xFF
        return (start == START_9B || start == START_9A) && id == F2_CHALLENGE
    }

    /**
     * Build the unlock response.
     *
     * Layout, from `BlePacketsProvider.byteArrayForUnLock`:
     * `0x9A 0xF1` + payload + challenge + <padding to 20> + `0xFF`.
     *
     * [iv] is the 16-byte challenge payload from the inbound frame.
     *
     * Returns null when [key] does not decrypt/encrypt cleanly, rather than
     * sending a malformed frame that might get the link dropped.
     */
    fun buildResponse(
        challengeFrame: ByteArray,
        key: ByteArray = HandshakeKey.OFFICIAL,
    ): ByteArray? {
        if (!isChallenge(challengeFrame)) return null
        val iv = challengeFrame.copyOfRange(2, minOf(18, challengeFrame.size))
        if (iv.size < 16) return null
        val iv16 = iv.copyOf(16)

        val cipher = runCatching {
            HandshakeKey.aesCtrNoPadding(key, iv16)
        }.getOrNull() ?: return null
        val encrypted = runCatching { cipher.doFinal(iv16) }.getOrNull() ?: return null

        val out = ByteArray(20)
        out[0] = START_9A.toByte()
        out[1] = F1_RESPONSE.toByte()
        encrypted.copyInto(out, destinationOffset = 2)
        out[19] = POSTFIX
        return out
    }

    /**
     * The obfuscated command blocks in `KeylessEntryUtils`
     * (`UNLOCK_COMMAND_UUID`, `FIND_VEHICLE_COMMAND_UUID`, …) are byte arrays
     * that embed protocol constants to disguise them, not real UUIDs. They are
     * not reproduced here; `PacketBuilder` covers the same operations with
     * readable code.
     */
}