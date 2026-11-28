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

    @Test
    fun `roundabout exits four through seven resolve to their ids`() {
        assertEquals(68, ManeuverResolver.fromText("At the roundabout, take the fourth exit"))
        assertEquals(69, ManeuverResolver.fromText("At the roundabout, take the fifth exit"))
        assertEquals(70, ManeuverResolver.fromText("At the roundabout, take the sixth exit"))
        assertEquals(71, ManeuverResolver.fromText("At the roundabout, take the seventh exit"))
    }
}
