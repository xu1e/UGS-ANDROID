package de.ugs.sicherheit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Ein Stempel im Dokument (wie iOS Build 36 / Mac 5.9.33). Position als Anteil der
 * Seite, oben links gemessen; Breite als Anteil der Seitenbreite.
 */
data class StampPlacement(
    val page: Int,
    val x: Float,
    val y: Float,
    val width: Float = DEFAULT_WIDTH,
    val showDate: Boolean = true,
    val dateScale: Float = 1f,
    val source: Source = Source.MANUAL,
    val id: String = UUID.randomUUID().toString(),
) {
    enum class Source(val label: String) {
        TEXT("Unterschriftsfeld in der Textebene gefunden"),
        MANUAL("Position von Hand gesetzt"),
        FALLBACK("Unterschriftsfeld nicht gefunden – bitte in der Vorschau antippen"),
    }

    fun copy(toPage: Int) = copy(page = toPage, id = UUID.randomUUID().toString())

    companion object {
        const val DEFAULT_WIDTH = .34f
        val widthRange = .07f..0.9f
        val dateRange = .5f..3f
    }
}

object ContractStamp {
    /** Seitenhöhe/-breite des Stempelbilds (ohne Datum). */
    const val RATIO_PLAIN = 370f / 1200f
    const val RATIO_SIGNED = 620f / 1200f

    fun lines(company: Map<String, String>) =
        listOf(
                company["name"].orEmpty(),
                company["street"].orEmpty(),
                "${company["postalCode"].orEmpty()} ${company["city"].orEmpty()}",
                listOf(company["phone"], company["email"]).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
            )
            .map { it.trim() }
            .filter { it.isNotBlank() }

    /** Stempelbild ohne Datum; optional mit hinterlegter Arbeitgeberunterschrift. */
    fun bitmap(c: Context, company: Map<String, String>, signature: Boolean): Bitmap {
        val stamp = Bitmap.createBitmap(1200, if (signature) 620 else 370, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stamp)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(19, 56, 105)
                textSize = 38f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
        var top = 58f
        for (line in lines(company)) {
            paint.textSize = 38f
            paint.textSize = minOf(38f, 38f * 1120f / paint.measureText(line).coerceAtLeast(1f))
            canvas.drawText(line, 28f, top, paint)
            top += 60f
            paint.typeface = Typeface.DEFAULT
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(19, 56, 105); style = Paint.Style.STROKE; strokeWidth = 5f }
        canvas.drawRect(6f, 6f, 1194f, top - 30f, border)
        if (signature) {
            val sig = c.assets.open("contracts/ugs-unterschrift.png").use { BitmapFactory.decodeStream(it) }
            val h = 1200f * sig.height / sig.width
            canvas.drawBitmap(sig, null, RectF(20f, top, 1180f, minOf(top + h, 610f)), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            sig.recycle()
        }
        return stamp
    }

    /** „alle“ oder „1-3, 5“ → 0-basierte Seitenindizes. */
    fun pageIndices(text: String, count: Int): List<Int> {
        val t = text.trim().lowercase()
        if (count <= 0) return emptyList()
        if (t == "alle" || t == "all" || t == "*") return (0 until count).toList()
        val out = sortedSetOf<Int>()
        for (part in t.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val range = part.split('-').map { it.trim() }
            val a = range.getOrNull(0)?.toIntOrNull() ?: return emptyList()
            val b = if (range.size == 2) range[1].toIntOrNull() ?: return emptyList() else a
            if (range.size > 2 || a < 1 || b < a || b > count) return emptyList()
            for (p in a..b) out += p - 1
        }
        return out.toList()
    }

    /** Foto (JPEG/PNG/HEIC per Android-Decoder) als einseitiges A4-PDF. */
    fun imageToPdf(data: ByteArray): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0) { "Das Bild kann nicht gelesen werden." }
        var sample = 1
        while (opts.outWidth / sample > 2600 || opts.outHeight / sample > 3600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Das Bild kann nicht gelesen werden.")
        val doc = PdfDocument()
        try {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            val scale = minOf(595f / bmp.width, 842f / bmp.height)
            val w = bmp.width * scale
            val h = bmp.height * scale
            page.canvas.drawBitmap(bmp, null, RectF((595 - w) / 2, (842 - h) / 2, (595 + w) / 2, (842 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
            return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally {
            doc.close()
            bmp.recycle()
        }
    }

    fun pageCount(data: ByteArray) = PDDocument.load(data).use { it.numberOfPages }

    /** Seitenverhältnisse (Höhe/Breite) für die Vorschau-Rahmen. */
    fun pageRatios(data: ByteArray) = PDDocument.load(data).use { d -> (0 until d.numberOfPages).map { d.getPage(it).cropBox.let { b -> b.height / b.width } } }

    /** Sucht „Firmenstempel“ / „Unterschrift Arbeitgeber“ in der Textebene; Stempel über dem Feld. */
    fun locate(data: ByteArray): StampPlacement =
        runCatching {
                PDDocument.load(data).use { d ->
                    val hits = mutableListOf<Triple<Int, Float, Float>>() // page, xFrac, yFrac(top of label)
                    val stripper =
                        object : PDFTextStripper() {
                            var page = 0

                            override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                                if (text == null || positions == null) return super.writeString(text, positions)
                                val lower = text.lowercase()
                                for (term in listOf("firmenstempel", "unterschrift arbeitgeber", "unterschrift des arbeitgebers")) {
                                    val i = lower.indexOf(term)
                                    if (i < 0 || i >= positions.size) continue
                                    val p = positions[i]
                                    val box = d.getPage(page).cropBox
                                    hits += Triple(page, (p.xDirAdj / box.width).coerceIn(0f, 1f), ((p.yDirAdj - p.heightDir) / box.height).coerceIn(0f, 1f))
                                }
                                super.writeString(text, positions)
                            }
                        }
                    for (index in d.numberOfPages - 1 downTo 0) {
                        stripper.page = index
                        stripper.startPage = index + 1
                        stripper.endPage = index + 1
                        stripper.getText(d)
                        val hit = hits.filter { it.first == index }.maxByOrNull { it.third } ?: continue
                        val ratio = d.getPage(index).cropBox.let { it.width / it.height }
                        // Stempel steht über der Beschriftung und überlappt die Linie etwas.
                        val height = StampPlacement.DEFAULT_WIDTH * RATIO_SIGNED * ratio
                        return@use StampPlacement(index, hit.second, (hit.third - height - .005f).coerceAtLeast(0f), source = StampPlacement.Source.TEXT)
                    }
                    StampPlacement(d.numberOfPages - 1, .1f, .72f, source = StampPlacement.Source.FALLBACK)
                }
            }
            .getOrElse { StampPlacement(0, .1f, .72f, source = StampPlacement.Source.FALLBACK) }

    /** Setzt alle Stempel; das Ausgangs-PDF bleibt unverändert. */
    fun render(c: Context, data: ByteArray, company: Map<String, String>, placements: List<StampPlacement>, date: String, signature: Boolean): ByteArray {
        require(placements.isNotEmpty()) { "Bitte mindestens einen Stempel setzen." }
        val stamp = bitmap(c, company, signature)
        try {
            return PDDocument.load(data).use { d ->
                val image = LosslessFactory.createFromImage(d, stamp)
                val dateText = if (date.isNotBlank()) Rules.german(date) else ""
                for (s in placements) {
                    require(s.page in 0 until d.numberOfPages) { "Seitenzahl außerhalb des Dokuments." }
                    val p = d.getPage(s.page)
                    require(p.rotation == 0) { "Gedrehte PDF-Seiten zuerst in der Ausgangsdatei aufrichten." }
                    val box = p.cropBox
                    val w = box.width * s.width.coerceIn(StampPlacement.widthRange)
                    val h = w * stamp.height / stamp.width
                    val px = (s.x * box.width).coerceIn(0f, (box.width - w).coerceAtLeast(0f)) + box.lowerLeftX
                    val py = (box.height - s.y * box.height - h).coerceIn(0f, (box.height - h).coerceAtLeast(0f)) + box.lowerLeftY
                    PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.drawImage(image, px, py, w, h)
                        if (s.showDate && dateText.isNotBlank()) {
                            val size = 11f * s.dateScale.coerceIn(StampPlacement.dateRange)
                            cs.beginText()
                            cs.setFont(PDType1Font.HELVETICA_BOLD, size)
                            cs.setNonStrokingColor(19, 56, 105)
                            cs.newLineAtOffset(px, (py - size - 4).coerceAtLeast(box.lowerLeftY + 2))
                            cs.showText("Datum: $dateText")
                            cs.endText()
                        }
                    }
                }
                ByteArrayOutputStream().also { d.save(it) }.toByteArray()
            }
        } finally {
            stamp.recycle()
        }
    }

    // --- Mitarbeiter und E-Mail (iOS Build 36) ---

    fun findWorker(workers: List<Entry>, input: String): Entry? {
        val key = input.trim().lowercase()
        if (key.isEmpty()) return null
        return workers.firstOrNull { it["bewacherId"].trim().lowercase() == key } ?: workers.firstOrNull { it["personnelNumber"].trim().lowercase() == key }
    }

    fun workerLine(w: Entry) =
        listOfNotNull(
                w["bewacherId"].takeIf { it.isNotBlank() }?.let { "Bewacher ID $it" },
                w["personnelNumber"].takeIf { it.isNotBlank() }?.let { "Pers.-Nr. $it" },
                w.title,
            )
            .joinToString(" · ")

    fun subject(company: Map<String, String>): String {
        val firma = company["name"].orEmpty().trim()
        return if (firma.isEmpty()) "Ihr gegengezeichneter Arbeitsvertrag" else "Ihr gegengezeichneter Arbeitsvertrag – $firma"
    }

    val layout = Mail.Layout(eyebrow = "Arbeitsvertrag", headline = "Ihr gegengezeichneter Arbeitsvertrag")

    fun body(worker: Entry?, company: Map<String, String>, returned: Boolean): String {
        val salutation = when (worker?.get("gender")) { "männlich" -> "Herr"; "weiblich" -> "Frau"; else -> "" }
        val name = listOf(worker?.get("firstName").orEmpty(), worker?.get("lastName").orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val opening =
            when {
                salutation.isNotEmpty() && !worker?.get("lastName").isNullOrBlank() -> Mail.greeting(salutation, worker!!["lastName"])
                name.isNotEmpty() -> "Guten Tag $name,"
                else -> "Sehr geehrte Damen und Herren,"
            }
        val first =
            if (returned) "vielen Dank für die Rücksendung Ihres unterschriebenen Arbeitsvertrages. Anbei erhalten Sie ihn – von uns mit Firmenstempel und Unterschrift gegengezeichnet – für Ihre Unterlagen."
            else "anbei erhalten Sie Ihren Arbeitsvertrag – von Ihnen unterschrieben und von uns mit Firmenstempel und Unterschrift gegengezeichnet – für Ihre Unterlagen."
        val middle = "$first\n\nBitte bewahren Sie das Dokument sorgfältig auf. Bei Fragen melden Sie sich gerne jederzeit."
        return listOf(opening, middle, Mail.signature(company)).filter { it.isNotEmpty() }.joinToString("\n\n") + "\n"
    }

    fun fileName(worker: Entry?): String {
        worker ?: return "Arbeitsvertrag-gestempelt.pdf"
        val number = worker["bewacherId"].ifBlank { worker["personnelNumber"] }
        val base = listOf("Arbeitsvertrag", number, worker["lastName"].ifBlank { worker.title }).filter { it.isNotBlank() }.joinToString("-")
        return base.replace(Regex("[/:\\s]+"), "-") + "-gestempelt.pdf"
    }
}
