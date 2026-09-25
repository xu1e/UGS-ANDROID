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

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var preview by mutableStateOf<File?>(null)

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

    suspend fun refresh() {
        val e = withContext(Dispatchers.IO) { repo.entries() }
        val s = withContext(Dispatchers.IO) { repo.settings() }
        entries = e
        company = mapOf("name" to "UGS Sicherheit GmbH") + s
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
        repo.logout()
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

    suspend fun showPdf(title: String, create: () -> ByteArray) {
        val f =
            withContext(Dispatchers.IO) {
                val bytes = create()
                repo.exported(title)
                pdf.export(bytes, "$title.pdf")
            }
        preview = f
    }

    fun rows(kind: Kind) = entries.filter { it.kind == kind }.sortedBy { it.title.lowercase() }

    val canEdit
        get() = user?.role != "Lesen"

    val admin
        get() = user?.role == "Administrator"
}

data class Destination(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val kind: Kind? = null,
)

val destinations =
    listOf(
        Destination("dashboard", "Übersicht", Icons.Default.Dashboard),
        Destination("workers", "Mitarbeiter", Icons.Default.People, Kind.WORKER),
        Destination("import", "Personalimport", Icons.Default.UploadFile),
        Destination("ocr", "Ausweis einlesen", Icons.Default.DocumentScanner),
        Destination("sites", "Objekte", Icons.Default.Business, Kind.SITE),
        Destination("duty", "Dienstplan", Icons.Default.CalendarMonth, Kind.SHIFT),
        Destination("times", "Zeiten", Icons.Default.Schedule, Kind.TIME),
        Destination("absences", "Abwesenheiten", Icons.Default.EventBusy, Kind.ABSENCE),
        Destination("vacation", "Urlaubskonto", Icons.Default.BeachAccess),
        Destination("documents", "Dokumente", Icons.Default.Folder, Kind.DOCUMENT),
        Destination("contract", "Arbeitsvertrag", Icons.Default.Description),
        Destination("stamp", "Vertrag stempeln", Icons.Default.Approval),
        Destination("agreement", "Aufhebungsvertrag", Icons.Default.Assignment),
        Destination("exports", "Exportzentrum", Icons.Default.PictureAsPdf),
        Destination("registrations", "Anmeldungen", Icons.Default.Badge, Kind.REGISTRATION),
        Destination("payroll", "Gehalt", Icons.Default.Payments, Kind.PAYROLL),
        Destination("tasks", "Aufgaben", Icons.Default.Checklist, Kind.TODO),
        Destination("reports", "Berichte", Icons.Default.BarChart),
        Destination("mail", "E-Mail", Icons.Default.Email),
        Destination("control", "Kontrollzentrum", Icons.Default.Security),
        Destination("settings", "Einstellungen", Icons.Default.Settings),
        Destination("users", "Benutzerverwaltung", Icons.Default.ManageAccounts),
        Destination("backup", "Datensicherung", Icons.Default.Backup),
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
    var selected by remember { mutableStateOf("dashboard") }
    var expanded by remember { mutableStateOf(true) }
    var drawer by remember { mutableStateOf(false) }
    var accountPassword by remember { mutableStateOf(false) }
    if (accountPassword)
        FormDialog(
            "Passwort ändern",
            listOf(
                Field("old", "Bisheriges Passwort", Input.PASSWORD, true),
                Field("new", "Neues Passwort", Input.PASSWORD, true),
                Field("confirm", "Wiederholen", Input.PASSWORD, true),
            ),
            emptyMap(),
            vm,
            { accountPassword = false },
        ) { v ->
            vm.run {
                require(v["new"] == v["confirm"]) { "Passwörter stimmen nicht überein." }
                withContext(Dispatchers.IO) {
                    vm.repo.password(v["old"].orEmpty(), v["new"].orEmpty())
                }
                accountPassword = false
                vm.notice = "Passwort geändert."
            }
        }
    val destination = destinations.first { it.id == selected }
    val visible =
        destinations.filter {
            vm.admin || it.id !in listOf("users", "control", "backup", "settings")
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 800.dp
        Row(Modifier.fillMaxSize()) {
            if (wide)
                Surface(
                    Modifier.width(if (expanded) 238.dp else 76.dp).fillMaxHeight(),
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
                            items(visible) { d ->
                                NavigationDrawerItem(
                                    label = { if (expanded) Text(d.title) },
                                    selected = d.id == selected,
                                    onClick = { selected = d.id },
                                    icon = { Icon(d.icon, d.title) },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
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
                        title = { Text(destination.title) },
                        navigationIcon = {
                            if (!wide)
                                IconButton(onClick = { drawer = true }) {
                                    Icon(Icons.Default.Menu, "Navigation")
                                }
                        },
                        actions = {
                            IconButton(onClick = { accountPassword = true }) {
                                Icon(Icons.Default.AccountCircle, "Eigenes Passwort ändern")
                            }
                            IconButton(onClick = { vm.lock() }) {
                                Icon(Icons.Default.Lock, "Sperren")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.padding(padding).fillMaxSize()) {
                    when (selected) {
                        "dashboard" -> Dashboard(vm) { selected = it }
                        "workers",
                        "sites",
                        "duty",
                        "times",
                        "absences",
                        "tasks",
                        "payroll",
                        "registrations",
                        "documents" -> RecordsScreen(vm, destination.kind!!)
                        "contract" -> ContractScreen(vm, "contract")
                        "agreement" -> ContractScreen(vm, "agreement")
                        "exports" -> ExportsScreen(vm)
                        "stamp" -> StampScreen(vm)
                        "import" -> ImportScreen(vm)
                        "ocr" -> OcrScreen(vm)
                        "vacation" -> VacationScreen(vm)
                        "reports" -> ReportsScreen(vm)
                        "settings" -> SettingsScreen(vm)
                        "users" -> UsersScreen(vm)
                        "control" -> ControlScreen(vm)
                        "backup" -> BackupScreen(vm)
                        "mail" -> MailScreen(vm)
                    }
                }
            }
        }
        if (drawer && !wide)
            ModalBottomSheet(onDismissRequest = { drawer = false }) {
                LazyColumn {
                    items(visible) { d ->
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
                                e["type"],
                                e["category"],
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
