package de.ugs.sicherheit

import android.app.Application
import android.content.ContentValues
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
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
    }

    @Synchronized
    fun needsSetup(): Boolean =
        db.rawQuery("SELECT count(*) FROM accounts", null).use {
            it.moveToFirst()
            it.getInt(0) == 0
        }

    private fun authorize(write: Boolean = false, admin: Boolean = false): Account {
        val a = session ?: error("Bitte erneut anmelden.")
        check(!admin || a.role == "Administrator") { "Nur für Administratoren." }
        check(!write || a.role != "Lesen") { "Dieses Konto hat Leserechte." }
        return a
    }

    @Synchronized
    fun setup(username: String, password: String): Account {
        check(needsSetup()) { "Administrator bereits eingerichtet." }
        val a = insertUser(username, password, "Administrator")
        session = a
        audit("Administrator eingerichtet")
        return a
    }

    private fun insertUser(username: String, password: String, role: String): Account {
        require(username.trim().matches(Regex("[\\p{L}\\p{N}._@-]{3,80}"))) {
            "Benutzername: 3–80 Buchstaben, Zahlen oder . _ @ -"
        }
        require(password.length in 12..256) { "Passwort: 12 bis 256 Zeichen." }
        require(role in listOf("Administrator", "Personal", "Lesen"))
        val salt = Crypto.bytes(16)
        val a = Account(UUID.randomUUID().toString(), username.trim(), role)
        val hash = Crypto.derive(password.toCharArray(), salt)
        try {
            db.execSQL(
                "INSERT INTO accounts VALUES(?,?,?,?,?)",
                arrayOf(a.id, a.username, Crypto.encode(salt), Crypto.encode(hash), role),
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
                    "SELECT id,username,salt,hash,role FROM accounts WHERE username=?",
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
                        if (ok) Account(c.getString(0), c.getString(1), c.getString(4)) else null
                    }
                }
        if (a == null) {
            failures++
            if (failures >= 5) blockedUntil = System.currentTimeMillis() + 30000
            throw IllegalArgumentException("Benutzername oder Passwort ist falsch.")
        }
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
        return db.rawQuery("SELECT id,username,role FROM accounts ORDER BY username", null).use { c
            ->
            buildList {
                while (c.moveToNext()) add(Account(c.getString(0), c.getString(1), c.getString(2)))
            }
        }
    }

    @Synchronized
    fun addUser(username: String, password: String, role: String) {
        authorize(admin = true)
        insertUser(username, password, role)
        audit("Benutzer angelegt: $username")
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
                        val f = JSONObject(c.getString(2))
                        add(
                            Entry(
                                c.getString(0),
                                Kind.valueOf(c.getString(1)),
                                f.keys().asSequence().associateWith { f.getString(it) },
                                c.getInt(3),
                                c.getInt(4) == 1,
                            )
                        )
                    }
                }
            }

    @Synchronized
    fun entries(includeDeleted: Boolean = false): List<Entry> {
        authorize()
        return records(includeDeleted)
    }

    private fun store(e: Entry, all: List<Entry>) {
        Rules.validate(e, all)
        val current = all.find { it.id == e.id }
        require(current == null && e.revision == 0 || current?.revision == e.revision) {
            "Datensatz wurde zwischenzeitlich geändert. Bitte neu öffnen."
        }
        val values =
            ContentValues().apply {
                put("id", e.id)
                put("kind", e.kind.name)
                put("payload", JSONObject(e.fields).toString())
                put("revision", e.revision + 1)
                put("deleted", 0)
            }
        check(
            db.insertWithOnConflict("records", null, values, SQLiteDatabase.CONFLICT_REPLACE) >= 0
        ) {
            "Datensatz konnte nicht gespeichert werden."
        }
    }

    @Synchronized
    fun save(e: Entry) {
        authorize(write = true)
        store(e, records(true))
        audit("${e.kind.title}: ${if(e.revision==0)"angelegt" else "bearbeitet"}")
    }

    @Synchronized
    fun saveBatch(batch: List<Entry>) {
        authorize(write = true)
        require(batch.size <= 5000)
        db.beginTransaction()
        try {
            val all = records(true).toMutableList()
            for (e in batch) {
                store(e, all)
                all.removeAll { it.id == e.id }
                all.add(e.copy(revision = e.revision + 1))
            }
            audit("Import / Serie: ${batch.size} Datensätze")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun delete(e: Entry) {
        authorize(write = true)
        val all = records()
        require(all.find { it.id == e.id }?.revision == e.revision) { "Datensatz wurde geändert." }
        require(e.kind != Kind.WORKER || all.none { it["workerId"] == e.id }) {
            "Mitarbeiter ist mit Datensätzen verknüpft. Bitte auf Inaktiv setzen oder Verknüpfungen zuerst entfernen."
        }
        require(e.kind != Kind.SITE || all.none { it["siteId"] == e.id }) {
            "Objekt ist mit Diensten verknüpft."
        }
        db.execSQL("UPDATE records SET deleted=1,revision=revision+1 WHERE id=?", arrayOf(e.id))
        audit("${e.kind.title}: in Papierkorb")
    }

    @Synchronized
    fun restoreEntry(e: Entry) {
        authorize(admin = true)
        require(e.deleted)
        store(e.copy(deleted = false), records(true))
        audit("${e.kind.title}: wiederhergestellt")
    }

    @Synchronized
    fun settings(): Map<String, String> {
        authorize()
        return db.rawQuery("SELECT key,value FROM settings", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }

    @Synchronized
    fun settings(values: Map<String, String>) {
        authorize(admin = true)
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

    private fun audit(text: String) {
        db.execSQL(
            "INSERT INTO audit(date,actor,action) VALUES(?,?,?)",
            arrayOf(java.time.Instant.now().toString(), session?.username ?: "Einrichtung", text),
        )
    }

    @Synchronized
    fun history(): List<String> {
        authorize(admin = true)
        return db.rawQuery("SELECT date,actor,action FROM audit ORDER BY id DESC LIMIT 300", null)
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(
                        "${c.getString(0)} · ${c.getString(1)}\n${c.getString(2)}"
                    )
                }
            }
    }

    @Synchronized
    fun integrity(): String {
        authorize(admin = true)
        return db.rawQuery("PRAGMA integrity_check", null).use {
            it.moveToFirst()
            it.getString(0)
        }
    }

    @Synchronized
    fun attach(e: Entry, data: ByteArray, mime: String) {
        authorize(write = true)
        require(data.size in 1..20 * 1024 * 1024) {
            "Datei muss zwischen 1 Byte und 20 MB groß sein."
        }
        require(mime == "application/pdf" || mime.startsWith("image/")) {
            "PDF oder Bild erforderlich."
        }
        val blob = UUID.randomUUID().toString()
        val f = File(docs, blob)
        Crypto.writeAtomic(f, Crypto.seal(data, vault))
        try {
            save(e.copy(fields = e.fields + mapOf("blob" to blob, "mime" to mime)))
        } catch (ex: Exception) {
            f.delete()
            throw ex
        }
    }

    @Synchronized
    fun document(e: Entry): ByteArray {
        authorize()
        val existing =
            records().find { it.id == e.id && it.kind == Kind.DOCUMENT }
                ?: error("Dokument nicht vorhanden.")
        val id = existing["blob"]
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return Crypto.open(File(docs, id).readBytes(), vault)
    }

    @Synchronized
    fun exported(label: String) {
        authorize()
        audit("Export: $label")
    }

    @Synchronized
    fun backup(password: String): ByteArray {
        authorize(admin = true)
        val rows = records(true)
        val files = JSONObject()
        for (e in rows.filter { it.kind == Kind.DOCUMENT && it["blob"].isNotEmpty() }) {
            val file = File(docs, e["blob"])
            files.put(e["blob"], Crypto.encode(Crypto.open(file.readBytes(), vault)))
        }
        val data =
            JSONObject()
                .put("format", 1)
                .put("records", JSONArray(rows.map { it.json() }))
                .put("settings", JSONObject(settings()))
                .put("files", files)
                .toString()
                .toByteArray()
        require(data.size <= 100 * 1024 * 1024) { "Sicherung überschreitet 100 MB." }
        audit("Fachdaten-Sicherung erstellt")
        return Crypto.backup(data, password)
    }

    @Synchronized
    fun restoreBackup(data: ByteArray, password: String) {
        authorize(admin = true)
        require(data.size <= 140 * 1024 * 1024) { "Sicherung zu groß." }
        val o =
            try {
                JSONObject(String(Crypto.restore(data, password)))
            } catch (e: Exception) {
                throw IllegalArgumentException("Passwort falsch oder Sicherung beschädigt.")
            }
        require(o.getInt("format") == 1)
        val a = o.getJSONArray("records")
        require(a.length() <= 20000)
        val rows = (0 until a.length()).map { Entry.from(a.getJSONObject(it)) }
        require(rows.map { it.id }.distinct().size == rows.size)
        val files = o.getJSONObject("files")
        val newFiles = mutableListOf<File>()
        val mapping = mutableMapOf<String, String>()
        try {
            for (key in files.keys()) {
                val next = UUID.randomUUID().toString()
                val f = File(docs, next)
                newFiles += f
                Crypto.writeAtomic(f, Crypto.seal(Crypto.decode(files.getString(key)), vault))
                mapping[key] = next
            }
            val mapped =
                rows.map {
                    if (it.kind == Kind.DOCUMENT && it["blob"].isNotEmpty())
                        it.copy(
                            fields =
                                it.fields +
                                    ("blob" to
                                        (mapping[it["blob"]]
                                            ?: error("Dokument fehlt in Sicherung.")))
                        )
                    else it
                }
            for (e in mapped.filter { !it.deleted }) Rules.validate(e, mapped)
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
                val s = o.getJSONObject("settings")
                db.execSQL("DELETE FROM settings")
                for (k in s.keys()) db.execSQL(
                    "INSERT INTO settings VALUES(?,?)",
                    arrayOf(k, s.getString(k)),
                )
                audit("Fachdaten-Sicherung wiederhergestellt")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            newFiles.forEach { it.delete() }
            throw e
        }
    }
}
