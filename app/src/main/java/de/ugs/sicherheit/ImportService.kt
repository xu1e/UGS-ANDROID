package de.ugs.sicherheit

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.Element

object ImportService {
    /** Größe laut Anbieter (−1 wenn unbekannt). */
    fun size(c: Context, uri: Uri): Long =
        runCatching {
                c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                    if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
                }
            }
            .getOrNull() ?: -1L

    /** Anzeigename der gewählten Datei. */
    fun name(c: Context, uri: Uri): String =
        runCatching {
                c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }
            .getOrNull() ?: uri.lastPathSegment.orEmpty()

    /** Legt die gewählte Datei (bis 250 MB) als Dokument ab, ohne sie ganz in den Speicher zu laden. */
    fun attach(vm: UGSViewModel, uri: Uri, values: Map<String, String>): Entry {
        val c = vm.app
        val size = size(c, uri)
        require(size <= FileLimits.FILE_BYTES) { "Maximal ${FileLimits.FILE_LABEL} erlaubt." }
        val mime = c.contentResolver.getType(uri) ?: "application/octet-stream"
        val named = values + ("originalName" to name(c, uri))
        return vm.repo.attachStream(
            Entry(kind = Kind.DOCUMENT, fields = named),
            { c.contentResolver.openInputStream(uri) ?: error("Datei kann nicht geöffnet werden.") },
            mime,
            size,
        )
    }

    fun read(c: Context, uri: Uri, limit: Int = FileLimits.FILE_BYTES): ByteArray =
        c.contentResolver.openInputStream(uri)?.use { input ->
            val b = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                require(b.size() + n <= limit) { "Datei überschreitet ${limit/1024/1024} MB." }
                b.write(buf, 0, n)
            }
            b.toByteArray()
        } ?: error("Datei kann nicht geöffnet werden.")

    fun csv(text: String): List<List<String>> {
        val clean = text.removePrefix("\uFEFF")
        val first = clean.lineSequence().firstOrNull().orEmpty()
        val separator = listOf(';', '\t', ',').maxBy { c -> first.count { it == c } }
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() {
            row += cell.toString()
            cell.setLength(0)
            require(row.size <= 100) { "Zu viele Spalten." }
        }
        fun endRow() {
            endCell()
            if (row.any { it.isNotBlank() }) rows += row.toList()
            row.clear()
            require(rows.size <= 5001) { "Maximal 5000 Mitarbeiter pro Import." }
        }
        while (i < clean.length) {
            val c = clean[i]
            if (c == '"') {
                if (quoted && i + 1 < clean.length && clean[i + 1] == '"') {
                    cell.append('"')
                    i++
                } else {
                    require(quoted || cell.isEmpty()) { "Ungültige CSV-Anführungszeichen." }
                    quoted = !quoted
                }
            } else if (!quoted && c == separator) endCell()
            else if (!quoted && (c == '\n' || c == '\r')) {
                if (c == '\r' && i + 1 < clean.length && clean[i + 1] == '\n') i++
                endRow()
            } else cell.append(c)
            i++
        }
        require(!quoted) { "CSV-Anführungszeichen nicht geschlossen." }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun xml(bytes: ByteArray): Element {
        // Android's DOM factory does not support the desktop Xerces feature flags.
        // Require UTF-8 XML and reject DTD/entity declarations before parsing.
        val decoder =
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        val text = decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(
            !text.contains('\u0000') &&
                !Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) {
            "XML mit DTD oder Entitäten ist nicht erlaubt."
        }
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ ->
            throw org.xml.sax.SAXException("Externe XML-Entitäten sind nicht erlaubt.")
        }
        return builder.parse(ByteArrayInputStream(bytes)).documentElement
    }

    fun xlsx(bytes: ByteArray): List<List<String>> {
        val parts = mutableMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.startsWith("xl/") && e.name.endsWith(".xml")) {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = z.read(buf)
                        if (n < 0) break
                        total += n
                        require(total <= 60 * 1024 * 1024) { "Entpackte Excel-Datei zu groß." }
                        out.write(buf, 0, n)
                    }
                    parts[e.name] = out.toByteArray()
                }
                z.closeEntry()
            }
        }
        val shared =
            parts["xl/sharedStrings.xml"]
                ?.let {
                    val n = xml(it).getElementsByTagName("si")
                    (0 until n.length).map { i -> n.item(i).textContent }
                }
                .orEmpty()
        val workbook = xml(parts["xl/workbook.xml"] ?: error("Excel-Arbeitsmappe fehlt."))
        val sheet =
            workbook.getElementsByTagName("sheet").item(0) as? Element
                ?: error("Kein Tabellenblatt.")
        val rel = sheet.getAttribute("r:id")
        // Relationships have a .rels extension; read this small entry separately.
        var relationships: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "xl/_rels/workbook.xml.rels") {
                    val out = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = z.read(chunk)
                        if (n < 0) break
                        require(out.size() + n <= 1024 * 1024) { "Excel-Verknüpfungen zu groß." }
                        out.write(chunk, 0, n)
                    }
                    relationships = out.toByteArray()
                    require(relationships!!.size <= 1024 * 1024)
                    break
                }
            }
        }
        var path = "xl/worksheets/sheet1.xml"
        relationships?.let {
            val n = xml(it).getElementsByTagName("Relationship")
            for (i in 0 until n.length) {
                val el = n.item(i) as Element
                if (el.getAttribute("Id") == rel) {
                    val target = el.getAttribute("Target")
                    require(!target.contains("..") && !target.contains(":"))
                    path = if (target.startsWith("/")) target.drop(1) else "xl/$target"
                }
            }
        }
        val dateStyles = mutableSetOf<Int>()
        parts["xl/styles.xml"]?.let {
            val root = xml(it)
            val formats = mutableMapOf<Int, String>()
            val fs = root.getElementsByTagName("numFmt")
            for (i in 0 until fs.length) {
                val e = fs.item(i) as Element
                formats[e.getAttribute("numFmtId").toInt()] = e.getAttribute("formatCode")
            }
            val x = root.getElementsByTagName("cellXfs").item(0) as? Element
            val nodes = x?.getElementsByTagName("xf")
            if (nodes != null)
                for (i in 0 until nodes.length) {
                    val id = (nodes.item(i) as Element).getAttribute("numFmtId").toIntOrNull() ?: 0
                    if (
                        id in 14..22 ||
                            formats[id].orEmpty().lowercase().let { s ->
                                s.contains('y') && s.contains('d')
                            }
                    )
                        dateStyles += i
                }
        }
        val epoch1904 =
            (workbook.getElementsByTagName("workbookPr").item(0) as? Element)?.getAttribute(
                "date1904"
            ) in listOf("1", "true")
        val rows = xml(parts[path] ?: error("Tabellenblatt fehlt.")).getElementsByTagName("row")
        require(rows.length <= 5001) { "Maximal 5000 Mitarbeiter." }
        return (0 until rows.length).map { r ->
            val cells = (rows.item(r) as Element).getElementsByTagName("c")
            val mapped = mutableMapOf<Int, String>()
            for (i in 0 until cells.length) {
                val cell = cells.item(i) as Element
                val index =
                    cell
                        .getAttribute("r")
                        .takeWhile { it.isLetter() }
                        .fold(0) { acc, c -> acc * 26 + c.uppercaseChar().code - 'A'.code + 1 } - 1
                require(index in 0..99) { "Maximal 100 Spalten." }
                val value = cell.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                val type = cell.getAttribute("t")
                val style = cell.getAttribute("s").toIntOrNull()
                mapped[index] =
                    when {
                        type == "s" -> shared.getOrNull(value.toIntOrNull() ?: -1).orEmpty()
                        type == "inlineStr" ->
                            cell.getElementsByTagName("is").item(0)?.textContent.orEmpty()
                        style in dateStyles && value.toDoubleOrNull() != null -> {
                            val serial = value.toDouble().toLong()
                            (if (epoch1904) LocalDate.of(1904, 1, 1).plusDays(serial)
                                else
                                    LocalDate.of(1899, 12, 31)
                                        .plusDays(serial - if (serial >= 60) 1 else 0))
                                .toString()
                        }
                        else -> value
                    }
            }
            List((mapped.keys.maxOrNull() ?: -1) + 1) { mapped[it].orEmpty() }
        }
    }

    fun suggest(headers: List<String>): Map<String, Int> {
        val names =
            mapOf(
                "personnelNumber" to listOf("personalnummer", "personalnr", "mitarbeiternummer"),
                "firstName" to listOf("vorname", "firstname"),
                "lastName" to listOf("nachname", "familienname", "lastname"),
                "street" to listOf("straße", "strasse", "anschrift"),
                "postalCode" to listOf("plz", "postleitzahl"),
                "city" to listOf("ort", "wohnort", "stadt"),
                "birthDate" to listOf("geburtsdatum"),
                "startDate" to listOf("eintritt", "vertragsbeginn", "eintrittsdatum"),
                "email" to listOf("email", "e-mail"),
                "phone" to listOf("telefon", "handy"),
                "hourlyRate" to listOf("stundenlohn"),
                "weeklyHours" to listOf("wochenstunden"),
            )
        return names
            .mapValues { (_, aliases) ->
                headers.indexOfFirst { it.lowercase().trim().replace(" ", "") in aliases }
            }
            .filterValues { it >= 0 }
    }

    suspend fun ocr(c: Context, uri: Uri): String {
        val bytes = read(c, uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Bitte ein Foto als PNG oder JPEG auswählen."
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2500) sample *= 2
        val bitmap =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: error("Bild kann nicht gelesen werden.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer
                    .process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }
}
