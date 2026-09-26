package de.ugs.sicherheit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VacationScreen(vm: UGSViewModel) {
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var editor by remember { mutableStateOf<VacationSummary?>(null) }
    var carry by remember { mutableStateOf(false) }
    val summaries = vm.rows(Kind.WORKER).map { VacationSummary.build(it, year, vm.entries) }
    val editable = vm.can(AccessAction.EDIT, "absences")
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (year > 2000) year-- }) { Text("‹") }
                Text("Urlaubskonto $year", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (year < 2100) year++ }) { Text("›") }
            }
        }
        item {
            Text(
                "Genommen: genehmigter Jahresurlaub Montag–Freitag im Jahr. Beantragt: noch nicht genehmigter Urlaub. Krankheit, Sonderurlaub usw. zählen nicht gegen den Anspruch. Feiertage und abweichende Arbeitswochen bitte über die Korrektur berücksichtigen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (editable) item { OutlinedButton(onClick = { carry = true }) { Text("Resturlaub aus Vorjahr übernehmen") } }
        items(summaries, key = { it.worker.id }) { v ->
            Card(onClick = { editor = v }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(v.worker.title, style = MaterialTheme.typography.titleMedium)
                    Text("Rest: ${decimal(v.remaining, 1)} · Anspruch: ${decimal(v.total, 1)} · Genommen: ${v.taken}")
                    Text(
                        "Beantragt: ${v.requested} Tage · Sonstige Abwesenheit: ${v.other} Tage${if (v.account == null) " · Anspruch aus Personalakte" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    editor?.let { v ->
        FormDialog(
            "${v.worker.title} · $year",
            listOf(num("entitlement", "Jahresanspruch (Tage)"), num("carryOver", "Übertrag"), field("adjustment", "Korrektur (auch negativ)"), longText("notes", "Notizen")),
            mapOf(
                "entitlement" to decimal(v.entitlement, 1),
                "carryOver" to decimal(v.carryOver, 1),
                "adjustment" to decimal(v.adjustment, 1),
                "notes" to v.account?.get("notes").orEmpty(),
            ),
            vm,
            { editor = null },
        ) { values ->
            if (!editable) editor = null
            else {
                val base = v.account ?: Entry(kind = Kind.VACATION_ACCOUNT, fields = mapOf("workerId" to v.worker.id, "year" to "$year"))
                vm.save(base.copy(fields = base.fields + values + ("title" to "Urlaubskonto ${v.worker.title} $year"))) { editor = null }
            }
        }
    }
    if (carry)
        AlertDialog(
            onDismissRequest = { carry = false },
            title = { Text("Resturlaub übernehmen?") },
            text = { Text("Der Rest aus ${year - 1} wird als Übertrag für $year eingetragen. Bestehende Überträge werden aktualisiert.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.run {
                            val batch =
                                vm.rows(Kind.WORKER).map { w ->
                                    val prev = VacationSummary.build(w, year - 1, vm.entries)
                                    val cur = VacationSummary.build(w, year, vm.entries)
                                    val base =
                                        cur.account
                                            ?: Entry(
                                                kind = Kind.VACATION_ACCOUNT,
                                                fields = mapOf("workerId" to w.id, "year" to "$year", "entitlement" to decimal(cur.entitlement, 1), "title" to "Urlaubskonto ${w.title} $year"),
                                            )
                                    base.copy(fields = base.fields + ("carryOver" to decimal(maxOf(0.0, prev.remaining), 1)))
                                }
                            withContext(Dispatchers.IO) { batch.forEach { vm.repo.save(it) } }
                            vm.refresh()
                            carry = false
                            vm.notice = "Resturlaub für ${batch.size} Mitarbeiter übernommen."
                        }
                    }
                ) {
                    Text("Übernehmen")
                }
            },
            dismissButton = { TextButton(onClick = { carry = false }) { Text("Abbrechen") } },
        )
}

@Composable
fun ReportsScreen(vm: UGSViewModel) {
    var kind by remember { mutableStateOf(Kind.TIME) }
    var month by remember { mutableStateOf(YearMonth.now().atDay(1).toString()) }
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Bericht erstellen", style = MaterialTheme.typography.headlineSmall)
        ChoiceInput(
            "Bereich",
            kind.name,
            Kind.entries.filter { it != Kind.DRAFT }.map { it.name to it.title },
        ) {
            kind = Kind.valueOf(it)
        }
        CalendarInput("Monat (ein Datum auswählen)", month, true) { month = it }
        val rows =
            vm.rows(kind).filter { it["date"].isBlank() || it["date"].startsWith(month.take(7)) }
        Text("${rows.size} Datensätze im gewählten Zeitraum")
        if (kind in listOf(Kind.TIME, Kind.SHIFT))
            Text(
                "Arbeitsstunden: ${String.format(Locale.GERMANY,"%.2f",rows.filter{it["status"]!="Abgesagt"}.sumOf{Rules.hours(it)})}"
            )
        Button(
            onClick = {
                vm.run {
                    vm.showPdf("${kind.title}_${month.take(7)}") {
                        vm.pdf.report(
                            "${kind.title} · ${month.take(7)}",
                            rows,
                            vm.entries,
                            vm.company,
                        )
                    }
                }
            }
        ) {
            Text("Bericht als PDF")
        }
    }
}

@Composable
fun SettingsScreen(vm: UGSViewModel) {
    var page by rememberSaveable { mutableStateOf("") }
    if (page.isNotEmpty()) BackHandler { page = "" }
    when (page) {
        "company" -> CompanySettings(vm) { page = "" }
        "lookups" -> LookupSettings(vm) { page = "" }
        "mail" -> MailSettingsScreen(vm) { page = "" }
        "voice" -> VoiceSettingsScreen(vm) { page = "" }
        else ->
            LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Card(onClick = { page = "company" }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(vm.company["name"] ?: "Firmendaten") },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                            vm.company["city"],
                                            vm.user?.let { "Angemeldet: ${it.username}" },
                                        )
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · ")
                                )
                            },
                            leadingContent = { Icon(Icons.Default.Business, null) },
                        )
                    }
                }
                item { SectionHeader("Darstellung") }
                item {
                    ChoiceInput(
                        "Darstellung",
                        vm.company["theme"] ?: "System",
                        listOf("System", "Hell", "Dunkel").map { it to it },
                    ) { v ->
                        vm.run {
                            withContext(Dispatchers.IO) { vm.repo.settings(mapOf("theme" to v)) }
                            vm.refresh()
                        }
                    }
                }
                item {
                    ChoiceInput(
                        "App-Symbol",
                        vm.company["appIcon"] ?: "Dunkel",
                        listOf("Dunkel", "Hell").map { it to it },
                    ) { v ->
                        vm.run {
                            withContext(Dispatchers.IO) {
                                vm.repo.settings(mapOf("appIcon" to v))
                                switchLauncher(vm, v == "Hell")
                            }
                            vm.refresh()
                        }
                    }
                }
                item { SectionHeader("Kommunikation und Stammdaten") }
                item {
                    SettingsRow("E-Mail-Versand und Posteingang", "SMTP, IMAP, Signatur, Ordner", Icons.Default.Email) {
                        page = "mail"
                    }
                }
                item {
                    SettingsRow("Sprachassistent „Alas“", if (Voice.enabled(vm)) "Ein" else "Aus", UgsIcons.Mic) {
                        page = "voice"
                    }
                }
                item {
                    SettingsRow("Auswahllisten", "${vm.lookups.size} Listen", Icons.Default.List) {
                        page = "lookups"
                    }
                }
                item { SectionHeader("Konto") }
                item {
                    SettingsRow("Eigenes Profil", "Passwort und Profilfoto", Icons.Default.AccountCircle) {
                        page = "profile"
                    }
                }
                item {
                    Text(
                        "UGS Sicherheit Android $APP_VERSION\nNative Kotlin / Jetpack Compose · Android 8 oder neuer\nFunktionsstand wie iPhone/iPad 1.9.0 (Build 36). Lokale Datenbank und Dokumente verschlüsselt.",
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
    }
    if (page == "profile") ProfileDialog(vm) { page = "" }
}

const val APP_VERSION = "1.9.0"

fun switchLauncher(vm: UGSViewModel, light: Boolean) {
    val manager = vm.app.packageManager
    val enable = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    val disable = android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    val selected = if (light) "LauncherLight" else "LauncherDark"
    val other = if (light) "LauncherDark" else "LauncherLight"
    manager.setComponentEnabledSetting(
        android.content.ComponentName(vm.app, "de.ugs.sicherheit.$selected"),
        enable,
        android.content.pm.PackageManager.DONT_KILL_APP,
    )
    manager.setComponentEnabledSetting(
        android.content.ComponentName(vm.app, "de.ugs.sicherheit.$other"),
        disable,
        android.content.pm.PackageManager.DONT_KILL_APP,
    )
}

@Composable
fun SettingsRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    click: () -> Unit,
) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { if (detail.isNotBlank()) Text(detail) },
            leadingContent = { Icon(icon, null) },
            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
        )
    }
}

@Composable
fun BackRow(title: String, back: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun CompanySettings(vm: UGSViewModel, back: () -> Unit) {
    val values =
        remember(vm.company) { mutableStateMapOf<String, String>().apply { putAll(vm.company) } }
    val editable = vm.can(AccessAction.EDIT, "settings")
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { BackRow("Firmendaten", back) }
        items(companyFields) { f ->
            FieldInput(f, values[f.key].orEmpty(), vm) { if (editable) values[f.key] = it }
        }
        item {
            Text(
                "Diese Angaben erscheinen in Verträgen, PDFs und E-Mails.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (editable)
            item {
                Button(
                    onClick = {
                        vm.run {
                            for (f in companyFields.filter { it.required }) require(
                                !values[f.key].isNullOrBlank()
                            ) {
                                "${f.label} fehlt."
                            }
                            withContext(Dispatchers.IO) {
                                vm.repo.settings(values.filterKeys { k -> companyFields.any { it.key == k } })
                            }
                            vm.refresh()
                            vm.notice = "Firmendaten gespeichert."
                        }
                    }
                ) {
                    Text("Sichern")
                }
            }
    }
}

@Composable
fun LookupSettings(vm: UGSViewModel, back: () -> Unit) {
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var newList by remember { mutableStateOf(false) }
    val editable = vm.can(AccessAction.EDIT, "settings")
    val current = group
    if (current != null) {
        BackHandler { group = null }
        LookupGroupEditor(vm, current) { group = null }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BackRow("Auswahllisten", back) }
        items(vm.lookups.keys.sortedBy { Lookups.title(it).lowercase() }) { g ->
            SettingsRow(Lookups.title(g), "${vm.lookups[g].orEmpty().size} Einträge", Icons.Default.List) {
                group = g
            }
        }
        item {
            Text(
                "Diese Listen erscheinen als Auswahl in Formularen der App. Freitext bleibt möglich.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (editable) item { OutlinedButton(onClick = { newList = true }) { Text("Neue Liste") } }
    }
    if (newList)
        FormDialog(
            "Neue Liste",
            listOf(field("name", "Name, z. B. Fahrzeuge", true)),
            emptyMap(),
            vm,
            { newList = false },
        ) { v ->
            val key =
                v["name"].orEmpty().trim().lowercase().replace(Regex("[^a-z0-9äöüß]+"), "_")
                    .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                    .trim('_')
            if (key.isNotEmpty()) group = key
            newList = false
        }
}

@Composable
fun LookupGroupEditor(vm: UGSViewModel, group: String, back: () -> Unit) {
    val values = remember(group, vm.lookups) { vm.lookups[group].orEmpty().toMutableStateList() }
    var input by remember { mutableStateOf("") }
    var rename by remember { mutableStateOf<Int?>(null) }
    val editable = vm.can(AccessAction.EDIT, "settings")
    fun persist() {
        val list = values.toList()
        vm.run {
            withContext(Dispatchers.IO) { vm.repo.saveLookups(vm.lookups + (group to list)) }
            vm.refresh()
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { BackRow(Lookups.title(group), back) }
        if (editable)
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        input,
                        { input = it },
                        label = { Text("Neuer Eintrag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            val v = input.trim()
                            if (v.isNotEmpty() && v !in values) {
                                values += v
                                input = ""
                                persist()
                            }
                        }
                    ) {
                        Icon(Icons.Default.AddCircle, "Eintrag hinzufügen")
                    }
                }
            }
        item { Text("${values.size} ${if (values.size == 1) "Eintrag" else "Einträge"}", style = MaterialTheme.typography.labelMedium) }
        itemsIndexed(values.toList()) { i, v ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, Modifier.weight(1f).clickable(enabled = editable) { rename = i })
                    if (editable) {
                        IconButton(
                            onClick = {
                                if (i > 0) {
                                    values.add(i - 1, values.removeAt(i))
                                    persist()
                                }
                            },
                            enabled = i > 0,
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, "Nach oben")
                        }
                        IconButton(
                            onClick = {
                                values.removeAt(i)
                                persist()
                            }
                        ) {
                            Icon(Icons.Default.Delete, "Löschen")
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Antippen zum Umbenennen. Bereits gespeicherte Datensätze behalten ihren Wert.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    rename?.let { i ->
        FormDialog(
            "Eintrag umbenennen",
            listOf(field("value", "Wert", true)),
            mapOf("value" to values.getOrElse(i) { "" }),
            vm,
            { rename = null },
        ) { v ->
            val text = v["value"].orEmpty().trim()
            if (text.isNotEmpty() && i in values.indices) {
                values[i] = text
                persist()
            }
            rename = null
        }
    }
}

@Composable
fun ProfileDialog(vm: UGSViewModel, dismiss: () -> Unit) {
    var password by remember { mutableStateOf(false) }
    val pickPhoto =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.run {
                    val png = withContext(Dispatchers.IO) { Photos.thumbnail(vm.app, uri) }
                    withContext(Dispatchers.IO) { vm.repo.setPhoto("user-${vm.user?.id}", png) }
                    Photos.invalidate()
                }
        }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(vm.user?.username.orEmpty()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(vm, 64.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(vm.user?.role.orEmpty(), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            "${vm.user?.let { AccessControl.permissionSet(it).size } ?: 0} Bereiche freigegeben",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Profilfoto wählen")
                }
                OutlinedButton(
                    onClick = {
                        vm.run {
                            withContext(Dispatchers.IO) { vm.repo.setPhoto("user-${vm.user?.id}", null) }
                            Photos.invalidate()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Profilfoto entfernen")
                }
                OutlinedButton(onClick = { password = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Eigenes Passwort ändern")
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Schließen") } },
    )
    if (password)
        FormDialog(
            "Passwort ändern",
            listOf(
                Field("old", "Bisheriges Passwort", Input.PASSWORD, true),
                Field("new", "Neues Passwort", Input.PASSWORD, true),
                Field("confirm", "Wiederholen", Input.PASSWORD, true),
            ),
            emptyMap(),
            vm,
            { password = false },
        ) { v ->
            vm.run {
                require(v["new"] == v["confirm"]) { "Passwörter stimmen nicht überein." }
                withContext(Dispatchers.IO) {
                    vm.repo.password(v["old"].orEmpty(), v["new"].orEmpty())
                }
                password = false
                vm.notice = "Passwort geändert."
            }
        }
}

@Composable
fun UsersScreen(vm: UGSViewModel) {
    var users by remember { mutableStateOf(emptyList<Account>()) }
    var add by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Account?>(null) }
    var remove by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(Unit) { vm.run { users = withContext(Dispatchers.IO) { vm.repo.users() } } }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Zugriff verwalten", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Administrator: alle Bereiche. Personal, Planung und Lesen: feste Bereiche. Benutzerdefiniert: Bereiche und Aktionen (Anlegen, Bearbeiten, Löschen, Exportieren) einzeln freigeben."
            )
        }
        item { Button(onClick = { add = true }) { Text("Benutzer anlegen") } }
        items(users) { u ->
            Card(onClick = { editing = u }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.username, style = MaterialTheme.typography.titleMedium)
                        Text(u.role + if (u.active) " · Aktiv" else " · Deaktiviert")
                    }
                    if (u.id != vm.user?.id)
                        TextButton(onClick = { remove = u }) { Text("Entfernen") }
                }
            }
        }
    }
    if (add || editing != null)
        UserEditor(vm, editing, { add = false; editing = null }) {
            users = withContext(Dispatchers.IO) { vm.repo.users() }
            add = false
            editing = null
        }
    remove?.let { u ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("Benutzer entfernen?") },
            text = { Text("${u.username} kann sich anschließend nicht mehr anmelden.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.run {
                            withContext(Dispatchers.IO) { vm.repo.removeUser(u.id) }
                            users = withContext(Dispatchers.IO) { vm.repo.users() }
                            remove = null
                        }
                    }
                ) {
                    Text("Entfernen")
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
fun UserEditor(
    vm: UGSViewModel,
    user: Account?,
    dismiss: () -> Unit,
    saved: suspend () -> Unit,
) {
    var username by remember { mutableStateOf(user?.username.orEmpty()) }
    var role by remember { mutableStateOf(user?.role ?: "Lesen") }
    var active by remember { mutableStateOf(user?.active ?: true) }
    var password by remember { mutableStateOf("") }
    val grants = remember {
        (user?.let { AccessControl.parse(it.permissions) } ?: emptySet()).toMutableStateList()
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = dismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 720.dp).fillMaxHeight(.94f),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column {
                Text(
                    if (user == null) "Benutzer anlegen" else "Benutzerkonto",
                    Modifier.padding(20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        OutlinedTextField(
                            username,
                            { if (user == null) username = it },
                            label = { Text("Benutzername") },
                            readOnly = user != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item { ChoiceInput("Rolle", role, AccessControl.roles.map { it to it }) { role = it } }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(active, { active = it })
                            Text("Aktiv", Modifier.padding(start = 12.dp))
                        }
                    }
                    item {
                        PasswordInput(
                            password,
                            { password = it },
                            if (user == null) "Passwort (mind. 12 Zeichen)"
                            else "Neues Passwort (leer = unverändert)",
                        )
                    }
                    if (role == "Benutzerdefiniert")
                        items(AccessControl.all.filter { it != "users" }) { key ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            key in grants,
                                            { checked ->
                                                if (checked) {
                                                    grants.add(key)
                                                } else {
                                                    grants.removeAll { g -> g == key || g.startsWith("$key.") }
                                                }
                                            },
                                        )
                                        Text(AccessControl.labels[key] ?: key, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    }
                                    if (key in grants)
                                        Row {
                                            for (a in AccessAction.entries.filter { it != AccessAction.VIEW }) {
                                                val k = "$key.${a.name.lowercase()}"
                                                FilterChip(
                                                    k in grants,
                                                    {
                                                        if (k in grants) {
                                                            grants.remove(k)
                                                        } else {
                                                            grants.add(k)
                                                        }
                                                    },
                                                    label = { Text(a.title) },
                                                    modifier = Modifier.padding(end = 4.dp),
                                                )
                                            }
                                        }
                                }
                            }
                        }
                    else
                        item {
                            Text(
                                "Bereiche: " +
                                    AccessControl.rolePresets[role].orEmpty().mapNotNull { AccessControl.labels[it] }.sorted().joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text("Abbrechen") }
                    Button(
                        onClick = {
                            vm.run {
                                withContext(Dispatchers.IO) {
                                    if (user == null)
                                        vm.repo.addUser(username, password, role, grants.toSet())
                                    else vm.repo.updateUser(user.id, role, grants.toSet(), active, password)
                                }
                                saved()
                            }
                        },
                        enabled = !vm.busy,
                    ) {
                        Text("Speichern")
                    }
                }
            }
        }
    }
}

@Composable
fun ControlScreen(vm: UGSViewModel) {
    var history by remember { mutableStateOf(emptyList<String>()) }
    var trash by remember { mutableStateOf(emptyList<Entry>()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var purge by remember { mutableStateOf<Entry?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    fun reload() =
        vm.run {
            history = withContext(Dispatchers.IO) { vm.repo.history() }
            trash = withContext(Dispatchers.IO) { vm.repo.entries(true).filter { it.deleted } }
        }
    LaunchedEffect(Unit) { reload() }
    val integrity = remember(vm.entries) { OperationsCheck.integrity(vm.entries) }
    val compliance = remember(vm.entries) { OperationsCheck.compliance(vm.entries) }
    val conflicts = remember(vm.entries) { OperationsCheck.dutyConflicts(vm.entries) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Übersicht", "Protokoll", "Papierkorb (${trash.size})").forEachIndexed { i, t -> FilterChip(tab == i, { tab = i }, label = { Text(t) }) }
            TextButton(onClick = {
                vm.run {
                    val result = withContext(Dispatchers.IO) { vm.repo.integrity() }
                    vm.notice = "Datenbankprüfung: $result"
                }
            }) { Text("Datenbank prüfen") }
        }
        LazyColumn(contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                0 -> {
                    item { SectionHeader("Integrität") }
                    if (integrity.isEmpty()) item { Text("✓ Keine Integritätsprobleme gefunden", color = Color(0xFF34C759)) }
                    items(integrity) { OperationsIssueRow(vm, it) }
                    item { SectionHeader("Personal und Dokumente (${compliance.size})") }
                    if (compliance.isEmpty()) item { Text("✓ Keine offenen Hinweise", color = Color(0xFF34C759)) }
                    items(compliance.take(100)) { OperationsIssueRow(vm, it) }
                    item { SectionHeader("Planung (${conflicts.size})") }
                    if (conflicts.isEmpty()) item { Text("✓ Keine Konflikte im laufenden und nächsten Monat", color = Color(0xFF34C759)) }
                    items(conflicts.take(100)) { c ->
                        Text("${DateText.german(c.date)} · ${c.workerName} · ${c.message}", color = if (c.critical) MaterialTheme.colorScheme.error else Color.Unspecified, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                1 -> {
                    item { OutlinedTextField(search, { search = it }, placeholder = { Text("Protokoll durchsuchen") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    items(history.filter { search.isBlank() || it.contains(search, true) }) {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
                else -> {
                    if (trash.isEmpty()) item { EmptyState("Papierkorb ist leer", "Gelöschte Einträge erscheinen hier und können wiederhergestellt werden.") }
                    items(trash.sortedBy { it.kind.ordinal }) { e ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title)
                                    Text(e.kind.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(
                                    onClick = {
                                        vm.run {
                                            withContext(Dispatchers.IO) { vm.repo.restoreEntry(e) }
                                            vm.refresh()
                                            trash = withContext(Dispatchers.IO) { vm.repo.entries(true).filter { it.deleted } }
                                        }
                                    },
                                    enabled = vm.can(AccessAction.EDIT, "operations"),
                                ) { Text("Wiederherstellen") }
                                if (vm.can(AccessAction.DELETE, "operations")) TextButton(onClick = { purge = e }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
    }
    purge?.let { e ->
        AlertDialog(
            onDismissRequest = { purge = null },
            title = { Text("Endgültig löschen?") },
            text = { Text("„${e.title}“ und eine zugehörige Datei werden unwiderruflich gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.run {
                        withContext(Dispatchers.IO) { vm.repo.purge(e) }
                        purge = null
                        reload()
                    }
                }) { Text("Endgültig löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { purge = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun OperationsIssueRow(vm: UGSViewModel, issue: OperationsCheck.Issue) {
    Card(onClick = { vm.navigation = issue.page }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(issue.title, fontWeight = FontWeight.SemiBold, color = if (issue.critical) MaterialTheme.colorScheme.error else Color.Unspecified)
            Text(issue.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun BackupScreen(vm: UGSViewModel) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var incoming by remember { mutableStateOf<ByteArray?>(null) }
    var replace by remember { mutableStateOf(false) }
    val save =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            if (uri != null)
                vm.run {
                    val bytes = pending ?: error("Sicherungsdaten fehlen.")
                    withContext(Dispatchers.IO) {
                        vm.app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                            ?: error("Sicherung konnte nicht geschrieben werden.")
                    }
                    pending = null
                    password = ""
                    confirmPassword = ""
                    vm.notice = "Verschlüsselte Fachdaten-Sicherung gespeichert."
                }
        }
    val load =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                vm.run {
                    incoming =
                        withContext(Dispatchers.IO) {
                            ImportService.read(vm.app, uri, 140 * 1024 * 1024)
                        }
                    replace = true
                }
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Verschlüsselte Datensicherung", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Sichert Personal, Planung, Dokumente, Entwürfe und Firmeneinstellungen. Benutzerkonten und Protokoll bleiben bei einer Wiederherstellung auf dem Zielgerät erhalten. Diese Sicherung ist für die Android-App bestimmt."
        )
        PasswordInput(password, { password = it }, "Sicherungspasswort (mindestens 12 Zeichen)")
        PasswordInput(
            confirmPassword,
            { confirmPassword = it },
            "Passwort wiederholen (nur neue Sicherung)",
        )
        Button(
            onClick = {
                vm.run {
                    require(password == confirmPassword) { "Passwörter stimmen nicht überein." }
                    pending = withContext(Dispatchers.IO) { vm.repo.backup(password) }
                    save.launch("UGS-Android-$today.ugsbackup")
                }
            }
        ) {
            Text("Sicherung erstellen")
        }
        OutlinedButton(
            onClick = {
                if (password.isBlank()) vm.error = "Sicherungspasswort eingeben."
                else load.launch(arrayOf("*/*"))
            }
        ) {
            Text("Sicherung wiederherstellen")
        }
        Text(
            "Bewahre das Sicherungspasswort getrennt auf. Ohne dieses Passwort ist keine Entschlüsselung möglich.",
            style = MaterialTheme.typography.bodySmall,
        )
        HorizontalDivider()
        PortableArchiveImport(vm)
    }
    if (replace)
        AlertDialog(
            onDismissRequest = {
                replace = false
                incoming = null
            },
            title = { Text("Fachdaten ersetzen?") },
            text = {
                Text(
                    "Die vorhandenen Fachdaten werden durch die Sicherung ersetzt. Erstelle vorher eine aktuelle Sicherung, wenn du den bisherigen Stand behalten möchtest. Benutzerkonten bleiben erhalten."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.run {
                            withContext(Dispatchers.IO) {
                                vm.repo.restoreBackup(incoming ?: error("Datei fehlt."), password)
                            }
                            vm.refresh()
                            incoming = null
                            replace = false
                            password = ""
                            vm.notice = "Sicherung wiederhergestellt."
                        }
                    }
                ) {
                    Text("Wiederherstellen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        replace = false
                        incoming = null
                    }
                ) {
                    Text("Abbrechen")
                }
            },
        )
}

/** Portables Archiv (".ugsarchive") vom Mac oder iPhone übernehmen – ergänzt, ersetzt nichts. */
@Composable
fun PortableArchiveImport(vm: UGSViewModel) {
    var uri by remember { mutableStateOf<android.net.Uri?>(null) }
    var info by remember { mutableStateOf<PortableArchive.Info?>(null) }
    var password by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PortableImport.Result?>(null) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
            if (picked != null)
                vm.run {
                    info = withContext(Dispatchers.IO) { PortableImport.info(vm.app.contentResolver.openInputStream(picked) ?: error("Datei kann nicht gelesen werden.")) }
                    uri = picked
                    result = null
                }
        }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Archiv vom Mac oder iPhone übernehmen", style = MaterialTheme.typography.titleMedium)
        Text(
            "Portable Archive („.ugsarchive“) aus UGS für Mac oder iPhone: Mitarbeiter, Dokumente, Fotos, Abwesenheiten, Zeiten, Gehalt, Statusmeldungen, Urlaubskonten, Dienstpläne, To Do und Firma-Ausgaben werden ergänzt. Vorhandene Daten bleiben erhalten; Mitarbeiter mit gleicher Bewacher-ID werden zusammengeführt. Benutzerkonten und Posteingang werden nicht übernommen.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = vm.can(AccessAction.EDIT, "backup")) { Text(if (uri == null) "Archiv auswählen" else "Anderes Archiv auswählen") }
        info?.let { i ->
            Text("Archiv von ${i.platform.ifBlank { "unbekannt" }} · erstellt ${i.createdAt.replace("T", " ").take(16)}${if (i.appVersion.isNotBlank()) " · Version ${i.appVersion}" else ""}", style = MaterialTheme.typography.bodySmall)
            PasswordInput(password, { password = it }, "Backup-Passwort des Archivs")
            Button(
                onClick = {
                    val source = uri ?: return@Button
                    vm.run {
                        val r = withContext(Dispatchers.IO) { PortableImport.run(vm.app, vm, { vm.app.contentResolver.openInputStream(source) ?: error("Datei kann nicht gelesen werden.") }, password) }
                        vm.refresh()
                        Photos.invalidate()
                        password = ""
                        result = r
                        vm.notice = r.summary
                    }
                },
                enabled = password.length >= PortableArchive.MIN_PASSWORD && !vm.busy,
            ) { Text("Archiv übernehmen") }
        }
        result?.let { r ->
            Text(r.summary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            r.skipped.take(20).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (r.skipped.size > 20) Text("… und ${r.skipped.size - 20} weitere.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MailScreen(vm: UGSViewModel) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Dokumente per E-Mail teilen", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Öffne einen PDF-Export und tippe auf Teilen. Wähle deine installierte E-Mail-App, prüfe Empfänger und Nachricht und sende die E-Mail dort ab."
        )
        Text(
            "Posteingang und Versandstatus werden von deiner E-Mail-App verwaltet. UGS protokolliert den Export; daraus wird keine Zustellung abgeleitet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Es werden keine E-Mail-Zugangsdaten in UGS benötigt.",
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
