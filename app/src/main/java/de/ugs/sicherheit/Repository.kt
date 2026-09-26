package de.ugs.sicherheit

import android.app.Application
import android.content.ContentValues
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

class UGSApplication : Application() {
    val repository by lazy { Repository(this) }

    override fun onCreate() {
        super.onCreate()
        File(cacheDir, "exports").deleteRecursively()
        PDFBoxResourceLoader.init(this)
    }
}

class Repository(private val context: Context) {
    private val vault = Crypto.key()
    private val db: SQLiteDatabase
    private var session: Account? = null
    private val docs = File(context.noBackupFilesDir, "documents").apply { mkdirs() }
    private val photos = File(context.noBackupFilesDir, "photos").apply { mkdirs() }
    private var failures = 0
    private var blockedUntil = 0L

    init {
        System.loadLibrary("sqlcipher")
        val passFile = File(context.noBackupFilesDir, "database-key.enc")
        val database = File(context.noBackupFilesDir, "ugs.db")
        require(passFile.exists() || !database.exists()) {
            "Datenbankschlüssel fehlt. Keine neue Datenbank angelegt."
        }
        val pass =
            if (passFile.exists()) Crypto.open(passFile.readBytes(), vault)
            else Crypto.bytes(32).also { Crypto.writeAtomic(passFile, Crypto.seal(it, vault)) }
        db = SQLiteDatabase.openOrCreateDatabase(database, pass, null, null, null)
        pass.fill(0)
        db.execSQL("PRAGMA foreign_keys=ON")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS accounts(id TEXT PRIMARY KEY, username TEXT UNIQUE COLLATE NOCASE, salt TEXT NOT NULL, hash TEXT NOT NULL, role TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, revision INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS audit(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL)"
        )
        // Posteingang: zwischengespeicherte Nachrichten je Konto und Ordner.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS inbox(id TEXT PRIMARY KEY, account TEXT NOT NULL, folder TEXT NOT NULL, uid INTEGER NOT NULL, date TEXT NOT NULL, payload TEXT NOT NULL, blob TEXT NOT NULL DEFAULT '', seen INTEGER NOT NULL DEFAULT 0, UNIQUE(account,folder,uid))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_inbox_folder ON inbox(account,folder,date DESC)")
        val columns =
            db.rawQuery("PRAGMA table_info(accounts)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
        if ("permissions" !in columns)
            db.execSQL("ALTER TABLE accounts ADD COLUMN permissions TEXT NOT NULL DEFAULT '[]'")
        if ("active" !in columns)
            db.execSQL("ALTER TABLE accounts ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
    }

    @Synchronized
    fun needsSetup(): Boolean =
        db.rawQuery("SELECT count(*) FROM accounts", null).use {
            it.moveToFirst()
            it.getInt(0) == 0
        }

    private fun account(id: String): Account? =
        db.rawQuery(
                "SELECT id,username,role,permissions,active FROM accounts WHERE id=?",
                arrayOf(id),
            )
            .use { c ->
                if (c.moveToFirst())
                    Account(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1)
                else null
            }

    /** Prüft das gespeicherte Konto erneut, damit ein Entzug sofort wirkt. */
    private fun authorize(action: AccessAction, page: String): Account {
        val s = session ?: error("Bitte erneut anmelden.")
        val current = account(s.id) ?: error("Bitte erneut anmelden.")
        check(current.active) { "Dieses Konto ist deaktiviert." }
        check(AccessControl.allows(current, action, page)) {
            if (current.role == "Lesen" && action != AccessAction.VIEW) "Dieses Konto hat Leserechte."
            else "Keine Berechtigung: ${action.title} · ${AccessControl.labels[page] ?: page}"
        }
        session = current
        return current
    }

    private fun authorize(write: Boolean = false, admin: Boolean = false): Account {
        val a = session ?: error("Bitte erneut anmelden.")
        check(!admin || a.role == "Administrator") { "Nur für Administratoren." }
        check(!write || a.role != "Lesen") { "Dieses Konto hat Leserechte." }
        return a
    }

    /** Für die Oberfläche: darf die angemeldete Person das? */
    fun can(action: AccessAction, page: String) = AccessControl.allows(session, action, page)

    @Synchronized
    fun setup(username: String, password: String): Account {
        check(needsSetup()) { "Administrator bereits eingerichtet." }
        val a = insertUser(username, password, "Administrator", AccessControl.all.toSet())
        session = a
        audit("Administrator eingerichtet")
        return a
    }

    private fun insertUser(
        username: String,
        password: String,
        role: String,
        permissions: Set<String>,
    ): Account {
        require(username.trim().matches(Regex("[\\p{L}\\p{N}._@-]{3,80}"))) {
            "Benutzername: 3–80 Buchstaben, Zahlen oder . _ @ -"
        }
        require(password.length in 12..256) { "Passwort: 12 bis 256 Zeichen." }
        require(role in AccessControl.roles) { "Unbekannte Rolle." }
        val salt = Crypto.bytes(16)
        val grants = if (role == "Benutzerdefiniert") permissions else AccessControl.rolePresets[role].orEmpty()
        val a =
            Account(UUID.randomUUID().toString(), username.trim(), role, AccessControl.encode(grants))
        val hash = Crypto.derive(password.toCharArray(), salt)
        try {
            db.execSQL(
                "INSERT INTO accounts(id,username,salt,hash,role,permissions,active) VALUES(?,?,?,?,?,?,1)",
                arrayOf(a.id, a.username, Crypto.encode(salt), Crypto.encode(hash), role, a.permissions),
            )
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Benutzername bereits vergeben oder Speichern fehlgeschlagen."
            )
        } finally {
            hash.fill(0)
        }
        return a
    }

    @Synchronized
    fun login(username: String, password: String): Account {
        require(System.currentTimeMillis() >= blockedUntil) {
            "Zu viele Versuche. Bitte kurz warten."
        }
        val a =
            db.rawQuery(
                    "SELECT id,username,salt,hash,role,permissions,active FROM accounts WHERE username=?",
                    arrayOf(username.trim()),
                )
                .use { c ->
                    if (!c.moveToFirst()) {
                        Crypto.derive(password.toCharArray(), ByteArray(16))
                        null
                    } else {
                        val test =
                            Crypto.derive(password.toCharArray(), Crypto.decode(c.getString(2)))
                        val ok = MessageDigest.isEqual(test, Crypto.decode(c.getString(3)))
                        test.fill(0)
                        if (ok)
                            Account(
                                c.getString(0),
                                c.getString(1),
                                c.getString(4),
                                c.getString(5),
                                c.getInt(6) == 1,
                            )
                        else null
                    }
                }
        if (a == null) {
            failures++
            if (failures >= 5) blockedUntil = System.currentTimeMillis() + 30000
            throw IllegalArgumentException("Benutzername oder Passwort ist falsch.")
        }
        require(a.active) { "Dieses Konto ist deaktiviert." }
        session = a
        failures = 0
        audit("Anmeldung")
        return a
    }

    @Synchronized
    fun logout() {
        session = null
        File(context.cacheDir, "exports").deleteRecursively()
    }

    @Synchronized
    fun users(): List<Account> {
        authorize(admin = true)
        return db.rawQuery(
                "SELECT id,username,role,permissions,active FROM accounts ORDER BY username",
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext())
                        add(
                            Account(
                                c.getString(0),
                                c.getString(1),
                                c.getString(2),
                                c.getString(3),
                                c.getInt(4) == 1,
                            )
                        )
                }
            }
    }

    @Synchronized
    fun addUser(
        username: String,
        password: String,
        role: String,
        permissions: Set<String> = emptySet(),
    ) {
        authorize(admin = true)
        insertUser(username, password, role, permissions)
        audit("Benutzer angelegt: $username ($role)")
    }

    /** Rolle, Rechte, Aktiv-Status und optional ein neues Passwort ändern. */
    @Synchronized
    fun updateUser(
        id: String,
        role: String,
        permissions: Set<String>,
        active: Boolean,
        newPassword: String = "",
    ) {
        val me = authorize(admin = true)
        require(role in AccessControl.roles) { "Unbekannte Rolle." }
        require(id != me.id || (role == "Administrator" && active)) {
            "Das eigene Administratorkonto kann nicht herabgestuft oder deaktiviert werden."
        }
        val grants = if (role == "Benutzerdefiniert") permissions else AccessControl.rolePresets[role].orEmpty()
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE accounts SET role=?,permissions=?,active=? WHERE id=?",
                arrayOf(role, AccessControl.encode(grants), if (active) 1 else 0, id),
            )
            if (newPassword.isNotEmpty()) {
                require(newPassword.length in 12..256) { "Passwort: 12 bis 256 Zeichen." }
                val salt = Crypto.bytes(16)
                val hash = Crypto.derive(newPassword.toCharArray(), salt)
                try {
                    db.execSQL(
                        "UPDATE accounts SET salt=?,hash=? WHERE id=?",
                        arrayOf(Crypto.encode(salt), Crypto.encode(hash), id),
                    )
                } finally {
                    hash.fill(0)
                }
            }
            val admins =
                db.rawQuery(
                        "SELECT count(*) FROM accounts WHERE role='Administrator' AND active=1",
                        null,
                    )
                    .use {
                        it.moveToFirst()
                        it.getInt(0)
                    }
            require(admins > 0) { "Mindestens ein aktiver Administrator ist erforderlich." }
            audit("Benutzer geändert: ${account(id)?.username} ($role${if (active) "" else ", deaktiviert"})")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun removeUser(id: String) {
        val a = authorize(admin = true)
        require(a.id != id) { "Eigenes aktives Konto kann nicht gelöscht werden." }
        db.execSQL("DELETE FROM accounts WHERE id=?", arrayOf(id))
        audit("Benutzer entfernt")
    }

    @Synchronized
    fun password(old: String, new: String) {
        val a = authorize()
        login(a.username, old)
        require(new.length in 12..256) { "Neues Passwort: 12 bis 256 Zeichen." }
        val salt = Crypto.bytes(16)
        val hash = Crypto.derive(new.toCharArray(), salt)
        try {
            db.execSQL(
                "UPDATE accounts SET salt=?,hash=? WHERE id=?",
                arrayOf(Crypto.encode(salt), Crypto.encode(hash), a.id),
            )
            audit("Passwort geändert")
        } finally {
            hash.fill(0)
        }
    }

    private fun records(includeDeleted: Boolean = false): List<Entry> =
        db.rawQuery(
                "SELECT id,kind,payload,revision,deleted FROM records" +
                    if (includeDeleted) "" else " WHERE deleted=0",
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val kind = runCatching { Kind.valueOf(c.getString(1)) }.getOrNull() ?: continue
                        val f = JSONObject(c.getString(2))
                        add(
                            Entry(
                                c.getString(0),
                                kind,
                                f.keys().asSequence().associateWith { f.getString(it) },
                                c.getInt(3),
                                c.getInt(4) == 1,
                            )
                        )
                    }
                }
            }

    /** Alle Datensätze, die die angemeldete Person ansehen darf. */
    @Synchronized
    fun entries(includeDeleted: Boolean = false): List<Entry> {
        val a = authorize()
        val visible = AccessControl.permissionSet(a)
        return records(includeDeleted).filter {
            a.role == "Administrator" ||
                AccessControl.page(it.kind) in visible ||
                // Namen und Nummern werden für Auswahllisten überall gebraucht.
                it.kind == Kind.WORKER ||
                it.kind == Kind.SITE
        }
    }

    /** Dreistellige Personalnummer aus den Ziffern 1–9, nie wiederverwendet. */
    private fun staffNumber(all: List<Entry>): String {
        val used = all.filter { it.kind == Kind.WORKER }.map { it["personnelNumber"].trim() }.toSet()
        val free =
            (111..999).map { it.toString() }.filter { '0' !in it && it !in used }
        require(free.isNotEmpty()) { "Alle 729 dreistelligen Personalnummern ohne 0 sind vergeben." }
        return free[java.security.SecureRandom().nextInt(free.size)]
    }

    private fun prepared(e: Entry, all: List<Entry>): Entry {
        var x = e.copy(fields = e.fields.mapValues { it.value.trim() })
        if (x.kind == Kind.WORKER) {
            val old = all.find { it.id == x.id }
            if (x["personnelNumber"].isBlank())
                x = x.copy(fields = x.fields + ("personnelNumber" to (old?.get("personnelNumber")?.takeIf { it.isNotBlank() } ?: staffNumber(all))))
            if (old != null && old["personnelNumber"].isNotBlank())
                require(old["personnelNumber"] == x["personnelNumber"]) {
                    "Die vergebene Personalnummer kann nicht geändert werden."
                }
            for (k in listOf("iban", "bic")) if (x[k].isNotBlank())
                x = x.copy(fields = x.fields + (k to x[k].uppercase().replace(" ", "")))
        }
        if (x.kind == Kind.EXPENSE)
            parseAmount(x["amount"])?.let { x = x.copy(fields = x.fields + ("amount" to String.format(java.util.Locale.ROOT, "%.2f", it))) }
        if (x.kind == Kind.DOCUMENT) {
            if (x["category"].isNotBlank())
                x = x.copy(fields = x.fields + ("category" to DocumentCategories.canonical(x["category"])))
            // Erweitertes Führungszeugnis: Erneuerung 6 Monate nach Antrag bzw. Ausstellung.
            val extended = x["extendedCertificate"] == "true" || x["category"] == "Erweitertes Führungszeugnis"
            val base = x["renewalRequestedOn"].ifBlank { x["date"] }
            val due = if (extended) runCatching { java.time.LocalDate.parse(base).plusMonths(6).toString() }.getOrDefault("") else ""
            x = x.copy(fields = x.fields + mapOf("extendedCertificate" to extended.toString(), "renewalDueOn" to due))
        }
        return x
    }

    private fun store(e: Entry, all: List<Entry>): Entry {
        val x = prepared(e, all)
        Rules.validate(x, all)
        val current = all.find { it.id == x.id }
        require(current == null && x.revision == 0 || current?.revision == x.revision) {
            "Datensatz wurde zwischenzeitlich geändert. Bitte neu öffnen."
        }
        val values =
            ContentValues().apply {
                put("id", x.id)
                put("kind", x.kind.name)
                put("payload", JSONObject(x.fields).toString())
                put("revision", x.revision + 1)
                put("deleted", 0)
            }
        check(
            db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0
        ) {
            "Datensatz konnte nicht gespeichert werden."
        }
        return x.copy(revision = x.revision + 1)
    }

    @Synchronized
    fun save(e: Entry): Entry {
        authorize(
            if (e.revision == 0) AccessAction.CREATE else AccessAction.EDIT,
            AccessControl.page(e.kind),
        )
        val saved = store(e, records(true))
        audit("${e.kind.title}: ${if(e.revision==0)"angelegt" else "bearbeitet"} · ${saved.title}")
        return saved
    }

    @Synchronized
    fun saveBatch(batch: List<Entry>) {
        authorize(AccessAction.CREATE, "import")
        authorize(AccessAction.EDIT, "workers")
        require(batch.size <= 5000)
        db.beginTransaction()
        try {
            val all = records(true).toMutableList()
            for (e in batch) {
                val saved = store(e, all)
                all.removeAll { it.id == e.id }
                all.add(saved)
            }
            audit("Import / Serie: ${batch.size} Datensätze")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun delete(e: Entry) {
        authorize(AccessAction.DELETE, AccessControl.page(e.kind))
        val all = records()
        require(all.find { it.id == e.id }?.revision == e.revision) { "Datensatz wurde geändert." }
        require(e.kind != Kind.WORKER || all.none { it["workerId"] == e.id }) {
            "Mitarbeiter ist mit Datensätzen verknüpft. Bitte auf Inaktiv setzen oder Verknüpfungen zuerst entfernen."
        }
        require(e.kind != Kind.SITE || all.none { it["siteId"] == e.id }) {
            "Objekt ist mit Diensten verknüpft."
        }
        db.execSQL("UPDATE records SET deleted=1,revision=revision+1 WHERE id=?", arrayOf(e.id))
        audit("${e.kind.title}: in Papierkorb · ${e.title}")
    }

    @Synchronized
    fun restoreEntry(e: Entry) {
        authorize(AccessAction.EDIT, "operations")
        require(e.deleted)
        store(e.copy(deleted = false), records(true))
        audit("${e.kind.title}: wiederhergestellt · ${e.title}")
    }

    /** Endgültig löschen: Datensatz und zugehörige verschlüsselte Datei. */
    @Synchronized
    fun purge(e: Entry) {
        authorize(AccessAction.DELETE, "operations")
        val current = records(true).find { it.id == e.id } ?: return
        require(current.deleted) { "Nur Einträge im Papierkorb können endgültig gelöscht werden." }
        db.execSQL("DELETE FROM records WHERE id=?", arrayOf(e.id))
        for (k in listOf("blob", "attachmentsBlob"))
            current[k].takeIf { it.matches(Regex("[a-f0-9-]{36}")) }?.let { File(docs, it).delete() }
        if (current.kind == Kind.WORKER) File(photos, current.id).delete()
        audit("${e.kind.title}: endgültig gelöscht · ${e.title}")
    }

    @Synchronized
    fun settings(): Map<String, String> {
        authorize()
        return db.rawQuery("SELECT key,value FROM settings WHERE key NOT LIKE 'secret.%'", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }

    @Synchronized
    fun settings(values: Map<String, String>) {
        authorize(AccessAction.EDIT, "settings")
        require(values.keys.none { it.startsWith("secret.") })
        db.beginTransaction()
        try {
            for ((k, v) in values) db.execSQL(
                "INSERT OR REPLACE INTO settings VALUES(?,?)",
                arrayOf(k, v),
            )
            audit("Einstellungen geändert")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Persönliche Einstellung der angemeldeten Person (Darstellung, Sprachassistent, …).
     * Braucht keine Einstellungsrechte.
     */
    @Synchronized
    fun personal(key: String, value: String) {
        val a = authorize()
        require(key.matches(Regex("[a-zA-Z0-9_.]{1,60}")))
        db.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", arrayOf("user.${a.id}.$key", value))
    }

    fun personalKey(key: String) = "user.${session?.id}.$key"

    /** Geheimnisse (Mail-Passwörter) liegen nur in der verschlüsselten Datenbank. */
    @Synchronized
    fun secret(key: String): String {
        authorize()
        return db.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf("secret.$key")).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }
    }

    @Synchronized
    fun secret(key: String, value: String) {
        authorize(AccessAction.EDIT, "settings")
        if (value.isEmpty()) db.execSQL("DELETE FROM settings WHERE key=?", arrayOf("secret.$key"))
        else db.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", arrayOf("secret.$key", value))
        audit("Zugangsdaten geändert: $key")
    }

    @Synchronized
    fun lookups(): Map<String, List<String>> = Lookups.all(settings())

    @Synchronized
    fun saveLookups(groups: Map<String, List<String>>) {
        authorize(AccessAction.EDIT, "settings")
        db.execSQL(
            "INSERT OR REPLACE INTO settings VALUES('lookups',?)",
            arrayOf(Lookups.encode(groups)),
        )
        audit("Auswahllisten geändert")
    }

    private fun audit(text: String) {
        db.execSQL(
            "INSERT INTO audit(date,actor,action) VALUES(?,?,?)",
            arrayOf(java.time.Instant.now().toString(), session?.username ?: "Einrichtung", text),
        )
    }

    data class AuditRow(val date: String, val actor: String, val action: String)

    @Synchronized
    fun auditRows(limit: Int = 300): List<AuditRow> {
        authorize(AccessAction.VIEW, "operations")
        return db.rawQuery("SELECT date,actor,action FROM audit ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(AuditRow(c.getString(0), c.getString(1), c.getString(2))) } }
    }

    @Synchronized
    fun history(): List<String> =
        auditRows().map { "${it.date} · ${it.actor}\n${it.action}" }

    @Synchronized
    fun integrity(): String {
        authorize(AccessAction.VIEW, "operations")
        return db.rawQuery("PRAGMA integrity_check", null).use {
            it.moveToFirst()
            it.getString(0)
        }
    }

    // MARK: Verschlüsselte Dateien

    private fun blobFile(id: String): File {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Ungültige Dateikennung." }
        return File(docs, id)
    }

    /** Speichert einen Datenstrom verschlüsselt und liefert die Dateikennung. */
    private fun putBlob(input: InputStream, limit: Long = FileLimits.FILE_BYTES.toLong()): Pair<String, Long> {
        val id = UUID.randomUUID().toString()
        val size = Crypto.sealStream(input, File(docs, id), vault, limit)
        return id to size
    }

    private fun sha256(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun attach(e: Entry, data: ByteArray, mime: String) =
        attachStream(e, { data.inputStream() }, mime, data.size.toLong())

    /**
     * Legt ein Dokument (PDF, Bild, Office …) bis 250 MB verschlüsselt ab. [open] liefert den
     * Datenstrom; er wird für die Prüfsumme und die Verschlüsselung je einmal gelesen.
     */
    @Synchronized
    fun attachStream(e: Entry, open: () -> InputStream, mime: String, size: Long = -1): Entry {
        authorize(if (e.revision == 0) AccessAction.CREATE else AccessAction.EDIT, "documents")
        require(size != 0L) { "Leere Datei." }
        require(size <= FileLimits.FILE_BYTES) { "Maximal ${FileLimits.FILE_LABEL} erlaubt." }
        val hash = open().use { sha256(it) }
        val (blob, bytes) = open().use { putBlob(it) }
        try {
            require(bytes > 0) { "Leere Datei." }
            return save(
                e.copy(
                    fields =
                        e.fields +
                            mapOf(
                                "blob" to blob,
                                "mime" to mime.ifBlank { "application/octet-stream" },
                                "size" to bytes.toString(),
                                "sha256" to hash,
                            )
                )
            )
        } catch (ex: Exception) {
            File(docs, blob).delete()
            throw ex
        }
    }

    /**
     * Legt ein erzeugtes PDF automatisch in der Personalakte ab. Dieselbe Fassung (gleiche
     * Prüfsumme beim selben Mitarbeiter) wird nie doppelt abgelegt.
     */
    @Synchronized
    fun archive(
        workerId: String,
        category: String,
        title: String,
        data: ByteArray,
        mime: String = "application/pdf",
        notes: String = "",
    ): Entry {
        val all = records()
        require(all.any { it.id == workerId && it.kind == Kind.WORKER }) {
            "Bitte zuerst einen Mitarbeiter laden."
        }
        val hash = sha256(data.inputStream())
        all.firstOrNull {
                it.kind == Kind.DOCUMENT && it["workerId"] == workerId && it["sha256"] == hash
            }
            ?.let { return it }
        return attachStream(
            Entry(
                kind = Kind.DOCUMENT,
                fields =
                    mapOf(
                        "title" to title,
                        "workerId" to workerId,
                        "category" to DocumentCategories.canonical(category),
                        "date" to today,
                        "notes" to notes,
                    ),
            ),
            { data.inputStream() },
            mime,
            data.size.toLong(),
        )
    }

    private fun documentEntry(e: Entry): Entry =
        records().find { it.id == e.id && it.kind == Kind.DOCUMENT }
            ?: error("Dokument nicht vorhanden.")

    @Synchronized
    fun document(e: Entry): ByteArray {
        authorize(AccessAction.VIEW, "documents")
        return Crypto.openFile(blobFile(documentEntry(e)["blob"]), vault)
    }

    /** Entschlüsselt ein Dokument direkt in eine Datei (für große Dateien). */
    @Synchronized
    fun documentTo(e: Entry, target: File) {
        authorize(AccessAction.VIEW, "documents")
        val source = blobFile(documentEntry(e)["blob"])
        target.parentFile?.mkdirs()
        target.outputStream().use { Crypto.openStream(source, vault, it) }
    }

    /** Beliebige interne Datei (Mail-Anhänge usw.) entschlüsseln. */
    @Synchronized
    fun blob(id: String, out: OutputStream) {
        authorize()
        Crypto.openStream(blobFile(id), vault, out)
    }

    @Synchronized
    fun blobBytes(id: String): ByteArray = java.io.ByteArrayOutputStream().also { blob(id, it) }.toByteArray()

    /** Interne Datei verschlüsselt ablegen (Posteingang, gesendete Mails, Modelle). */
    @Synchronized
    fun storeBlob(data: ByteArray, limit: Long = FileLimits.INCOMING_MAIL_BYTES.toLong()): String {
        authorize()
        return putBlob(data.inputStream(), limit).first
    }

    @Synchronized
    fun dropBlob(id: String) {
        authorize()
        blobFile(id).delete()
    }

    // MARK: Mitarbeiter- und Profilfotos (verschlüsselt, höchstens 512 KB)

    @Synchronized
    fun photo(id: String): ByteArray? {
        authorize()
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val f = File(photos, id)
        return if (f.exists()) runCatching { Crypto.openFile(f, vault) }.getOrNull() else null
    }

    @Synchronized
    fun setPhoto(id: String, png: ByteArray?) {
        val own = id == "user-${session?.id}"
        if (!own) authorize(AccessAction.EDIT, "workers") else authorize()
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val f = File(photos, id)
        if (png == null) {
            f.delete()
            audit(if (own) "Profilfoto entfernt" else "Mitarbeiterfoto entfernt")
            return
        }
        require(png.size in 1..524288) { "Foto zu groß (höchstens 512 KB nach dem Verkleinern)." }
        Crypto.writeAtomic(f, Crypto.seal(png, vault))
        audit(if (own) "Profilfoto geändert" else "Mitarbeiterfoto geändert")
    }

    // MARK: Posteingang-Zwischenspeicher

    data class InboxRow(
        val id: String,
        val account: String,
        val folder: String,
        val uid: Long,
        val date: String,
        val payload: String,
        val blob: String,
        val seen: Boolean,
    )

    @Synchronized
    fun inbox(account: String, folder: String, limit: Int = 500): List<InboxRow> {
        authorize(AccessAction.VIEW, "inbox")
        return db.rawQuery(
                "SELECT id,account,folder,uid,date,payload,blob,seen FROM inbox WHERE account=? AND folder=? ORDER BY date DESC, uid DESC LIMIT ?",
                arrayOf(account, folder, limit.toString()),
            )
            .use { c ->
                buildList {
                    while (c.moveToNext())
                        add(
                            InboxRow(
                                c.getString(0),
                                c.getString(1),
                                c.getString(2),
                                c.getLong(3),
                                c.getString(4),
                                c.getString(5),
                                c.getString(6),
                                c.getInt(7) == 1,
                            )
                        )
                }
            }
    }

    @Synchronized
    fun inboxUids(account: String, folder: String): Set<Long> {
        authorize(AccessAction.VIEW, "inbox")
        return db.rawQuery("SELECT uid FROM inbox WHERE account=? AND folder=?", arrayOf(account, folder))
            .use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    @Synchronized
    fun putInbox(row: InboxRow) {
        authorize(AccessAction.VIEW, "inbox")
        db.execSQL(
            "INSERT OR REPLACE INTO inbox(id,account,folder,uid,date,payload,blob,seen) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(row.id, row.account, row.folder, row.uid, row.date, row.payload, row.blob, if (row.seen) 1 else 0),
        )
    }

    @Synchronized
    fun markSeen(id: String) {
        authorize(AccessAction.VIEW, "inbox")
        db.execSQL("UPDATE inbox SET seen=1 WHERE id=?", arrayOf(id))
    }

    @Synchronized
    fun dropInbox(account: String, folder: String, uids: Collection<Long>) {
        authorize(AccessAction.VIEW, "inbox")
        for (u in uids) {
            db.rawQuery("SELECT blob FROM inbox WHERE account=? AND folder=? AND uid=?", arrayOf(account, folder, u.toString()))
                .use { if (it.moveToFirst()) it.getString(0).takeIf { b -> b.isNotBlank() }?.let { b -> File(docs, b).delete() } }
            db.execSQL("DELETE FROM inbox WHERE account=? AND folder=? AND uid=?", arrayOf(account, folder, u))
        }
    }

    /**
     * Versandverlauf: wird vor jedem Versand reserviert („nicht bestätigt“) und danach
     * auf gesendet/fehlgeschlagen gesetzt. Die Rohnachricht mit Anhängen bleibt verschlüsselt.
     */
    @Synchronized
    fun recordMail(e: Entry, raw: ByteArray? = null): Entry {
        authorize()
        require(e.kind == Kind.SENT_MAIL)
        val all = records(true)
        var fields = e.fields
        if (raw != null) fields = fields + ("blob" to putBlob(raw.inputStream(), (FileLimits.MAIL_BYTES * 2L)).first)
        val saved = store(e.copy(fields = fields), all)
        if (e.revision == 0) audit("E-Mail: ${e["status"]} an ${e["recipient"]} · ${e["subject"]}")
        else audit("E-Mail-Status: ${e["status"]} · ${e["subject"]}")
        return saved
    }

    /** Farben und Blockierregeln des Posteingangs (für alle Benutzer gemeinsam). */
    @Synchronized
    fun inboxMeta(key: String, value: String) {
        authorize(AccessAction.EDIT, "inbox")
        require(key.matches(Regex("[a-zA-Z0-9_.:-]{1,120}")))
        db.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?)", arrayOf("inbox.meta.$key", value))
    }

    @Synchronized
    fun exported(label: String) {
        authorize()
        audit("Export: $label")
    }

    @Synchronized
    fun logged(label: String) {
        authorize()
        audit(label)
    }

    // MARK: Portables Archiv vom Mac/iPhone (ergänzt den Datenbestand, ersetzt nichts)

    /** Legt Datensätze in einer Transaktion an; ungültige werden übersprungen und gemeldet. */
    @Synchronized
    fun importRecords(batch: List<Entry>, label: String): Pair<List<Entry>, List<String>> {
        authorize(AccessAction.EDIT, "backup")
        check(session?.role != "Lesen") { "Dieses Konto hat Leserechte." }
        require(batch.size <= 200000) { "Das Archiv enthält zu viele Datensätze." }
        val saved = mutableListOf<Entry>()
        val errors = mutableListOf<String>()
        db.beginTransaction()
        try {
            val all = records(true).toMutableList()
            for (e in batch) {
                try {
                    val x = store(e, all)
                    all.removeAll { it.id == x.id }
                    all.add(x)
                    saved += x
                } catch (ex: Exception) {
                    errors += "${e.kind.title} „${e.title}“: ${ex.message}"
                }
            }
            audit("$label: ${saved.size} Datensätze übernommen, ${errors.size} übersprungen")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return saved to errors
    }

    /** Dokument aus einem Datenstrom, der nur einmal gelesen werden kann (Prüfsumme beim Verschlüsseln). */
    @Synchronized
    fun importDocument(e: Entry, input: InputStream, mime: String): Entry {
        authorize(AccessAction.EDIT, "backup")
        val md = MessageDigest.getInstance("SHA-256")
        val (blob, bytes) = putBlob(java.security.DigestInputStream(input, md))
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        try {
            require(bytes > 0) { "Leere Datei." }
            val all = records()
            all.firstOrNull { it.kind == Kind.DOCUMENT && it["workerId"] == e["workerId"] && it["sha256"] == hash }?.let {
                File(docs, blob).delete()
                return it
            }
            val x = store(e.copy(fields = e.fields + mapOf("blob" to blob, "mime" to mime.ifBlank { "application/octet-stream" }, "size" to bytes.toString(), "sha256" to hash)), all)
            return x
        } catch (ex: Exception) {
            File(docs, blob).delete()
            throw ex
        }
    }

    // MARK: Datensicherung (Android-Format UGSB01; Dokumente bis 250 MB je Datei)

    @Synchronized
    fun backup(password: String): ByteArray {
        authorize(AccessAction.EXPORT, "backup")
        check(session?.role != "Lesen") { "Dieses Konto hat Leserechte." }
        val rows = records(true)
        val files = JSONObject()
        for (e in rows) for (key in listOf("blob", "attachmentsBlob")) {
            val id = e[key]
            if (id.matches(Regex("[a-f0-9-]{36}")) && File(docs, id).exists())
                files.put(id, Crypto.encode(Crypto.openFile(File(docs, id), vault)))
        }
        val photoFiles = JSONObject()
        photos.listFiles()?.forEach { f ->
            runCatching { photoFiles.put(f.name, Crypto.encode(Crypto.openFile(f, vault))) }
        }
        val data =
            JSONObject()
                .put("format", 2)
                .put("records", JSONArray(rows.map { it.json() }))
                .put("settings", JSONObject(settings()))
                .put("files", files)
                .put("photos", photoFiles)
                .toString()
                .toByteArray()
        audit("Fachdaten-Sicherung erstellt")
        return Crypto.backup(data, password)
    }

    @Synchronized
    fun restoreBackup(data: ByteArray, password: String) {
        authorize(AccessAction.EDIT, "backup")
        check(session?.role != "Lesen") { "Dieses Konto hat Leserechte." }
        val o =
            try {
                JSONObject(String(Crypto.restore(data, password)))
            } catch (e: Exception) {
                throw IllegalArgumentException("Passwort falsch oder Sicherung beschädigt.")
            }
        require(o.getInt("format") in 1..2) { "Unbekanntes Sicherungsformat." }
        replaceAll(
            o.getJSONArray("records").let { a -> (0 until a.length()).map { Entry.from(a.getJSONObject(it)) } },
            o.getJSONObject("settings").let { s -> s.keys().asSequence().associateWith { s.getString(it) } },
            o.getJSONObject("files").let { f -> f.keys().asSequence().associateWith { Crypto.decode(f.getString(it)) } },
            o.optJSONObject("photos")?.let { f -> f.keys().asSequence().associateWith { Crypto.decode(f.getString(it)) } }
                ?: emptyMap(),
            "Fachdaten-Sicherung wiederhergestellt",
        )
    }

    /**
     * Ersetzt alle Fachdaten atomar. Dateien werden neu verschlüsselt, Kennungen neu vergeben.
     * Benutzerkonten und Protokoll bleiben erhalten.
     */
    @Synchronized
    fun replaceAll(
        rows: List<Entry>,
        settings: Map<String, String>,
        files: Map<String, ByteArray>,
        photoFiles: Map<String, ByteArray>,
        label: String,
    ) {
        authorize(AccessAction.EDIT, "backup")
        require(rows.size <= 200000) { "Sicherung enthält zu viele Datensätze." }
        require(rows.map { it.id }.distinct().size == rows.size) { "Doppelte Datensätze in der Sicherung." }
        val newFiles = mutableListOf<File>()
        val mapping = mutableMapOf<String, String>()
        try {
            for ((key, bytes) in files) {
                require(bytes.size <= FileLimits.BACKUP_DOCUMENT_BYTES) { "Dokument in der Sicherung zu groß." }
                val next = UUID.randomUUID().toString()
                val f = File(docs, next)
                newFiles += f
                Crypto.sealStream(bytes.inputStream(), f, vault, FileLimits.BACKUP_DOCUMENT_BYTES.toLong())
                mapping[key] = next
            }
            val mapped =
                rows.map { e ->
                    var fields = e.fields
                    for (k in listOf("blob", "attachmentsBlob")) if (e[k].isNotEmpty())
                        fields = fields + (k to (mapping[e[k]] ?: error("Datei fehlt in Sicherung: ${e.title}")))
                    e.copy(fields = fields)
                }
            for (e in mapped.filter { !it.deleted && it.kind.generic }) Rules.validate(e, mapped)
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM records")
                for (e in mapped) db.execSQL(
                    "INSERT INTO records VALUES(?,?,?,?,?)",
                    arrayOf(
                        e.id,
                        e.kind.name,
                        JSONObject(e.fields).toString(),
                        e.revision + 1,
                        if (e.deleted) 1 else 0,
                    ),
                )
                db.execSQL("DELETE FROM settings WHERE key NOT LIKE 'secret.%' AND key NOT LIKE 'user.%'")
                for ((k, v) in settings) if (!k.startsWith("secret.")) db.execSQL(
                    "INSERT OR REPLACE INTO settings VALUES(?,?)",
                    arrayOf(k, v),
                )
                audit(label)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            for ((name, bytes) in photoFiles) if (name.matches(Regex("[A-Za-z0-9-]{1,80}")) && bytes.size <= 524288)
                Crypto.writeAtomic(File(photos, name), Crypto.seal(bytes, vault))
        } catch (e: Exception) {
            newFiles.forEach { it.delete() }
            throw e
        }
    }
}
