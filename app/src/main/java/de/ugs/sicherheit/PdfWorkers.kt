package de.ugs.sicherheit

import android.content.Context
import android.graphics.Color
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Spalten der Mitarbeiterliste für den PDF-Export (gleiche Kennungen wie am Mac). */
data class WorkerColumn(val id: String, val label: String, val sensitive: Boolean)

object WorkerListColumns {
    val all =
        listOf(
            WorkerColumn("personnel_number", "Bewacher ID", false),
            WorkerColumn("staff_number", "Personalnummer", false),
            WorkerColumn("name", "Name", false),
            WorkerColumn("first_name", "Vorname", false),
            WorkerColumn("last_name", "Nachname", false),
            WorkerColumn("address", "Adresse", true),
            WorkerColumn("nationality", "Nationalität", false),
            WorkerColumn("identity_number", "Ausweisnummer", true),
            WorkerColumn("birth_date", "Geburtsdatum", true),
            WorkerColumn("birth_place", "Geburtsort", false),
            WorkerColumn("mobile", "Mobilnummer", true),
            WorkerColumn("email", "E-Mail", true),
            WorkerColumn("department", "Abteilung", false),
            WorkerColumn("position", "Tätigkeit", false),
            WorkerColumn("location", "Standort", false),
            WorkerColumn("object", "Objekt", false),
            WorkerColumn("hire_date", "Eintritt", false),
            WorkerColumn("contract_type", "Vertragsart", false),
            WorkerColumn("base_salary", "Grundgehalt", true),
            WorkerColumn("allowances", "Zulagen", true),
            WorkerColumn("current_status", "Status heute", false),
            WorkerColumn("documents_count", "Dok.", false),
            WorkerColumn("notes", "Anmerkungen", true),
        )

    val presets =
        listOf(
            "Name und Adresse" to listOf("name", "address"),
            "Wie die Excel-Liste" to listOf("first_name", "last_name", "birth_date", "address"),
            "Standard" to
                listOf(
                    "staff_number",
                    "personnel_number",
                    "name",
                    "nationality",
                    "object",
                    "hire_date",
                    "current_status",
                ),
            "Alle Spalten" to all.map { it.id },
        )

    val defaultKeys = listOf("first_name", "last_name", "birth_date", "address")

    /** Zellinhalt: Datum als TT.MM.JJJJ, sensible Werte auf Wunsch maskiert. */
    fun text(w: Entry, id: String, masked: Boolean, status: String, documents: Int): String {
        if (masked && all.firstOrNull { it.id == id }?.sensitive == true) return "••••••"
        fun amount(k: String) = w[k].takeIf { it.isNotBlank() }?.let { runCatching { money(Rules.number(it)) }.getOrDefault(it) }.orEmpty()
        return when (id) {
            "personnel_number" -> w["bewacherId"]
            "staff_number" -> w["personnelNumber"]
            "name" -> w.title
            "first_name" -> w["firstName"]
            "last_name" -> w["lastName"]
            "address" -> Workers.address(w)
            "nationality" -> w["nationality"]
            "identity_number" -> w["identityNumber"]
            "birth_date" -> DateText.german(w["birthDate"])
            "birth_place" -> w["birthPlace"]
            "mobile" -> w["phone"]
            "email" -> w["email"]
            "department" -> w["department"]
            "position" -> w["position"]
            "location" -> w["location"]
            "object" -> w["object"]
            "hire_date" -> DateText.german(w["startDate"])
            "contract_type" -> w["contractType"].ifBlank { w["employmentType"] }
            "base_salary" -> amount("baseSalary")
            "allowances" -> amount("allowances")
            "current_status" -> status
            "documents_count" -> documents.toString()
            "notes" -> w["notes"]
            else -> ""
        }
    }
}

/**
 * Mitarbeiterliste als PDF im Stil „Mitarbeiterdaten“: dunkelblauer Titelbalken, kursiver
 * Untertitel, Kopfzeile auf jeder Seite, Times, feine Gitterlinien; Hoch- oder Querformat.
 */
object WorkerListPdf {
    private const val MARGIN = 34f
    private const val MAX_LINES = 8
    private val NAVY = Color.rgb(31, 43, 74)
    private val HEADER_LINE = Color.rgb(84, 102, 140)
    private val SUBTITLE = Color.rgb(82, 94, 117)
    private val INK = Color.rgb(18, 20, 26)
    private val MUTED = Color.rgb(107, 115, 128)
    private val GRID = Color.rgb(204, 209, 217)
    private const val PAD_X = 6f
    private const val PAD_Y = 5f

    data class Plan(val landscape: Boolean, val size: Float, val widths: List<Float>)

    fun plan(measure: (String, Float, Face) -> Float, labels: List<String>, rows: List<List<String>>): Plan {
        // Längstes Wort (soll nicht zerbrechen) und längste Zeile je Spalte bei 10 pt.
        val metrics =
            labels.indices.map { i ->
                var word = 0f
                var full = 0f
                val texts = listOf(labels[i] to Face.SERIF_BOLD) + rows.map { it[i] to Face.SERIF }
                for ((t, f) in texts) for (line in t.split('\n')) if (line.isNotEmpty()) {
                    val w = measure(line, 10f, f)
                    full = maxOf(full, w)
                    if (w > word) for (part in line.split(Regex("\\s+"))) word = maxOf(word, measure(part, 10f, f))
                }
                word to full
            }
        val padding = PAD_X * 2 + 2
        fun needs(size: Float, usable: Float): Pair<List<Float>, List<Float>> {
            val scale = size / 10
            val least = metrics.map { minOf(it.first * scale + padding, usable * .34f) }
            val want = metrics.mapIndexed { i, m -> maxOf(least[i], minOf(m.second * scale + padding, usable * .45f)) }
            return least to want
        }
        val portraitUsable = 595.2f - MARGIN * 2
        val atPortrait = needs(11f, portraitUsable)
        val landscape = !(atPortrait.second.sum() <= portraitUsable || labels.size <= 5 && atPortrait.first.sum() <= portraitUsable)
        val usable = (if (landscape) 841.8f else 595.2f) - MARGIN * 2
        var size = 11f
        while (size > 9 && needs(size, usable).second.sum() > usable) size -= .5f
        if (needs(size, usable).second.sum() > usable) while (size > 7 && needs(size, usable).first.sum() > usable) size -= .5f
        val (least, want) = needs(size, usable)
        val tw = want.sum()
        val tl = least.sum()
        val widths =
            when {
                tw <= usable -> want.map { it * usable / tw }
                tl <= usable -> {
                    val extra = usable - tl
                    val flexible = tw - tl
                    least.mapIndexed { i, l -> l + if (flexible > 0) (want[i] - l) * extra / flexible else 0f }
                }
                else -> least.map { it * usable / tl }
            }
        return Plan(landscape, size, widths)
    }

    fun build(
        labels: List<String>,
        rows: List<List<String>>,
        footer: String,
        title: String = "Mitarbeiterdaten",
        subtitle: String = "der Mitarbeiter- und Einsatzdaten",
        generated: LocalDate = LocalDate.now(),
    ): ByteArray {
        require(labels.isNotEmpty()) { "Bitte mindestens eine Spalte auswählen." }
        require(rows.isNotEmpty()) { "Keine Mitarbeiter für den gewählten Filter." }
        require(rows.all { it.size == labels.size }) { "Spalten und Werte passen nicht zusammen." }
        val writer = PdfWriter()
        val probe = Sheet(android.graphics.Canvas(), 10f, 10f)
        val measure = { t: String, s: Float, f: Face -> probe.measure(t, s, f) }
        val plan = plan(measure, labels, rows)
        val pw = if (plan.landscape) 841.8f else 595.2f
        val ph = if (plan.landscape) 595.2f else 841.8f
        val tableWidth = pw - MARGIN * 2
        val size = plan.size
        val lineHeight = kotlin.math.ceil(size * 1.25f)
        val headLineHeight = kotlin.math.ceil((size + .5f) * 1.25f)
        val minRow = lineHeight + PAD_Y * 2 + 2
        val bottom = ph - MARGIN - 26f
        val headerCells = labels.mapIndexed { i, l -> PdfKit.wrap(l, plan.widths[i] - PAD_X * 2) { measure(it, size + .5f, Face.SERIF_BOLD) } }
        val headerHeight = maxOf(26f, headerCells.maxOf { it.size } * headLineHeight + PAD_Y * 2 + 2)
        val titleTop = MARGIN
        val subtitleTop = titleTop + 58f + 8
        val firstTableTop = subtitleTop + 32
        data class Row(val top: Float, val height: Float, val cells: List<List<String>>)
        val pages = mutableListOf(mutableListOf<Row>())
        val tableTops = mutableListOf(firstTableTop)
        var y = firstTableTop + headerHeight
        for (values in rows) {
            val cells =
                values.mapIndexed { i, v ->
                    var lines = PdfKit.wrap(v, plan.widths[i] - PAD_X * 2) { measure(it, size, Face.SERIF) }
                    if (lines.size > MAX_LINES) lines = lines.take(MAX_LINES - 1) + (lines[MAX_LINES - 1] + " …")
                    lines
                }
            val h = maxOf(minRow, cells.maxOf { it.size } * lineHeight + PAD_Y * 2)
            if (y + h > bottom && pages.last().isNotEmpty()) {
                pages += mutableListOf<Row>()
                tableTops += MARGIN
                y = MARGIN + headerHeight
            }
            pages.last() += Row(y, h, cells)
            y += h
        }
        val created = "Erstellt am ${generated.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
        val footerText = listOf(created, footer).filter { it.isNotEmpty() }.joinToString(" · ")
        var titleSize = 30f
        while (titleSize > 14 && measure(title, titleSize, Face.SERIF_BOLD) > tableWidth - 24) titleSize -= 1
        pages.forEachIndexed { pageIndex, pageRows ->
            val s = writer.begin(pw, ph)
            s.fill(0f, 0f, pw, ph, Color.WHITE)
            if (pageIndex == 0) {
                s.fill(MARGIN, titleTop, tableWidth, 58f, NAVY)
                val th = s.font(titleSize, Face.SERIF_BOLD).let { it.descent() - it.ascent() }
                s.fit(title, MARGIN, titleTop + (58f - th) / 2, tableWidth, titleSize, titleSize, Face.SERIF_BOLD, Color.WHITE, Align.CENTER)
                s.fit(subtitle, MARGIN, subtitleTop, tableWidth, 14f, 9f, Face.SERIF_ITALIC, SUBTITLE, Align.CENTER)
            }
            val tableTop = tableTops[pageIndex]
            s.fill(MARGIN, tableTop, tableWidth, headerHeight, NAVY)
            var x = MARGIN
            headerCells.forEachIndexed { i, lines ->
                val start = tableTop + (headerHeight - lines.size * headLineHeight) / 2
                lines.forEachIndexed { n, v ->
                    s.fit(v, x + PAD_X, start + n * headLineHeight, plan.widths[i] - PAD_X * 2, size + .5f, 5f, Face.SERIF_BOLD, Color.WHITE, Align.CENTER)
                }
                if (i > 0) s.fill(x - .25f, tableTop + 4, .5f, headerHeight - 8, HEADER_LINE)
                x += plan.widths[i]
            }
            for (row in pageRows) {
                x = MARGIN
                row.cells.forEachIndexed { i, lines ->
                    val start = row.top + maxOf(PAD_Y, (row.height - lines.size * lineHeight) / 2)
                    lines.forEachIndexed { n, v ->
                        s.fit(v, x + PAD_X, start + n * lineHeight, plan.widths[i] - PAD_X * 2 + 1, size, 5f, Face.SERIF, INK)
                    }
                    x += plan.widths[i]
                }
                s.fill(MARGIN, row.top + row.height - .5f, tableWidth, .5f, GRID)
            }
            pageRows.lastOrNull()?.let { last ->
                val bodyTop = tableTop + headerHeight
                val bodyHeight = last.top + last.height - bodyTop
                x = MARGIN
                for (i in 0..plan.widths.size) {
                    val lx = if (i == plan.widths.size) MARGIN + tableWidth - .5f else x
                    s.fill(lx, bodyTop, .5f, bodyHeight, GRID)
                    if (i < plan.widths.size) x += plan.widths[i]
                }
            }
            val footerTop = ph - MARGIN - 10
            s.fill(MARGIN, footerTop - 6, tableWidth, .5f, GRID)
            s.fit(footerText, MARGIN, footerTop, tableWidth - 110, 8.5f, 6f, Face.SERIF_ITALIC, MUTED)
            s.fit("Seite ${pageIndex + 1} von ${pages.size}", MARGIN + tableWidth - 110, footerTop, 110f, 8.5f, 6f, Face.SERIF_ITALIC, MUTED, Align.RIGHT)
        }
        return writer.bytes()
    }
}

object QuestionnairePdf {
    fun fileName(w: Entry): String {
        val person = listOf(w["firstName"], w["lastName"]).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val base = if (person.isEmpty()) "Fragebogen ${w["personnelNumber"]}" else "Fragebogen $person"
        return PdfKit.safeName(base)
    }

    /** Fragebogen der UGS-Vorlage mit den Angaben aus der Personalakte (auch ungespeicherte Entwürfe). */
    fun render(c: Context, w: Entry, company: Map<String, String>): ByteArray {
        require(w["firstName"].isNotBlank() && w["lastName"].isNotBlank()) {
            "Für den Fragebogen Vor- und Nachname getrennt eintragen."
        }
        val template = c.assets.open("templates/fragebogen.pdf").use { it.readBytes() }
        val tops =
            listOf(60.5f, 88.5f, 115f, 141.5f, 168.4f, 196.2f, 223.5f, 250f, 277.2f, 303f, 331.5f, 358.2f, 385.3f, 413f, 440f, 467.2f, 494.4f, 521.2f, 548.5f, 574.8f, 602.5f, 630.8f)
        val birth = listOf(w["birthPlace"], w["birthCountry"]).filter { it.isNotBlank() }.joinToString(" / ")
        val values =
            listOf(
                w["lastName"],
                w["firstName"],
                w["street"],
                "${w["postalCode"]} ${w["city"]}".trim(),
                DateText.german(w["startDate"]),
                DateText.german(w["birthDate"]),
                w["iban"],
                w["bic"],
                w["taxId"],
                w["taxClass"],
                w["religion"],
                w["childAllowance"],
                w["socialId"],
                w["position"],
                listOf(birth, w["nationality"]).filter { it.isNotBlank() }.joinToString("\n"),
                w["healthInsurance"],
                w["weeklyHours"],
                w["vacationDays"],
                w["grossPay"].ifBlank { w["hourlyRate"].takeIf { it.isNotBlank() }?.let { "$it €/h" }.orEmpty() },
                w["secondaryJob"],
                w["secondaryEmployer"],
            )
        val pages = mutableListOf<Pair<Int, (Sheet) -> Unit>>()
        pages +=
            0 to { s: Sheet ->
                val line =
                    listOf(company["name"].orEmpty(), company["street"].orEmpty(), "${company["postalCode"].orEmpty()} ${company["city"].orEmpty()}".trim())
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                s.text(line, 23f, 7f, 390f, 8f)
                s.text("Bewacher ID: ${w["bewacherId"]} · Personalnummer: ${w["personnelNumber"]}", 23f, 27f, 390f, 8f)
                values.forEachIndexed { i, v ->
                    val h = maxOf(10f, tops[i + 1] - tops[i] - 6)
                    s.text(v, 282f, tops[i] + 3, 262f, if (i == 14) 8f else 10f, maxHeight = h + 4)
                }
                s.text("Originalhinweise unverändert; Steuer-ID-Hinweis vor Verwendung fachlich prüfen.", 23f, 819f, 548f, 8f)
            }
        val filled = PdfKit.overlay(template, pages)
        if (w["birthName"].isBlank()) return filled
        val extra =
            PdfWriter().run {
                val s = begin()
                PdfKit.logo(c, s, 410f, 30f, 145f)
                s.text("Anlage · Fragebogen", 40f, 42f, 340f, 14f, Face.BOLD)
                s.text("${w.title} · Bewacher ID ${w["bewacherId"]}", 40f, 70f, 515f, 10f)
                s.text("Geburtsname (zusätzliche Angabe)", 40f, 105f, 515f, 11f, Face.BOLD)
                s.text(w["birthName"], 40f, 128f, 515f, 10f)
                bytes()
            }
        return PdfKit.merge(listOf(filled, extra))
    }
}
