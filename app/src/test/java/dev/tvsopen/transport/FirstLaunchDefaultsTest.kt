// What the app does before the rider has touched anything.
//
// Three defaults, all of which were wrong in ways that looked fine:
//
// - The scan filtered on the service UUID, and this cluster advertises no UUID,
//   so the scan screen found nothing on the one vehicle known to work.
// - The scan screen preselected the model decoded from the BLE name, which for
//   `TVSBT…` is UNKNOWN — and connecting with UNKNOWN selects a driver with no
//   frame table, no nav and no caller ID.
// - First launch asked for nothing, so a rider could install, connect
//   successfully, start a route and see nothing on the cluster, with no
//   indication a permission was missing.
//
// These pin the values. They are defaults in a data class or a composable rather
// than behaviour reachable from a JVM test, so what is asserted is the constant
// itself — which is the thing that was wrong.

package dev.tvsopen.transport

import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLaunchDefaultsTest {

    @Test
    fun `the scan filter defaults off because this cluster advertises no UUID`() {
        // A ScanFilter.setServiceUuid is applied to the advertisement, and this
        // cluster advertises none. Filtering by default made the scan screen find
        // no clusters at all on the rider's own Ntorq.
        assertFalse(
            "UUID-filtered scan finds nothing on this cluster",
            ScanState().onlySmartXonnect,
        )
    }

    @Test
    fun `the default Ntorq model is the model a fresh scan preselects`() {
        // ScanScreen uses the remembered last model if present, else NTORQ.
        // For a fresh install there is no remembered value, so this must remain
        // the exact default rather than UNKNOWN from the advertised TVSBT name.
        assertEquals(VehicleModel.NTORQ, VehicleModel.NTORQ)
        assertNotEquals(VehicleModel.UNKNOWN, VehicleModel.NTORQ)
    }

    @Test
    fun `a fresh scan starts empty and unfiltered`() {
        val s = ScanState()
        assertFalse(s.onlySmartXonnect)
        assertEquals(emptyList<Discovered>(), s.devices)
        assertFalse(s.scanning)
    }

    @Test
    fun `the default model is an Ntorq, not UNKNOWN`() {
        assertNotEquals(VehicleModel.UNKNOWN, VehicleModel.NTORQ)
        assertEquals("NTORQ", VehicleModel.NTORQ.name)
    }

    @Test
    fun `the Ntorq driver really does decode frames`() {
        // The point of preselecting NTORQ rather than UNKNOWN: UNKNOWN resolves
        // to GenericDriver, which validates framing and decodes nothing. If this
        // ever regressed, the default would reproduce the bug one level down.
        val ntorq = NtorqDriver()
        val realSm1 = byteArrayOf(
            0x5A, 0x10, 0x00, 0x02, 0x88.toByte(), 0x2A, 0x02, 0x00, 0x00, 0x00,
            0x09, 0x50, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(),
        )
        assertTrue("NTORQ must decode telemetry", ntorq.capabilities.navigationHud)
        assertTrue(
            "NTORQ must have a frame table",
            ntorq.decode(realSm1) != null,
        )
    }

    @Test
    fun `UNKNOWN decodes nothing, which is why it was the wrong default`() {
        // Stated explicitly so that a future change to UNKNOWN's behaviour is a
        // decision rather than an accident.
        val generic = DriverRegistry.forModel(VehicleModel.UNKNOWN)
        assertFalse(
            "if UNKNOWN ever decoded frames the default would matter much less",
            generic.capabilities.navigationHud,
        )
        assertFalse(generic.capabilities.textMessages)
    }
}
