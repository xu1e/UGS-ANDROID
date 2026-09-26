package de.ugs.sicherheit

import android.content.Context
import java.util.Locale
import org.json.JSONObject

/** BDSW-Entgeltübersicht aus contracts/ugs-tarife.json (Tarifgebiete, Entgeltgruppen, Zuschläge). */
object Tariffs {
    const val NONE = "Kein Tarifgebiet"
    const val NO_GROUP = "Keine Entgeltgruppe"

    data class Group(val id: String, val title: String)

    data class Region(
        val id: String,
        val name: String,
        val validityFrom: String,
        val validityTo: String,
        val rates: Map<String, Double>,
        val rateNotes: Map<String, String>,
        val night: String,
        val sunday: String,
        val holiday: String,
    )

    data class Catalog(val source: String, val stand: String, val standLabel: String, val groups: List<Group>, val regions: List<Region>) {
        fun region(key: String) = regions.firstOrNull { it.id == key || it.name == key }

        fun group(key: String) = groups.firstOrNull { it.id == key || it.title == key }
    }

    val empty = Catalog("", "", "", emptyList(), emptyList())

    @Volatile private var cached: Catalog? = null

    fun parse(json: String): Catalog {
        val o = JSONObject(json)
        fun map(obj: JSONObject?) = obj?.keys()?.asSequence()?.associateWith { obj.get(it) }.orEmpty()
        val groups = o.getJSONArray("groups").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { g -> Group(g.getString("id"), g.getString("title")) } } }
        val regions =
            o.getJSONArray("regions").let { a ->
                (0 until a.length()).map {
                    val r = a.getJSONObject(it)
                    Region(
                        r.getString("id"),
                        r.getString("name"),
                        r.optString("validityFrom"),
                        r.optString("validityTo"),
                        map(r.optJSONObject("rates")).mapValues { (_, v) -> (v as Number).toDouble() },
                        map(r.optJSONObject("rateNotes")).mapValues { (_, v) -> v.toString() },
                        r.optString("night"),
                        r.optString("sunday"),
                        r.optString("holiday"),
                    )
                }
            }
        return Catalog(o.optString("source"), o.optString("stand"), o.optString("standLabel"), groups, regions)
    }

    fun catalog(c: Context): Catalog =
        cached ?: runCatching { parse(c.assets.open("contracts/ugs-tarife.json").bufferedReader().use { it.readText() }) }.getOrDefault(empty).also { cached = it }

    fun euro(v: Double) = String.format(Locale.GERMANY, "%.2f", v)

    private fun german(iso: String) = iso.split("-").takeIf { it.size == 3 }?.let { "${it[2]}.${it[1]}.${it[0]}" } ?: iso

    /** Prüfung wie iOS: Stundenlohn nicht unter dem Katalogwert der gewählten Gruppe. */
    fun validate(catalog: Catalog, regionKey: String, groupKey: String, hourly: Double) {
        if (regionKey.isBlank() || regionKey == NONE) return
        val region = catalog.region(regionKey) ?: throw IllegalArgumentException("Das gewählte Tarifgebiet ist unbekannt.")
        val rate = catalog.group(groupKey)?.let { region.rates[it.id] } ?: return
        require(hourly + 0.005 >= rate) {
            "Der Stundenlohn liegt unter dem hinterlegten Katalogwert von ${euro(rate)} EUR für ${region.name}. Aktuell geschuldete Vergütung zusätzlich prüfen."
        }
    }

    /** Kurzübersicht im Vertragsformular. */
    fun summary(catalog: Catalog, regionKey: String, groupKey: String): String {
        val region = catalog.region(regionKey) ?: return "Kein Tarifgebiet gewählt – der Vertrag nennt nur den eingetragenen Stundenlohn."
        val lines = mutableListOf<String>()
        catalog.group(groupKey)?.let { g ->
            lines += region.rates[g.id]?.let { "${g.title}: ${euro(it)} € / Std." } ?: "${g.title}: kein Tariflohn in diesem Tarifgebiet"
            region.rateNotes[g.id]?.let { lines += "Hinweis: $it" }
        }
        region.rates["fluechtlinge"]?.let { lines += "Flüchtlingsunterkünfte: ${euro(it)} € / Std." }
        lines += "Nacht ${region.night} · Sonntag ${region.sunday} · Feiertag ${region.holiday}"
        lines += "Tarifvertrag ${german(region.validityFrom)} – ${german(region.validityTo)} · Stand ${catalog.standLabel.ifBlank { catalog.stand }}"
        return lines.joinToString("\n")
    }

    /** Vertragstexte: Zuordnung für Anlage 4 und Entgeltgruppe. */
    fun tokens(catalog: Catalog, regionKey: String, groupKey: String): Map<String, String> {
        val region = catalog.region(regionKey)
        val group = catalog.group(groupKey)
        val out = mutableMapOf("tariff_group" to (group?.title ?: "____________________________"))
        val assignment = listOfNotNull(region?.name, group?.title).joinToString(" · ")
        if (assignment.isNotBlank()) out["tariff_assignment"] = assignment
        return out
    }

    /** Auswahllisten für das Formular (aus ugs-tarife.json, Stand 1. April 2026). */
    val regionNames =
        listOf(
            "Baden-Württemberg", "Bayern (Ortsklasse 1)", "Berlin", "Brandenburg", "Bremen", "Hamburg", "Hessen", "Mecklenburg-Vorpommern",
            "Niedersachsen (GÖD)", "Nordrhein-Westfalen", "Rheinland-Pfalz / Saarland", "Sachsen (GÖD)", "Sachsen-Anhalt", "Schleswig-Holstein", "Thüringen",
        )
    val groupTitles =
        listOf(
            "Objektschutzdienst / Separatwachdienst", "Beschäftigte in betriebseigener NRZ / NSL", "Revierwachdienst",
            "IHK-geprüfte Werkschutzfachkraft / geprüfte Schutz- und Sicherheitskraft", "Fachkraft für Schutz und Sicherheit", "Veranstaltungsdienst",
            "Schutz von Flüchtlingsunterkünften",
        )
}
