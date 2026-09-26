# Änderungen

## 1.9.0 – Funktionsstand UGS für iPhone 1.9.0 (Build 36)

Alle Neuerungen der iOS-Version 1.9.0 wurden in die Android-App übernommen.

### Neu

- **Rechte und Rollen**: Rechte je Seite und Aktion, Rollen Planung und Benutzerdefiniert; Lesen darf exportieren.
- **Personal**: automatische dreistellige Personalnummer (Ziffern 1–9, nie wiederverwendet) neben der Bewacher-ID, Fotos und Avatare, Zeitleiste, Fragebogen, Mitarbeiterliste als PDF, pflegbare Auswahllisten.
- **Dokumente**: bis 250 MB je Datei (verschlüsselt in 1-MB-Blöcken), neue Kategorien, erweitertes Führungszeugnis mit Neuantrag, Fristen, Archiv.
- **Schreiben**: Abmahnung, Kündigung, Strafen, Prüfbericht; Kooperationsverträge und Angebote; Firma Ausgaben.
- **Dienstplan**: Monatsraster je Objekt, bis zu vier Schichten, Konfliktprüfung, Vorlagen, Stundenzettel; Urlaubskonten mit Übertrag.
- **Tarife**: BDSW-Tarifgebiete und Entgeltgruppen (Stand 1. April 2026) im Arbeitsvertrag, Prüfung des Stundenlohns gegen den Katalogwert.
- **Sofortmeldung**: Anschreiben mit Betriebsnummer 69562254, bis zu 10 geprüfte PDFs (zusammen max. 100 MB).
- **E-Mail**: SMTP-Versand mit HTML-Vorlage, Gesendete E-Mails, IMAP-Posteingang mit Ordnern, Filtern, Farben, Blockieren, Drucken und automatischem Abruf.
- **Vertrag stempeln**: mehrere Stempel, automatische Positionssuche, Seitenlisten, Mitarbeitersuche, Senden und ablegen, Übergabe aus dem Posteingang.
- **To-Do-Kalender** mit Getriebe-Karte und **Gebetszeiten Köln**.
- **Sprachassistent „Alas“** (Arabisch/Deutsch) per Mikrofon-Knopf.
- **Übersicht** und **Kontrollzentrum** wie iOS; **Übernahme portabler Archive** (`.ugsarchive`) vom Mac oder iPhone.

### Berechtigungen im Manifest

`INTERNET` (SMTP/IMAP), `POST_NOTIFICATIONS` (optionale Posteingangs-Mitteilungen), `RECORD_AUDIO` (Sprachassistent, nur nach Antippen).

### Prüfstand

- In der Erstellungsumgebung war Googles Maven-Repository (`dl.google.com`) gesperrt. Ein Gradle-Build mit Android Gradle Plugin, Lint und Geräte-Tests war deshalb **nicht möglich**; bitte vor der Verteilung `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` ausführen.
- Ersatzprüfung: Der gesamte App-Code wurde mit Kotlin 2.2 und dem Compose-Compiler-Plugin gegen das Android-15-Framework (Robolectric `android-all`), Compose Multiplatform 1.7.3 und die Original-Bibliotheken SQLCipher und PDFBox **fehlerfrei kompiliert**; nur für die ausschließlich bei Google veröffentlichten Bibliotheken (AndroidX Activity/Core/Lifecycle, ML Kit) wurden Signatur-Platzhalter verwendet. Der Geräte-Integrationstest kompiliert ebenfalls.
- Alle 33 Unit-Tests laufen erfolgreich, darunter neue Tests für Tarife, Vertrag stempeln, Gebetszeiten, To-Do-Kennzahlen, Sprachbefehle (Arabisch/Deutsch), portables Archiv (HKDF-Testvektor nach RFC 5869, Rundlauf, falsches Passwort) und Kontrollzentrum.
- Nicht geprüft: Darstellung auf echten Geräten, Verbindung zu echten SMTP-/IMAP-Servern, Import eines echten Mac-/iPhone-Archivs und Spracherkennung auf dem Gerät.
