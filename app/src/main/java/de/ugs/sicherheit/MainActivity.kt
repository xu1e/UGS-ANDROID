@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.ugs.sicherheit

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private lateinit var vm: UGSViewModel
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        vm =
            ViewModelProvider(
                this,
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(c: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return UGSViewModel(application as UGSApplication) as T
                    }
                },
            )[UGSViewModel::class.java]
        setContent { UGSApp(vm) }
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        if (::vm.isInitialized && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 300000)
            vm.lock()
    }
}

class UGSViewModel(val app: UGSApplication) : ViewModel() {
    val repo
        get() = app.repository

    val pdf = PdfService(app)
    var setup by mutableStateOf<Boolean?>(null)
        private set

    var user by mutableStateOf<Account?>(null)
        private set

    var entries by mutableStateOf(emptyList<Entry>())
        private set

    var company by mutableStateOf(mapOf("name" to "UGS Sicherheit GmbH"))
        private set

    var lookups by mutableStateOf(Lookups.defaults)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var preview by mutableStateOf<File?>(null)

    /** Seitenwechsel aus anderen Bereichen (Suche, Sprachassistent, Posteingang). */
    var navigation by mutableStateOf<String?>(null)
    /** Übergabe eines Anhangs aus dem Posteingang an „Vertrag stempeln“. */
    var stampHandoff by mutableStateOf<StampHandoff?>(null)
    /** Posteingang: laufender Abruf, Änderungszähler für Listen, letzter Fehler. */
    var inboxBusy by mutableStateOf(false)
    var inboxRevision by mutableStateOf(0)
    var inboxError by mutableStateOf("")

    init {
        run { setup = withContext(Dispatchers.IO) { repo.needsSetup() } }
    }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Vorgang fehlgeschlagen."
            } finally {
                busy = false
            }
        }
    }

    /** Hintergrundarbeit ohne Sperrbildschirm (Posteingang, Sprachassistent). */
    fun launch(action: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Vorgang fehlgeschlagen."
            }
        }

    suspend fun refresh() {
        val e = withContext(Dispatchers.IO) { repo.entries() }
        val s = withContext(Dispatchers.IO) { repo.settings() }
        entries = e
        company = mapOf("name" to "UGS Sicherheit GmbH") + s
        lookups = Lookups.all(s)
    }

    fun auth(name: String, password: String, confirm: String) {
        run {
            if (setup == true) require(password == confirm) { "Passwörter stimmen nicht überein." }
            user =
                withContext(Dispatchers.IO) {
                    if (setup == true) repo.setup(name, password) else repo.login(name, password)
                }
            setup = false
            refresh()
        }
    }

    fun lock() {
        user = null
        entries = emptyList()
        company = mapOf("name" to "UGS Sicherheit GmbH")
        preview = null
        stampHandoff = null
        repo.logout()
        Voice.stop()
    }

    fun save(e: Entry, done: () -> Unit) {
        run {
            withContext(Dispatchers.IO) { repo.save(e) }
            refresh()
            done()
        }
    }

    fun delete(e: Entry, done: () -> Unit) {
        run {
            withContext(Dispatchers.IO) { repo.delete(e) }
            refresh()
            done()
        }
    }

    /**
     * Erzeugt ein PDF, protokolliert den Export und zeigt es an. Mit [archiveTo] wird es zuvor
     * in der Personalakte dieses Mitarbeiters abgelegt; ein Fehler dabei bricht ab, statt
     * einen Erfolg vorzutäuschen.
     */
    suspend fun showPdf(
        title: String,
        archiveTo: String? = null,
        category: String = "",
        create: () -> ByteArray,
    ) {
        val f =
            withContext(Dispatchers.IO) {
                val bytes = create()
                if (archiveTo != null) repo.archive(archiveTo, category, title, bytes)
                repo.exported(title)
                pdf.export(bytes, "$title.pdf")
            }
        if (archiveTo != null) refresh()
        preview = f
    }

    fun rows(kind: Kind) = entries.filter { it.kind == kind }.sortedBy { it.title.lowercase() }

    fun worker(id: String) = entries.find { it.id == id && it.kind == Kind.WORKER }

    fun status(w: Entry) = Workers.currentStatus(w, entries)

    fun can(action: AccessAction, page: String) = AccessControl.allows(user, action, page)

    fun permitted(page: String) = can(AccessAction.VIEW, page)

    fun canCreate(kind: Kind) = can(AccessAction.CREATE, AccessControl.page(kind))

    fun canEdit(kind: Kind) = can(AccessAction.EDIT, AccessControl.page(kind))

    fun canDelete(kind: Kind) = can(AccessAction.DELETE, AccessControl.page(kind))

    fun canExport(page: String) = can(AccessAction.EXPORT, page)

    /** Auswahlwerte einer Liste; Dokumentkategorien ergänzen eigene Kategorien. */
    fun options(group: String): List<String> =
        if (group == "expense_categories")
            (expenseCategories + rows(Kind.EXPENSE).map { it["category"] }.filter { it.isNotBlank() }).distinct()
        else if (group == "document_categories")
            DocumentCategories.options(
                lookups["document_types"].orEmpty() + rows(Kind.DOCUMENT).map { it["category"] }
            )
        else lookups[group].orEmpty()

    val admin
        get() = user?.role == "Administrator"
}

data class Destination(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val page: String,
    val section: String,
    val kind: Kind? = null,
)

val destinations =
    listOf(
        Destination("dashboard", "Übersicht", Icons.Default.Dashboard, "dashboard", ""),
        Destination("workers", "Mitarbeiter", Icons.Default.People, "workers", "Personal", Kind.WORKER),
        Destination("import", "Import", Icons.Default.UploadFile, "import", "Personal"),
        Destination("ocr", "Ausweis einlesen", Icons.Default.DocumentScanner, "workers", "Personal"),
        Destination("status", "Anmeldung", Icons.Default.Badge, "registrations", "Personal", Kind.STATUS),
        Destination("sofortmeldung", "Sofortmeldung", Icons.Default.Email, "sofortmeldung", "Personal"),
        Destination("registrations", "Meldungen", Icons.Default.Approval, "registrations", "Personal", Kind.REGISTRATION),
        Destination("absences", "Abwesenheiten", Icons.Default.EventBusy, "absences", "Personal", Kind.ABSENCE),
        Destination("vacation", "Urlaubskonto", Icons.Default.BeachAccess, "absences", "Personal"),
        Destination("documents", "Dokumente", Icons.Default.Folder, "documents", "Personal", Kind.DOCUMENT),
        Destination("contract", "Arbeitsvertrag", Icons.Default.Description, "contract", "Verträge und Dokumente"),
        Destination("stamp", "Vertrag stempeln", UgsIcons.Signature, "contract", "Verträge und Dokumente"),
        Destination("agreement", "Aufhebungsvertrag", Icons.Default.Assignment, "contract", "Verträge und Dokumente"),
        Destination("exports", "Exportzentrum", Icons.Default.PictureAsPdf, "exports", "Verträge und Dokumente"),
        Destination("penalties", "Strafe", UgsIcons.Euro, "exports", "Verträge und Dokumente"),
        Destination("cooperation", "Kooperationsverträge", UgsIcons.Handshake, "exports", "Verträge und Dokumente"),
        Destination("offers", "Angebote", UgsIcons.Receipt, "exports", "Verträge und Dokumente"),
        Destination("inbox", "Posteingang", Icons.Default.Inbox, "inbox", "Kommunikation"),
        Destination("sentMail", "Gesendete E-Mails", Icons.Default.Send, "sent_mail", "Kommunikation"),
        Destination("duty", "Dienstplan", Icons.Default.CalendarMonth, "duty", "Planung"),
        Destination("shifts", "Dienste", Icons.Default.DateRange, "duty", "Planung", Kind.SHIFT),
        Destination("sites", "Objekte", Icons.Default.Business, "duty", "Planung", Kind.SITE),
        Destination("times", "Zeiten", Icons.Default.Schedule, "time_entries", "Planung", Kind.TIME),
        Destination("todos", "To Do", Icons.Default.Checklist, "todo", "Planung"),
        Destination("expenses", "Firma Ausgaben", UgsIcons.Receipt, "expenses", "Finanzen"),
        Destination("payroll", "Gehalt", Icons.Default.Payments, "payrolls", "Finanzen", Kind.PAYROLL),
        Destination("reports", "Berichte", Icons.Default.BarChart, "reports", "Finanzen"),
        Destination("control", "Kontrollzentrum", Icons.Default.Security, "operations", "Verwaltung"),
        Destination("settings", "Einstellungen", Icons.Default.Settings, "settings", "Verwaltung"),
        Destination("users", "Benutzerverwaltung", Icons.Default.ManageAccounts, "users", "Verwaltung"),
        Destination("backup", "Datensicherung", Icons.Default.Backup, "backup", "Verwaltung"),
    )

@Composable
fun UGSApp(vm: UGSViewModel) {
    var theme by remember { mutableStateOf("System") }
    LaunchedEffect(vm.company["theme"]) { theme = vm.company["theme"] ?: "System" }
    val dark = theme == "Dunkel" || theme == "System" && isSystemInDarkTheme()
    val blue = Color(0xFF008BFF)
    val scheme =
        if (dark)
            darkColorScheme(
                primary = Color(0xFF65B8FF),
                background = Color(0xFF071320),
                surface = Color(0xFF0E1D2C),
                secondaryContainer = Color(0xFF153B60),
            )
        else
            lightColorScheme(
                primary = Color(0xFF006BD6),
                background = Color(0xFFF3F6FA),
                surface = Color.White,
                secondaryContainer = Color(0xFFDCEEFF),
            )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) {
            if (vm.setup == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (vm.user == null) LoginScreen(vm) else key(vm.user!!.id) { Workspace(vm) }
            if (vm.busy)
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.Black.copy(alpha = .12f))
                        .clickable(enabled = true, onClick = {}),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(20.dp)) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    }
                }
            vm.error?.let {
                AlertDialog(
                    onDismissRequest = { vm.error = null },
                    title = { Text("Bitte prüfen") },
                    text = { Text(it) },
                    confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
                )
            }
            vm.notice?.let {
                AlertDialog(
                    onDismissRequest = { vm.notice = null },
                    title = { Text("UGS Sicherheit") },
                    text = { Text(it) },
                    confirmButton = { TextButton(onClick = { vm.notice = null }) { Text("OK") } },
                )
            }
            vm.preview?.let { PdfPreview(it, vm) }
        }
    }
}

@Composable
fun LoginScreen(vm: UGSViewModel) {
    var name by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 440.dp).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painterResource(
                    if (MaterialTheme.colorScheme.background.luminance() < .5f)
                        R.drawable.ugs_logo_dark
                    else R.drawable.ugs_logo_light
                ),
                "UGS Sicherheit",
                Modifier.size(145.dp).align(Alignment.CenterHorizontally),
            )
            Text(
                if (vm.setup == true) "Willkommen bei UGS" else "Anmeldung",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (vm.setup == true)
                    "Lege deinen Administrator an. Benutzername und Passwort bestimmst du selbst."
                else "Melde dich mit dem auf diesem Gerät eingerichteten Konto an.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Benutzername") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordInput(pass, { pass = it }, "Passwort")
            if (vm.setup == true) {
                PasswordInput(confirm, { confirm = it }, "Passwort wiederholen")
                Text(
                    "Mindestens 12 Zeichen. Bewahre deine Zugangsdaten sicher auf.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { vm.auth(name, pass, confirm) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (vm.setup == true) "Administrator anlegen" else "Anmelden")
            }
            Text(
                "Native Android-App · Daten bleiben auf diesem Gerät",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun Workspace(vm: UGSViewModel) {
    var selected by rememberSaveable { mutableStateOf("dashboard") }
    var expanded by rememberSaveable { mutableStateOf(true) }
    var drawer by remember { mutableStateOf(false) }
    var profile by remember { mutableStateOf(false) }
    LaunchedEffect(vm.navigation) {
        vm.navigation?.let {
            if (destinations.any { d -> d.id == it }) selected = it
            vm.navigation = null
        }
    }
    val visible = destinations.filter { vm.permitted(it.page) }
    val destination = visible.firstOrNull { it.id == selected } ?: visible.first()
    InboxPoller(vm)
    if (profile) ProfileDialog(vm) { profile = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 800.dp
        Row(Modifier.fillMaxSize()) {
            if (wide)
                Surface(
                    Modifier.width(if (expanded) 248.dp else 76.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(Modifier.systemBarsPadding()) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painterResource(
                                    if (MaterialTheme.colorScheme.background.luminance() < .5f)
                                        R.drawable.ugs_logo_dark
                                    else R.drawable.ugs_logo_light
                                ),
                                "UGS",
                                Modifier.size(46.dp),
                            )
                            if (expanded)
                                Text(
                                    "UGS Sicherheit",
                                    Modifier.padding(start = 8.dp),
                                    fontWeight = FontWeight.Bold,
                                )
                        }
                        LazyColumn(Modifier.weight(1f)) {
                            visible.groupBy { it.section }.forEach { (section, items) ->
                                if (section.isNotEmpty() && expanded)
                                    item(key = "h-$section") {
                                        Text(
                                            section,
                                            Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                items(items, key = { it.id }) { d ->
                                    NavigationDrawerItem(
                                        label = { if (expanded) Text(d.title) },
                                        selected = d.id == destination.id,
                                        onClick = { selected = d.id },
                                        icon = { Icon(d.icon, d.title) },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Icon(
                                if (expanded) Icons.AutoMirrored.Filled.KeyboardArrowLeft
                                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                "Seitenleiste umschalten",
                            )
                        }
                    }
                }
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    TopAppBar(
                        title = { Text(destination.title, maxLines = 1) },
                        navigationIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!wide)
                                    IconButton(onClick = { drawer = true }) {
                                        Icon(Icons.Default.Menu, "Navigation")
                                    }
                                PrayerChip(vm, compact = !wide)
                            }
                        },
                        actions = {
                            VoiceButton(vm)
                            IconButton(onClick = { profile = true }) {
                                UserAvatar(vm, 30.dp)
                            }
                            IconButton(onClick = { vm.lock() }) {
                                Icon(Icons.Default.Lock, "Sperren")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (destination.id) {
                        "dashboard" -> Dashboard(vm) { selected = it }
                        "workers" -> WorkersScreen(vm)
                        "sites",
                        "shifts",
                        "times",
                        "absences",
                        "payroll",
                        "registrations",
                        "status" -> RecordsScreen(vm, destination.kind!!)
                        "documents" -> DocumentsScreen(vm)
                        "sofortmeldung" -> SofortmeldungScreen(vm)
                        "contract" -> ContractScreen(vm, "contract")
                        "agreement" -> ContractScreen(vm, "agreement")
                        "exports" -> ExportsScreen(vm)
                        "stamp" -> StampScreen(vm)
                        "penalties" -> PenaltyScreen(vm)
                        "cooperation" -> BusinessScreen(vm, BusinessKind.COOPERATION)
                        "offers" -> BusinessScreen(vm, BusinessKind.OFFER)
                        "inbox" -> InboxScreen(vm)
                        "sentMail" -> SentMailScreen(vm)
                        "duty" -> DutyPlanScreen(vm)
                        "todos" -> TodoScreen(vm)
                        "expenses" -> ExpensesScreen(vm)
                        "import" -> ImportScreen(vm)
                        "ocr" -> OcrScreen(vm)
                        "vacation" -> VacationScreen(vm)
                        "reports" -> ReportsScreen(vm)
                        "settings" -> SettingsScreen(vm)
                        "users" -> UsersScreen(vm)
                        "control" -> ControlScreen(vm)
                        "backup" -> BackupScreen(vm)
                    }
                    VoiceAnswerCard(vm, Modifier.align(Alignment.BottomEnd))
                }
            }
        }
        if (drawer && !wide)
            ModalBottomSheet(onDismissRequest = { drawer = false }) {
                LazyColumn {
                    visible.groupBy { it.section }.forEach { (section, items) ->
                        if (section.isNotEmpty())
                            item(key = "h-$section") {
                                Text(
                                    section,
                                    Modifier.padding(start = 16.dp, top = 12.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        items(items, key = { it.id }) { d ->
                            ListItem(
                                headlineContent = { Text(d.title) },
                                leadingContent = { Icon(d.icon, null) },
                                modifier =
                                    Modifier.clickable {
                                        selected = d.id
                                        drawer = false
                                    },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
    }
}

@Composable
fun Dashboard(vm: UGSViewModel, navigate: (String) -> Unit) {
    val active = vm.rows(Kind.WORKER).count { it["status"] != "Inaktiv" }
    val shifts = vm.rows(Kind.SHIFT).count { it["date"] == today && it["status"] != "Abgesagt" }
    val tasks = vm.rows(Kind.TODO).count { it["status"] != "Erledigt" }
    val expiring =
        vm.rows(Kind.DOCUMENT).filter {
            it["expiryDate"].isNotBlank() &&
                Rules.date(it["expiryDate"]) <= LocalDate.now().plusDays(30)
        }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Guten Tag, ${vm.user?.username}",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(Rules.german(today), Modifier.padding(top = 8.dp))
                        Text("Dein Team. Deine Übersicht.", Modifier.padding(top = 18.dp))
                    }
                    Image(
                        painterResource(
                            if (MaterialTheme.colorScheme.background.luminance() < .5f)
                                R.drawable.ugs_logo_dark
                            else R.drawable.ugs_logo_light
                        ),
                        "UGS",
                        Modifier.size(110.dp),
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("Mitarbeiter", active.toString(), Modifier.weight(1f)) {
                    navigate("workers")
                }
                Metric("Dienste heute", shifts.toString(), Modifier.weight(1f)) { navigate("duty") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("Offene Aufgaben", tasks.toString(), Modifier.weight(1f)) {
                    navigate("tasks")
                }
                Metric("Dokumentfristen", expiring.size.toString(), Modifier.weight(1f)) {
                    navigate("documents")
                }
            }
        }
        item {
            Text(
                "Schnellzugriff",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { navigate("contract") }, modifier = Modifier.weight(1f)) {
                    Text("Arbeitsvertrag")
                }
                OutlinedButton(onClick = { navigate("exports") }, modifier = Modifier.weight(1f)) {
                    Text("PDF-Exporte")
                }
            }
        }
        item {
            Text(
                "Als Nächstes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        val upcoming =
            vm.rows(Kind.SHIFT)
                .filter { it["date"] >= today && it["status"] != "Abgesagt" }
                .sortedBy { it["date"] + it["startTime"] }
                .take(5)
        if (upcoming.isEmpty())
            item {
                EmptyState("Noch keine Dienste geplant", "Lege zuerst Mitarbeiter und Objekte an.")
            }
        items(upcoming) { e -> RecordCard(e, vm) { navigate("duty") } }
        if (expiring.isNotEmpty()) {
            item { Text("Nachweise prüfen", style = MaterialTheme.typography.titleMedium) }
            items(expiring.take(6)) { e -> RecordCard(e, vm) { navigate("documents") } }
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(18.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Inbox,
            null,
            Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(title, Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
        Text(
            body,
            Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun RecordCard(e: Entry, vm: UGSViewModel, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (e.kind == Kind.WORKER || e["workerId"].isBlank()) e.title
                    else vm.entries.find { it.id == e["workerId"] }?.title ?: e.title,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle =
                    if (e.kind == Kind.WORKER)
                        listOf(e["personnelNumber"], e["employmentType"], e["city"])
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    else
                        listOf(
                                e["date"]
                                    .takeIf { it.isNotBlank() }
                                    ?.let { Rules.german(it) }
                                    .orEmpty(),
                                e["startTime"].let {
                                    if (it.isBlank()) "" else "$it–${e["endTime"]}"
                                },
                                if (e.kind == Kind.STATUS) statusKinds[e["type"]].orEmpty() else e["type"],
                                e["category"],
                                if (e.kind == Kind.EXPENSE) money(Rules.number(e["amount"].ifBlank { "0" })) else "",
                                vm.entries.find { it.id == e["siteId"] }?.title.orEmpty(),
                            )
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (e["status"].isNotBlank())
                    Text(
                        e["status"],
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
