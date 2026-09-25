package de.ugs.sicherheit

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContractTemplatesTest {
    private fun asset(name: String) =
        javaClass.classLoader!!.getResourceAsStream("contracts/$name")!!.bufferedReader().use {
            it.readText()
        }

    private val company =
        mapOf(
            "name" to "UGS Sicherheit GmbH",
            "street" to "Musterstraße 1",
            "postalCode" to "10115",
            "city" to "Berlin",
            "representative" to "Max Mustermann",
        )
    private val worker =
        Entry(
            kind = Kind.WORKER,
            fields =
                schemas.getValue(Kind.WORKER).associate { it.key to it.initial } +
                    mapOf(
                        "personnelNumber" to "TEST-001",
                        "firstName" to "Erika",
                        "lastName" to "Müller",
                        "street" to "Teststraße 2",
                        "postalCode" to "10115",
                        "city" to "Berlin",
                        "startDate" to "2026-09-16",
                        "hourlyRate" to "18.50",
                    ),
        )
    private val values =
        contractFields.associate { it.key to it.initial } +
            mapOf(
                "signingDate" to "2026-09-15",
                "signingPlace" to "Berlin",
                "workerId" to worker.id,
                "reviewed" to "true",
            )

    private fun tokens(v: Map<String, String>): Map<String, String> {
        val arr = JSONArray(asset("detail-fields.json"))
        val fields =
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                field(o.getString("key"), o.getString("label"))
            }
        return Contracts.tokens(worker, company, v, fields, JSONObject(asset("deployment.json")))
    }

    private fun render(name: String, t: Map<String, String>): String {
        val a = JSONObject(asset(name)).getJSONArray("items")
        return (0 until a.length())
            .mapNotNull {
                val o = a.getJSONObject(it)
                val gate = o.optString("when")
                if (gate.isNotEmpty()) {
                    assertTrue("unknown gate $gate", t.containsKey(gate))
                    if (t[gate] != "true") return@mapNotNull null
                }
                Rules.resolve(o.optString("t"), t) + " " + Rules.resolve(o.optString("r"), t)
            }
            .joinToString("\n")
    }

    @Test
    fun allContractTemplateTokensResolve() {
        val text = render("ugs-arbeitsvertrag.json", tokens(values))
        assertFalse(text.contains("{{"))
        assertTrue(text.contains("Erika Müller"))
        assertTrue(text.contains("unbefristet"))
        assertTrue(text.contains("UGS Sicherheit GmbH"))
    }

    @Test
    fun purposeRequiresReviewAndTemporaryFacts() {
        val purpose =
            values +
                mapOf(
                    "duration" to "Bewachungsauftrag",
                    "object" to "Baustelle Teststraße 3, Berlin",
                    "customer" to "Musterkunde",
                    "orderReference" to "Bauauftrag 001",
                    "completionEvent" to "Vollständige Abnahme und Übergabe der Baustelle",
                    "temporaryNeed" to "Zusätzlicher Bedarf während der konkret geplanten Bauphase",
                    "expectedDuration" to "Sechs Monate",
                    "purposeReviewed" to "true",
                )
        val t = tokens(purpose)
        val text = render("ugs-arbeitsvertrag.json", t)
        assertTrue(text.contains("frühestens zwei Wochen"))
        assertFalse(t.getValue("einsatzort_clause").contains("wechselnden"))
        assertThrows(IllegalArgumentException::class.java) {
            tokens(purpose + ("purposeReviewed" to "false"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            tokens(purpose + ("temporaryNeed" to ""))
        }
    }

    @Test
    fun calendarFixedTermNeedsNoPreviousEmployment() {
        assertThrows(IllegalArgumentException::class.java) {
            tokens(values + ("duration" to "1 Jahr"))
        }
        val t = tokens(values + mapOf("duration" to "1 Jahr", "noPriorEmployment" to "true"))
        assertTrue(t.getValue("duration_clause").contains("15.09.2027"))
        render("ugs-arbeitsvertrag.json", t)
    }

    @Test
    fun agreementGatesAndTokensResolve() {
        val v =
            agreementFields.associate { it.key to it.initial } +
                mapOf(
                    "workerId" to worker.id,
                    "signingDate" to "2026-10-01",
                    "signingPlace" to "Berlin",
                    "endDate" to "2026-10-31",
                    "reviewed" to "true",
                )
        val text = render("ugs-aufhebungsvertrag.json", Contracts.agreement(worker, company, v))
        assertTrue(text.contains("31.10.2026"))
        assertFalse(text.contains("noch keine Arbeitsleistung"))
        assertFalse(text.contains("{{"))
    }

    @Test
    fun instructionTokensResolve() {
        render("ugs-belehrung-schwarzarbg.json", Contracts.base(worker, company, values))
    }
}
