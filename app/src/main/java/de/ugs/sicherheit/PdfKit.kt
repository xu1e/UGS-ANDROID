package de.ugs.sicherheit

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import java.io.ByteArrayOutputStream

/** Schriftarten für erzeugte PDFs. */
enum class Face {
    REGULAR,
    BOLD,
    SEMIBOLD,
    ITALIC,
    SERIF,
    SERIF_BOLD,
    SERIF_ITALIC;

    val typeface: Typeface
        get() =
            when (this) {
                REGULAR -> Typeface.create("sans-serif", Typeface.NORMAL)
                BOLD -> Typeface.create("sans-serif", Typeface.BOLD)
                SEMIBOLD -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                ITALIC -> Typeface.create("sans-serif", Typeface.ITALIC)
                SERIF -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                SERIF_BOLD -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                SERIF_ITALIC -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
}

enum class Align {
    LEFT,
    CENTER,
    RIGHT,
}

/**
 * Zeichenfläche einer PDF-Seite mit Koordinaten von oben links (wie die iOS-Generatoren),
 * Einheiten in PDF-Punkten.
 */
class Sheet(val canvas: Canvas, val width: Float, val height: Float) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    fun font(size: Float, face: Face = Face.REGULAR, color: Int = Color.BLACK): TextPaint {
        paint.textSize = size
        paint.typeface = face.typeface
        paint.color = color
        return paint
    }

    fun measure(text: String, size: Float, face: Face = Face.REGULAR): Float =
        font(size, face).measureText(text)

    fun layout(text: String, width: Float, size: Float, face: Face, align: Align, color: Int, spacing: Float = 2f): StaticLayout {
        val p = TextPaint(font(size, face, color))
        return StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(1))
            .setAlignment(
                when (align) {
                    Align.LEFT -> Layout.Alignment.ALIGN_NORMAL
                    Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                    Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                }
            )
            .setIncludePad(false)
            .setLineSpacing(spacing, 1f)
            .build()
    }

    /** Höhe eines umbrochenen Texts. */
    fun textHeight(text: String, width: Float, size: Float, face: Face = Face.REGULAR): Float =
        if (text.isEmpty()) 0f else layout(text, width, size, face, Align.LEFT, Color.BLACK).height.toFloat()

    /** Umbrochener Text; liefert die gezeichnete Höhe. Mit [maxHeight] wird abgeschnitten. */
    fun text(
        text: String,
        x: Float,
        top: Float,
        width: Float,
        size: Float,
        face: Face = Face.REGULAR,
        color: Int = Color.BLACK,
        align: Align = Align.LEFT,
        maxHeight: Float = Float.MAX_VALUE,
    ): Float {
        if (text.isEmpty()) return 0f
        val l = layout(text, width, size, face, align, color)
        canvas.save()
        canvas.translate(x, top)
        if (maxHeight < Float.MAX_VALUE) canvas.clipRect(0f, 0f, width, maxHeight)
        l.draw(canvas)
        canvas.restore()
        return minOf(l.height.toFloat(), maxHeight)
    }

    /** Einzeilig; verkleinert die Schrift bis [minSize], damit der Text in [width] passt. */
    fun fit(
        text: String,
        x: Float,
        top: Float,
        width: Float,
        size: Float,
        minSize: Float = 6f,
        face: Face = Face.REGULAR,
        color: Int = Color.BLACK,
        align: Align = Align.LEFT,
    ) {
        if (text.isEmpty()) return
        var s = size
        while (s > minSize && measure(text, s, face) > width) s -= .25f
        val p = font(s, face, color)
        val w = p.measureText(text)
        val dx =
            when (align) {
                Align.LEFT -> 0f
                Align.CENTER -> (width - w) / 2
                Align.RIGHT -> width - w
            }
        canvas.drawText(text, x + dx.coerceAtLeast(0f), top - p.ascent(), p)
    }

    fun fill(x: Float, top: Float, w: Float, h: Float, color: Int) {
        canvas.drawRect(x, top, x + w, top + h, Paint().apply { this.color = color })
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int = Color.rgb(166, 166, 166), width: Float = .5f) {
        canvas.drawLine(x1, y1, x2, y2, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width })
    }

    fun rect(x: Float, top: Float, w: Float, h: Float, color: Int = Color.rgb(166, 166, 166), width: Float = .45f) {
        canvas.drawRect(
            x,
            top,
            x + w,
            top + h,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = width
            },
        )
    }

    fun image(bitmap: Bitmap, x: Float, top: Float, w: Float, h: Float, alpha: Int = 255) {
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(x, top, x + w, top + h),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha },
        )
    }
}

/** Mehrseitiges PDF, Seite für Seite gezeichnet. */
class PdfWriter(private val pageWidth: Float = 595.28f, private val pageHeight: Float = 841.89f) {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var count = 0
    lateinit var sheet: Sheet
        private set

    val pages
        get() = count

    fun begin(width: Float = pageWidth, height: Float = pageHeight): Sheet {
        end()
        count++
        require(count <= 400) { "Dokument zu umfangreich." }
        val p = doc.startPage(PdfDocument.PageInfo.Builder(width.toInt(), height.toInt(), count).create())
        page = p
        sheet = Sheet(p.canvas, width, height)
        return sheet
    }

    fun end() {
        page?.let { doc.finishPage(it) }
        page = null
    }

    fun bytes(): ByteArray =
        try {
            end()
            require(count > 0) { "PDF konnte nicht erstellt werden." }
            ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally {
            doc.close()
        }
}

object PdfKit {
    private var wordmark: Bitmap? = null
    private var signature: Bitmap? = null

    fun wordmark(c: Context): Bitmap =
        wordmark ?: c.assets.open("branding/ugs-wordmark-light.png").use { BitmapFactory.decodeStream(it) }.also { wordmark = it }

    fun signature(c: Context): Bitmap =
        signature ?: c.assets.open("contracts/ugs-unterschrift.png").use { BitmapFactory.decodeStream(it) }.also { signature = it }

    /** UGS-Schild mit Schriftzug; liefert die Höhe. */
    fun logo(c: Context, s: Sheet, x: Float, top: Float, width: Float): Float {
        val h = width * 64 / 302
        val logo = wordmark(c)
        val scale = minOf(width / logo.width, h / logo.height)
        s.image(logo, x, top, logo.width * scale, logo.height * scale)
        val size = h * .44f
        val nameX = x + h * 1.16f
        val ugs = s.font(size, Face.BOLD, Color.rgb(10, 18, 20))
        val baseline = top + (h - (ugs.descent() - ugs.ascent())) / 2 - ugs.ascent()
        s.canvas.drawText("UGS ", nameX, baseline, ugs)
        val w = ugs.measureText("UGS ")
        s.canvas.drawText("Sicherheit", nameX + w, baseline, s.font(size, Face.SEMIBOLD, Color.rgb(119, 97, 45)))
        return h
    }

    val STAMP_INK = Color.argb(235, 23, 74, 158)
    const val STAMP_WIDTH = 196f

    fun stampLines(company: Map<String, String>) =
        listOf(
                company["name"].orEmpty(),
                company["street"].orEmpty(),
                "${company["postalCode"].orEmpty()} ${company["city"].orEmpty()}".trim(),
                company["phone"].orEmpty().let { if (it.isBlank()) "" else "Tel.: $it" },
            )
            .filter { it.isNotBlank() }

    private fun stampSize(p: Paint, value: String, index: Int, width: Float): Float {
        var size = if (index == 0) 11.5f else 9f
        p.typeface = if (index == 0) Face.SEMIBOLD.typeface else Face.REGULAR.typeface
        p.textSize = size
        while (size > 6 && p.measureText(value) > width - 22) {
            size -= .25f
            p.textSize = size
        }
        return size
    }

    fun stampHeight(lines: List<String>, width: Float = STAMP_WIDTH): Float {
        if (lines.isEmpty()) return 0f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        var total = 22f
        lines.forEachIndexed { i, v -> total += stampSize(p, v, i, width) + 2.5f }
        return total - 2.5f
    }

    /** Firmenstempel (leicht gedreht, blau) mit optionaler Unterschrift darüber. */
    fun stamp(c: Context, s: Sheet, lines: List<String>, x: Float, top: Float, width: Float = STAMP_WIDTH, signature: Boolean): Float {
        if (lines.isEmpty()) return 0f
        val total = stampHeight(lines, width)
        val canvas = s.canvas
        canvas.save()
        canvas.translate(x, top)
        canvas.rotate(3.2f, 0f, total)
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = STAMP_INK }
        var y = 11f
        lines.forEachIndexed { i, v ->
            val size = stampSize(p, v, i, width)
            val w = p.measureText(v)
            canvas.drawText(v, ((width - w) / 2).coerceAtLeast(0f), y - p.ascent(), p)
            y += size + 2.5f
        }
        if (signature) {
            val sig = signature(c)
            val sw = minOf(width - 26, 168f)
            val sh = sw * sig.height / sig.width
            s.image(sig, (width - sw) / 2, (total - sh) / 2, sw, sh, 242)
        }
        canvas.restore()
        return total
    }

    /**
     * Legt gezeichnete Seiten über Seiten einer PDF-Vorlage. [pages] liefert je Ausgabeseite
     * die Nummer der Vorlagenseite und die Zeichenfunktion.
     */
    fun overlay(template: ByteArray, pages: List<Pair<Int, (Sheet) -> Unit>>): ByteArray {
        require(pages.isNotEmpty()) { "Keine Seiten zum Erstellen." }
        PDDocument.load(template).use { source ->
            val sizes = (0 until source.numberOfPages).map { source.getPage(it).mediaBox }
            val drawn =
                PdfWriter().run {
                    for ((index, draw) in pages) {
                        val box = sizes.getOrNull(index) ?: error("Vorlagenseite fehlt.")
                        draw(begin(box.width, box.height))
                    }
                    bytes()
                }
            PDDocument.load(drawn).use { layer ->
                PDDocument().use { out ->
                    val util = LayerUtility(out)
                    pages.forEachIndexed { i, (index, _) ->
                        val page = out.importPage(source.getPage(index))
                        val form = util.importPageAsForm(layer, i)
                        PDPageContentStream(out, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
                            it.drawForm(form)
                        }
                    }
                    return ByteArrayOutputStream().also { out.save(it) }.toByteArray()
                }
            }
        }
    }

    /** Hängt PDFs aneinander (z. B. Forderung, Kontrollprotokoll und Anlage). */
    fun merge(parts: List<ByteArray>): ByteArray {
        require(parts.isNotEmpty())
        if (parts.size == 1) return parts[0]
        PDDocument().use { out ->
            val sources = mutableListOf<PDDocument>()
            try {
                for (bytes in parts) {
                    val d =
                        try {
                            PDDocument.load(bytes)
                        } catch (e: Exception) {
                            throw IllegalArgumentException("Eine Anlage ist keine lesbare PDF.")
                        }
                    require(!d.isEncrypted && d.numberOfPages > 0) { "Eine Anlage ist keine lesbare PDF." }
                    sources += d
                    for (i in 0 until d.numberOfPages) out.importPage(d.getPage(i))
                }
                return ByteArrayOutputStream().also { out.save(it) }.toByteArray()
            } finally {
                sources.forEach { it.close() }
            }
        }
    }

    /** Wort-Umbruch für Tabellenzellen; zu lange Wörter werden zeichenweise geteilt. */
    fun wrap(value: String, width: Float, measure: (String) -> Float): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in value.split('\n')) {
            var line = ""
            for (word in paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (measure(candidate) <= width) {
                    line = candidate
                    continue
                }
                if (line.isNotEmpty()) {
                    lines += line
                    line = ""
                }
                for (ch in word) {
                    if (line.isNotEmpty() && measure(line + ch) > width) {
                        lines += line
                        line = ""
                    }
                    line += ch
                }
            }
            lines += line
        }
        while (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines.ifEmpty { listOf("") }
    }

    fun germanNumber(v: Double) = String.format(java.util.Locale.GERMANY, "%.2f", v)

    fun germanHours(minutes: Int) = germanNumber(minutes / 60.0)

    fun safeName(v: String) = v.replace("/", "-").replace(":", "-").trim()
}
