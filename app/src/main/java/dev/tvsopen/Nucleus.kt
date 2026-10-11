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
        // Only Ntorq has a transcribed frame table. Every other model falls
        // through to GenericDriver, which still connects and streams raw frames
        // so a new vehicle can be characterised without writing a driver first.
        DriverRegistry.register(NtorqDriver())
    }

    fun describe() = VehicleModel.entries.map { model ->
        val d = DriverRegistry.forModel(model)
        model to d.confidence()
    }
}