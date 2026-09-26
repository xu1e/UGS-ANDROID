package de.ugs.sicherheit

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sprachassistent „Alas“ (iOS Build 36 / Mac 5.9.63): versteht, was gesagt wurde –
 * Arabisch oder Deutsch. Reine Textlogik ohne Android-Klassen, damit sie testbar ist.
 */
sealed class VoiceIntent {
    data class Todos(val tomorrow: Boolean) : VoiceIntent()

    data class AddTodo(val title: String, val tomorrow: Boolean) : VoiceIntent()

    data class Expenses(val previousMonth: Boolean, val category: String?) : VoiceIntent()

    data object MailSummary : VoiceIntent()

    data object MailReadLatest : VoiceIntent()

    data object Time : VoiceIntent()

    data object Help : VoiceIntent()

    data object Stop : VoiceIntent()
}

object VoiceParser {
    val stopWords = listOf("خلاص", "وقف", "اسكت", "بس", "شكرا", "الغاء", "الغي", "انسى", "لا شي", "stop", "stopp", "danke", "abbrechen", "ruhe", "fertig", "vergiss es", "egal")
    val helpWords = listOf("شو بتعرف", "شو تعرف", "شو بتقدر", "شو تقدر", "ايش تقدر", "مساعده", "ساعدني", "شو فيك تعمل", "hilfe", "was kannst du", "wie funktioniert")
    val addTodoTriggers =
        listOf(
            "اضف مهمه", "ضيف مهمه", "زيد مهمه", "سجل مهمه", "اكتب مهمه", "حط مهمه", "مهمه جديده", "اضافه مهمه", "اضف تذكير", "ضيف تذكير", "ذكرني", "فكرني",
            "neue aufgabe", "aufgabe hinzufugen", "aufgabe anlegen", "aufgabe notieren", "notiere", "notier", "erinnere mich", "erinner mich", "merke dir", "merk dir", "schreib auf", "neue erinnerung",
        )
    val mailWords =
        listOf(
            "ايميل", "ايميلات", "الايميل", "الايميلات", "بريد", "البريد", "رسائل", "رسايل", "الرسائل", "الرسايل", "رساله", "الرساله", "مسجات", "ميلات", "الميل", "الوارد", "انبوكس",
            "mail", "mails", "e mail", "email", "e mails", "emails", "posteingang", "nachricht", "nachrichten",
        )
    val readWords = listOf("اقرا", "اقرالي", "اقراء", "قراه", "اسمعني", "lies", "lese", "vorlesen", "vor lesen")
    val latestWords = listOf("اخر", "الاخير", "الاخيره", "اخير", "اول", "الاول", "الاولى", "اولى", "letzte", "letzten", "neueste", "neuesten", "erste", "ersten")
    val expenseWords = listOf("مصاريف", "مصروف", "مصروفات", "صرفنا", "صرفت", "نفقات", "دفعنا", "تكاليف", "مدفوعات", "المصاريف", "ausgaben", "ausgegeben", "kosten", "bezahlt")
    val previousMonthWords =
        listOf("الشهر الماضي", "الشهر اللي فات", "الشهر يلي فات", "الشهر السابق", "الشهر الفايت", "الشهر الفات", "الشهر قبل", "letzten monat", "letzter monat", "vormonat", "vorigen monat", "vergangenen monat")
    val todoWords =
        listOf(
            "مهام", "مهامي", "المهام", "مهمه", "شو عندي", "ايش عندي", "شو في عندي", "شو عنا", "شو عندنا", "مواعيد", "موعد", "مواعيدي", "برنامج", "برنامجي", "جدول", "اجندا", "تودو", "تو دو", "شغلات",
            "aufgaben", "aufgabe", "to do", "todo", "todos", "termine", "termin", "was steht", "was habe ich", "was hab ich", "was liegt an", "tagesplan",
        )
    val tomorrowWords = listOf("بكره", "بكرا", "غدا", "الغد", "بعد بكره", "morgen")
    val timeWords = listOf("كم الساعه", "الساعه كم", "قديش الساعه", "ايش الساعه", "شو الساعه", "wie spat", "uhrzeit", "wieviel uhr", "wie viel uhr")
    val dayFillerWords = setOf("بكره", "بكرا", "غدا", "اليوم", "morgen", "heute", "fur", "لل", "ل")
    val leadingFillers = setOf("ان", "انو", "انه", "بان", "انني", "اني", "dass", "daran", "an", "zu", "bitte", "من", "فضلك")
    val nameWords = setOf("علاس", "علوس", "عليس", "يا", "alas", "alus", "alis", "hey")
    val categoryWords =
        listOf(
            listOf("فندق", "فنادق", "اوتيل", "hotel", "ubernachtung") to "Hotel / Übernachtung",
            listOf("سياره", "سيارات", "ليزنغ", "ليسنغ", "leasing", "auto", "fahrzeug") to "Auto / Leasing",
            listOf("بنزين", "وقود", "مازوت", "ديزل", "مواصلات", "tanken", "benzin", "diesel", "sprit", "fahrtkosten") to "Tanken / Fahrtkosten",
            listOf("ملابس", "لباس", "يونيفورم", "كلير", "kleidung", "dienstkleidung", "uniform") to "Dienstkleidung",
            listOf("معدات", "تجهيزات", "ausrustung") to "Ausrüstung",
            listOf("هاتف", "تلفون", "موبايل", "انترنت", "telefon", "handy", "internet") to "Telefon / Internet",
            listOf("تامين", "versicherung") to "Versicherung",
            listOf("مكتب", "قرطاسيه", "buro", "material") to "Büro / Material",
            listOf("ايجار", "اجار", "miete") to "Miete / Räume",
            listOf("دورات", "دوره", "تدريب", "كورس", "schulung", "weiterbildung", "kurs") to "Weiterbildung / Schulung",
            listOf("تسويق", "اعلان", "اعلانات", "marketing", "werbung") to "Marketing",
            listOf("ضرائب", "ضرايب", "محاسب", "استشاره", "مستشار", "steuer", "steuerberater", "beratung") to "Beratung / Steuer",
            listOf("برامج", "سوفتوير", "اشتراك", "اشتراكات", "software", "lizenz", "lizenzen") to "IT / Software",
            listOf("كمبيوتر", "لابتوب", "اجهزه", "هاردوير", "hardware", "computer", "laptop") to "Hardware",
        )

    /** Klein, ohne Akzente/Taschkil, arabische Alef-/Ya-/Ta-Marbuta-Formen vereinheitlicht. */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace("ß", "ss")
        val out = StringBuilder()
        for (ch in folded) {
            val c = ch.code
            when {
                Character.getType(ch) == Character.NON_SPACING_MARK.toInt() && c !in 0x0600..0x06FF -> continue
                c in 0x064B..0x065F || c == 0x0670 || c == 0x0640 -> continue
                c == 0x0622 || c == 0x0623 || c == 0x0625 || c == 0x0671 -> out.append('ا')
                c == 0x0649 -> out.append('ي')
                c == 0x0629 -> out.append('ه')
                else -> out.append(ch)
            }
        }
        // NFD zerlegt آ/أ/إ in ا + Zeichen; die Zeichen wurden oben entfernt.
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC)
    }

    fun words(text: String): List<String> = normalize(text).split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }

    private fun padded(words: List<String>) = " " + words.joinToString(" ") + " "

    private fun arabic(s: String) = s.any { it.code in 0x0600..0x06FF }

    /** Ganze Wörter/Wortfolgen; arabische Einträge auch mit angehängtem و/ب/ل/ال davor. */
    fun containsAny(line: String, keys: List<String>): Boolean =
        keys.any { key ->
            val k = padded(words(key))
            if (line.contains(k)) return@any true
            if (!arabic(key)) return@any false
            val bare = k.drop(1)
            listOf("و", "ب", "ل", "ال", "وال", "بال", "لل", "ف").any { line.contains(" $it$bare") }
        }

    fun mentionsTomorrow(line: String) = containsAny(line.replace(" guten morgen ", " "), tomorrowWords)

    fun category(line: String) = categoryWords.firstOrNull { containsAny(line, it.first) }?.second

    fun parse(text: String): VoiceIntent? {
        val w = words(text).filterNot { it in nameWords }
        if (w.isEmpty()) return null
        val line = padded(w)
        if (w.size <= 3 && containsAny(line, stopWords)) return VoiceIntent.Stop
        if (containsAny(line, helpWords)) return VoiceIntent.Help
        parseAddTodo(text)?.let { return it }
        if (containsAny(line, mailWords)) return if (containsAny(line, readWords) && containsAny(line, latestWords)) VoiceIntent.MailReadLatest else VoiceIntent.MailSummary
        if (containsAny(line, expenseWords)) return VoiceIntent.Expenses(containsAny(line, previousMonthWords), category(line))
        if (containsAny(line, timeWords)) return VoiceIntent.Time
        if (containsAny(line, todoWords)) return VoiceIntent.Todos(mentionsTomorrow(line))
        return null
    }

    /** Neue Aufgabe: Titel = die Originalwörter nach dem Auslöser (ohne Datum-/Füllwörter). */
    fun parseAddTodo(original: String): VoiceIntent.AddTodo? {
        val originalWords = original.split(Regex("\\s+")).map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val normalized = originalWords.map { words(it).joinToString(" ") }
        for (trigger in addTodoTriggers) {
            val t = words(trigger)
            if (t.isEmpty() || normalized.size < t.size) continue
            for (start in 0..normalized.size - t.size) {
                val slice = normalized.subList(start, start + t.size)
                val match = slice.zip(t).all { (spoken, wanted) -> spoken == wanted || (wanted.length > 2 && spoken.endsWith(wanted) && spoken.length <= wanted.length + 3) }
                if (!match) continue
                val rest = originalWords.drop(start + t.size)
                val restNorm = normalized.drop(start + t.size)
                val tomorrow = mentionsTomorrow(padded(restNorm))
                val title = mutableListOf<String>()
                for ((word, key) in rest.zip(restNorm)) {
                    if (title.isEmpty() && key in leadingFillers) continue
                    if (key in dayFillerWords) continue
                    title += word
                }
                val text = title.joinToString(" ").trim()
                if (text.isEmpty()) return null
                return VoiceIntent.AddTodo(text.take(180), tomorrow)
            }
        }
        return null
    }

    fun isArabic(text: String) = arabic(text)
}

/** Ein vorzulesender Satz; `arabic == null` = Sprache am Text erkennen. */
data class SpeechSegment(val text: String, val arabic: Boolean?)

/** Antworten aus den App-Daten. Gleiche Rechte wie die Seiten. */
object VoiceAnswers {
    fun say(ar: String, de: String, arabic: Boolean) = SpeechSegment(if (arabic) ar else de, arabic)

    fun auto(text: String) = SpeechSegment(text, null)

    fun arabicCount(count: Int, one: String, two: String, many: String) = when (count) { 1 -> one; 2 -> two; else -> "$count $many" }

    fun arabicEuro(amount: Double): String {
        val cents = Math.round(amount * 100)
        val euros = cents / 100
        val rest = cents % 100
        return if (rest == 0L) "$euros يورو" else "$euros يورو و$rest سنت"
    }

    /** Mailtext zum Vorlesen: ohne Zitate und Links, höchstens ~500 Zeichen. */
    fun speakableBody(body: String): String {
        var text = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(">") }.joinToString(" ")
        text = text.replace(Regex("https?://\\S+"), "").replace(Regex("\\s+"), " ")
        if (text.length > 500) {
            val cut = text.take(500)
            val end = cut.indexOfLast { it in ".!?؟" }
            text = if (end > 0) cut.take(end + 1) else cut
        }
        return text.trim()
    }

    val arabicCategoryNames =
        mapOf(
            "IT / Software" to "البرامج والتقنية", "Hardware" to "الأجهزة", "Hotel / Übernachtung" to "الفنادق", "Auto / Leasing" to "السيارات",
            "Tanken / Fahrtkosten" to "الوقود والتنقل", "Dienstkleidung" to "ملابس العمل", "Ausrüstung" to "المعدات", "Telefon / Internet" to "الهاتف والإنترنت",
            "Versicherung" to "التأمين", "Büro / Material" to "المكتب", "Miete / Räume" to "الإيجار", "Weiterbildung / Schulung" to "التدريب", "Marketing" to "التسويق",
            "Beratung / Steuer" to "الاستشارات والضرائب", "Sonstiges" to "أخرى",
        )
    private val aliases = mapOf("IT / Software" to listOf("Software", "IT"), "Telefon / Internet" to listOf("Telefon"), "Büro / Material" to listOf("Büro"))

    private fun matches(item: String, wanted: String) = item == wanted || aliases[wanted].orEmpty().contains(item)

    private val noPermission = { a: Boolean -> say("ما عندك صلاحية لهذا.", "Dafür fehlt dir die Berechtigung.", a) }

    fun help(a: Boolean) =
        listOf(
            say(
                "قول مثلاً: شو عندي اليوم؟ ضيف مهمة اتصل بالزبون بكرا. كم صرفنا هالشهر؟ في ايميلات جديدة؟ اقرأ آخر رسالة.",
                "Sag zum Beispiel: Was steht heute an? Neue Aufgabe Kunde anrufen morgen. Ausgaben diesen Monat. Neue Mails? Lies die letzte Mail vor.",
                a,
            )
        )

    fun time(a: Boolean, now: LocalTime = LocalTime.now()): List<SpeechSegment> {
        val t = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        return listOf(say("الساعة الآن $t.", "Es ist $t Uhr.", a))
    }

    fun todos(todos: List<Entry>, tomorrow: Boolean, a: Boolean, permitted: Boolean, today: LocalDate = LocalDate.now()): List<SpeechSegment> {
        if (!permitted) return listOf(noPermission(a))
        val target = (if (tomorrow) today.plusDays(1) else today).toString()
        val open = todos.filterNot { TodoModel.done(it) }
        val due = open.filter { it["date"] == target }.sortedBy { if (TodoModel.appointment(it)) it["dueTime"].ifBlank { "99:99" } else "99:99" }
        val overdue = if (!tomorrow) open.filter { it["date"].isNotEmpty() && it["date"] < today.toString() } else emptyList()
        val dayAR = if (tomorrow) "بكرا" else "اليوم"
        val dayDE = if (tomorrow) "morgen" else "heute"
        if (due.isEmpty() && overdue.isEmpty()) return listOf(say("ما عندك شي $dayAR.", "Für $dayDE steht nichts an.", a))
        val out = mutableListOf<SpeechSegment>()
        if (due.isEmpty()) out += say("ما في شي جديد $dayAR.", "Für $dayDE steht nichts Neues an.", a)
        else {
            out += say("عندك $dayAR ${arabicCount(due.size, "مهمة واحدة", "مهمتين", "مهام")}:", "${dayDE.replaceFirstChar { it.uppercase() }} ${if (due.size == 1) "steht ein Eintrag" else "stehen ${due.size} Einträge"} an:", a)
            for (item in due.take(6)) {
                if (TodoModel.appointment(item) && item["dueTime"].isNotBlank()) out += say("موعد الساعة ${item["dueTime"]}:", "Termin um ${item["dueTime"]} Uhr:", a)
                out += auto(item["title"])
            }
            if (due.size > 6) out += say("و${due.size - 6} غيرها.", "und ${due.size - 6} weitere.", a)
        }
        if (overdue.isNotEmpty())
            out += say("وعندك ${arabicCount(overdue.size, "مهمة متأخرة", "مهمتين متأخرتين", "مهام متأخرة")}.", "Außerdem ${if (overdue.size == 1) "ist eine Aufgabe" else "sind ${overdue.size} Aufgaben"} überfällig.", a)
        return out
    }

    fun newTodo(title: String, tomorrow: Boolean, today: LocalDate = LocalDate.now()) =
        Entry(kind = Kind.TODO, fields = mapOf("title" to title, "todoKind" to "Aufgabe", "date" to (if (tomorrow) today.plusDays(1) else today).toString(), "priority" to "Normal", "status" to "Offen", "notes" to "Per Sprachassistent angelegt"))

    fun added(title: String, tomorrow: Boolean, a: Boolean) =
        listOf(say("تمام، سجّلت المهمة:", "Erledigt, neue Aufgabe:", a), auto(title), say(if (tomorrow) "لبكرا." else "لليوم.", if (tomorrow) "für morgen." else "für heute.", a))

    fun expenses(items: List<Entry>, previous: Boolean, category: String?, a: Boolean, permitted: Boolean, now: YearMonth = YearMonth.now()): List<SpeechSegment> {
        if (!permitted) return listOf(noPermission(a))
        val month = if (previous) now.minusMonths(1) else now
        val prefix = month.toString()
        var list = items.filter { it["date"].startsWith(prefix) && parseAmount(it["amount"]) != null }
        if (category != null) list = list.filter { matches(it["category"], category) }
        val total = list.sumOf { parseAmount(it["amount"]) ?: 0.0 }
        val monthDE = DateText.monthName(month.monthValue)
        val monthAR = listOf("كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران", "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول")[month.monthValue - 1]
        val catAR = category?.let { " على " + (arabicCategoryNames[it] ?: it) }.orEmpty()
        val catDE = category?.let { " für $it" }.orEmpty()
        if (list.isEmpty()) return listOf(say("ما في مصاريف مسجّلة$catAR لشهر $monthAR.", "Im $monthDE sind keine Ausgaben$catDE erfasst.", a))
        val out =
            mutableListOf(
                say(
                    "مصاريف شهر $monthAR$catAR: ${arabicEuro(total)} في ${arabicCount(list.size, "مصروف واحد", "مصروفين", "مصروف")}.",
                    "Ausgaben im $monthDE$catDE: ${money(total)} in ${list.size} ${if (list.size == 1) "Buchung" else "Buchungen"}.",
                    a,
                )
            )
        if (category == null) {
            val byCategory = list.groupBy { it["category"] }.mapValues { (_, v) -> v.sumOf { parseAmount(it["amount"]) ?: 0.0 } }.entries.sortedByDescending { it.value }
            if (byCategory.size > 1) {
                val top = byCategory.first()
                out += say("أكبرها ${arabicCategoryNames[top.key] ?: top.key}: ${arabicEuro(top.value)}.", "Größter Posten: ${top.key} mit ${money(top.value)}.", a)
            }
        }
        return out
    }

    fun mails(unread: List<InboxItem>, latest: InboxItem?, readLatest: Boolean, a: Boolean): List<SpeechSegment> {
        fun sender(m: InboxItem) = m.sender.trim().ifEmpty { m.senderAddress }
        if (readLatest) {
            val m = unread.firstOrNull() ?: latest ?: return listOf(say("ما في رسائل.", "Keine Nachrichten vorhanden.", a))
            val out = mutableListOf(say("آخر رسالة من", "Die neueste Nachricht ist von", a), auto(sender(m)))
            if (m.subject.isNotBlank()) out += listOf(say("الموضوع:", "Betreff:", a), auto(m.subject))
            speakableBody(m.body).takeIf { it.isNotEmpty() }?.let { out += auto(it) }
            return out
        }
        if (unread.isEmpty()) return listOf(say("ما عندك رسائل جديدة.", "Du hast keine ungelesenen E-Mails.", a))
        val out = mutableListOf(say("عندك ${arabicCount(unread.size, "رسالة جديدة", "رسالتين جديدتين", "رسائل جديدة")}.", if (unread.size == 1) "Du hast eine ungelesene E-Mail." else "Du hast ${unread.size} ungelesene E-Mails.", a))
        for (m in unread.take(3)) {
            out += say("من", "Von", a)
            out += auto(sender(m))
            if (m.subject.isNotBlank()) out += auto(m.subject)
        }
        if (unread.size > 3) out += say("قول: اقرأ آخر رسالة، لأقرأها لك.", "Sag „lies die letzte Mail vor“, dann lese ich sie vor.", a)
        return out
    }
}
