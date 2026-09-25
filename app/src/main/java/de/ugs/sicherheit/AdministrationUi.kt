package de.ugs.sicherheit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun VacationScreen(vm: UGSViewModel) {
    var year by remember { mutableIntStateOf(LocalDate.now().year) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { year-- }) { Text("‹") }
                Text(
                    "Urlaubskonto $year",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = { year++ }) { Text("›") }
            }
        }
        item {
            Text(
                "Planungsübersicht: genehmigter Urlaub, Montag–Freitag. Feiertage, abweichende Arbeitswochen, Teiljahre und Überträge sind hier nicht automatisch berücksichtigt.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(vm.rows(Kind.WORKER)) { w ->
            val from = LocalDate.of(year, 1, 1)
            val to = LocalDate.of(year, 12, 31)
            val days = mutableSetOf<LocalDate>()
            vm.rows(Kind.ABSENCE)
                .filter {
                    it["workerId"] == w.id && it["type"] == "Urlaub" && it["status"] == "Genehmigt"
                }
                .forEach { e ->
                    val a = maxOf(Rules.date(e["date"]), from)
                    val b = minOf(Rules.date(e["endDate"]), to)
                    if (a <= b)
                        generateSequence(a) { it.plusDays(1) }
                            .takeWhile { it <= b }
                            .filter { it.dayOfWeek.value <= 5 }
                            .forEach { days += it }
                }
            val allowance = w["vacationDays"].toIntOrNull() ?: 0
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(w.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Anspruch laut Personalakte: $allowance Tage\nGenehmigte Werktage: ${days.size}\nRechnerische Differenz: ${allowance-days.size} Tage"
                    )
                }
            }
        }
    }
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
    val values =
        remember(vm.company) { mutableStateMapOf<String, String>().apply { putAll(vm.company) } }
    var password by remember { mutableStateOf(false) }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Firma & Erscheinungsbild", style = MaterialTheme.typography.headlineSmall) }
        items(companyFields) { f ->
            FieldInput(f, values[f.key].orEmpty(), vm) { values[f.key] = it }
        }
        item {
            ChoiceInput(
                "Darstellung",
                values["theme"] ?: "System",
                listOf("System", "Hell", "Dunkel").map { it to it },
            ) {
                values["theme"] = it
            }
        }
        item {
            ChoiceInput(
                "App-Symbol",
                values["appIcon"] ?: "Dunkel",
                listOf("Dunkel", "Hell").map { it to it },
            ) {
                values["appIcon"] = it
            }
        }
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
                            vm.repo.settings(values.toMap())
                            val manager = vm.app.packageManager
                            val light = values["appIcon"] == "Hell"
                            val enable =
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            val disable =
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            val selected = if (light) "LauncherLight" else "LauncherDark"
                            val other = if (light) "LauncherDark" else "LauncherLight"
                            manager.setComponentEnabledSetting(
                                android.content.ComponentName(
                                    vm.app,
                                    "de.ugs.sicherheit.$selected",
                                ),
                                enable,
                                android.content.pm.PackageManager.DONT_KILL_APP,
                            )
                            manager.setComponentEnabledSetting(
                                android.content.ComponentName(vm.app, "de.ugs.sicherheit.$other"),
                                disable,
                                android.content.pm.PackageManager.DONT_KILL_APP,
                            )
                        }
                        vm.refresh()
                        vm.notice = "Einstellungen gespeichert."
                    }
                }
            ) {
                Text("Einstellungen speichern")
            }
        }
        item { OutlinedButton(onClick = { password = true }) { Text("Eigenes Passwort ändern") } }
        item {
            Text(
                "UGS Sicherheit Android 1.0.0\nNative Kotlin / Jetpack Compose · Android 8 oder neuer\nLokale Datenbank und Dokumente verschlüsselt. Kein automatischer Abgleich mit Mac oder iPhone.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
    var remove by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(Unit) { vm.run { users = withContext(Dispatchers.IO) { vm.repo.users() } } }
    LazyColumn(
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Zugriff verwalten", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Administrator: alle Bereiche. Personal: Fachdaten bearbeiten. Lesen: Fachdaten ansehen und exportieren."
            )
        }
        item { Button(onClick = { add = true }) { Text("Benutzer anlegen") } }
        items(users) { u ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.username, style = MaterialTheme.typography.titleMedium)
                        Text(u.role)
                    }
                    if (u.id != vm.user?.id)
                        TextButton(onClick = { remove = u }) { Text("Entfernen") }
                }
            }
        }
    }
    if (add)
        FormDialog(
            "Benutzer anlegen",
            listOf(
                field("username", "Benutzername", true),
                Field("password", "Passwort (mindestens 12 Zeichen)", Input.PASSWORD, true),
                choice("role", "Rolle", "Personal", "Lesen", "Administrator"),
            ),
            emptyMap(),
            vm,
            { add = false },
        ) { v ->
            vm.run {
                withContext(Dispatchers.IO) {
                    vm.repo.addUser(
                        v["username"].orEmpty(),
                        v["password"].orEmpty(),
                        v["role"].orEmpty(),
                    )
                }
                users = withContext(Dispatchers.IO) { vm.repo.users() }
                add = false
            }
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
fun ControlScreen(vm: UGSViewModel) {
    var history by remember { mutableStateOf(emptyList<String>()) }
    var trash by remember { mutableStateOf(emptyList<Entry>()) }
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        vm.run {
            history = withContext(Dispatchers.IO) { vm.repo.history() }
            trash = withContext(Dispatchers.IO) { vm.repo.entries(true).filter { it.deleted } }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text("Protokoll") })
            Spacer(Modifier.width(8.dp))
            FilterChip(tab == 1, { tab = 1 }, label = { Text("Papierkorb") })
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    vm.run {
                        val result = withContext(Dispatchers.IO) { vm.repo.integrity() }
                        vm.notice = "Datenbankprüfung: $result"
                    }
                }
            ) {
                Text("Prüfen")
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tab == 0)
                items(history) {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
            else
                items(trash) { e ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(e.title, Modifier.weight(1f))
                            TextButton(
                                onClick = {
                                    vm.run {
                                        withContext(Dispatchers.IO) { vm.repo.restoreEntry(e) }
                                        vm.refresh()
                                        trash =
                                            withContext(Dispatchers.IO) {
                                                vm.repo.entries(true).filter { it.deleted }
                                            }
                                    }
                                }
                            ) {
                                Text("Wiederherstellen")
                            }
                        }
                    }
                }
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
