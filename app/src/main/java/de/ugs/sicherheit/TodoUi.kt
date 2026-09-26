package de.ugs.sicherheit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Reine Logik des To-Do-Kalenders (wie iOS TodoCalendarModel), ohne Oberfläche testbar. */
object TodoModel {
    val red = Color(0xFFFF3B30)
    val orange = Color(0xFFFF9500)
    val green = Color(0xFF34C759)

    fun done(e: Entry) = e["status"] == "Erledigt"

    fun appointment(e: Entry) = e["todoKind"] == "Termin"

    fun high(e: Entry) = e["priority"] == "Hoch"

    /** Farbe wie im Kalender: Rot = hohe Priorität, Orange = Termin, Grün = übrige Aufgaben. */
    fun color(e: Entry) = if (high(e)) red else if (appointment(e)) orange else green

    private fun rank(p: String) = when (p) { "Hoch" -> 0; "Niedrig" -> 2; else -> 1 }

    val order =
        compareBy<Entry>({ done(it) }, { it["date"].isEmpty() }, { it["date"] }, { it["dueTime"] }, { rank(it["priority"]) }, { it["title"].lowercase() }, { it.id })

    fun visible(todos: List<Entry>, filter: String, query: String, showCompleted: Boolean): List<Entry> {
        val q = query.trim()
        return todos
            .filter { t ->
                (showCompleted || !done(t)) &&
                    (filter == "Alle" || (filter == "Termine") == appointment(t)) &&
                    (q.isEmpty() || t["title"].contains(q, true) || t["notes"].contains(q, true))
            }
            .sortedWith(order)
    }

    fun agenda(todos: List<Entry>, scope: String, day: String) =
        when (scope) {
            "Tag" -> todos.filter { it["date"] == day }
            "Ohne Datum" -> todos.filter { it["date"].isEmpty() }
            else -> todos
        }

    data class Summary(val total: Int, val open: Int, val done: Int, val high: Int, val appointments: Int, val normal: Int, val overdue: Int) {
        val percent
            get() = if (total == 0) 0 else Math.round(done * 100.0 / total).toInt()
    }

    fun summary(todos: List<Entry>, today: String = LocalDate.now().toString()): Summary {
        val open = todos.filterNot { done(it) }
        val high = open.count { high(it) }
        val appointments = open.count { !high(it) && appointment(it) }
        return Summary(todos.size, open.size, todos.size - open.size, high, appointments, open.size - high - appointments, open.count { it["date"].isNotEmpty() && it["date"] < today })
    }
}

/** Zahnrad-Umriss: Modul m, Teilkreis R = m·N/2, Kopfkreis R + 0,7m, Fußkreis R − 0,8m. */
private fun gearPath(teeth: Int, m: Float, hole: Float = .46f): Path {
    val pitch = m * teeth / 2
    val outer = pitch + .7f * m
    val root = pitch - .8f * m
    val step = (2 * PI / teeth).toFloat()
    val p = Path()
    fun pt(r: Float, a: Float) = Offset(r * cos(a), r * sin(a))
    for (k in 0 until teeth) {
        val c = k * step
        val pts = listOf(pt(root, c - .27f * step), pt(outer, c - .19f * step), pt(outer, c + .19f * step), pt(root, c + .27f * step), pt(root, c + .73f * step))
        if (k == 0) p.moveTo(pts[0].x, pts[0].y) else p.lineTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { p.lineTo(it.x, it.y) }
    }
    p.close()
    if (hole > 0) p.addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, pitch * hole))
    p.fillType = PathFillType.EvenOdd
    return p
}

/** Getriebe-Karte (iOS Build 35): drei Räder in den Kalenderfarben, feste Drehzahl. */
@Composable
fun TodoGearCard(s: TodoModel.Summary) {
    val transition = rememberInfiniteTransition(label = "gears")
    // Großes Rad 0,6 × 2,75 rad/s ≈ eine Umdrehung in 3,8 s; die anderen im Zahnverhältnis.
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(3808, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val big = remember { gearPath(12, 11f) }
    val small = remember { gearPath(8, 11f) }
    val middle = remember { gearPath(9, 11f) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(120.dp)) {
                val scale = size.minDimension / 300f
                val m = 11f
                val rb = m * 12 / 2
                val rs = m * 8 / 2
                val rm = m * 9 / 2
                val th1 = (-65 * PI / 180).toFloat()
                val th2 = (20 * PI / 180).toFloat()
                val b = Offset(110f, 200f)
                val sPos = Offset(b.x + (rb + rs) * cos(th1), b.y + (rb + rs) * sin(th1))
                val mPos = Offset(sPos.x + (rs + rm) * cos(th2), sPos.y + (rs + rm) * sin(th2))
                val gears = listOf(Triple(b, big, 1f to (if (s.high > 0) TodoModel.red else TodoModel.red.copy(alpha = .35f))), Triple(sPos, small, -12f / 8 to (if (s.appointments > 0) TodoModel.orange else TodoModel.orange.copy(alpha = .35f))), Triple(mPos, middle, 12f / 9 to (if (s.normal > 0) TodoModel.green else TodoModel.green.copy(alpha = .35f))))
                for ((c, path, rc) in gears) {
                    translate(c.x * scale, c.y * scale) {
                        scale(scale, scale, Offset.Zero) {
                            rotate(if (s.open == 0) 0f else angle * rc.first + if (rc.first < 0) 22.5f else 0f, Offset.Zero) { drawPath(path, rc.second) }
                        }
                    }
                }
            }
            Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${s.open} offen · ${s.done} erledigt", fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total }, Modifier.fillMaxWidth())
                Text("${s.percent} % erledigt", style = MaterialTheme.typography.bodySmall)
                Legend(TodoModel.red, "Hohe Priorität", s.high)
                Legend(TodoModel.orange, "Termine", s.appointments)
                Legend(TodoModel.green, "Aufgaben", s.normal)
                if (s.overdue > 0) Text("${s.overdue} überfällig", color = TodoModel.red, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("  $label: $count", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TodoScreen(vm: UGSViewModel) {
    val today = LocalDate.now().toString()
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var day by rememberSaveable { mutableStateOf(today) }
    var filter by rememberSaveable { mutableStateOf("Alle") }
    var scope by rememberSaveable { mutableStateOf("Tag") }
    var query by rememberSaveable { mutableStateOf("") }
    var showCompleted by rememberSaveable { mutableStateOf(true) }
    var edit by remember { mutableStateOf<Entry?>(null) }
    var delete by remember { mutableStateOf<Entry?>(null) }
    val todos = vm.rows(Kind.TODO)
    val visible = TodoModel.visible(todos, filter, query, showCompleted)
    val grouped = visible.filter { it["date"].isNotEmpty() }.groupBy { it["date"] }
    val agenda = TodoModel.agenda(visible, scope, day)
    val canCreate = vm.can(AccessAction.CREATE, "todo")
    val canEdit = vm.can(AccessAction.EDIT, "todo")
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    fun choose(d: String) {
        day = d
        month = d.take(7)
        scope = "Tag"
    }
    val calendar: @Composable () -> Unit = {
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = YearMonth.parse(month).minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Vorheriger Monat") }
                    val ym = YearMonth.parse(month)
                    Text("${DateText.monthName(ym.monthValue)} ${ym.year}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { choose(today) }) { Text("Heute") }
                    IconButton(onClick = { month = YearMonth.parse(month).plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Nächster Monat") }
                }
                Row {
                    Text("KW", Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                MonthCalendar.grid(month).chunked(7).forEach { week ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val first = week.firstOrNull { it != null }
                        Text(first?.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)?.toString().orEmpty(), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        week.forEachIndexed { i, d ->
                            val iso = d?.toString()
                            val items = iso?.let { grouped[it] }.orEmpty()
                            val selected = iso == day
                            val holiday = d?.let { GermanHolidays.name(it) }.orEmpty()
                            Column(
                                Modifier.weight(1f)
                                    .height(52.dp)
                                    .padding(1.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else Color.Transparent)
                                    .then(if (iso == today) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable(enabled = iso != null) { choose(iso!!) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (d != null) {
                                    Text(
                                        "${d.dayOfMonth}",
                                        fontWeight = if (iso == today) FontWeight.Bold else FontWeight.Normal,
                                        color = if (holiday.isNotEmpty() || i >= 5) TodoModel.red.copy(alpha = .8f) else Color.Unspecified,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        items.take(4).forEach { t -> Box(Modifier.size(6.dp).clip(CircleShape).background(if (TodoModel.done(t)) Color.Gray else TodoModel.color(t))) }
                                    }
                                    if (items.size > 4) Text("+${items.size - 4}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val agendaPane: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Tag", "Alle", "Ohne Datum").forEach { FilterChip(scope == it, { scope = it }, label = { Text(it) }) }
            }
            Text(if (scope == "Tag") DateText.long(day) else if (scope == "Alle") "Alle Einträge" else "Einträge ohne Datum", style = MaterialTheme.typography.titleMedium)
            val holiday = runCatching { GermanHolidays.name(LocalDate.parse(day)) }.getOrNull().orEmpty()
            if (scope == "Tag" && holiday.isNotEmpty()) Text("Feiertag: $holiday", color = TodoModel.red, style = MaterialTheme.typography.bodySmall)
            if (agenda.isEmpty()) Text("Keine Einträge.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            agenda.forEach { t ->
                ListItem(
                    headlineContent = { Text(t["title"], textDecoration = if (TodoModel.done(t)) TextDecoration.LineThrough else null) },
                    supportingContent = {
                        Text(
                            listOf(t["todoKind"], t["date"].takeIf { it.isNotEmpty() && scope != "Tag" }?.let { DateText.german(it) }, t["dueTime"].takeIf { it.isNotEmpty() }?.let { "$it Uhr" }, t["priority"].takeIf { it == "Hoch" }?.let { "Hohe Priorität" }, t["notes"].take(80).takeIf { it.isNotBlank() })
                                .filterNotNull()
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                        )
                    },
                    leadingContent = {
                        Checkbox(TodoModel.done(t), { v -> vm.save(t.copy(fields = t.fields + ("status" to if (v) "Erledigt" else "Offen"))) {} }, enabled = canEdit, colors = CheckboxDefaults.colors(checkedColor = TodoModel.color(t), uncheckedColor = TodoModel.color(t)))
                    },
                    trailingContent = { if (vm.canDelete(Kind.TODO)) IconButton(onClick = { delete = t }) { Icon(Icons.Default.Delete, "In Papierkorb") } },
                    modifier = Modifier.clickable(enabled = canEdit) { edit = t },
                )
                HorizontalDivider()
            }
            if (scope == "Tag") {
                val date = runCatching { LocalDate.parse(day) }.getOrNull()
                if (date != null && vm.company["prayerTimes"] != "false") {
                    val times = PrayerTimes.koeln(date)
                    val s = if (day == today) PrayerSchedule.at() else PrayerSchedule(times, if (day > today) 0 else null, "", 0)
                    ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) { PrayerList(s, Modifier.padding(14.dp)) }
                }
            }
        }
    }
    Scaffold(
        floatingActionButton = {
            if (canCreate)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtendedFloatingActionButton(onClick = { edit = Entry(kind = Kind.TODO, fields = mapOf("todoKind" to "Termin", "date" to day, "status" to "Offen", "priority" to "Normal")) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Termin") })
                    ExtendedFloatingActionButton(onClick = { edit = Entry(kind = Kind.TODO, fields = mapOf("todoKind" to "Aufgabe", "date" to day, "status" to "Offen", "priority" to "Normal")) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Aufgabe") })
                }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TodoGearCard(TodoModel.summary(todos, today)) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("Alle", "Aufgaben", "Termine").forEach { FilterChip(filter == it, { filter = it }, label = { Text(it) }) }
                    FilterChip(showCompleted, { showCompleted = !showCompleted }, label = { Text("Erledigte") })
                }
            }
            item { OutlinedTextField(query, { query = it }, placeholder = { Text("Titel oder Beschreibung suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            if (wide)
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1.4f)) { calendar() }
                        Box(Modifier.weight(1f)) { agendaPane() }
                    }
                }
            else {
                item { calendar() }
                item { agendaPane() }
            }
        }
    }
    edit?.let { e ->
        FormDialog(if (e.revision == 0 && todos.none { it.id == e.id }) "Neuer Eintrag" else "Eintrag bearbeiten", schemas.getValue(Kind.TODO), e.fields, vm, { edit = null }) { values ->
            vm.save(e.copy(fields = e.fields + values)) { edit = null }
        }
    }
    delete?.let { e ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("In Papierkorb verschieben?") },
            text = { Text(e["title"]) },
            confirmButton = { TextButton(onClick = { vm.delete(e) { delete = null } }) { Text("In Papierkorb") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } },
        )
    }
}
