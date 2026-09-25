@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PasswordInput(value: String, change: (String) -> Unit, label: String) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            IconButton(onClick = { show = !show }) {
                Icon(
                    if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (show) "Passwort verbergen" else "Passwort anzeigen",
                )
            }
        },
    )
}

@Composable
fun ChoiceInput(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            options.find { it.first == value }?.second ?: value,
            {},
            label = { Text(label) },
            readOnly = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 340.dp),
        ) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        change(id)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
fun CalendarInput(
    label: String,
    value: String,
    required: Boolean = false,
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                if (value.isBlank()) ""
                else runCatching { Rules.german(value) }.getOrDefault(value),
                {},
                readOnly = true,
                label = { Text(label + if (required) " *" else "") },
                trailingIcon = { Icon(Icons.Default.CalendarMonth, "Datum auswählen") },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { open = true })
        }
        if (!required && value.isNotEmpty())
            IconButton(onClick = { change("") }) { Icon(Icons.Default.Clear, "Datum löschen") }
    }
    if (open) {
        val initial =
            runCatching {
                    LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
                .getOrNull()
        val state =
            rememberDatePickerState(initialSelectedDateMillis = initial, yearRange = 1900..2120)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let {
                            change(
                                Instant.ofEpochMilli(it)
                                    .atZone(ZoneOffset.UTC)
                                    .toLocalDate()
                                    .toString()
                            )
                        }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) {
                    Text("Übernehmen")
                }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
        ) {
            DatePicker(state, showModeToggle = false)
        }
    }
}

@Composable
fun FieldInput(f: Field, value: String, vm: UGSViewModel, change: (String) -> Unit) {
    val label = f.label + if (f.required) " *" else ""
    when (f.input) {
        Input.DATE -> CalendarInput(f.label, value, f.required, change)
        Input.CHECK ->
            Row(
                Modifier.fillMaxWidth().clickable { change((value != "true").toString()) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(value == "true", { change(it.toString()) })
                Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        Input.CHOICE -> ChoiceInput(label, value, f.options.map { it to it }, change)
        Input.WORKER,
        Input.SITE ->
            ChoiceInput(
                label,
                value,
                listOf("" to "Bitte wählen") +
                    vm.rows(if (f.input == Input.WORKER) Kind.WORKER else Kind.SITE).map {
                        it.id to
                            if (f.input == Input.WORKER) "${it.title} · ${it["personnelNumber"]}"
                            else it.title
                    },
                change,
            )
        Input.PASSWORD -> PasswordInput(value, change, label)
        Input.TIME -> {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedTextField(
                    value,
                    {},
                    label = { Text(label) },
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Default.Schedule, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { open = true })
            }
            if (open) {
                val old = runCatching { LocalTime.parse(value) }.getOrDefault(LocalTime.of(8, 0))
                val state = rememberTimePickerState(old.hour, old.minute, true)
                AlertDialog(
                    onDismissRequest = { open = false },
                    title = { Text(f.label) },
                    text = { TimeInput(state) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                change("%02d:%02d".format(state.hour, state.minute))
                                open = false
                            }
                        ) {
                            Text("Übernehmen")
                        }
                    },
                    dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
                )
            }
        }
        else ->
            OutlinedTextField(
                value,
                change,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = f.input != Input.MULTILINE,
                minLines = if (f.input == Input.MULTILINE) 3 else 1,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            if (f.input == Input.NUMBER) KeyboardType.Decimal else KeyboardType.Text
                    ),
            )
    }
}

@Composable
fun FormDialog(
    title: String,
    fields: List<Field>,
    initial: Map<String, String>,
    vm: UGSViewModel,
    dismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    val values = remember {
        mutableStateMapOf<String, String>().apply {
            fields.forEach { put(it.key, it.initial) }
            putAll(initial)
        }
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 720.dp).fillMaxHeight(.94f),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.imePadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = dismiss) { Icon(Icons.Default.Close, "Schließen") }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(fields, key = { it.key }) { f ->
                        FieldInput(f, values[f.key].orEmpty(), vm) { values[f.key] = it }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = dismiss) { Text("Abbrechen") }
                    Button(onClick = { onSave(values.toMap()) }, enabled = !vm.busy) {
                        Text("Speichern")
                    }
                }
            }
        }
    }
}

@Composable
fun RecordsScreen(vm: UGSViewModel, kind: Kind) {
    var search by remember(kind) { mutableStateOf("") }
    var edit by remember(kind) { mutableStateOf<Entry?>(null) }
    var detail by remember(kind) { mutableStateOf<Entry?>(null) }
    var delete by remember { mutableStateOf<Entry?>(null) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var monthFilter by
        remember(kind) { mutableStateOf(kind in listOf(Kind.SHIFT, Kind.TIME, Kind.PAYROLL)) }
    var pendingDoc by remember { mutableStateOf<Map<String, String>?>(null) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.run {
                    val values = pendingDoc ?: return@run
                    withContext(Dispatchers.IO) {
                        val mime = vm.app.contentResolver.getType(uri) ?: "application/pdf"
                        val data = ImportService.read(vm.app, uri)
                        vm.repo.attach(Entry(kind = Kind.DOCUMENT, fields = values), data, mime)
                    }
                    vm.refresh()
                    pendingDoc = null
                    edit = null
                }
        }
    val rows =
        vm.rows(kind)
            .filter { e ->
                (!monthFilter || e["date"].startsWith(month.toString())) &&
                    (search.isBlank() ||
                        (e.fields.values.joinToString(" ") +
                                vm.entries.find { it.id == e["workerId"] }?.title)
                            .contains(search, true))
            }
            .let {
                if (kind == Kind.WORKER) it
                else it.sortedByDescending { e -> e["date"] + e["startTime"] }
            }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                search,
                { search = it },
                label = { Text("Suchen") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            if (vm.canEdit)
                IconButton(
                    onClick = {
                        edit =
                            Entry(
                                kind = kind,
                                fields =
                                    schemas[kind].orEmpty().associate {
                                        it.key to
                                            if (it.input == Input.DATE && it.required) today
                                            else it.initial
                                    },
                            )
                    }
                ) {
                    Icon(Icons.Default.Add, "Neu")
                }
            IconButton(
                onClick = {
                    vm.run {
                        vm.showPdf(kind.title) {
                            vm.pdf.report(kind.title, rows, vm.entries, vm.company)
                        }
                    }
                }
            ) {
                Icon(Icons.Default.PictureAsPdf, "Liste als PDF")
            }
        }
        if (kind in listOf(Kind.SHIFT, Kind.TIME, Kind.PAYROLL, Kind.ABSENCE)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { month = month.minusMonths(1) }) {
                    Icon(Icons.Default.ChevronLeft, "Voriger Monat")
                }
                Text(
                    month.format(
                        java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY)
                    ),
                    Modifier.weight(1f),
                )
                IconButton(onClick = { month = month.plusMonths(1) }) {
                    Icon(Icons.Default.ChevronRight, "Nächster Monat")
                }
                FilterChip(
                    selected = monthFilter,
                    onClick = { monthFilter = !monthFilter },
                    label = { Text("Monat") },
                )
            }
        }
        if (kind == Kind.REGISTRATION)
            Text(
                "Meldungen werden dokumentiert. Die gesetzliche Übermittlung erfolgt über dein Meldeportal.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rows.isEmpty())
                item {
                    EmptyState(
                        "Keine Einträge",
                        if (search.isBlank()) "Mit + einen neuen Datensatz anlegen."
                        else "Bitte Suchbegriff oder Monatsfilter ändern.",
                    )
                }
            items(rows, key = { it.id }) { e -> RecordCard(e, vm) { detail = e } }
        }
    }
    edit?.let { e ->
        FormDialog(
            if (e.revision == 0) "${kind.title} anlegen" else "${kind.title} bearbeiten",
            schemas[kind].orEmpty(),
            e.fields,
            vm,
            { edit = null },
        ) { values ->
            if (kind == Kind.DOCUMENT && e.revision == 0) {
                pendingDoc = values
                pick.launch(arrayOf("application/pdf", "image/*"))
            } else
                vm.save(e.copy(fields = e.fields + values)) {
                    edit = null
                    detail = null
                }
        }
    }
    detail?.let { e ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(e.title) },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    schemas[kind].orEmpty().forEach { f ->
                        val raw = e[f.key]
                        if (raw.isNotBlank()) {
                            Text(
                                f.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                when (f.input) {
                                    Input.DATE -> Rules.german(raw)
                                    Input.WORKER,
                                    Input.SITE -> vm.entries.find { it.id == raw }?.title.orEmpty()
                                    else -> raw
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Column {
                    if (kind == Kind.DOCUMENT)
                        TextButton(
                            onClick = {
                                vm.run {
                                    val bytes = withContext(Dispatchers.IO) { vm.repo.document(e) }
                                    if (e["mime"] == "application/pdf")
                                        vm.showPdf(e.title) { bytes }
                                    else {
                                        val ext = if (e["mime"] == "image/png") "png" else "jpg"
                                        val file = vm.pdf.export(bytes, "${e.title}.$ext")
                                        vm.pdf.share(file, e["mime"])
                                    }
                                    detail = null
                                }
                            }
                        ) {
                            Text("Öffnen / Teilen")
                        }
                    if (vm.canEdit)
                        TextButton(
                            onClick = {
                                detail = null
                                edit = e
                            }
                        ) {
                            Text("Bearbeiten")
                        }
                }
            },
            dismissButton = {
                Row {
                    if (vm.canEdit)
                        TextButton(
                            onClick = {
                                delete = e
                                detail = null
                            }
                        ) {
                            Text("Löschen")
                        }
                    TextButton(onClick = { detail = null }) { Text("Schließen") }
                }
            },
        )
    }
    delete?.let { e ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text("In den Papierkorb verschieben?") },
            text = { Text(e.title) },
            confirmButton = {
                TextButton(onClick = { vm.delete(e) { delete = null } }) { Text("Verschieben") }
            },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
fun ContractScreen(vm: UGSViewModel, mode: String) {
    val detailFields = remember { Contracts.detailFields(vm.app) }
    val fields =
        remember(mode) {
            when (mode) {
                "contract" -> contractFields
                "agreement" -> agreementFields
                else -> probationFields
            }
        }
    val defaults =
        remember(mode) {
            fields.associate {
                it.key to if (it.input == Input.DATE && it.required) today else it.initial
            } + ("signingPlace" to vm.company["city"].orEmpty())
        }
    val values = remember(mode) { mutableStateMapOf<String, String>().apply { putAll(defaults) } }
    var details by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf(false) }
    val selected = vm.entries.find { it.id == values["workerId"] }
    LaunchedEffect(values["workerId"]) {
        selected?.let { if (mode == "probation") values["contractStart"] = it["startDate"] }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                when (mode) {
                    "contract" -> "Arbeitsvertrag erstellen"
                    "agreement" -> "Aufhebungsvertrag erstellen"
                    else -> "Kündigung innerhalb der Probezeit"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        item {
            Text(
                "Mitarbeiterdaten und Firmenanschrift werden übernommen. Vor dem Teilen kannst du das vollständige PDF prüfen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(Contracts.visibleFields(fields, values), key = { it.key }) { f ->
            FieldInput(f, values[f.key].orEmpty(), vm) { values[f.key] = it }
        }
        if (mode == "contract") {
            item {
                TextButton(onClick = { details = !details }) {
                    Text(
                        if (details) "Weitere Vertragsangaben schließen"
                        else "Tarif, Zuschläge, Datenschutz und weitere Angaben"
                    )
                }
            }
            if (details)
                items(detailFields, key = { it.key }) { f ->
                    FieldInput(f, values[f.key].orEmpty(), vm) { values[f.key] = it }
                }
        }
        if (mode == "probation")
            item {
                Text(
                    "Diese Vorlage prüft die gesetzliche Zweiwochenfrist. Abweichende Tariffristen und besonderen Kündigungsschutz vor Verwendung prüfen. Das Schreiben muss formwirksam unterschrieben und zugestellt werden.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        if (mode == "contract" && values["duration"] == "Bewachungsauftrag")
            item {
                Text(
                    "Der Verlust eines Kundenauftrags allein beendet das Arbeitsverhältnis nicht. Die Zweckbefristung setzt einen tragfähigen Sachgrund und einen konkret bestimmbaren vorübergehenden Zweck voraus.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        item {
            Button(
                onClick = {
                    vm.run {
                        val worker = selected ?: error("Bitte Mitarbeiter wählen.")
                        val v = values.toMap()
                        vm.showPdf(
                            when (mode) {
                                "contract" -> "Arbeitsvertrag"
                                "agreement" -> "Aufhebungsvertrag"
                                else -> "Kuendigung_Probezeit"
                            }
                        ) {
                            when (mode) {
                                "contract" ->
                                    vm.pdf.template(
                                        "ugs-arbeitsvertrag",
                                        Contracts.tokens(vm.app, worker, vm.company, v),
                                        vm.company,
                                    )
                                "agreement" ->
                                    vm.pdf.template(
                                        "ugs-aufhebungsvertrag",
                                        Contracts.agreement(worker, vm.company, v),
                                        vm.company,
                                    )
                                else -> vm.pdf.probation(worker, vm.company, v)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(8.dp))
                Text("PDF erstellen und prüfen")
            }
        }
        item {
            Row {
                if (vm.canEdit)
                    TextButton(
                        onClick = {
                            vm.save(
                                Entry(
                                    kind = Kind.DRAFT,
                                    fields =
                                        values.toMap() +
                                            mapOf(
                                                "title" to
                                                    "${selected?.title?:"Entwurf"} · ${Rules.german(today)}",
                                                "mode" to mode,
                                            ),
                                )
                            ) {
                                vm.notice = "Entwurf gespeichert."
                            }
                        }
                    ) {
                        Text("Entwurf speichern")
                    }
                TextButton(onClick = { drafts = true }) { Text("Entwurf laden") }
            }
        }
    }
    if (drafts)
        AlertDialog(
            onDismissRequest = { drafts = false },
            title = { Text("Gespeicherte Entwürfe") },
            text = {
                LazyColumn {
                    val rows = vm.rows(Kind.DRAFT).filter { it["mode"] == mode }
                    if (rows.isEmpty()) item { Text("Keine Entwürfe vorhanden.") }
                    items(rows) { e ->
                        TextButton(
                            onClick = {
                                values.clear()
                                values.putAll(defaults)
                                values.putAll(e.fields)
                                drafts = false
                            }
                        ) {
                            Text(e.title)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { drafts = false }) { Text("Schließen") } },
        )
}
