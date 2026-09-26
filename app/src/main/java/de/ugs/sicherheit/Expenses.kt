@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import android.content.Context
import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

data class ExpenseTotal(val category: String, val count: Int, val total: Double)

object Expenses {
    fun amount(e: Entry) = parseAmount(e["amount"]) ?: 0.0

    fun totals(rows: List<Entry>): List<ExpenseTotal> =
        rows.groupBy { it["category"].ifBlank { "Sonstiges" } }
            .map { (k, v) -> ExpenseTotal(k, v.size, v.sumOf { amount(it) }) }
            .sortedWith(compareByDescending<ExpenseTotal> { it.total }.thenBy { it.category })

    /** A4-Bericht; jeder gefilterte Eintrag, danach die Summe je Kategorie. */
    fun pdf(c: Context, company: Map<String, String>, rows: List<Entry>, category: String, from: String, to: String): ByteArray {
        val margin = 44f
        val width = 507.2f
        val cols = listOf(76f, 88f, 215.2f, 128f)
        val bottom = 760f
        val lh = 14f
        val ink = AColor.rgb(26, 41, 64)
        val muted = AColor.rgb(92, 105, 122)
        val blue = AColor.rgb(10, 92, 173)
        val pale = AColor.rgb(240, 245, 250)
        val rule = AColor.rgb(209, 219, 230)
        val probe = Sheet(android.graphics.Canvas(), 10f, 10f)
        fun wrap(t: String, w: Float, size: Float, bold: Boolean = false) = PdfKit.wrap(t, w) { probe.measure(it, size, if (bold) Face.BOLD else Face.REGULAR) }
        val companyLines = wrap(company["name"].orEmpty().ifBlank { "Meine Firma" }, 330f, 11f, true)
        val titleTop = maxOf(84f, 36 + companyLines.size * 15f + 20)
        val filter =
            "Kategorie: ${if (category == "Alle") "Alle Kategorien" else category}  |  Zeitraum: ${if (from.isEmpty()) "Beginn offen" else DateText.german(from)} bis ${if (to.isEmpty()) "Ende offen" else DateText.german(to)}"
        val filterLines = wrap(filter, width, 9f)
        val summaryTop = titleTop + 38 + filterLines.size * 13 + 18
        val tableTop = summaryTop + 82
        val bodyTop = tableTop + 28
        require(bodyTop + 32 <= bottom) { "Firmenname oder Filterangaben sind zu lang für den PDF-Kopf." }
        val total = rows.sumOf { amount(it) }
        data class Seg(val index: Int, val top: Float, val height: Float, val cells: List<List<Pair<String, Boolean>>>)
        val pages = mutableListOf(mutableListOf<Seg>())
        var y = bodyTop
        rows.forEachIndexed { index, item ->
            val values = listOf(DateText.german(item["date"]), item["category"], item.title, money(amount(item)))
            val cells = values.mapIndexed { col, v -> wrap(v, cols[col] - 16, 10f, col == 3).map { it to false } }.toMutableList()
            if (item["notes"].isNotEmpty()) cells[2] = cells[2] + wrap("Notiz: ${item["notes"]}", cols[2] - 16, 9f).map { it to true }
            val count = cells.maxOf { it.size }
            val full = maxOf(32f, count * lh + 16)
            if (y > bodyTop && y + full > bottom) {
                pages += mutableListOf<Seg>()
                y = bodyTop
            }
            var offset = 0
            var guard = 0
            while (offset < count && guard++ < 1000) {
                val capacity = ((bottom - y - 16) / lh).toInt()
                if (capacity < 1) {
                    pages += mutableListOf<Seg>()
                    y = bodyTop
                    continue
                }
                val length = minOf(count - offset, capacity)
                val h = maxOf(32f, length * lh + 16)
                val chunk = cells.map { it.drop(offset).take(length) }.toMutableList()
                if (offset > 0 && chunk[0].isEmpty()) chunk[0] = listOf("Forts." to true)
                pages.last() += Seg(index, y, h, chunk)
                y += h
                offset += length
                if (offset < count) {
                    pages += mutableListOf<Seg>()
                    y = bodyTop
                }
            }
        }
        data class Sum(val kind: Int, val top: Float, val label: String, val count: String = "", val amount: String = "")
        val sums = MutableList(pages.size) { mutableListOf<Sum>() }
        val totals = totals(rows)
        if (totals.isNotEmpty()) {
            val rh = 20f
            y += 22
            if (y + minOf(34 + rh * (totals.size + 1), bottom - bodyTop) > bottom) {
                pages += mutableListOf<Seg>()
                sums += mutableListOf<Sum>()
                y = bodyTop
            }
            sums.last() += Sum(0, y, "Summe je Kategorie")
            y += 34
            val lines = totals.map { Sum(1, 0f, it.category, "${it.count} ${if (it.count == 1) "Eintrag" else "Einträge"}", money(it.total)) } +
                Sum(2, 0f, "Gesamt", "${rows.size} Einträge", money(total))
            for (l in lines) {
                if (y + rh > bottom) {
                    pages += mutableListOf<Seg>()
                    sums += mutableListOf<Sum>()
                    y = bodyTop
                }
                sums.last() += l.copy(top = y)
                y += rh
            }
        }
        val writer = PdfWriter(595.2f, 841.8f)
        pages.forEachIndexed { pi, segs ->
            val s = writer.begin(595.2f, 841.8f)
            s.fill(margin, 24f, width, 3f, blue)
            companyLines.forEachIndexed { i, l -> s.fit(l, margin, 36 + i * 15f, 330f, 11f, 11f, Face.BOLD, ink) }
            PdfKit.logo(c, s, 595.2f - margin - 140, 36f, 140f)
            s.fit("Firma Ausgaben", margin, titleTop, width, 25f, 25f, Face.BOLD, ink)
            filterLines.forEachIndexed { i, l -> s.fit(l, margin, titleTop + 38 + i * 13, width, 9f, 9f, color = muted) }
            s.fill(margin, summaryTop, width, 62f, pale)
            s.fit("GESAMTAUSGABEN", margin + 14, summaryTop + 10, width - 28, 8f, 8f, Face.BOLD, muted)
            s.fit(money(total), margin + 14, summaryTop + 27, 340f, 22f, 10f, Face.BOLD, blue)
            s.fit("${rows.size} Einträge", margin + width - 126, summaryTop + 32, 112f, 10f, 10f, color = muted, align = Align.RIGHT)
            s.fill(margin, tableTop, width, 28f, ink)
            var x = margin
            listOf("Datum", "Kategorie", "Bezeichnung / Notiz", "Betrag (EUR)").forEachIndexed { i, t ->
                s.fit(t, x + 8, tableTop + 8, cols[i] - 16, 9f, 7f, Face.BOLD, AColor.WHITE, if (i == 3) Align.RIGHT else Align.LEFT)
                x += cols[i]
            }
            if (rows.isEmpty()) s.fit("Keine Ausgaben für den gewählten Filter.", margin + 8, bodyTop + 16, width - 16, 10f, color = muted)
            for (seg in segs) {
                if (seg.index % 2 == 0) s.fill(margin, seg.top, width, seg.height, pale)
                x = margin
                seg.cells.forEachIndexed { col, lines ->
                    lines.forEachIndexed { i, (v, note) ->
                        s.fit(v, x + 8, seg.top + 8 + i * lh, cols[col] - 16, if (note) 9f else 10f, 6f, if (col == 3) Face.BOLD else Face.REGULAR, if (note) muted else ink, if (col == 3) Align.RIGHT else Align.LEFT)
                    }
                    x += cols[col]
                }
                s.fill(margin, seg.top + seg.height - .5f, width, .5f, rule)
            }
            for (l in sums.getOrElse(pi) { emptyList() }) when (l.kind) {
                0 -> {
                    s.fill(margin, l.top, width, 26f, pale)
                    s.fit(l.label, margin + 8, l.top + 7, width - 16, 11f, 11f, Face.BOLD, blue)
                }
                else -> {
                    val bold = l.kind == 2
                    if (bold) s.fill(margin, l.top, width, .8f, ink)
                    s.fit(l.label, margin + 8, l.top + 4, 250f, 10f, 7f, if (bold) Face.BOLD else Face.REGULAR, ink)
                    s.fit(l.count, margin + 270, l.top + 4, 100f, 9f, 7f, color = muted)
                    s.fit(l.amount, margin + width - 158, l.top + 4, 150f, 10f, 7f, Face.BOLD, if (bold) blue else ink, Align.RIGHT)
                    if (!bold) s.fill(margin, l.top + 19.5f, width, .5f, rule)
                }
            }
            s.fill(margin, 784f, width, .5f, rule)
            s.fit("Erstellt am ${DateText.german(today)}", margin, 796f, 330f, 8f, color = muted)
            s.fit("Seite ${pi + 1} von ${pages.size}", margin + width - 130, 796f, 130f, 8f, color = muted, align = Align.RIGHT)
        }
        return writer.bytes()
    }
}

@Composable
fun ExpensesScreen(vm: UGSViewModel) {
    var category by rememberSaveable { mutableStateOf("Alle") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<Entry?>(null) }
    var delete by remember { mutableStateOf<Entry?>(null) }
    val all = vm.rows(Kind.EXPENSE).sortedByDescending { it["date"] }
    val dated = all.filter { (from.isEmpty() || it["date"] >= from) && (to.isEmpty() || it["date"] <= to) }
    val filtered = dated.filter { category == "Alle" || it["category"] == category }
    val total = filtered.sumOf { Expenses.amount(it) }
    val weekStart = LocalDate.now().minusDays(6).toString()
    val week = filtered.filter { it["date"] in weekStart..today }.sumOf { Expenses.amount(it) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Firma Ausgaben", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (vm.canCreate(Kind.EXPENSE))
                    IconButton(onClick = { edit = Entry(kind = Kind.EXPENSE, fields = mapOf("date" to today, "category" to if (category == "Alle") "IT / Software" else category)) }) {
                        Icon(Icons.Default.Add, "Neue Ausgabe")
                    }
                if (vm.canExport("expenses"))
                    IconButton(onClick = { vm.run { vm.showPdf("Firma-Ausgaben-$today") { Expenses.pdf(vm.app, vm.company, filtered, category, from, to) } } }) {
                        Icon(Icons.Default.PictureAsPdf, if (category == "Alle") "PDF" else "PDF: $category")
                    }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, value) in listOf("Gesamt" to money(total), "Einträge" to "${filtered.size}", "Letzte Woche" to money(week)))
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(label, style = MaterialTheme.typography.labelSmall)
                            Text(value, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                        }
                    }
            }
        }
        item { SectionHeader("Nach Kategorie") }
        val totals = Expenses.totals(dated)
        if (totals.isEmpty()) item { Text("Noch keine Ausgaben im gewählten Zeitraum.", style = MaterialTheme.typography.bodySmall) }
        items(totals, key = { "t-" + it.category }) { t ->
            ListItem(
                headlineContent = { Text(t.category) },
                supportingContent = { Text("${t.count} ${if (t.count == 1) "Eintrag" else "Einträge"}") },
                trailingContent = { Text(money(t.total), fontWeight = FontWeight.SemiBold) },
                leadingContent = { if (category == t.category) Icon(Icons.Default.Check, null) else Icon(UgsIcons.Receipt, null) },
                modifier = Modifier.clickable { category = if (category == t.category) "Alle" else t.category },
            )
        }
        item { Text("Antippen filtert Liste und PDF auf diese Kategorie.", style = MaterialTheme.typography.bodySmall) }
        item {
            SectionHeader("Filter")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterMenu("Kategorie", category, (expenseCategories + all.map { it["category"] }).filter { it.isNotBlank() }.distinct()) { category = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { CalendarInput("Von", from) { from = it } }
                Box(Modifier.weight(1f)) { CalendarInput("Bis", to) { to = it } }
            }
            TextButton(onClick = { category = "Alle"; from = ""; to = "" }) { Text("Filter zurücksetzen") }
        }
        item { SectionHeader("Ausgaben") }
        if (filtered.isEmpty()) item { Text("Keine Ausgaben für den aktuellen Filter.", style = MaterialTheme.typography.bodySmall) }
        items(filtered, key = { it.id }) { e ->
            Card(onClick = { if (vm.canEdit(Kind.EXPENSE)) edit = e }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(e.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(money(Expenses.amount(e)), fontWeight = FontWeight.SemiBold)
                    }
                    Text("${e["category"]} · ${DateText.german(e["date"])}", style = MaterialTheme.typography.bodySmall)
                    if (e["notes"].isNotBlank()) Text(e["notes"], style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    edit?.let { e ->
        FormDialog(
            if (e.revision == 0) "Neue Ausgabe" else "Ausgabe bearbeiten",
            schemas.getValue(Kind.EXPENSE),
            e.fields + if (e.revision > 0) mapOf("amount" to decimal(Expenses.amount(e))) else emptyMap(),
            vm,
            { edit = null },
            extra = {
                if (e.revision > 0 && vm.canDelete(Kind.EXPENSE))
                    OutlinedButton(onClick = { delete = e; edit = null }) { Text("Ausgabe löschen") }
            },
        ) { values ->
            vm.save(e.copy(fields = e.fields + values)) {
                edit = null
                val saved = values
                if ((category != "Alle" && category != saved["category"]) || (from.isNotEmpty() && saved["date"].orEmpty() < from) || (to.isNotEmpty() && saved["date"].orEmpty() > to))
                    vm.notice = "Ausgabe gespeichert. Sie liegt außerhalb des aktuellen Filters."
            }
        }
    }
    delete?.let { e ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("Ausgabe löschen?") },
            text = { Text("${e.title} · ${money(Expenses.amount(e))}") },
            confirmButton = { TextButton(onClick = { vm.delete(e) { delete = null } }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } },
        )
    }
}

/** Übersicht: Firma Ausgaben der letzten Tage, Woche, Monat oder Jahr mit Vergleich zum Vorzeitraum. */
@Composable
fun DashboardExpenses(vm: UGSViewModel, open: () -> Unit) {
    var range by rememberSaveable { mutableStateOf("Tage") }
    var days by rememberSaveable { mutableIntStateOf(30) }
    var shift by rememberSaveable { mutableIntStateOf(0) }
    val rows = vm.rows(Kind.EXPENSE)
    fun buckets(offset: Int): Pair<List<Pair<String, String>>, String> {
        val now = LocalDate.now()
        return when (range) {
            "Woche" -> {
                val start = now.minusWeeks(offset.toLong()).with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                (0 until 7).map { start.plusDays(it.toLong()).let { d -> d.toString() to listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")[it] } } to "KW ${start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
            }
            "Monat" -> {
                val start = now.minusMonths(offset.toLong()).withDayOfMonth(1)
                (0 until start.lengthOfMonth()).map { start.plusDays(it.toLong()).let { d -> d.toString() to "%02d.%02d".format(d.dayOfMonth, d.monthValue) } } to
                    start.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", java.util.Locale.GERMANY))
            }
            "Jahr" -> {
                val year = now.year - offset
                val names = listOf("Jan", "Feb", "Mär", "Apr", "Mai", "Jun", "Jul", "Aug", "Sep", "Okt", "Nov", "Dez")
                (1..12).map { "%04d-%02d".format(year, it) to names[it - 1] } to "$year"
            }
            else -> {
                val end = now.minusDays((days * offset).toLong())
                val start = end.minusDays((days - 1).toLong())
                (0 until days).map { start.plusDays(it.toLong()).let { d -> d.toString() to "%02d.%02d".format(d.dayOfMonth, d.monthValue) } } to
                    if (days == 1) "Heute" else "Letzte $days Tage"
            }
        }
    }
    fun sums(b: List<Pair<String, String>>) = b.map { (k, _) -> rows.filter { it["date"].startsWith(k) }.sumOf { Expenses.amount(it) } }
    val (current, title) = buckets(shift)
    val previous = buckets(shift + 1).first
    val values = sums(current)
    val total = values.sum()
    val before = sums(previous).sum()
    val change = if (before > 0) (total - before) / before * 100 else null
    val bar = MaterialTheme.colorScheme.primary
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Firma Ausgaben", Modifier.weight(1f).clickable(onClick = open), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { shift++ }) { Icon(Icons.Default.ChevronLeft, "Früher") }
                Text(title, style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { if (shift > 0) shift-- }, enabled = shift > 0) { Icon(Icons.Default.ChevronRight, "Später") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in listOf("Tage", "Woche", "Monat", "Jahr")) FilterChip(range == r, { range = r; shift = 0 }, label = { Text(r) })
                if (range == "Tage") for (d in listOf(1, 7, 14, 30)) FilterChip(days == d, { days = d; shift = 0 }, label = { Text("$d") })
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(money(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = bar)
                Text(
                    change?.let { "  ${if (it >= 0) "+" else ""}${decimal(it, 0)} % zum Vorzeitraum" } ?: "  kein Vorzeitraum",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val max = (values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
            Canvas(Modifier.fillMaxWidth().height(90.dp)) {
                val n = values.size.coerceAtLeast(1)
                val w = size.width / n
                values.forEachIndexed { i, v ->
                    val h = (v / max * size.height).toFloat()
                    drawRoundRect(bar.copy(alpha = if (v > 0) 1f else .15f), Offset(i * w + w * .15f, size.height - h.coerceAtLeast(2f)), Size(w * .7f, h.coerceAtLeast(2f)), CornerRadius(3f, 3f))
                }
            }
            Row {
                Text(current.firstOrNull()?.second.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(current.lastOrNull()?.second.orEmpty(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
