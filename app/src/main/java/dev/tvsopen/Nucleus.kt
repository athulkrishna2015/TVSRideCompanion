// Nucleus / wiring.
//
// Registers drivers at startup and exposes the single live session.
// Kept deliberately small: the model-specific knowledge is all in the drivers.

package dev.tvsopen

import dev.tvsopen.protocol.DriverRegistry
import dev.tvsopen.protocol.NtorqDriver
import dev.tvsopen.protocol.VehicleModel

object Nucleus {
    fun init() {
        // Only the Ntorq protocol family has a transcribed frame table.
        // Raider is bound to the same config upstream, so it gets the same
        // driver. Every other model falls through to GenericDriver, which still
        // connects and streams raw frames so a new vehicle can be characterised
        // without writing a driver first.
        for (m in VehicleModel.entries.filter { it.sharesNtorqProtocol() }) {
            DriverRegistry.register(NtorqDriver(m))
        }
    }

    fun describe() = VehicleModel.entries.map { model ->
        val d = DriverRegistry.forModel(model)
        model to d.confidence()
    }
}