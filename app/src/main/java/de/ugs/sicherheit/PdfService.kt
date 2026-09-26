package de.ugs.sicherheit

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import org.json.JSONObject

class PdfService(private val context: Context) {
    data class Block(val text: String = "", val kind: String = "normal", val right: String = "")

    val templates =
        linkedMapOf(
            "Dienstausweis" to "ugs-dienstausweis.pdf",
            "Visitenkarte" to "ugs-visitenkarte.pdf",
            "Broschüre Hochformat" to "ugs-broschuere-hoch.pdf",
            "Broschüre Querformat" to "ugs-broschuere-quer.pdf",
            "Stundenzettel (Vorlage)" to "ugs-stundenzettel.pdf",
            "Personalfragebogen" to "fragebogen.pdf",
        )

    fun asset(name: String) = context.assets.open("templates/$name").use { it.readBytes() }

    fun template(
        name: String,
        tokens: Map<String, String>,
        company: Map<String, String>,
    ): ByteArray {
        val a =
            JSONObject(context.assets.open("contracts/$name.json").bufferedReader().readText())
                .getJSONArray("items")
        val blocks = buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val gate = o.optString("when")
                if (gate.isNotEmpty() && tokens[gate] != "true") continue
                add(
                    Block(
                        Rules.resolve(o.optString("t"), tokens),
                        o.getString("k"),
                        Rules.resolve(o.optString("r"), tokens),
                    )
                )
            }
        }
        return render(blocks, company)
    }

    fun render(blocks: List<Block>, company: Map<String, String>): ByteArray {
        val doc = PdfDocument()
        var page: PdfDocument.Page? = null
        var y = 0f
        var number = 0
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val logo =
            BitmapFactory.decodeStream(context.assets.open("branding/ugs-wordmark-light.png"))
        fun footer() {
            page?.let { p ->
                val line =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(180, 193, 208)
                        strokeWidth = .6f
                    }
                p.canvas.drawLine(44f, 794f, 551f, 794f, line)
                paint.textSize = 8f
                paint.typeface = Typeface.DEFAULT
                paint.color = Color.DKGRAY
                p.canvas.drawText(
                    "${company["name"]?:"UGS Sicherheit"} · Seite $number",
                    44f,
                    810f,
                    paint,
                )
                doc.finishPage(p)
            }
        }
        fun next() {
            footer()
            number++
            require(number <= 150) { "Dokument zu umfangreich." }
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            page!!
                .canvas
                .drawBitmap(
                    logo,
                    null,
                    RectF(45f, 20f, 90f, 65f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
            paint.textSize = 14f
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.color = Color.rgb(9, 29, 47)
            page!!.canvas.drawText("UGS SICHERHEIT", 96f, 44f, paint)
            y = 88f
        }
        fun layout(text: String, size: Float, bold: Boolean, width: Int): StaticLayout {
            paint.textSize = size
            paint.color = Color.rgb(20, 28, 37)
            paint.typeface =
                Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(2f, 1f)
                .build()
        }
        fun write(text: String, size: Float = 10f, bold: Boolean = false, space: Float = 7f) {
            if (text.isBlank()) return
            var rest = text
            while (rest.isNotBlank()) {
                val lay = layout(rest, size, bold, 507)
                val room = (776 - y).toInt()
                var lines = 0
                while (lines < lay.lineCount && lay.getLineBottom(lines) <= room) lines++
                if (lines == 0) {
                    next()
                    continue
                }
                val end = lay.getLineEnd(lines - 1)
                val part = layout(rest.substring(0, end), size, bold, 507)
                page!!.canvas.save()
                page!!.canvas.translate(44f, y)
                part.draw(page!!.canvas)
                page!!.canvas.restore()
                y += part.height + space
                rest = rest.substring(end)
                if (rest.isNotBlank()) next()
            }
        }
        try {
            next()
            for (b in blocks) {
                when (b.kind) {
                    "pagebreak" -> if (y > 95) next()
                    "gap" -> y += 10
                    "title" -> {
                        if (y > 700) next()
                        write(b.text, 22f, true, 18f)
                    }
                    "h1",
                    "h2" -> {
                        if (y > 710) next()
                        y += 5
                        write(b.text, if (b.kind == "h1") 13f else 11.5f, true, 9f)
                    }
                    "subtitle" -> write(b.text, 11f, false, 10f)
                    "signatures",
                    "employee_signature" -> {
                        if (y > 675) next()
                        y += 36
                        val line =
                            Paint().apply {
                                color = Color.DKGRAY
                                strokeWidth = .6f
                            }
                        page!!.canvas.drawLine(44f, y, 270f, y, line)
                        if (b.kind == "signatures") page!!.canvas.drawLine(317f, y, 551f, y, line)
                        y += 9
                        val l = layout(b.text, 9f, false, 226)
                        page!!.canvas.save()
                        page!!.canvas.translate(44f, y)
                        l.draw(page!!.canvas)
                        page!!.canvas.restore()
                        var height = l.height
                        if (b.right.isNotBlank()) {
                            val r = layout(b.right, 9f, false, 234)
                            page!!.canvas.save()
                            page!!.canvas.translate(317f, y)
                            r.draw(page!!.canvas)
                            page!!.canvas.restore()
                            height = maxOf(height, r.height)
                        }
                        y += height + 18
                    }
                    "table_row",
                    "cols" -> {
                        write(b.text, 9.5f, true, 3f)
                        write(b.right, 9.5f, false, 8f)
                    }
                    "li" -> write("• ${b.text}")
                    else -> write(b.text)
                }
            }
            footer()
            page = null
            return ByteArrayOutputStream().also { doc.writeTo(it) }.toByteArray()
        } finally {
            doc.close()
            logo.recycle()
        }
    }

    fun probation(w: Entry, company: Map<String, String>, v: Map<String, String>): ByteArray {
        Contracts.validateForm(probationFields, v)
        Contracts.base(w, company, v)
        Rules.probation(
            v.getValue("contractStart"),
            v.getValue("probationMonths").toInt(),
            v.getValue("letterDate"),
            v.getValue("receiptDate"),
            v.getValue("endDate"),
        )
        val b =
            listOf(
                Block(company["name"].orEmpty()),
                Block("${company["street"]}\n${company["postalCode"]} ${company["city"]}"),
                Block(kind = "gap"),
                Block("${w.title}\n${w["street"]}\n${w["postalCode"]} ${w["city"]}"),
                Block(kind = "gap"),
                Block("${v["signingPlace"]}, ${Rules.german(v.getValue("letterDate"))}"),
                Block("Kündigung innerhalb der Probezeit", "h1"),
                Block("Sehr geehrte/r ${w.title},"),
                Block(
                    "hiermit kündigen wir das mit Ihnen bestehende Arbeitsverhältnis ordentlich innerhalb der vereinbarten Probezeit zum ${Rules.german(v.getValue("endDate"))}, hilfsweise zum nächstzulässigen Termin."
                ),
                Block(
                    "Bitte geben Sie sämtliche überlassenen Arbeitsmittel, Schlüssel, Ausweise und Unterlagen spätestens bei Beendigung des Arbeitsverhältnisses zurück. Die Abwicklung offener Vergütungs- und Urlaubsansprüche erfolgt nach den gesetzlichen und vertraglichen Bestimmungen."
                ),
                Block(
                    "Bitte beachten Sie Ihre Pflicht zur frühzeitigen Arbeitsuchendmeldung bei der Agentur für Arbeit. Liegen zwischen Kenntnis des Beendigungszeitpunkts und dem Ende weniger als drei Monate, hat die Meldung innerhalb von drei Tagen nach Kenntnis zu erfolgen. Die Arbeitslosmeldung ist davon unabhängig erforderlich."
                ),
                Block("Mit freundlichen Grüßen"),
                Block("${company["representative"]}\n${company["name"]}", "employee_signature"),
            )
        return render(b, company)
    }

    fun report(
        title: String,
        rows: List<Entry>,
        all: List<Entry>,
        company: Map<String, String>,
    ): ByteArray {
        val blocks =
            mutableListOf(
                Block(title, "title"),
                Block("Stand: ${Rules.german(today)} · ${rows.size} Einträge", "subtitle"),
            )
        for (e in rows) {
            val worker = all.find { it.id == e["workerId"] }?.title.orEmpty()
            blocks +=
                Block(
                    listOf(e.title, worker)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · "),
                    "h2",
                )
            for (f in schemas[e.kind].orEmpty()) {
                val raw = e[f.key]
                if (raw.isBlank() || f.input == Input.WORKER) continue
                val value =
                    when (f.input) {
                        Input.DATE -> Rules.german(raw)
                        Input.SITE -> all.find { it.id == raw }?.title.orEmpty()
                        else -> raw
                    }
                blocks += Block("${f.label}: $value")
            }
            if (e.kind in listOf(Kind.TIME, Kind.SHIFT))
                blocks +=
                    Block(
                        "Nettoarbeitszeit: ${String.format(Locale.GERMANY,"%.2f",Rules.hours(e))} Stunden"
                    )
            if (e.kind == Kind.PAYROLL) {
                val gross =
                    Rules.number(e["hours"]) * Rules.number(e["rate"]) +
                        Rules.number(e["allowances"])
                blocks +=
                    Block(
                        "Rechnerisches Brutto: ${String.format(Locale.GERMANY,"%.2f",gross)} €\nNach manuell erfassten Abzügen: ${String.format(Locale.GERMANY,"%.2f",gross-Rules.number(e["deductions"]))} €\nInterne Übersicht; keine gesetzliche Entgeltabrechnung."
                    )
            }
        }
        return render(blocks, company)
    }

    fun badge(w: Entry, issued: String, valid: String): ByteArray {
        Rules.date(issued)
        require(Rules.date(valid) >= Rules.date(issued)) { "Gültigkeit endet vor Ausstellung." }
        return PDDocument.load(asset("ugs-dienstausweis.pdf")).use { d ->
            val form =
                d.documentCatalog.acroForm ?: error("Dienstausweis enthält keine Formularfelder.")
            val font =
                com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(
                    d,
                    context.assets.open("fonts/NotoSans-Regular.ttf"),
                )
            val resources = form.defaultResources ?: com.tom_roush.pdfbox.pdmodel.PDResources()
            resources.put(com.tom_roush.pdfbox.cos.COSName.getPDFName("UGS"), font)
            form.defaultResources = resources
            form.defaultAppearance = "/UGS 0 Tf 0 g"
            form.needAppearances = false
            for (field in form.fieldTree) {
                if (field is com.tom_roush.pdfbox.pdmodel.interactive.form.PDVariableText)
                    field.defaultAppearance = "/UGS 0 Tf 0 g"
            }
            val values =
                mapOf(
                    "mitarbeiter_name" to w.title,
                    "mitarbeiter_bewacher_id" to w["bewacherId"],
                    "mitarbeiter_nummer" to w["personnelNumber"],
                    "gueltig_bis" to Rules.german(valid),
                    "ausgestellt_am" to Rules.german(issued),
                )
            for ((k, v) in values) (form.getField(k) ?: error("Formularfeld fehlt: $k")).setValue(v)
            form.flatten()
            ByteArrayOutputStream().also { d.save(it) }.toByteArray()
        }
    }

    fun stamp(
        data: ByteArray,
        company: Map<String, String>,
        pageIndex: Int,
        x: Float,
        y: Float,
        width: Float,
        date: String,
        signature: Boolean,
    ): ByteArray {
        require(width in .15f..0.65f && x in 0f..1f && y in 0f..1f)
        val stamp = Bitmap.createBitmap(1200, if (signature) 620 else 370, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stamp)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(19, 56, 105)
                textSize = 38f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
        val lines =
            listOf(
                    company["name"].orEmpty(),
                    company["street"].orEmpty(),
                    "${company["postalCode"].orEmpty()} ${company["city"].orEmpty()}",
                    listOf(company["phone"], company["email"])
                        .filterNotNull()
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    if (date.isNotBlank()) Rules.german(date) else "",
                )
                .filter { it.isNotBlank() }
        var top = 48f
        for (line in lines) {
            paint.textSize = minOf(38f, 1120f / (paint.measureText(line).coerceAtLeast(1f) / 38))
            canvas.drawText(line, 28f, top, paint)
            top += 53f
            paint.typeface = Typeface.DEFAULT
        }
        if (signature) {
            val sig =
                BitmapFactory.decodeStream(context.assets.open("contracts/ugs-unterschrift.png"))
            val h = 1200f * sig.height / sig.width
            canvas.drawBitmap(
                sig,
                null,
                RectF(20f, top, 1180f, minOf(top + h, 610f)),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            sig.recycle()
        }
        return try {
            PDDocument.load(data).use { d ->
                require(pageIndex in 0 until d.numberOfPages) {
                    "Seitenzahl außerhalb des Dokuments."
                }
                val p = d.getPage(pageIndex)
                require(p.rotation == 0) {
                    "Gedrehte PDF-Seiten zuerst in der Ausgangsdatei aufrichten."
                }
                val box = p.cropBox
                val w = box.width * width
                val h = w * stamp.height / stamp.width
                require(w <= box.width && h <= box.height) { "Stempel zu groß." }
                val px = (x * box.width).coerceIn(0f, box.width - w) + box.lowerLeftX
                val py =
                    (box.height - y * box.height - h).coerceIn(0f, box.height - h) + box.lowerLeftY
                val image = LosslessFactory.createFromImage(d, stamp)
                PDPageContentStream(d, p, PDPageContentStream.AppendMode.APPEND, true, true).use {
                    it.drawImage(image, px, py, w, h)
                }
                ByteArrayOutputStream().also { d.save(it) }.toByteArray()
            }
        } finally {
            stamp.recycle()
        }
    }

    fun export(data: ByteArray, name: String, mime: String = "application/pdf"): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(100)
        return File(dir, safe).also { it.writeBytes(data) }
    }

    fun share(file: File, mime: String = "application/pdf") {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent =
            Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(intent, "Teilen / E-Mail").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Öffnet eine Adresse (mailto:, tel:, https:) in der passenden App. */
    fun dial(uri: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Öffnet eine exportierte Datei (Bild, Office …) in einer passenden App. */
    fun open(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, "Öffnen mit").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: android.content.ActivityNotFoundException) {
            share(file, mime)
        }
    }

    /** Leerer Exportordner-Pfad für große Dateien. */
    fun exportFile(name: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(120).ifBlank { "Datei" })
    }
}
