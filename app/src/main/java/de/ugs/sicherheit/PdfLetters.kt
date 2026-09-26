package de.ugs.sicherheit

import android.content.Context
import android.graphics.Color
import org.json.JSONObject

object LetterPdf {
    private const val MARGIN = 57f
    private const val RIGHT = 538f

    fun render(c: Context, w: Entry, company: Map<String, String>, data: LetterData): ByteArray {
        data.validate(w, company)
        val writer = PdfWriter()
        val width = RIGHT - MARGIN
        val stampLines = if (data.kind == LetterData.Kind.PROBATION) emptyList() else PdfKit.stampLines(company)
        var top = 0f
        var number = 0
        lateinit var s: Sheet
        val grey = Color.rgb(166, 166, 166)
        fun begin(first: Boolean) {
            s = writer.begin()
            number++
            if (first) {
                PdfKit.logo(c, s, RIGHT - 134, 30f, 134f)
                top = 35f
                top += maxOf(18f, s.text(company["name"].orEmpty(), MARGIN, top, 330f, 14f, Face.BOLD)) + 2
                top += maxOf(12f, s.text("${company["street"]} · ${company["postalCode"]} ${company["city"]}", MARGIN, top, 330f, 9f)) + 12
                s.line(MARGIN, top, RIGHT, top, grey)
                top = maxOf(112f, top + 14)
                for (v in listOf(w.title, w["street"], "${w["postalCode"]} ${w["city"]}"))
                    top += maxOf(14f, s.text(v, MARGIN, top, 310f, 11f)) + 1
                top += 18
                s.text("${company["city"]}, ${DateText.german(data.letterDate)}", MARGIN, top, 470f, 10f)
                top += 32
                s.text(data.title, MARGIN, top, 470f, 14f, Face.BOLD)
                top += 25
                s.text(
                    listOf("Bewacher ID: ${w["bewacherId"]}".takeIf { w["bewacherId"].isNotBlank() }, "Personalnummer: ${w["personnelNumber"]}")
                        .filterNotNull()
                        .joinToString(" · "),
                    MARGIN,
                    top,
                    470f,
                    9f,
                )
                top += 32
            } else {
                PdfKit.logo(c, s, RIGHT - 112, 25f, 112f)
                s.text("${if (data.kind == LetterData.Kind.WARNING) "Abmahnung" else "Kündigung"} · ${w.title} · ${w["personnelNumber"]}", MARGIN, 32f, 340f, 9f)
                top = 78f
            }
        }
        fun finish() {
            s.line(MARGIN, 786f, RIGHT, 786f, grey)
            s.text("Entwurf zur Prüfung · $number", MARGIN, 795f, width, 8f)
        }
        fun paragraph(v: String, bold: Boolean = false) {
            val h = maxOf(16f, s.textHeight(v, 480f, 11f, if (bold) Face.BOLD else Face.REGULAR))
            if (top + h + 11 > 738) {
                finish()
                begin(false)
            }
            s.text(v, MARGIN, top, 480f, 11f, if (bold) Face.BOLD else Face.REGULAR)
            top += h + 11
        }
        begin(true)
        for ((text, bold) in data.paragraphs(w)) paragraph(text, bold)
        val stampDepth = PdfKit.stampHeight(stampLines)
        val minimum = if (data.kind == LetterData.Kind.PROBATION) 60f else 26f
        if (top + 27 + 6 + 20 + maxOf(stampDepth, minimum) + 10 + 16 + 16 + 22 + 14 > 772) {
            finish()
            begin(false)
        }
        paragraph("Mit freundlichen Grüßen")
        top += 6
        s.text("${company["city"]}, ${DateText.german(data.letterDate)}", MARGIN, top, 480f, 10f)
        top += 20
        val stampTop = top
        val lineTop = stampTop + maxOf(stampDepth, if (data.kind == LetterData.Kind.PROBATION) 60f else 20f) - 14
        s.line(MARGIN, lineTop, 330f, lineTop, grey)
        if (stampDepth > 0) PdfKit.stamp(c, s, stampLines, MARGIN, stampTop, signature = true)
        top = maxOf(lineTop + 10, stampTop + stampDepth + 10)
        paragraph(company["representative"].orEmpty(), true)
        paragraph(company["name"].orEmpty())
        val note =
            if (data.kind != LetterData.Kind.PROBATION)
                "Eingefügte Unterschrift · ersetzt bei einer Kündigung nicht die eigenhändige Unterschrift auf Papier (§ 623 BGB)."
            else "Eigenhändige Unterschrift der berechtigten Person"
        s.text(note, MARGIN, top, 480f, 8f)
        finish()
        return writer.bytes()
    }
}

object CertificatePdf {
    /** Nur Daten in die Freiflächen; das Formular bleibt als Hintergrund unverändert. */
    fun render(c: Context, data: CertificateRequest): ByteArray {
        data.validate()
        val layout = JSONObject(c.assets.open("templates/erweitertes-fuehrungszeugnis-layout.json").bufferedReader().readText())
        val fields = layout.getJSONArray("fields")
        val template = c.assets.open("templates/erweitertes-fuehrungszeugnis.pdf").use { it.readBytes() }
        val values = data.values
        val probe = Sheet(android.graphics.Canvas(), 10f, 10f)
        // Erst alles messen: lange Werte werden nie still abgeschnitten.
        val prepared =
            (0 until fields.length()).map { i ->
                val f = fields.getJSONObject(i)
                val value = values[f.getString("key")].orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                var size = f.getDouble("fontSize").toFloat()
                val min = f.getDouble("minimumFontSize").toFloat()
                val w = f.getDouble("width").toFloat()
                while (probe.measure(value, size) > w && size > min) size = maxOf(min, size - .25f)
                require(probe.measure(value, size) <= w) {
                    "${f.getString("title")}: Der Text ist zu lang für das Formularfeld. Bitte im Exportformular kürzen."
                }
                Triple(f, value, size)
            }
        return PdfKit.overlay(
            template,
            listOf(
                0 to { s: Sheet ->
                    for ((f, value, size) in prepared) {
                        val h = f.getDouble("height").toFloat()
                        val p = s.font(size, Face.REGULAR)
                        val textHeight = p.descent() - p.ascent()
                        s.fit(value, f.getDouble("x").toFloat(), f.getDouble("top").toFloat() + (h - textHeight).coerceAtLeast(0f), f.getDouble("width").toFloat(), size, size)
                    }
                }
            ),
        )
    }
}

object TimesheetPdf {
    private val edges = listOf(70f, 135.5f, 185f, 235f, 285f, 310.5f, 380.5f, 528.5f)

    private fun shortWeekday(v: String) =
        mapOf("montag" to "Mo", "dienstag" to "Di", "mittwoch" to "Mi", "donnerstag" to "Do", "freitag" to "Fr", "samstag" to "Sa", "sonntag" to "So")[v.trim().lowercase()].orEmpty()

    /** Ein ausgefülltes Monatsblatt auf der UGS-Stundenzettel-Vorlage. */
    fun draw(s: Sheet, w: Entry, company: Map<String, String>, month: String, rows: List<TimesheetRow>, planned: Boolean = false, label: String = "") {
        val companyLine =
            listOf(company["name"].orEmpty(), company["street"].orEmpty(), "${company["postalCode"].orEmpty()} ${company["city"].orEmpty()}".trim())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        s.text(companyLine, 58f, 56f, 470f, 9f, maxHeight = 12f)
        s.text(w.title, 125f, 73f, 393f, 9f, maxHeight = 12f)
        // Pers.-Nr. ist die Personalnummer, nie die Bewacher-ID.
        s.text(w["personnelNumber"], 70f, 90f, 98f, 9f, maxHeight = 12f)
        val mm = month.split("-")
        s.text(if (mm.size == 2) "${mm[1]} / ${mm[0]}" else month, 237f, 90f, 95f, 9f, maxHeight = 12f)
        var total = 0
        var hasDuration = false
        for (r in rows) {
            val top = 134.6f + (r.day - 1) * 15.5f
            val wd = shortWeekday(r.weekday)
            if (wd.isNotEmpty()) s.fit(wd, 48f, top + 2, 20f, 6.6f, 5f, align = Align.RIGHT)
            if (r.workFree) s.fill(edges[0], top, edges[7] - edges[0], 15.5f, Color.argb(31, 255, 59, 48))
            val raw = r.duration
            // Arbeitsfreie Tage zeigen immer 0 geplante Stunden.
            val duration = if (r.workFree) raw?.let { 0 } else raw
            if (duration != null) {
                total += duration
                hasDuration = true
            }
            val notes =
                (listOfNotNull("arbeitsfrei".takeIf { r.workFree }) +
                        r.notes.split(" · ").map { it.trim() }.filter { it.isNotEmpty() && !it.equals("arbeitsfrei", true) })
                    .joinToString(" · ")
            val values =
                listOf(
                    r.start,
                    if (r.breakMinutes.isEmpty()) "" else "${r.breakMinutes} Min.",
                    r.end,
                    duration?.let { PdfKit.germanHours(it) }.orEmpty(),
                    r.code,
                    if (r.recordedOn.isEmpty()) "" else DateText.german(r.recordedOn),
                    notes,
                )
            values.forEachIndexed { i, v ->
                s.fit(v, edges[i] + 3, top + 2.5f, edges[i + 1] - edges[i] - 6, 7.2f, 5f, align = if (i == 6) Align.LEFT else Align.CENTER)
            }
        }
        for (day in rows.size + 1..31) s.fit("entfällt", 74f, 136f + (day - 1) * 15.5f, 55f, 8f, 6f, align = Align.CENTER)
        if (hasDuration) s.fit("${PdfKit.germanHours(total)} Std.", 64f, 620f, 148f, 9f, 7f, align = Align.CENTER)
        if (planned) {
            s.text("Dienstplan: geplante Stunden mit festem Pausenabzug von 60 Minuten. Aufzeichnung bitte ergänzen.", 25f, 795f, 470f, 7f)
            s.fit(label, 495f, 795f, 70f, 7f, 6f, align = Align.RIGHT)
        }
    }

    fun render(c: Context, w: Entry, company: Map<String, String>, month: String, rows: List<TimesheetRow>): ByteArray {
        TimesheetRow.validate(month, rows)
        require(w["personnelNumber"].isNotBlank()) { "Bitte bei Mitarbeiter ${w.title} die Personalnummer eintragen." }
        val template = c.assets.open("templates/ugs-stundenzettel.pdf").use { it.readBytes() }
        return PdfKit.overlay(template, listOf(0 to { s: Sheet -> draw(s, w, company, month, rows) }))
    }
}

/** Einfache Berichte (Lohnabrechnung, Mitarbeiterbericht) im UGS-Kopf. */
object ReportPdf {
    fun render(c: Context, title: String, company: Map<String, String>, dateLine: String, sections: List<Pair<String, String>>): ByteArray {
        val writer = PdfWriter(595f, 842f)
        var top = 55f
        var page = 0
        lateinit var s: Sheet
        fun begin() {
            s = writer.begin(595f, 842f)
            page++
            top = 55f
            PdfKit.logo(c, s, 430f, 24f, 120f)
            s.text(company["name"].orEmpty(), 45f, 30f, 360f, 11f, Face.BOLD)
        }
        fun end() = s.text("${company["name"] ?: "UGS Sicherheit GmbH"} · Seite $page", 45f, 812f, 505f, 7.5f, align = Align.CENTER)
        begin()
        top += s.text(title, 45f, top, 500f, 22f, Face.BOLD) + 12
        if (dateLine.isNotEmpty()) {
            s.text(dateLine, 45f, top, 500f, 10f, align = Align.RIGHT)
            top += 24
        }
        for ((heading, body) in sections) {
            if (body.isBlank()) continue
            val h = s.textHeight(body, 505f, 10.5f) + 10
            if (top + h + 30 > 790) {
                end()
                begin()
            }
            s.text(heading, 45f, top, 505f, 12f, Face.BOLD)
            top += 23
            s.text(body, 45f, top, 505f, 10.5f)
            top += h + 10
        }
        end()
        return writer.bytes()
    }

    fun payroll(c: Context, e: Entry, w: Entry, company: Map<String, String>): ByteArray {
        fun n(k: String) = runCatching { Rules.number(e[k].ifBlank { "0" }) }.getOrDefault(0.0)
        val hours = n("hours")
        val rate = n("rate")
        val base = if (e["baseSalary"].isNotBlank()) n("baseSalary") else hours * rate
        val net = base + n("allowances") + n("bonus") - n("deductions")
        val body =
            listOfNotNull(
                    if (hours > 0) "Vergütete Stunden ${decimal(hours)} × ${money(rate)}" else null,
                    "Grundgehalt ${money(base)}",
                    "Zulagen ${money(n("allowances"))}",
                    "Bonus ${money(n("bonus"))}",
                    "Abzüge ${money(n("deductions"))}",
                    "Auszahlungsbetrag ${money(net)}",
                )
                .joinToString("\n")
        return render(
            c,
            "Lohnabrechnung ${e["date"].take(7)}",
            company,
            "",
            listOf(
                "Mitarbeiter" to "${w.title} · ${w["personnelNumber"]}",
                "Abrechnung" to body,
                "Status" to listOf(e["status"], e["paymentStatus"]).filter { it.isNotBlank() }.joinToString(" · "),
                "Notiz" to e["notes"],
                "Hinweis" to "Interne Übersicht; keine gesetzliche Entgeltabrechnung.",
            ),
        )
    }
}
