// Unit tests for Spanish navigation parsing, maneuver pictograms, short codes
// and ASCII diacritic folding.
//
// All street names are invented, per the repo privacy rule.

package dev.tvsopen.nav

import dev.tvsopen.protocol.ManeuverResolver
import dev.tvsopen.protocol.PacketBuilder
import dev.tvsopen.protocol.Pictograms
import org.junit.Assert.assertEquals
import org.junit.Test

class SpanishNavTest {

    @Test
    fun `spanish maneuvers resolve to the right cluster pictograms`() {
        assertEquals(0, ManeuverResolver.fromText("Gira a la izquierda hacia Av. Ejemplo"))
        assertEquals(3, ManeuverResolver.fromText("Gira a la derecha hacia Calle 10"))
        assertEquals(1, ManeuverResolver.fromText("Gira muy a la izquierda"))
        assertEquals(4, ManeuverResolver.fromText("Muy a la derecha"))
        assertEquals(2, ManeuverResolver.fromText("Mantente a la izquierda"))
        assertEquals(5, ManeuverResolver.fromText("Ligeramente a la derecha"))
        assertEquals(7, ManeuverResolver.fromText("Dirígete hacia Cra. 10"))
        assertEquals(7, ManeuverResolver.fromText("Continúa recto por Av. Ejemplo"))
        assertEquals(68, ManeuverResolver.fromText("En la rotonda, toma la segunda salida"))
        assertEquals(65, ManeuverResolver.fromText("En la glorieta, toma la primera salida"))
        assertEquals(Pictograms.U_TURN_LEGACY.id, ManeuverResolver.fromText("Haz una vuelta en U"))
        assertEquals(Pictograms.U_TURN_LEGACY.id, ManeuverResolver.fromText("Toma el retorno"))
        assertEquals(8, ManeuverResolver.fromText("Has llegado a tu destino"))
    }

    @Test
    fun `spanish google maps arrival clock with narrow space parses ETA without faking destination`() {
        // Baseline 11:23 (683 min); "Llegada: 11:53\u202Fa.m." is 30 min away.
        val now = 11 * 60 + 23
        val p = NavParsing.parse(
            title = "Dirígete hacia Av. Ejemplo.",
            text = "Llegada: 11:53\u202Fa.m.",
            bigText = "",
            subText = "",
            nowMinutes = now,
        )
        assertEquals(30, p.etaMinutes)
        assertEquals(7, p.pictogram) // straight/head toward, NOT destination (8)
    }

    @Test
    fun `spanish hour and minute durations sum cleanly`() {
        assertEquals(85, NavParsing.extractEtaMinutes("1 h 25 min · 42 km"))
        assertEquals(120, NavParsing.extractEtaMinutes("2 horas"))
        assertEquals(15, NavParsing.extractEtaMinutes("15 minutos"))
    }

    @Test
    fun `spanish accents and enie fold to plain ASCII for the cluster`() {
        assertEquals(
            "Dirigete hacia Ca",
            String(PacketBuilder.sanitize("Dirígete hacia Cañaveral"), Charsets.UTF_8),
        )
        assertEquals(
            "Nino Aguero",
            String(PacketBuilder.sanitize("Niño Agüero"), Charsets.UTF_8),
        )
    }

    @Test
    fun `spanish short codes compress without breaking street names`() {
        assertEquals("DER hacia Av Ejemplo", NavShortCodes.compress("Gira a la derecha hacia Avenida Ejemplo"))
        assertEquals("M-DER", NavShortCodes.compress("Muy a la derecha"))
        assertEquals("IZQ en Cra 10", NavShortCodes.compress("Gira a la izquierda en Carrera 10"))
        assertEquals("HACIA Cll 5", NavShortCodes.compress("Dirígete hacia Calle 5"))
    }
}
