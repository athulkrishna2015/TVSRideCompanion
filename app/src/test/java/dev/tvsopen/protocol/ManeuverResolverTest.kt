package dev.tvsopen.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverResolverTest {

    @Test
    fun `specific roundabout exits beat generic roundabout`() {
        assertEquals(65, ManeuverResolver.fromText("Roundabout, first exit"))
        assertEquals(66, ManeuverResolver.fromText("Roundabout, second exit"))
    }

    @Test
    fun `compass and merge instructions resolve to useful symbols`() {
        assertEquals(56, ManeuverResolver.fromText("Head west"))
        assertEquals(22, ManeuverResolver.fromText("Merge left"))
        assertEquals(24, ManeuverResolver.fromText("Merge right"))
        assertEquals(Pictograms.STRAIGHT, ManeuverResolver.fromText("Continue straight"))
    }
}
