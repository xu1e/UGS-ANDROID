# UGS Sicherheit · Native Android-App

Version 1.9.0 (Funktionsstand iOS 1.9.0 Build 36) · Kotlin / Jetpack Compose · Android 8.0 oder neuer

## Start auf deinem Android-Gerät

1. Die APK wie unten beschrieben in Android Studio oder mit `./gradlew :app:assembleDebug` bauen.
2. Die Datei `app/build/outputs/apk/debug/app-debug.apk` auf das Android-Gerät kopieren und öffnen.
3. Falls Android danach fragt, die Installation für die verwendete Dateien-App erlauben.
4. Beim ersten Start **Administrator anlegen** wählen. Benutzername und Passwort bestimmst du selbst. Es gibt keine vorgegebenen Zugangsdaten. Das Passwort benötigt mindestens 12 Zeichen.
5. Unter **Einstellungen** die vollständigen Firmenangaben ergänzen. Danach Mitarbeiter und Objekte anlegen oder Personal importieren.

Eine Debug-APK ist eine Testversion, kein Play-Store-Release. Zum Prüfstand von Version 1.9.0 siehe `CHANGELOG.md`; der Prüfbericht der Version 1.0.0 liegt in `validation/PRUEFBERICHT.md`.

## In Android Studio öffnen

Öffne den Ordner `Android-Studio-Projekt` mit **Open** in Android Studio. Verwende eine Version mit Unterstützung für Android Gradle Plugin 8.13, JDK 17 und Android SDK 36. Gradle lädt die festgelegten Abhängigkeiten beim ersten Synchronisieren. Danach das Modul `app` und dein Gerät beziehungsweise einen Emulator auswählen und **Run** drücken.

Alternativ im Projektordner:

```bash
bash ./gradlew :app:assembleDebug
bash ./gradlew :app:testDebugUnitTest :app:lintDebug
```

Die APK liegt anschließend unter `app/build/outputs/apk/debug/app-debug.apk`. Ohne Zusatzoption baut das Projekt für ARM64, ARMv7, x86_64 und x86. Nur ARM64:

```bash
bash ./gradlew :app:assembleDebug -PugsAbi=arm64-v8a
```

Für die beigefügten Geräte-Integrationstests auf einem verbundenen Testgerät oder Emulator:

```bash
bash ./gradlew :app:connectedDebugAndroidTest
```

Diese Tests verwenden separate Testdatenbanken. Sie senden keine E-Mails und verändern keine vorhandenen Personalakten.

## Enthaltene Funktionen

**Personal**
- Anmeldung, Administrator-Ersteinrichtung, eigene Passwortänderung, Benutzerverwaltung mit Rollen Administrator / Personal / Planung / Lesen / Benutzerdefiniert und Rechten je Seite und Aktion (Ansehen, Anlegen, Bearbeiten, Löschen, Exportieren).
- Personalakten mit Bewacher-ID, automatischer dreistelliger Personalnummer (nie doppelt vergeben), Foto oder Avatar, Dokumenten, Zeitleiste und Fragebogen; Mitarbeiterliste als PDF mit wählbaren Spalten.
- CSV-/XLSX-Import, Texterkennung aus Ausweisfotos, Auswahllisten (Abteilungen, Positionen, Status, Abwesenheitsarten …) in den Einstellungen pflegbar.
- Dokumente bis 250 MB je Datei (verschlüsselt, in Teilen), Kategorien, Fristen, erweitertes Führungszeugnis mit Neuantrag, Archiv.
- Abwesenheiten, Urlaubskonten mit Übertrag, Meldungen, Statusmeldungen und **Sofortmeldung** (Anschreiben mit Betriebsnummer, bis zu 10 geprüfte PDFs, Versand per SMTP).

**Verträge und Schreiben**
- Arbeitsvertrag mit **BDSW-Tarifgebiet und Entgeltgruppe** (Stand 1. April 2026, Prüfung gegen den Katalogwert), Aufhebungsvertrag, Kündigung in der Probezeit, Abmahnung, Kündigung, Strafen und Prüfbericht.
- **Vertrag stempeln**: mehrere Stempel je Dokument, automatische Suche des Unterschriftsfelds, Kopieren auf Seiten („alle“, „1-3, 5“), Fotos als PDF, Mitarbeiter per Bewacher-ID oder Personalnummer, Ablage in der Personalakte und Versand per E-Mail („Senden und ablegen“).
- Kooperationsverträge und Angebote, Exportzentrum (Dienstausweis, Visitenkarte, Broschüren, Stundenzettel, Belehrung).

**Planung**
- Monatsdienstplan je Objekt mit bis zu vier Schichten, Konfliktprüfung (Abwesenheit, Doppelbelegung), Vorlagen, Stundenzettel-PDFs.
- **To-Do-Kalender** mit Monatsansicht, Kalenderwochen, Feiertagen NRW, Getriebe-Karte und Agenda; **Gebetszeiten Köln** (Muslim World League) in der Kopfzeile.
- Objekte, Dienste, Arbeitszeiten, Gehaltsübersichten, Berichte.

**E-Mail**
- Eigener Versand über SMTP (SSL/TLS oder STARTTLS) mit HTML-Vorlage und Logo; Verlauf „Gesendete E-Mails“ mit ehrlichem Status (angenommen, Fehler, nicht bestätigt).
- **Posteingang** über IMAP: Ordner Posteingang / Junk / Spam / Gespeichert, Filter, Farben, Absender blockieren, Antworten, Weiterleiten, Drucken, Anhänge direkt in „Vertrag stempeln“ öffnen; automatischer Abruf und Mitteilungen ohne Absender und Betreff.

**Firma und Verwaltung**
- Firma Ausgaben mit Kategorien, Zeiträumen und PDF; Übersicht mit zwölf Kennzahlen, Heute-Leiste, Priorität, Ausgaben und Personalstatus.
- **Sprachassistent „Alas“** (Arabisch und Deutsch): To Do, neue Aufgabe, Ausgaben, E-Mails, Uhrzeit – per Mikrofon-Knopf, Antworten werden vorgelesen.
- Kontrollzentrum mit Integrität, Personal- und Dokumenthinweisen, Planungskonflikten, Protokoll und Papierkorb.
- Verschlüsselte Android-Sicherung sowie **Übernahme portabler Archive (`.ugsarchive`) vom Mac oder iPhone**.

## Abgrenzung zur Mac-/iOS-App

Version 1.9.0 übernimmt die Funktionen von UGS für iPhone 1.9.0 (Build 36). Unterschiede:

| Bereich | Android 1.9.0 |
| --- | --- |
| Datenaustausch | Portable Archive vom Mac/iPhone werden **ergänzend** übernommen (Mitarbeiter mit gleicher Bewacher-ID werden zusammengeführt). Benutzerkonten, Posteingang und gesendete E-Mails werden nicht übertragen. Android-Sicherungen sind für Android bestimmt. |
| Sprachassistent | Kein Weckwort-Modell (Core ML gibt es nur auf Apple-Geräten). Alas hört nach Antippen des Mikrofon-Knopfs über die Spracherkennung des Geräts; je nach Gerät ist dafür eine Netzverbindung oder ein Offline-Sprachpaket nötig. |
| E-Mail | SMTP-Annahme ist keine Zustell- oder Lesebestätigung. IMAP nur über SSL/TLS (Port 993). |
| Meldungen | Die Sofortmeldung selbst erfolgt weiter über das SV-Meldeportal; die App versendet nur das Anschreiben mit der Bestätigung. |
| Gehalt | Keine automatische Lohnsteuer-/Sozialversicherungsberechnung oder gesetzliche Entgeltabrechnung. |
| Tarif | Katalogwerte aus `ugs-tarife.json` (Stand 1. April 2026); aktuell geschuldete Vergütung im Einzelfall prüfen. |
| PDF-Stempel | Seiten ohne Rotation. Gedrehte Seiten zuerst in der Ausgangsdatei aufrichten. |

## Daten und Anmeldung

Die App speichert ihre Daten lokal. SQLCipher verschlüsselt die Datenbank; Dokumentdateien werden separat mit AES-GCM verschlüsselt. Die Datenbankschlüssel werden durch Android Keystore geschützt. Passwörter werden gesalzen mit PBKDF2-HMAC-SHA256 (310.000 Durchläufe) verarbeitet. Es gibt keinen festen Administrator und keinen eingebauten Passwort-Bypass.

Die App sperrt nach mehr als fünf Minuten im Hintergrund oder über das Schloss-Symbol. Screenshots sensibler App-Inhalte sind blockiert. PDF-Dateien werden für das ausdrückliche Teilen vorübergehend im privaten Cache entschlüsselt; der Cache wird beim Sperren und beim nächsten App-Start geleert. Normale Android-Cloud-Sicherung und Gerätekopie sind für diese Daten deaktiviert.

Die eigene Android-Sicherung umfasst Fachdaten, Dokumente, Entwürfe und Firmenangaben. Benutzerkonten und das lokale Protokoll werden nicht übertragen. Die Sicherung wird mit einem separat gewählten Passwort verschlüsselt. Ohne dieses Passwort kann sie nicht wiederhergestellt werden. Bei einer Wiederherstellung werden vorhandene Fachdaten nach Bestätigung ersetzt.

Vor einem Wechsel zwischen der mitgelieferten APK und einem selbst signierten Build eine Fachdaten-Sicherung exportieren. Unterschiedliche Signierschlüssel können eine Neuinstallation erforderlich machen. Für dauerhafte Verteilung einen eigenen Release-Signierschlüssel verwenden und dauerhaft aufbewahren.

## Vertragsvorlagen

Die UGS-Vorlagen wurden aus den bereitgestellten Mac-/iOS-Dateien übernommen. Die Android-Oberfläche prüft die notwendigen Angaben; sie kann die Wirksamkeit im Einzelfall nicht garantieren. Insbesondere Auftragsverlust, Zweckbefristung, Tarifgeltung, Sonderkündigungsschutz, Zugang und Formanforderungen sind anhand des konkreten Falles zu prüfen. Eine eingefügte Bildunterschrift ersetzt nicht automatisch eine gesetzlich erforderliche Schriftform.

Primärquellen für die berücksichtigten Regeln:

- [§ 14 TzBfG – Zulässigkeit der Befristung](https://www.gesetze-im-internet.de/tzbfg/__14.html)
- [§ 15 TzBfG – Ende des befristeten Arbeitsvertrags](https://www.gesetze-im-internet.de/tzbfg/__15.html)
- [§ 622 BGB – Kündigungsfristen](https://www.gesetze-im-internet.de/bgb/__622.html)
- [§ 623 BGB – Form der Beendigung](https://www.gesetze-im-internet.de/bgb/__623.html)

## Aufbau des Projekts

`Domain.kt` enthält Modelle, Formfelder und fachliche Prüfungen. `Repository.kt` und `Security.kt` verwalten Anmeldung, Datenbank, Dokumente und Sicherungen. `Contracts.kt` und `PdfService.kt` erzeugen beziehungsweise bearbeiten PDF-Dateien. Die Compose-Oberfläche liegt in `MainActivity.kt`, `Forms.kt`, `DocumentsUi.kt`, `AdministrationUi.kt` und den Bereichsdateien (`WorkersUi.kt`, `DutyPlanUi.kt`, `TodoUi.kt`, `MailUi.kt`, `StampUi.kt`, `VoiceUi.kt` …). `ImportService.kt` verarbeitet CSV, XLSX und Texterkennung. E-Mail: `Mail.kt` (MIME, Vorlagen), `MailNet.kt` (SMTP/IMAP), `MailService.kt`. Portable Archive: `PortableArchive.kt`, `PortableImport.kt`. Reine Fachlogik ohne Oberfläche (Gebetszeiten, Tarife, Sprachbefehle, Kennzahlen) ist mit Unit-Tests unter `app/src/test` abgedeckt.

Die mitgelieferten PDF-/JSON-Vorlagen liegen unter `app/src/main/assets`. Branding-Ressourcen liegen unter `app/src/main/res/drawable-nodpi`. Die originalen Vorlagendateien wurden übernommen; PDF-Texte werden auf Android nativ neu gesetzt.
