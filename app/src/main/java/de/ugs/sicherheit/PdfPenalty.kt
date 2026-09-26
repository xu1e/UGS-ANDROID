package de.ugs.sicherheit

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Forderung (Vertragsstrafe) im A4-Rechnungslayout; der Text bleibt durchsuchbar. */
object PenaltyPdf {
    private const val W = 595.2f
    private const val H = 841.8f
    private val TEAL = Color.rgb(56, 143, 153)

    private sealed class Mark {
        data class Text(val v: String, val x: Float, val top: Float, val size: Float, val bold: Boolean, val color: Int) : Mark()

        data class Rect(val x: Float, val top: Float, val w: Float, val h: Float, val color: Int) : Mark()
    }

    fun render(c: Context, data: PenaltyDocument): ByteArray {
        data.validationError?.let { throw IllegalArgumentException(it) }
        val probe = Sheet(android.graphics.Canvas(), 10f, 10f)
        fun width(t: String, size: Float, bold: Boolean = false) = probe.measure(t, size, if (bold) Face.BOLD else Face.REGULAR)
        fun wrap(t: String, w: Float, size: Float, bold: Boolean = false) = PdfKit.wrap(t, w) { width(it, size, bold) }
        val pages = mutableListOf(mutableListOf<Mark>())
        var y = 224f
        fun add(m: Mark) = pages.last().add(m)
        fun text(v: String, x: Float, top: Float, size: Float = 9.5f, bold: Boolean = false, color: Int = Color.BLACK) = add(Mark.Text(v, x, top, size, bold, color))
        fun rect(x: Float, top: Float, w: Float, h: Float, color: Int) = add(Mark.Rect(x, top, w, h, color))
        fun fixed(v: String, x: Float, top: Float, w: Float, maxLines: Int, size: Float = 9.5f, bold: Boolean = false, label: String, into: MutableList<Mark> = pages.last()) {
            val lines = wrap(v, w, size, bold)
            require(lines.size <= maxLines) { "$label: Text zu lang für den Briefkopf oder die Fußzeile. Bitte kürzen." }
            lines.forEachIndexed { i, l -> into += Mark.Text(l, x, top + i * (size + 3), size, bold, Color.BLACK) }
        }
        fun nextPage() {
            pages += mutableListOf<Mark>()
            y = 104f
            text("Vertragsstrafe · Fortsetzung", 44f, y, 12f, true)
            y += 24
        }
        fun ensure(space: Float) {
            if (y + space > 738) nextPage()
        }
        fun paragraph(v: String, bold: Boolean = false) {
            if (v.isEmpty()) return
            for (line in wrap(v, 507f, 9.5f, bold)) {
                ensure(13f)
                text(line, 44f, y, bold = bold)
                y += 13
            }
            y += 8
        }
        fun metadata(label: String, value: String) {
            wrap(value, 390f, 9.5f).forEachIndexed { i, line ->
                ensure(13f)
                if (i == 0) text(label, 44f, y, bold = true)
                text(line, 161f, y)
                y += 13
            }
            y += 2
        }
        fun header() {
            rect(44f, y, 507f, 23f, TEAL)
            for ((t, x) in listOf("Pos" to 48f, "Beschreibung" to 74f, "Menge" to 325f, "Pauschale" to 377f, "Gesamtpreis" to 459f))
                text(t, x, y + 6, 9f, true, Color.WHITE)
            y += 23
        }
        fun amount(v: String, right: Float, top: Float, maxWidth: Float, bold: Boolean = false) {
            require(width(v, 9.5f, bold) <= maxWidth) { "Betrag zu groß für die PDF-Spalte." }
            text(v, right - width(v, 9.5f, bold), top, bold = bold)
        }
        fixed(listOf(data.issuer, data.issuerAddress.replace("\n", " · ")).joinToString(" · "), 44f, 99f, 330f, 2, 7.5f, label = "Absenderzeile")
        rect(44f, 121f, 330f, .7f, TEAL)
        fixed(data.recipient, 44f, 132f, 315f, 2, 11f, true, "Empfänger")
        fixed(data.recipientAddress, 44f, 164f, 315f, 3, label = "Empfängeranschrift")
        fixed(listOf(data.issuer, data.issuerAddress, data.phone, data.email).filter { it.isNotEmpty() }.joinToString("\n"), 395f, 103f, 156f, 9, 8f, label = "Absenderkontakt")
        metadata("Datum:", DateText.german(data.date))
        if (data.customerNumber.isNotEmpty()) metadata("Kundennummer:", data.customerNumber)
        metadata("Leistungstitel:", data.title)
        metadata("Leistungszeitraum:", data.servicePeriod)
        metadata("Forderungsnr.:", data.invoiceNumber)
        y += 12
        ensure(60f)
        header()
        data.lines.forEachIndexed { i, row ->
            val description = wrap(row.description, 241f, 9.5f)
            val h = maxOf(31f, description.size * 13f + 14)
            require(h <= 570) { "Position ${i + 1}: Bitte die lange Beschreibung auf mehrere Positionen aufteilen." }
            if (y + h > 738) {
                nextPage()
                header()
            }
            text("${i + 1}", 48f, y + 7)
            description.forEachIndexed { n, l -> text(l, 74f, y + 7 + n * 13) }
            amount(row.quantity.trim().replace(".", ","), 366f, y + 7, 45f)
            amount(Money.text(Money.number(row.unitPrice)!!), 448f, y + 7, 74f)
            amount(Money.text(row.amount!!), 547f, y + 7, 94f)
            y += h
            rect(44f, y, 507f, .5f, Color.LTGRAY)
        }
        y += 19
        if (data.site.isNotEmpty()) paragraph("Objekt: ${data.site}", true)
        if (data.incidentDate.isNotEmpty()) paragraph("Tag: ${DateText.german(data.incidentDate)}", true)
        paragraph(data.incident)
        ensure(86f)
        val taxLabel = if (data.taxTreatment == TaxTreatment.DAMAGES) "Umsatzsteuer (nicht steuerbar)" else "Umsatzsteuer ${data.taxPercent} %"
        listOf("Forderungsbetrag" to data.net, taxLabel to data.tax, "Gesamtbetrag" to data.total).forEachIndexed { i, (label, v) ->
            val bold = i == 2
            rect(295f, y, 256f, 24f, if (bold) Color.rgb(240, 240, 240) else Color.WHITE)
            rect(295f, y, 256f, .5f, Color.GRAY)
            rect(295f, y, .5f, 24f, Color.GRAY)
            rect(550.5f, y, .5f, 24f, Color.GRAY)
            text(label, 303f, y + 6, bold = bold)
            amount(Money.text(v), 544f, y + 6, 112f, bold)
            y += 24
        }
        rect(295f, y, 256f, .5f, Color.GRAY)
        y += 20
        paragraph("Grundlage der Forderung", true)
        paragraph(data.legalBasis)
        if (data.taxTreatment == TaxTreatment.DAMAGES) paragraph("Echter Schadenersatz / Vertragsstrafe: kein umsatzsteuerbares Leistungsentgelt.")
        paragraph(data.closing)
        paragraph(data.paymentTerms, true)
        ensure(68f)
        paragraph("Mit freundlichen Grüßen")
        y += 15
        paragraph(listOf(data.representative, data.issuer).filter { it.isNotEmpty() }.joinToString("\n"))
        // Wiederkehrende Kopf- und Fußzeilen getrennt sammeln, damit sie den Umbruch nicht ändern.
        val decorated =
            pages.mapIndexed { index, marks ->
                val deco = mutableListOf<Mark>()
                deco += Mark.Text("Seite ${index + 1} von ${pages.size}", 273f, 35f, 8f, false, Color.BLACK)
                fixed(data.website, 406f, 35f, 145f, 3, 8f, label = "Website", into = deco)
                deco += Mark.Rect(44f, 768f, 507f, 1f, TEAL)
                fixed(listOf(data.issuer, data.representative, data.issuerAddress).filter { it.isNotEmpty() }.joinToString("\n"), 44f, 781f, 173f, 5, 7.5f, label = "Firmenfußzeile", into = deco)
                fixed(data.registration, 233f, 781f, 144f, 5, 7.5f, label = "Register / Steuernummer", into = deco)
                fixed(data.bank, 396f, 781f, 155f, 5, 7.5f, label = "Bankverbindung", into = deco)
                marks + deco
            }
        val writer = PdfWriter(W, H)
        for (marks in decorated) {
            val s = writer.begin(W, H)
            PdfKit.logo(c, s, 44f, 27f, 160f)
            for (m in marks)
                when (m) {
                    is Mark.Text -> s.fit(m.v, m.x, m.top, W, m.size, m.size, if (m.bold) Face.BOLD else Face.REGULAR, m.color)
                    is Mark.Rect -> s.fill(m.x, m.top, m.w, m.h, m.color)
                }
        }
        return writer.bytes()
    }
}

/** Kontrollprotokoll – Sicherheitsdienst, mit Fortsetzungsanlage für lange Bemerkungen. */
object InspectionPdf {
    private const val H = 841.8f
    private val BLUE = Color.rgb(61, 128, 184)

    private sealed class Mark {
        data class Text(val v: String, val x: Float, val top: Float, val size: Float, val bold: Boolean, val blue: Boolean) : Mark()

        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Mark()

        data class Signature(val strokes: List<List<Pair<Float, Float>>>, val x: Float, val y: Float, val w: Float, val h: Float) : Mark()
    }

    fun render(c: Context, report: InspectionReport): ByteArray {
        report.validationError?.let { throw IllegalArgumentException(it) }
        val probe = Sheet(android.graphics.Canvas(), 10f, 10f)
        fun wrap(t: String, w: Float, size: Float, bold: Boolean = false) = PdfKit.wrap(t, w) { probe.measure(it, size, if (bold) Face.BOLD else Face.REGULAR) }
        val pages = mutableListOf(mutableListOf<Mark>())
        val continuation = mutableListOf<Pair<String, String>>()
        fun add(m: Mark) = pages.last().add(m)
        fun text(v: String, x: Float, y: Float, size: Float = 9f, bold: Boolean = false, blue: Boolean = false) = add(Mark.Text(v, x, y, size, bold, blue))
        fun line(x: Float, y: Float, x2: Float, y2: Float) = add(Mark.Line(x, y, x2, y2))
        fun check(x: Float, y: Float, selected: Boolean) {
            line(x, y, x + 7, y)
            line(x + 7, y, x + 7, y + 7)
            line(x + 7, y + 7, x, y + 7)
            line(x, y + 7, x, y)
            if (selected) {
                line(x + 1, y + 1, x + 6, y + 6)
                line(x + 1, y + 6, x + 6, y + 1)
            }
        }
        fun fixed(v: String, x: Float, y: Float, w: Float, maxLines: Int, label: String, size: Float = 9f) {
            val lines = wrap(v, w, size)
            require(lines.size <= maxLines) { "Kontrollprotokoll – $label: Bitte den Kopftext kürzen." }
            lines.forEachIndexed { i, l -> text(l, x, y + i * (size + 2), size) }
        }
        text("Kontrollprotokoll – Sicherheitsdienst", 44f, 78f, 14f, true)
        fixed("Protokoll: ${report.reference}", 44f, 99f, 340f, 1, "Protokollnummer", 8f)
        for ((label, value, x, y) in
            listOf(
                Quad("Objektname", report.site, 44f, 120f),
                Quad("Adresse", report.address, 44f, 145f),
                Quad("Ausweis-Nr.", report.badgeNumber, 44f, 170f),
                Quad("Kontrolleur", report.controller, 326f, 120f),
                Quad("Datum", DateText.german(report.date), 326f, 145f),
                Quad("Uhrzeit der Kontrolle", report.time, 326f, 170f),
            )) {
            val offset = if (label == "Uhrzeit der Kontrolle") 122f else 69f
            text("$label:", x, y, 9f, true)
            fixed(value, x + offset, y, (if (x == 44f) 246f else 225f) - offset, 2, label, 8.5f)
            line(x + offset, y + 21, if (x == 44f) 290f else 551f, y + 21)
        }
        fixed(listOf(report.workerName, if (report.staffNumber.isEmpty()) "" else "Personalnummer: ${report.staffNumber}").filter { it.isNotEmpty() }.joinToString(" · "), 44f, 196f, 507f, 1, "Mitarbeiter", 8f)
        text("Kontrollpunkte", 44f, 215f, 11f, true, true)
        val xs = listOf(44f, 70f, 300f, 322f, 348f, 551f)
        var y = 237f
        val start = y
        listOf("Nr.", "Prüfpunkte", "Ja", "Nein", "Anmerkungen / Bemerkungen").forEachIndexed { i, l -> text(l, xs[i] + 4, y + 5, if (i == 3) 8f else 9f, true) }
        line(44f, y, 551f, y)
        y += 22
        line(44f, y, 551f, y)
        for (p in report.points) {
            val rowHeight = if (p.id == "roster" || p.id == "language") 30f else 17f
            text(p.number, 49f, y + 4, 8.5f)
            p.title.split("\n").forEachIndexed { i, v -> text(v, 74f, y + 4 + i * 11, 8.5f) }
            var comment = p.remarks
            var commentX = 353f
            if (p.id == "language") {
                check(307f, y + 5, report.language == InspectionLanguage.VERY_GOOD)
                check(331f, y + 5, report.language == InspectionLanguage.GOOD)
                check(356f, y + 5, report.language == InspectionLanguage.POOR)
                text("1", 308f, y + 16, 8f)
                text("2", 332f, y + 16, 8f)
                text("3", 357f, y + 16, 8f)
                commentX = 372f
                if (report.language == InspectionLanguage.NOT_APPLICABLE) comment = "Entfällt. $comment"
            } else {
                check(307f, y + 5, p.answer == InspectionAnswer.YES)
                check(331f, y + 5, p.answer == InspectionAnswer.NO)
                if (p.answer == InspectionAnswer.NOT_APPLICABLE) comment = "Entfällt. $comment"
                if (p.id == "roster" && report.alternateWorker.isNotEmpty())
                    comment = "Andere Person: ${report.alternateWorker}" + if (comment.isEmpty()) "" else "\n$comment"
            }
            val lines = wrap(comment, 547 - commentX, 8f)
            val capacity = ((rowHeight - 6) / 10).toInt()
            if (lines.size > capacity) {
                val label = if (p.number.isEmpty()) "1 · ${p.title}" else "${p.number} · ${p.title.replace("\n", " ")}"
                continuation += label to comment
                text("Siehe Anlage, Eintrag ${continuation.size}", commentX, y + 4, 8f)
            } else lines.forEachIndexed { i, v -> text(v, commentX, y + 4 + i * 10, 8f) }
            y += rowHeight
            line(44f, y, 551f, y)
        }
        for (x in xs) line(x, start, x, y)
        y += 18
        text("Gesamtbewertung der Kontrolle", 44f, y, 11f, true, true)
        y += 22
        check(45f, y + 2, report.overall == InspectionOverall.OKAY)
        text("Alles in Ordnung", 58f, y)
        check(203f, y + 2, report.overall == InspectionOverall.DEFECTS)
        text("Mängel festgestellt (siehe oben)", 216f, y)
        y += 25
        text("Bericht / Bemerkungen des Kontrolleurs:", 44f, y, 11f, true, true)
        y += 20
        val remarks = wrap(report.remarks, 500f, 9f)
        if (remarks.size > 3) {
            continuation += "Bericht / Bemerkungen des Kontrolleurs" to report.remarks
            text("Vollständiger Bericht: siehe Anlage, Eintrag ${continuation.size}.", 46f, y)
        } else remarks.forEachIndexed { i, v -> text(v, 46f, y + i * 16, 9f) }
        for (i in 0 until 3) line(44f, y + 14 + i * 16, 551f, y + 14 + i * 16)
        text("Unterschrift Kontrolleur:", 44f, 784f)
        add(Mark.Signature(report.signature, 174f, 747f, 220f, 46f))
        line(172f, 797f, 415f, 797f)
        if (continuation.isNotEmpty()) {
            pages += mutableListOf<Mark>()
            y = 124f
            text("Kontrollprotokoll · Anlage", 44f, 78f, 14f, true)
            continuation.forEachIndexed { index, (label, body) ->
                val heading = wrap("Eintrag ${index + 1} – $label", 507f, 10f, true)
                if (y + heading.size * 14 + 28 > 775) {
                    pages += mutableListOf<Mark>()
                    y = 124f
                    text("Kontrollprotokoll · Anlage (Fortsetzung)", 44f, 78f, 14f, true)
                }
                for (v in heading) {
                    text(v, 44f, y, 10f, true)
                    y += 14
                }
                for (v in wrap(body, 507f, 9f)) {
                    if (y + 13 > 775) {
                        pages += mutableListOf<Mark>()
                        y = 124f
                        text("Kontrollprotokoll · Anlage (Fortsetzung)", 44f, 78f, 14f, true)
                        text("Fortsetzung Eintrag ${index + 1}", 44f, 111f, 8f, true)
                    }
                    text(v, 44f, y, 9f)
                    y += 13
                }
                y += 14
            }
        }
        val writer = PdfWriter(595.2f, H)
        pages.forEachIndexed { index, marks ->
            val s = writer.begin(595.2f, H)
            PdfKit.logo(c, s, 423f, 39f, 128f)
            val all = marks.toMutableList()
            all += Mark.Text("UGS Sicherheit · Kontrollprotokoll 09/2026 · ${report.reference} · ${index + 1}/${pages.size}", 44f, 816f, 7f, false, false)
            if (index > 0) all += Mark.Text("${report.reference} · ${DateText.german(report.date)}", 44f, 99f, 8f, false, false)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.BLACK
                strokeWidth = .45f
            }
            for (m in all)
                when (m) {
                    is Mark.Text -> s.fit(m.v, m.x, m.top, 600f, m.size, m.size, if (m.bold) Face.BOLD else Face.REGULAR, if (m.blue) BLUE else Color.BLACK)
                    is Mark.Line -> s.canvas.drawLine(m.x1, m.y1, m.x2, m.y2, stroke)
                    is Mark.Signature -> {
                        val sig = Paint(stroke).apply { strokeWidth = 1f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
                        for (st in m.strokes) {
                            val first = st.firstOrNull() ?: continue
                            val path = Path().apply { moveTo(m.x + first.first * m.w, m.y + first.second * m.h) }
                            for ((px, py) in st.drop(1)) path.lineTo(m.x + px * m.w, m.y + py * m.h)
                            s.canvas.drawPath(path, sig)
                        }
                    }
                }
        }
        return writer.bytes()
    }

    private data class Quad(val a: String, val b: String, val c: Float, val d: Float)
}

object PenaltyPackage {
    fun render(c: Context, claim: PenaltyDocument, report: InspectionReport, mode: PenaltyMode, attachments: List<ByteArray>): ByteArray {
        if (mode.includesReport) report.validationError?.let { throw IllegalArgumentException(it) }
        if (mode.includesClaim) claim.validationError?.let { throw IllegalArgumentException(it) }
        val parts = mutableListOf<ByteArray>()
        if (mode.includesClaim) parts += PenaltyPdf.render(c, claim)
        if (mode.includesReport) parts += InspectionPdf.render(c, report)
        parts += attachments
        return PdfKit.merge(parts)
    }
}
