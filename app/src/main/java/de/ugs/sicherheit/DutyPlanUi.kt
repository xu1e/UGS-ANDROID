@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import android.content.Context
import android.graphics.Color as AColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DutyPlanPdf {
    private const val LEFT = 30f
    private val widths = listOf(91f, 91f, 171f, 91f, 91f)
    private const val BOTTOM = 748f

    /** Monatsübersicht: je aktiver Schicht eine Seite im Tabellenstil. */
    fun grid(c: Context, month: String, plan: DutyPlan): ByteArray {
        require(plan.shiftCount in 1..4 && plan.shifts.size >= plan.shiftCount) { "Bitte mindestens eine gültige Schicht für den PDF-Export auswählen." }
        val title = "Dienstplanung für ${YearMonth.parse(month).format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.GERMANY))}"
        val meta = listOf("Kunde:" to plan.customer, "Auftrag:" to plan.order, "Leistungsverzeichnisnummer" to plan.serviceNumber, "Objektadresse:" to plan.address)
        val probe = Sheet(android.graphics.Canvas(), 10f, 10f)
        val metaHeights = meta.map { maxOf(20f, probe.textHeight(it.second, 347f, 11f, Face.SERIF) + 6) }
        val tableTop = 96 + metaHeights.sum() + 14
        require(tableTop + 40 <= BOTTOM) { "Die Auftragsangaben sind für eine Dienstplanseite zu lang. Bitte kürzen Sie die Angaben." }
        val red = AColor.rgb(255, 0, 0)
        val writer = PdfWriter(595f, 842f)
        for (shift in 0 until plan.shiftCount) {
            val rows = plan.exportRows(month, shift)
            val label = plan.shifts[shift].label.ifBlank { "Schicht ${shift + 1}" }
            val headings = listOf("Tag", "Datum", label, "Dienstzeit", "Std")
            val headH = maxOf(22f, headings.indices.maxOf { probe.textHeight(headings[it], widths[it] - 6, 11.5f, Face.SERIF_BOLD) + 6 })
            val rowHs = rows.map { maxOf(18f, probe.textHeight(it.employee, widths[2] - 6, 11.5f, Face.SERIF) + 4) }
            val scale = minOf(1f, (BOTTOM - tableTop) / (headH + rowHs.sum()))
            require(scale >= .72f) { "Die Dienstplandaten sind für eine einseitige PDF-Ausgabe zu lang. Bitte Angaben kürzen." }
            val s = writer.begin(595f, 842f)
            val size = maxOf(8.25f, 11.5f * scale)
            PdfKit.logo(c, s, LEFT, 18f, 156f)
            s.text(title, LEFT, 60f, 535f, 15f, Face.SERIF_BOLD, align = Align.CENTER)
            var mt = 96f
            meta.forEachIndexed { i, (k, v) ->
                s.text(k, LEFT + 3, mt, 176f, 10.5f, Face.BOLD, red)
                s.text(v, LEFT + 185, mt, 347f, 11f, Face.SERIF)
                mt += metaHeights[i]
            }
            fun cell(v: String, x: Float, top: Float, w: Float, h: Float, bold: Boolean = false, align: Align = Align.LEFT, fill: Int = AColor.WHITE) {
                s.fill(x, top, w, h, fill)
                s.rect(x, top, w, h, AColor.BLACK, .65f)
                val th = s.textHeight(v, w - 6, size, if (bold) Face.SERIF_BOLD else Face.SERIF)
                s.text(v, x + 3, top + maxOf(2f, (h - th) / 2), w - 6, size, if (bold) Face.SERIF_BOLD else Face.SERIF, align = align, maxHeight = h)
            }
            var top = tableTop
            var x = LEFT
            headings.forEachIndexed { i, h ->
                cell(h, x, top, widths[i], headH * scale, true, Align.CENTER)
                x += widths[i]
            }
            top += headH * scale
            rows.forEachIndexed { r, row ->
                val h = rowHs[r] * scale
                val values = listOf(row.weekday, row.date, row.employee, row.time, row.minutes?.let { hoursText(it) }.orEmpty())
                x = LEFT
                values.forEachIndexed { i, v ->
                    cell(v, x, top, widths[i], h, align = if (i == 0 || i == 2) Align.LEFT else if (i == 1) Align.RIGHT else Align.CENTER, fill = if (row.workFree) red else AColor.WHITE)
                    x += widths[i]
                }
                top += h
            }
            val total = rows.mapNotNull { it.minutes }.sum()
            val free = rows.filter { it.workFree }.mapNotNull { it.minutes }.sum()
            s.text("Gesamtstunden: ${hoursText(total)}", LEFT, 758f, 260f, 10.5f, Face.SERIF_BOLD)
            s.text("davon arbeitsfrei: ${hoursText(free)}", LEFT + 270, 758f, 265f, 10.5f, Face.SERIF, align = Align.RIGHT)
            s.fill(LEFT, 777f, 105f, 18f, red)
            s.rect(LEFT, 777f, 105f, 18f, AColor.BLACK, .65f)
            s.text("arbeitsfrei", LEFT + 3, 779f, 99f, 11f, Face.SERIF)
            s.text("Seite ${writer.pages}", LEFT + 435, 800f, 100f, 8f, align = Align.RIGHT)
        }
        return writer.bytes()
    }

    /** Stundenzettel je eingeplantem Mitarbeiter auf der UGS-Vorlage. */
    fun timesheets(c: Context, month: String, plan: DutyPlan, workers: List<Entry>, company: Map<String, String>): ByteArray {
        val sheets = plan.timesheets(month, workers)
        val template = c.assets.open("templates/ugs-stundenzettel.pdf").use { it.readBytes() }
        return PdfKit.overlay(template, sheets.map { t -> 0 to { s: Sheet -> TimesheetPdf.draw(s, t.worker, company, month, t.rows, true, "Blatt ${t.part}/${t.parts}") } })
    }
}

@Composable
fun DutyPlanScreen(vm: UGSViewModel) {
    val sites = vm.rows(Kind.SITE)
    var siteId by rememberSaveable { mutableStateOf("") }
    var month by rememberSaveable { mutableStateOf(today.take(7)) }
    var record by remember { mutableStateOf<Entry?>(null) }
    var plan by remember { mutableStateOf<DutyPlan?>(null) }
    var saved by remember { mutableStateOf<DutyPlan?>(null) }
    var conflicts by remember { mutableStateOf(emptyList<DutyConflict>()) }
    var message by remember { mutableStateOf("") }
    var slot by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var newSite by remember { mutableStateOf(false) }
    var fillWorker by remember { mutableStateOf<Entry?>(null) }
    var fillShift by remember { mutableIntStateOf(0) }
    var replace by remember { mutableStateOf<String?>(null) }
    val dirty = plan != null && plan != saved
    val editable = vm.can(if (record == null) AccessAction.CREATE else AccessAction.EDIT, "duty")
    val templateKey = "dutyTemplate.${siteId.take(36)}"
    LaunchedEffect(sites) { if (siteId.isEmpty()) siteId = sites.firstOrNull()?.id.orEmpty() }
    fun check(p: DutyPlan) {
        val absences = vm.rows(Kind.ABSENCE)
        val ym = MonthCalendar.month(month)
        val near = setOf(ym.minusMonths(1).toString(), month, ym.plusMonths(1).toString())
        val others =
            vm.rows(Kind.DUTY_PLAN)
                .filter { it["month"] in near && !(it["siteId"] == siteId && it["month"] == month) }
                .map { e -> "${vm.entries.find { it.id == e["siteId"] }?.title ?: "Objekt"} ${e["month"]}" to DutyPlan.from(e["payload"], e["month"]) }
        conflicts = p.conflicts(absences, others)
        message = if (conflicts.isEmpty()) "Keine Planungskonflikte gefunden." else ""
    }
    fun load() {
        runCatching {
                MonthCalendar.month(month)
                require(siteId.isNotBlank()) { "Bitte zuerst ein Objekt anlegen oder wählen." }
                val e = vm.rows(Kind.DUTY_PLAN).firstOrNull { it["siteId"] == siteId && it["month"] == month }
                record = e
                val p = if (e == null) DutyPlan.fresh(month) else DutyPlan.from(e["payload"], month)
                saved = p
                plan = p
                conflicts = emptyList()
                fillShift = 0
                message = if (e == null) "Neuer Plan – noch nicht gespeichert." else ""
            }
            .onFailure { message = it.message.orEmpty() }
    }
    LaunchedEffect(siteId, month) { if (siteId.isNotBlank()) load() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Planung öffnen", fontWeight = FontWeight.Bold)
                    ChoiceInput("Objekt", siteId, sites.map { it.id to it.title }) { siteId = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = YearMonth.parse(month).minusMonths(1).toString() }) { Icon(Icons.Default.ChevronLeft, "Voriger Monat") }
                        Text(
                            YearMonth.parse(month).format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.GERMANY)),
                            Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(onClick = { month = YearMonth.parse(month).plusMonths(1).toString() }) { Icon(Icons.Default.ChevronRight, "Nächster Monat") }
                    }
                    if (vm.canCreate(Kind.SITE)) TextButton(onClick = { newSite = true }) { Text("Objekt anlegen") }
                }
            }
        }
        val current = plan
        if (current == null) {
            item { EmptyState("Objekt und Monat wählen", message.ifBlank { "Lege zuerst unter „Objekte“ ein Planungsobjekt an." }) }
            return@LazyColumn
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Auftrag und Schichten", fontWeight = FontWeight.Bold)
                    TextRow("Kunde", current.customer) { if (editable) plan = current.copy(customer = it) }
                    TextRow("Auftrag", current.order) { if (editable) plan = current.copy(order = it) }
                    TextRow("Leistungsnummer", current.serviceNumber) { if (editable) plan = current.copy(serviceNumber = it) }
                    TextRow("Adresse / Objekt", current.address) { if (editable) plan = current.copy(address = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Schichten: ${current.shiftCount}", Modifier.weight(1f))
                        IconButton(onClick = { if (editable && current.shiftCount > 1) plan = current.copy(shiftCount = current.shiftCount - 1).also { fillShift = minOf(fillShift, it.shiftCount - 1) } }) { Icon(Icons.Default.KeyboardArrowDown, "Weniger") }
                        IconButton(onClick = { if (editable && current.shiftCount < 4) plan = current.copy(shiftCount = current.shiftCount + 1) }) { Icon(Icons.Default.KeyboardArrowUp, "Mehr") }
                    }
                    for (i in 0 until current.shiftCount) {
                        val sh = current.shifts[i]
                        fun set(n: DutyShift) {
                            if (editable) plan = current.copy(shifts = current.shifts.toMutableList().also { it[i] = n })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.weight(2f)) { TextRow("Bezeichnung", sh.label) { set(sh.copy(label = it)) } }
                            Box(Modifier.weight(1f)) { TextRow("Beginn", sh.start) { set(sh.copy(start = it)) } }
                            Box(Modifier.weight(1f)) { TextRow("Ende", sh.end) { set(sh.copy(end = it)) } }
                        }
                    }
                    Text("Schichtvorlagen gelten beim Zuweisen und Füllen. Bestehende Tageszeiten bleiben erhalten.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (editable)
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Werktage füllen", fontWeight = FontWeight.Bold)
                        WorkerLookup(vm, fillWorker) { fillWorker = it }
                        ChoiceInput("Schicht", "$fillShift", (0 until current.shiftCount).map { "$it" to current.shifts[it].label }) { fillShift = it.toInt() }
                        OutlinedButton(onClick = { replace = "fill" }, enabled = fillWorker != null) { Text("Montag–Freitag füllen") }
                    }
                }
            }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$month · Version ${record?.revision ?: 0}${if (dirty) " · Entwurf" else ""}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { check(current) }) { Text("Prüfen") }
                if (editable)
                    Button(
                        onClick = {
                            vm.run {
                                val base = record ?: Entry(kind = Kind.DUTY_PLAN, fields = mapOf("siteId" to siteId, "month" to month))
                                val e = withContext(Dispatchers.IO) {
                                    vm.repo.save(base.copy(fields = base.fields + mapOf("title" to "Dienstplan $month", "payload" to current.json())))
                                }
                                vm.refresh()
                                record = e
                                saved = current
                                message = "Gespeichert."
                                check(current)
                            }
                        },
                        enabled = dirty || record == null,
                    ) {
                        Text("Speichern")
                    }
            }
        }
        if (message.isNotBlank()) item { Text(message, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
        if (conflicts.isNotEmpty())
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Planungshinweise", fontWeight = FontWeight.Bold)
                        conflicts.forEach { Text("${DateText.german(it.date)} · ${it.workerName}: ${it.message}", color = Color(0xFFFF9500), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.canExport("duty")) {
                    OutlinedButton(onClick = { vm.run { vm.showPdf("Dienstplan-$month") { DutyPlanPdf.timesheets(vm.app, month, current, vm.rows(Kind.WORKER), vm.company) } } }) { Text("Stundenzettel") }
                    OutlinedButton(onClick = { vm.run { vm.showPdf("Dienstplan-Uebersicht-$month") { DutyPlanPdf.grid(vm.app, month, current) } } }) { Text("Übersicht") }
                }
            }
        }
        if (editable)
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(
                        onClick = {
                            vm.run {
                                withContext(Dispatchers.IO) { vm.repo.personal(templateKey, current.json()) }
                                vm.refresh()
                                message = "Vorlage gespeichert."
                            }
                        }
                    ) {
                        Text("Als Vorlage")
                    }
                    TextButton(onClick = { replace = "template" }) { Text("Vorlage anwenden") }
                    TextButton(onClick = { replace = "previous" }) { Text("Vormonat") }
                    TextButton(onClick = { replace = "clear" }) { Text("Leeren") }
                }
            }
        val calendar = MonthCalendar.days(month).associateBy { it.iso }
        items(current.days, key = { it.date }) { day ->
            val meta = calendar[day.date]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${meta?.weekday.orEmpty()}, ${DateText.german(day.date)}${if (meta?.workFree == true) " · ${meta.holiday ?: "arbeitsfrei"}" else ""}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (meta?.workFree == true) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface,
                    )
                    current.activeSlots(day).forEachIndexed { i, s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .06f)).clickable { slot = day.date to i }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(s.shiftLabel, style = MaterialTheme.typography.labelLarge)
                                Text(s.name.ifBlank { "Nicht besetzt" }, color = if (s.name.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                Text("${s.start}–${s.end}", style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
    slot?.let { (date, index) ->
        val p = plan
        val s = p?.days?.firstOrNull { it.date == date }?.slots?.getOrNull(index)
        if (p != null && s != null) SlotEditor(vm, s, p.shifts.take(p.shiftCount), editable, { slot = null }) { edited ->
            plan = p.withSlot(date, index, edited)
            conflicts = emptyList()
            slot = null
        }
    }
    replace?.let { action ->
        AlertDialog(
            onDismissRequest = { replace = null },
            title = { Text(if (action == "fill") "Werktage füllen?" else "Aktuelle Belegungen im Entwurf ersetzen?") },
            text = { Text(if (action == "fill") "Diese Schicht an allen Werktagen mit ${fillWorker?.title} belegen? Vorhandene Belegungen werden ersetzt." else "Der Entwurf wird erst mit „Speichern“ übernommen.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val p = plan
                        replace = null
                        if (p != null)
                            runCatching {
                                    plan =
                                        when (action) {
                                            "fill" -> p.fillWeekdays(month, fillShift, fillWorker ?: error("Mitarbeiter wählen."))
                                            "clear" -> p.cleared()
                                            "template" -> p.adopt(DutyPlan.from(vm.company[vm.repo.personalKey(templateKey)] ?: error("Keine Vorlage gespeichert."), month), month)
                                            else -> {
                                                val prev = YearMonth.parse(month).minusMonths(1).toString()
                                                val e = vm.rows(Kind.DUTY_PLAN).firstOrNull { it["siteId"] == siteId && it["month"] == prev } ?: error("Kein Dienstplan im Vormonat.")
                                                p.adopt(DutyPlan.from(e["payload"], prev), month)
                                            }
                                        }
                                    plan?.let { check(it) }
                                }
                                .onFailure { vm.error = it.message }
                    }
                ) {
                    Text(if (action == "fill") "Montag–Freitag füllen" else "Ersetzen")
                }
            },
            dismissButton = { TextButton(onClick = { replace = null }) { Text("Abbrechen") } },
        )
    }
    if (newSite)
        FormDialog("Neues Planungsobjekt", schemas.getValue(Kind.SITE), emptyMap(), vm, { newSite = false }) { v ->
            vm.run {
                val e = withContext(Dispatchers.IO) { vm.repo.save(Entry(kind = Kind.SITE, fields = v)) }
                vm.refresh()
                siteId = e.id
                newSite = false
            }
        }
}

@Composable
fun SlotEditor(vm: UGSViewModel, slot: DutySlot, shifts: List<DutyShift>, editable: Boolean, close: () -> Unit, apply: (DutySlot) -> Unit) {
    var s by remember { mutableStateOf(slot) }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Schicht bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkerLookup(vm, vm.worker(s.workerId)) { w -> s = s.copy(workerId = w.id, bewacherId = w["bewacherId"], name = w.title) }
                Text(if (s.name.isBlank()) "Nicht besetzt" else "Belegt: ${s.name}", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    shifts.forEach { sh -> AssistChip(onClick = { s = s.copy(shiftLabel = sh.label, start = sh.start, end = sh.end) }, label = { Text(sh.label) }) }
                }
                TextRow("Bezeichnung", s.shiftLabel) { s = s.copy(shiftLabel = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f)) { TextRow("Beginn HH:mm", s.start) { s = s.copy(start = it) } }
                    Box(Modifier.weight(1f)) { TextRow("Ende HH:mm", s.end) { s = s.copy(end = it) } }
                }
                TextButton(onClick = { s = s.copy(workerId = "", bewacherId = "", name = "") }) { Text("Belegung leeren") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (editable)
                TextButton(
                    onClick = {
                        if (s.assigned && ClockTime.duration(s.start, s.end) == null) error = "Gültige, unterschiedliche Zeiten HH:mm erforderlich."
                        else apply(s)
                    }
                ) {
                    Text("Übernehmen")
                }
        },
        dismissButton = { TextButton(onClick = close) { Text("Schließen") } },
    )
}
