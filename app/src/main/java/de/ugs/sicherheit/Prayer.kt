package de.ugs.sicherheit

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.coroutines.delay

/**
 * Gebetszeiten für Köln (Firmensitz) wie iOS Build 35 / Mac 5.9.61:
 * Muslim World League (Fajr 18°, Ischa 17°), Asr Standard, winkelbasierte
 * Korrektur für hohe Breiten. Minuten ab Mitternacht Ortszeit, ohne Netzabfrage.
 */
data class PrayerTimes(val fajr: Int, val dhuhr: Int, val asr: Int, val maghrib: Int, val isha: Int) {
    data class Prayer(val name: String, val arabic: String, val minutes: Int)

    val list
        get() =
            listOf(
                Prayer("Fajr", "الفجر", fajr),
                Prayer("Dhuhr", "الظهر", dhuhr),
                Prayer("Asr", "العصر", asr),
                Prayer("Maghrib", "المغرب", maghrib),
                Prayer("Ischa", "العشاء", isha),
            )

    companion object {
        const val LATITUDE = 50.9375
        const val LONGITUDE = 6.9603
        val zone: ZoneId = ZoneId.of("Europe/Berlin")

        fun compute(year: Int, month: Int, day: Int, utcOffsetHours: Double, fajrAngle: Double = 18.0, ishaAngle: Double = 17.0, asrFactor: Double = 1.0): PrayerTimes {
            val lat = LATITUDE
            val lng = LONGITUDE
            val rad = Math.PI / 180
            fun fixAngle(a: Double) = a - 360 * floor(a / 360)
            fun fixHour(a: Double) = a - 24 * floor(a / 24)
            var y = year
            var m = month
            if (m <= 2) {
                y -= 1
                m += 12
            }
            val century = floor(y / 100.0)
            val correction = 2 - century + floor(century / 4)
            val julian = floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + correction - 1524.5 - lng / (15 * 24)
            fun sun(t: Double): Pair<Double, Double> {
                val d = julian + t - 2451545
                val g = fixAngle(357.529 + 0.98560028 * d)
                val q = fixAngle(280.459 + 0.98564736 * d)
                val l = fixAngle(q + 1.915 * sin(g * rad) + 0.02 * sin(2 * g * rad))
                val e = 23.439 - 0.00000036 * d
                val ascension = atan2(cos(e * rad) * sin(l * rad), cos(l * rad)) / rad / 15
                return asin(sin(e * rad) * sin(l * rad)) / rad to q / 15 - fixHour(ascension)
            }
            fun midDay(t: Double) = fixHour(12 - sun(t).second)
            // null, wenn die Sonne den Winkel an diesem Tag nicht erreicht (Sommernächte).
            fun sunAngleTime(angle: Double, t: Double, beforeNoon: Boolean = false): Double? {
                val decl = sun(t).first
                val x = (-sin(angle * rad) - sin(decl * rad) * sin(lat * rad)) / (cos(decl * rad) * cos(lat * rad))
                if (x < -1 || x > 1) return null
                val hours = acos(x) / rad / 15
                return midDay(t) + if (beforeNoon) -hours else hours
            }
            fun asrTime(t: Double): Double {
                val decl = sun(t).first
                return sunAngleTime(-atan(1 / (asrFactor + tan(abs(lat - decl) * rad))) / rad, t) ?: midDay(t)
            }
            var fajr = 5.0
            var sunrise = 6.0
            var dhuhr = 12.0
            var asr = 13.0
            var maghrib = 18.0
            var isha = 18.0
            var fajrReached = true
            var ishaReached = true
            repeat(3) {
                val nextFajr = sunAngleTime(fajrAngle, fajr / 24, beforeNoon = true)
                val nextSunrise = sunAngleTime(0.833, sunrise / 24, beforeNoon = true) ?: 6.0
                val nextDhuhr = midDay(dhuhr / 24)
                val nextAsr = asrTime(asr / 24)
                val nextMaghrib = sunAngleTime(0.833, maghrib / 24) ?: 18.0
                val nextIsha = sunAngleTime(ishaAngle, isha / 24)
                fajrReached = nextFajr != null
                ishaReached = nextIsha != null
                fajr = nextFajr ?: fajr
                sunrise = nextSunrise
                dhuhr = nextDhuhr
                asr = nextAsr
                maghrib = nextMaghrib
                isha = nextIsha ?: isha
            }
            // Hohe Breiten: Fajr/Ischa höchstens Winkel/60 der Nacht vor Sonnenaufgang bzw. nach Sonnenuntergang.
            val night = 24 - (maghrib - sunrise)
            val fajrPortion = fajrAngle / 60 * night
            if (!fajrReached || sunrise - fajr > fajrPortion) fajr = sunrise - fajrPortion
            val ishaPortion = ishaAngle / 60 * night
            if (!ishaReached || isha - maghrib > ishaPortion) isha = maghrib + ishaPortion
            fun minutes(hours: Double) = ((hours + utcOffsetHours - lng / 15) * 60).roundToInt()
            return PrayerTimes(minutes(fajr), minutes(dhuhr), minutes(asr), minutes(maghrib), minutes(isha))
        }

        /** Zeiten für den Kalendertag in Köln (Sommer-/Winterzeit automatisch). */
        fun koeln(date: LocalDate): PrayerTimes {
            val offset = date.atStartOfDay(zone).plusHours(12).offset.totalSeconds / 3600.0
            return compute(date.year, date.monthValue, date.dayOfMonth, offset)
        }

        fun clock(minutes: Int): String {
            val v = ((minutes % 1440) + 1440) % 1440
            return "%02d:%02d".format(v / 60, v % 60)
        }
    }
}

/** Nächstes Gebet und Restzeit. */
data class PrayerSchedule(val times: PrayerTimes, val nextIndex: Int?, val nextName: String, val minutesUntilNext: Int) {
    val nextMinutes
        get() = nextIndex?.let { times.list[it].minutes } ?: times.fajr

    val countdown
        get() = if (minutesUntilNext >= 60) "in %d:%02d Std".format(minutesUntilNext / 60, minutesUntilNext % 60) else "in $minutesUntilNext Min"

    companion object {
        fun at(now: ZonedDateTime = ZonedDateTime.now(PrayerTimes.zone)): PrayerSchedule {
            val local = now.withZoneSameInstant(PrayerTimes.zone)
            val nowMinutes = local.hour * 60 + local.minute
            val today = PrayerTimes.koeln(local.toLocalDate())
            val index = today.list.indexOfFirst { it.minutes > nowMinutes }.takeIf { it >= 0 }
            return if (index != null) PrayerSchedule(today, index, today.list[index].name, today.list[index].minutes - nowMinutes)
            else PrayerSchedule(today, null, "Fajr", 1440 - nowMinutes + PrayerTimes.koeln(local.toLocalDate().plusDays(1)).fajr)
        }
    }
}

@Composable
fun rememberPrayerSchedule(): PrayerSchedule {
    var schedule by remember { mutableStateOf(PrayerSchedule.at()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            schedule = PrayerSchedule.at()
        }
    }
    return schedule
}

/** Kopfzeilen-Chip: nächstes Gebet mit Restzeit; Tippen zeigt alle fünf Zeiten. */
@Composable
fun PrayerChip(vm: UGSViewModel, compact: Boolean) {
    if (vm.company["prayerTimes"] == "false") return
    val s = rememberPrayerSchedule()
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!compact) Text(s.nextName, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(PrayerTimes.clock(s.nextMinutes), fontWeight = FontWeight.SemiBold)
                    if (!compact) Text(s.countdown, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            leadingIcon = { Icon(Icons.Default.NightsStay, "Gebetszeiten Köln", tint = Color(0xFFFFC107), modifier = Modifier.size(16.dp)) },
        )
        DropdownMenu(open, { open = false }) {
            PrayerList(s, Modifier.padding(14.dp).width(260.dp))
        }
    }
}

@Composable
fun PrayerList(s: PrayerSchedule, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Gebetszeiten Köln", fontWeight = FontWeight.SemiBold)
        s.times.list.forEachIndexed { i, p ->
            val next = i == s.nextIndex
            val past = s.nextIndex == null || i < s.nextIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontWeight = if (next) FontWeight.SemiBold else FontWeight.Normal, color = if (next) MaterialTheme.colorScheme.primary else if (past) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                Text("  ${p.arabic}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(PrayerTimes.clock(p.minutes), fontWeight = if (next) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Text("Nächstes Gebet: ${s.nextName} ${s.countdown}", style = MaterialTheme.typography.bodySmall)
        Text("Muslim World League · Fajr 18°, Ischa 17°, Asr Standard", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
