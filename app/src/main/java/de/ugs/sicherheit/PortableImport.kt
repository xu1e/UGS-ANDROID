package de.ugs.sicherheit

import android.content.Context
import android.database.Cursor
import java.io.File
import java.io.InputStream
import java.net.URLConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Übernimmt ein portables UGS-Archiv (".ugsarchive") vom Mac oder iPhone.
 * Die Tabellen der Mac-/iPhone-Datenbank werden in Android-Datensätze übersetzt und
 * ergänzen den vorhandenen Bestand; Mitarbeiter mit bekannter Bewacher-ID werden
 * zusammengeführt, gleiche Dokumente nicht doppelt abgelegt. Nichts wird gelöscht.
 */
object PortableImport {
    data class Result(val records: Int, val documents: Int, val photos: Int, val skipped: List<String>, val info: PortableArchive.Info) {
        val summary
            get() =
                "Übernommen: $records Datensätze, $documents Dokumente, $photos Fotos" +
                    if (skipped.isEmpty()) "." else " · ${skipped.size} übersprungen (Einzelheiten im Protokoll)."
    }

    /** Wie das Archiv beschrieben ist, bevor das Passwort eingegeben wird. */
    fun info(input: InputStream): PortableArchive.Info = input.use { PortableArchive.info(PortableArchive.readHeader(java.io.DataInputStream(it)).second) }

    private fun Cursor.s(name: String): String {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) "" else getString(i).orEmpty().trim()
    }

    private fun Cursor.d(name: String): Double {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) 0.0 else getDouble(i)
    }

    private fun Cursor.l(name: String): Long {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    private fun SQLiteDatabase.rows(sql: String, each: (Cursor) -> Unit) {
        val exists = rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(sql.substringAfter(" FROM ").substringBefore(" ").trim())).use { it.moveToFirst() }
        if (!exists) return
        rawQuery(sql, null).use { c -> while (c.moveToNext()) each(c) }
    }

    private fun num(v: Double) = decimal(v).replace(".", "")

    private val isoDate = Regex("\\d{4}-\\d{2}-\\d{2}")

    private fun day(v: String) = v.take(10).takeIf { it.matches(isoDate) }.orEmpty()

    private fun clock(v: String) = Regex("(\\d{1,2}):(\\d{2})").find(v)?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }.orEmpty()

    /** Auswahlfelder auf gültige Werte bringen; ungültige E-Mail in die Notizen. */
    fun sanitize(e: Entry): Entry {
        val schema = schemas[e.kind].orEmpty()
        var f = e.fields.filterValues { it.isNotBlank() }
        for (field in schema) {
            val v = f[field.key] ?: continue
            if (field.input == Input.CHOICE && v !in field.options) f = f + (field.key to field.initial)
        }
        if (e.kind == Kind.WORKER && f["email"]?.let { !Mail.isAddress(it) } == true) {
            f = f - "email" + ("notes" to listOfNotNull(f["notes"], "E-Mail laut Mac/iPhone: ${e["email"]}").joinToString("\n"))
        }
        return e.copy(fields = f)
    }

    /** Ersetzt Mitarbeiter-Kennungen im Dienstplan (worker_id) durch die neuen Android-Kennungen. */
    fun remapWorkers(json: String, map: Map<Long, String>): String {
        fun walk(v: Any?): Any? =
            when (v) {
                is JSONObject -> {
                    v.keys().asSequence().toList().forEach { k ->
                        val x = v.get(k)
                        if (k == "worker_id") {
                            val id = when (x) { is Number -> x.toLong(); is String -> x.toLongOrNull(); else -> null }
                            v.put(k, id?.let { map[it] } ?: if (x is String && map.containsValue(x)) x else "")
                        } else walk(x)
                    }
                    v
                }
                is JSONArray -> {
                    for (i in 0 until v.length()) walk(v.get(i))
                    v
                }
                else -> v
            }
        return runCatching { walk(JSONObject(json)).toString() }.getOrDefault(json)
    }

    fun run(c: Context, vm: UGSViewModel, open: () -> InputStream, password: String): Result {
        require(password.length >= PortableArchive.MIN_PASSWORD) { "Das Backup-Passwort hat mindestens ${PortableArchive.MIN_PASSWORD} Zeichen." }
        val repo = vm.repo
        val staging = File(c.noBackupFilesDir, "portable-import").apply { deleteRecursively(); mkdirs() }
        val databaseFile = File(staging, "portable.sqlite3")
        try {
            open().use { stream ->
                val reader = PortableArchive.Reader(stream, password)
                var manifestSeen = false
                var dbSeen = false
                var documents: Map<String, Entry> = emptyMap()
                var workerMap: Map<Long, String> = emptyMap()
                var records = 0
                var docs = 0
                var photos = 0
                val skipped = mutableListOf<String>()
                while (true) {
                    val entry = reader.next() ?: break
                    when {
                        entry.name == PortableArchive.MANIFEST -> {
                            runCatching { JSONObject(String(reader.readBody(1 shl 20))) }.getOrElse { throw PortableArchive.error("Ungültiges Archivverzeichnis.") }
                            manifestSeen = true
                        }
                        entry.name == PortableArchive.DATABASE -> {
                            // Die Datenbank bleibt auf dem Datenträger mit dem Archivschlüssel verschlüsselt.
                            databaseFile.outputStream().use { reader.copyBody(it) }
                            val hex = reader.databaseKey.joinToString("") { "%02x".format(it) }
                            val db =
                                runCatching { SQLiteDatabase.openDatabase(databaseFile.path, "x'$hex'".toByteArray(), null, SQLiteDatabase.OPEN_READONLY, null, null) }
                                    .getOrElse { throw PortableArchive.error("Die Datenbank im Archiv konnte nicht entschlüsselt werden. Passwort oder Datei stimmen nicht.") }
                            db.use {
                                it.rawQuery("SELECT count(*) FROM sqlite_master", null).use { q -> q.moveToFirst() }
                                val converted = convert(vm, it)
                                val (saved, errors) = repo.importRecords(converted.records, "Portables Archiv (${reader.info.platform}) übernommen")
                                records = saved.size
                                skipped += errors
                                val savedIds = saved.map { s -> s.id }.toSet()
                                workerMap = converted.workers.filterValues { id -> id in savedIds || vm.entries.any { w -> w.id == id } }
                                documents = converted.documents.filterValues { d -> d["workerId"] in workerMap.values }
                                for ((workerId, png) in converted.photos) {
                                    val id = workerMap[workerId] ?: continue
                                    if (runCatching { repo.setPhoto(id, png) }.isSuccess) photos++
                                }
                            }
                            dbSeen = true
                            databaseFile.delete()
                        }
                        entry.name.startsWith(PortableArchive.DOCUMENTS) -> {
                            val relative = entry.name.removePrefix(PortableArchive.DOCUMENTS)
                            if (relative.isEmpty() || relative.contains("..") || relative.startsWith("/")) throw PortableArchive.error("Das Archiv enthält einen ungültigen Dateipfad und wurde nicht übernommen.")
                            val doc = documents[relative] ?: documents.entries.firstOrNull { (path, _) -> path.endsWith("/$relative") || relative.endsWith("/$path") }?.value
                            if (doc == null) continue
                            if (entry.size > FileLimits.FILE_BYTES) {
                                skipped += "Dokument „${doc["title"]}“: größer als ${FileLimits.FILE_LABEL}."
                                continue
                            }
                            runCatching { repo.importDocument(sanitize(doc), reader.body(), doc["mime"].ifBlank { URLConnection.guessContentTypeFromName(doc["title"]) ?: "application/octet-stream" }) }
                                .onSuccess { docs++ }
                                .onFailure { skipped += "Dokument „${doc["title"]}“: ${it.message}" }
                        }
                        else -> Unit
                    }
                }
                if (!dbSeen) throw PortableArchive.error("Das Archiv enthält keine Datenbank und kann nicht übernommen werden.")
                if (!manifestSeen) skipped += "Archivverzeichnis fehlte."
                reader.close()
                return Result(records, docs, photos, skipped, reader.info)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    class Converted(val records: List<Entry>, val workers: Map<Long, String>, val documents: Map<String, Entry>, val photos: Map<Long, ByteArray>)

    /** Tabellen der Mac-/iPhone-Datenbank → Android-Datensätze. */
    fun convert(vm: UGSViewModel, db: SQLiteDatabase): Converted {
        val existing = vm.rows(Kind.WORKER)
        val out = mutableListOf<Entry>()
        val workers = mutableMapOf<Long, String>()
        db.rows("SELECT * FROM workers ORDER BY id") { c ->
            val bewacher = c.s("personnel_number")
            val known = existing.firstOrNull { bewacher.isNotBlank() && it["bewacherId"].trim().equals(bewacher, true) }
            if (known != null) {
                workers[c.l("id")] = known.id
                return@rows
            }
            val profile = runCatching { JSONObject(c.s("document_profile").ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val name = c.s("name")
            val first = c.s("first_name").ifBlank { name.substringBefore(' ', "") }
            val last = c.s("last_name").ifBlank { if (first.isBlank()) name else name.substringAfter(' ') }
            val staff = c.s("staff_number").takeIf { it.matches(Regex("[1-9]{3}")) && existing.none { w -> w["personnelNumber"] == it } && out.none { w -> w["personnelNumber"] == it } }.orEmpty()
            val gender = when (profile.optString("gender").lowercase()) { "männlich", "m", "herr" -> "männlich"; "weiblich", "w", "frau" -> "weiblich"; "divers" -> "divers"; else -> when (profile.optString("salutation")) { "Herr" -> "männlich"; "Frau" -> "weiblich"; else -> "" } }
            val e =
                Entry(
                    kind = Kind.WORKER,
                    fields =
                        mapOf(
                            "bewacherId" to bewacher,
                            "personnelNumber" to staff,
                            "firstName" to first,
                            "lastName" to last,
                            "gender" to gender,
                            "birthDate" to day(c.s("birth_date")),
                            "birthPlace" to profile.optString("birth_place"),
                            "nationality" to c.s("nationality"),
                            "identityNumber" to c.s("identity_number"),
                            "street" to c.s("street"),
                            "postalCode" to c.s("postal_code"),
                            "city" to c.s("city"),
                            "email" to c.s("email"),
                            "phone" to c.s("mobile"),
                            "department" to c.s("department"),
                            "position" to c.s("position"),
                            "location" to c.s("location"),
                            "object" to c.s("object"),
                            "contractType" to c.s("contract_type"),
                            "startDate" to day(c.s("hire_date")),
                            "status" to c.s("status").ifBlank { "Aktiv" },
                            "baseSalary" to c.d("base_salary").takeIf { it != 0.0 }?.let { num(it) }.orEmpty(),
                            "allowances" to c.d("allowances").takeIf { it != 0.0 }?.let { num(it) }.orEmpty(),
                            "notes" to c.s("notes"),
                        ),
                )
            workers[c.l("id")] = e.id
            out += sanitize(e)
        }
        fun w(c: Cursor) = workers[c.l("worker_id")]
        db.rows("SELECT * FROM absences") { c ->
            val id = w(c) ?: return@rows
            out += sanitize(Entry(kind = Kind.ABSENCE, fields = mapOf("workerId" to id, "type" to c.s("type"), "date" to day(c.s("starts_on")), "endDate" to day(c.s("ends_on")), "status" to c.s("status"), "notes" to c.s("notes"))))
        }
        db.rows("SELECT * FROM time_entries") { c ->
            val id = w(c) ?: return@rows
            out += sanitize(Entry(kind = Kind.TIME, fields = mapOf("workerId" to id, "date" to day(c.s("work_date")), "startTime" to clock(c.s("started_at")), "endTime" to clock(c.s("ended_at")), "status" to "Erfasst", "notes" to c.s("notes"))))
        }
        db.rows("SELECT * FROM payrolls") { c ->
            val id = w(c) ?: return@rows
            val base = c.d("base_salary")
            out +=
                sanitize(
                    Entry(
                        kind = Kind.PAYROLL,
                        fields =
                            mapOf(
                                "workerId" to id,
                                "date" to "${c.s("month").take(7)}-01",
                                "hours" to "0",
                                "rate" to "0",
                                "baseSalary" to num(base),
                                "allowances" to num(c.d("allowances")),
                                "bonus" to num(c.d("bonus")),
                                "deductions" to num(c.d("deductions")),
                                "paymentStatus" to c.s("payment_status"),
                                "notes" to listOf("Grundgehalt laut Mac/iPhone: ${money(base)}", c.s("notes")).filter { it.isNotBlank() }.joinToString("\n"),
                            ),
                    )
                )
        }
        db.rows("SELECT * FROM worker_registrations") { c ->
            val id = w(c) ?: return@rows
            out +=
                sanitize(
                    Entry(
                        kind = Kind.STATUS,
                        fields = mapOf("workerId" to id, "type" to c.s("kind"), "date" to day(c.s("starts_on")), "endDate" to day(c.s("ends_on")), "quantity" to c.d("quantity").takeIf { it != 0.0 }?.let { num(it) }.orEmpty(), "unit" to c.s("unit"), "notes" to c.s("notes")),
                    )
                )
        }
        db.rows("SELECT * FROM vacation_accounts") { c ->
            val id = w(c) ?: return@rows
            val name = out.firstOrNull { it.id == id }?.title ?: vm.entries.firstOrNull { it.id == id }?.title.orEmpty()
            out +=
                Entry(
                    kind = Kind.VACATION_ACCOUNT,
                    fields = mapOf("workerId" to id, "year" to c.l("year").toString(), "entitlement" to decimal(c.d("entitlement"), 1), "carryOver" to decimal(c.d("carry_over"), 1), "adjustment" to decimal(c.d("adjustment"), 1), "notes" to c.s("notes"), "title" to "Urlaubskonto $name ${c.l("year")}"),
                )
        }
        db.rows("SELECT * FROM company_expenses") { c ->
            out += sanitize(Entry(kind = Kind.EXPENSE, fields = mapOf("title" to c.s("title"), "category" to c.s("category"), "amount" to num(c.d("amount")), "date" to day(c.s("expense_date")), "notes" to c.s("notes"))))
        }
        db.rows("SELECT * FROM todos") { c ->
            val priority = when (c.s("priority")) { "high" -> "Hoch"; "low" -> "Niedrig"; else -> "Normal" }
            out +=
                sanitize(
                    Entry(
                        kind = Kind.TODO,
                        fields = mapOf("title" to c.s("title"), "notes" to c.s("notes"), "date" to day(c.s("due_on")), "dueTime" to if (c.s("kind") == "appointment") clock(c.s("due_time")) else "", "todoKind" to if (c.s("kind") == "appointment") "Termin" else "Aufgabe", "priority" to priority, "status" to if (c.l("done") != 0L) "Erledigt" else "Offen"),
                    )
                )
        }
        val sites = mutableMapOf<Long, String>()
        val existingSites = vm.rows(Kind.SITE)
        db.rows("SELECT * FROM duty_sites") { c ->
            val name = c.s("name")
            val known = existingSites.firstOrNull { it["title"].equals(name, true) }
            if (known != null) sites[c.l("id")] = known.id
            else {
                val e = Entry(kind = Kind.SITE, fields = mapOf("title" to name))
                sites[c.l("id")] = e.id
                out += e
            }
        }
        db.rows("SELECT * FROM duty_plans") { c ->
            val site = sites[c.l("site_id")] ?: return@rows
            val month = c.s("month").take(7)
            if (vm.rows(Kind.DUTY_PLAN).any { it["siteId"] == site && it["month"] == month }) return@rows
            out += Entry(kind = Kind.DUTY_PLAN, fields = mapOf("siteId" to site, "month" to month, "title" to "Dienstplan $month", "payload" to remapWorkers(c.s("payload"), workers)))
        }
        // Firmenangaben und Auswahllisten nur ergänzen, nie überschreiben.
        val settings = mutableMapOf<String, String>()
        db.rows("SELECT * FROM company_profiles ORDER BY id LIMIT 1") { c ->
            for ((k, col) in listOf("name" to "name", "street" to "street", "postalCode" to "postal_code", "city" to "city", "representative" to "representative", "phone" to "phone"))
                if (vm.company[k].isNullOrBlank() || k == "name" && vm.company[k] == "UGS Sicherheit GmbH" && c.s(col).isNotBlank()) c.s(col).takeIf { it.isNotBlank() }?.let { settings[k] = it }
        }
        val lookups = Lookups.all(vm.company).mapValues { it.value.toMutableList() }.toMutableMap()
        var lookupsChanged = false
        db.rows("SELECT * FROM lookup_options ORDER BY group_name, sort_order") { c ->
            val list = lookups.getOrPut(c.s("group_name")) { mutableListOf() }
            val v = c.s("value")
            if (v.isNotBlank() && list.none { it.equals(v, true) }) {
                list += v
                lookupsChanged = true
            }
        }
        if (lookupsChanged) settings["lookups"] = Lookups.encode(lookups)
        if (settings.isNotEmpty()) vm.repo.settings(settings)
        val documents = mutableMapOf<String, Entry>()
        db.rows("SELECT * FROM worker_documents") { c ->
            val id = w(c) ?: return@rows
            documents[c.s("path")] =
                Entry(
                    kind = Kind.DOCUMENT,
                    fields =
                        mapOf(
                            "title" to c.s("original_name").ifBlank { c.s("category") },
                            "workerId" to id,
                            "category" to DocumentCategories.canonical(c.s("category")),
                            "documentNumber" to c.s("document_number"),
                            "date" to day(c.s("issued_on")),
                            "expiryDate" to day(c.s("expires_on")),
                            "extendedCertificate" to (c.l("is_extended_certificate") != 0L).toString(),
                            "renewalRequestedOn" to day(c.s("renewal_requested_on")),
                            "archived" to (c.l("is_archived") != 0L).toString(),
                            "notes" to c.s("notes"),
                            "mime" to c.s("mime_type"),
                        ),
                )
        }
        val photos = mutableMapOf<Long, ByteArray>()
        db.rows("SELECT * FROM worker_photos") { c ->
            val i = c.getColumnIndex("png_data")
            if (i >= 0 && !c.isNull(i)) c.getBlob(i)?.takeIf { it.size in 1..524288 }?.let { photos[c.l("worker_id")] = it }
        }
        return Converted(out, workers, documents, photos)
    }
}
