package de.ugs.sicherheit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Laufzeit des Sprachassistenten „Alas“ auf Android. Statt des Weckwort-Modells
 * (Core ML, nur Apple) startet der Mikrofon-Knopf die Spracherkennung des Systems;
 * Verstehen und Antworten sind dieselben wie auf iPhone und Mac.
 */
object Voice {
    enum class Phase(val title: String) {
        OFF("Aus"),
        LISTENING("Ich höre …"),
        THINKING("Verstehe …"),
        SPEAKING("Antwortet"),
        FAILED("Fehler"),
    }

    var phase by mutableStateOf(Phase.OFF)
    var heard by mutableStateOf("")
    var answer by mutableStateOf("")
    var panel by mutableStateOf(false)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun available(c: Context) = SpeechRecognizer.isRecognitionAvailable(c)

    /** Persönliche Einstellungen (wie AppStorage auf dem iPhone, aber je Benutzer). */
    fun setting(vm: UGSViewModel, key: String, default: String) = vm.company[vm.repo.personalKey("voice.$key")] ?: default

    fun enabled(vm: UGSViewModel) = setting(vm, "enabled", "true") == "true"

    fun stop() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        if (phase != Phase.OFF) phase = Phase.OFF
        panel = false
    }

    fun toggle(vm: UGSViewModel) {
        if (phase == Phase.LISTENING || phase == Phase.SPEAKING) {
            stop()
            return
        }
        listen(vm)
    }

    private fun listen(vm: UGSViewModel) {
        val c = vm.app
        if (!available(c)) return fail("Auf diesem Gerät ist keine Spracherkennung verfügbar.")
        recognizer?.destroy()
        tts?.stop()
        val r = SpeechRecognizer.createSpeechRecognizer(c)
        recognizer = r
        heard = ""
        answer = ""
        panel = true
        phase = Phase.LISTENING
        val language = setting(vm, "language", "both")
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (language == "german") "de-DE" else "ar")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                if (language == "both" && Build.VERSION.SDK_INT >= 34) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("ar", "de-DE"))
                }
            }
        r.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    phase = Phase.THINKING
                }

                override fun onError(error: Int) {
                    recognizer = null
                    r.destroy()
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                            phase = Phase.OFF
                            answer = "Nichts verstanden. Bitte noch einmal tippen und sprechen."
                        }
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Mikrofonzugriff fehlt. Bitte in den Android-Einstellungen erlauben.")
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail("Die Spracherkennung braucht eine Netzverbindung oder das Offline-Sprachpaket.")
                        else -> fail("Spracherkennung fehlgeschlagen ($error).")
                    }
                }

                override fun onResults(results: Bundle?) {
                    recognizer = null
                    r.destroy()
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    handle(vm, texts)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { if (it.isNotBlank()) heard = it }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
        )
        r.startListening(intent)
    }

    private fun fail(message: String) {
        phase = Phase.FAILED
        answer = message
        panel = true
    }

    private fun handle(vm: UGSViewModel, texts: List<String>) {
        val text = texts.firstOrNull { VoiceParser.parse(it) != null } ?: texts.firstOrNull().orEmpty()
        heard = text
        val intent = VoiceParser.parse(text)
        val arabic = when (setting(vm, "language", "both")) { "arabic" -> true; "german" -> false; else -> VoiceParser.isArabic(text) }
        phase = Phase.THINKING
        vm.launch {
            val segments =
                if (intent == null) listOf(VoiceAnswers.say("ما فهمت. قول: شو بتعرف؟", "Das habe ich nicht verstanden. Sag „Hilfe“ für Beispiele.", arabic))
                else answerFor(vm, intent, arabic)
            answer = segments.joinToString(" ") { it.text }
            if (intent == VoiceIntent.Stop) {
                tts?.stop()
                phase = Phase.OFF
                return@launch
            }
            if (setting(vm, "speak", "true") == "true") speak(vm.app, segments, arabic) else phase = Phase.OFF
        }
    }

    private suspend fun answerFor(vm: UGSViewModel, intent: VoiceIntent, a: Boolean): List<SpeechSegment> =
        when (intent) {
            is VoiceIntent.Todos -> VoiceAnswers.todos(vm.rows(Kind.TODO), intent.tomorrow, a, vm.permitted("todo"))
            is VoiceIntent.AddTodo ->
                if (!vm.can(AccessAction.CREATE, "todo")) listOf(VoiceAnswers.say("ما عندك صلاحية لهذا.", "Dafür fehlt dir die Berechtigung.", a))
                else
                    runCatching {
                            withContext(Dispatchers.IO) { vm.repo.save(VoiceAnswers.newTodo(intent.title, intent.tomorrow)) }
                            vm.refresh()
                            VoiceAnswers.added(intent.title, intent.tomorrow, a)
                        }
                        .getOrElse { listOf(VoiceAnswers.say("ما قدرت أسجّل المهمة.", "Die Aufgabe konnte nicht gespeichert werden.", a), VoiceAnswers.auto(it.message.orEmpty())) }
            is VoiceIntent.Expenses -> VoiceAnswers.expenses(vm.rows(Kind.EXPENSE), intent.previousMonth, intent.category, a, vm.permitted("expenses"))
            VoiceIntent.MailSummary, VoiceIntent.MailReadLatest -> mails(vm, intent == VoiceIntent.MailReadLatest, a)
            VoiceIntent.Time -> VoiceAnswers.time(a)
            VoiceIntent.Help -> VoiceAnswers.help(a)
            VoiceIntent.Stop -> listOf(VoiceAnswers.say("حاضر.", "Okay.", a))
        }

    private suspend fun mails(vm: UGSViewModel, readLatest: Boolean, a: Boolean): List<SpeechSegment> {
        if (!vm.permitted("inbox")) return listOf(VoiceAnswers.say("ما عندك صلاحية لهذا.", "Dafür fehlt dir die Berechtigung.", a))
        val s = MailService.inbox(vm)
        if (!s.configured) return listOf(VoiceAnswers.say("البريد الوارد مو مضبوط. روح على الإعدادات، البريد، الوارد.", "Der Posteingang ist noch nicht eingerichtet: Einstellungen → E-Mail → Posteingang.", a))
        if (!vm.inboxBusy)
            runCatching {
                vm.inboxBusy = true
                withContext(Dispatchers.IO) { MailService.synchronize(vm, s, "INBOX") }
                vm.inboxRevision++
            }
        vm.inboxBusy = false
        val items = withContext(Dispatchers.IO) { vm.repo.inbox(s.accountId, "INBOX", 500).map { InboxItem.from(it) } }.sortedByDescending { it.date }
        return VoiceAnswers.mails(items.filter { it.unread }, items.firstOrNull(), readLatest, a)
    }

    private fun speak(c: Context, segments: List<SpeechSegment>, arabic: Boolean) {
        phase = Phase.SPEAKING
        fun run(engine: TextToSpeech) {
            engine.setOnUtteranceProgressListener(
                object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "last") phase = Phase.OFF
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        phase = Phase.OFF
                    }
                }
            )
            segments.forEachIndexed { i, s ->
                val ar = s.arabic ?: VoiceParser.isArabic(s.text)
                engine.language = if (ar) Locale("ar") else Locale.GERMANY
                engine.speak(s.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == segments.lastIndex) "last" else "s$i")
            }
        }
        val existing = tts
        if (existing != null && ttsReady) return run(existing)
        tts =
            TextToSpeech(c.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                val engine = tts
                if (ttsReady && engine != null) run(engine) else phase = Phase.OFF
            }
    }
}

/** Mikrofon-Knopf in der Kopfzeile: Tippen = zuhören / anhalten. */
@Composable
fun VoiceButton(vm: UGSViewModel) {
    val context = LocalContext.current
    if (!Voice.enabled(vm) || !remember { Voice.available(context) }) return
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) Voice.toggle(vm) else vm.notice = "Ohne Mikrofonzugriff kann Alas nicht zuhören." }
    val tint =
        when (Voice.phase) {
            Voice.Phase.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            Voice.Phase.FAILED -> Color(0xFFFF9500)
            else -> MaterialTheme.colorScheme.primary
        }
    IconButton(onClick = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Voice.toggle(vm) else permission.launch(Manifest.permission.RECORD_AUDIO)
    }) {
        Icon(if (Voice.phase == Voice.Phase.FAILED) Icons.Default.MicOff else Icons.Default.Mic, "Sprachassistent Alas · ${Voice.phase.title}", tint = tint)
    }
}

/** Karte unten rechts: was gehört wurde und was „Alas“ antwortet. */
@Composable
fun VoiceAnswerCard(vm: UGSViewModel, modifier: Modifier) {
    AnimatedVisibility(Voice.panel && vm.user != null, modifier.padding(16.dp)) {
        ElevatedCard(Modifier.widthIn(max = 420.dp)) {
            Row(Modifier.padding(14.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .12f), modifier = Modifier.size(38.dp)) {
                    Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (Voice.phase == Voice.Phase.LISTENING) "علاس — Ich höre …" else "علاس · ${Voice.phase.title}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Voice.heard.isNotBlank()) Text("„${Voice.heard}“", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    if (Voice.answer.isNotBlank()) Text(Voice.answer, fontWeight = FontWeight.Medium, maxLines = 8)
                }
                IconButton(onClick = { Voice.stop() }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Schließen") }
            }
        }
    }
}

private val examples =
    listOf(
        "علاس … شو عندي اليوم؟ · شو عندي بكرا؟",
        "علاس … ضيف مهمة اتصل بالزبون بكرا",
        "علاس … كم صرفنا هالشهر؟ · مصاريف الفندق الشهر الماضي",
        "علاس … في ايميلات جديدة؟ · اقرأ آخر رسالة",
        "Alas … Was steht heute an? · Ausgaben letzten Monat · Neue Mails?",
    )

/** Einstellungen → Sprachassistent. */
@Composable
fun VoiceSettingsScreen(vm: UGSViewModel, back: () -> Unit) {
    val context = LocalContext.current
    fun set(key: String, value: String) =
        vm.run {
            withContext(Dispatchers.IO) { vm.repo.personal("voice.$key", value) }
            vm.refresh()
        }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BackRow("Sprachassistent", back) }
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(Voice.enabled(vm), { set("enabled", it.toString()) })
                Text("Sprachassistent „Alas“ einschalten", Modifier.padding(start = 10.dp))
            }
        }
        item { Text("Status: ${Voice.phase.title}${if (!Voice.available(context)) " · Auf diesem Gerät ist keine Spracherkennung installiert." else ""}", style = MaterialTheme.typography.bodySmall) }
        item {
            Text(
                "Mikrofon-Knopf in der Kopfzeile antippen und fragen: To Do, Firma-Ausgaben, E-Mails. Alas hört nur nach dem Antippen und nur, solange UGS Personal geöffnet und entsperrt ist. Das Weckwort-Modell der Mac-App wird auf Android nicht verwendet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SectionHeader("Befehle verstehen in") }
        item {
            val value = Voice.setting(vm, "language", "both")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("both" to "Arabisch + Deutsch", "arabic" to "Arabisch", "german" to "Deutsch").forEach { (k, t) -> FilterChip(value == k, { set("language", k) }, label = { Text(t) }) }
            }
        }
        item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Switch(Voice.setting(vm, "speak", "true") == "true", { set("speak", it.toString()) })
                Text("Antworten vorlesen", Modifier.padding(start = 10.dp))
            }
        }
        item { SectionHeader("Beispiele") }
        items(examples) { Text(it) }
    }
}
