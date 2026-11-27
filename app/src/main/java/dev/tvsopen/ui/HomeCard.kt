// User-configurable Home screen cards.
//
// A card's default visibility depends on the vehicle: the Ntorq 125 has no
// verified find-my packet, so that card starts hidden there. The rider can
// still switch any supported card on or off in More -> Home screen.

package dev.tvsopen.ui

import dev.tvsopen.protocol.VehicleModel

enum class HomeCard(val key: String, val label: String) {
    SHORTCUTS("shortcuts", "Overview / Last parked shortcuts"),
    CONNECT("connect", "Connect card"),
    RIDE_MODE("ride_mode", "Ride mode"),
    MIRROR_NAV("mirror_nav", "Mirror navigation"),
    FIND_MY("find_my", "Find My Scooter"),
    PARKED("parked", "Last parked"),
    LINK("link", "Link status"),
    ;

    companion object {
        /** Cards that make sense for this model at all. */
        fun supported(model: VehicleModel): Set<HomeCard> = entries.toSet().minus(
            if (model == VehicleModel.NTORQ_125_RACE) setOf(FIND_MY) else emptySet(),
        )

        /** Defaults before the rider changes anything in settings. */
        fun defaults(model: VehicleModel): Set<HomeCard> = supported(model)

        fun fromKeys(keys: Set<String>): Set<HomeCard> =
            entries.filter { it.key in keys }.toSet()
    }
}
