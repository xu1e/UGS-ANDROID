package de.ugs.sicherheit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffsTest {
    private val catalog =
        Tariffs.parse(javaClass.classLoader!!.getResourceAsStream("contracts/ugs-tarife.json")!!.bufferedReader().use { it.readText() })

    @Test
    fun formListsMatchCatalog() {
        assertEquals(catalog.regions.map { it.name }, Tariffs.regionNames)
        assertEquals(catalog.groups.map { it.title }, Tariffs.groupTitles)
    }

    @Test
    fun rejectsHourlyRateBelowCatalog() {
        val nrw = catalog.region("Nordrhein-Westfalen")!!
        val rate = nrw.rates.getValue("objektschutz")
        Tariffs.validate(catalog, nrw.name, "Objektschutzdienst / Separatwachdienst", rate)
        val error = runCatching { Tariffs.validate(catalog, nrw.name, "objektschutz", rate - 0.5) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Katalogwert"))
        Tariffs.validate(catalog, Tariffs.NONE, "objektschutz", 1.0)
    }

    @Test
    fun tokensNameRegionAndGroup() {
        val t = Tariffs.tokens(catalog, "Berlin", "Revierwachdienst")
        assertEquals("Berlin · Revierwachdienst", t["tariff_assignment"])
        assertEquals("Revierwachdienst", t["tariff_group"])
        assertTrue(Tariffs.summary(catalog, "Berlin", "Revierwachdienst").contains("Nacht"))
    }
}
