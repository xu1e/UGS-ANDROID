# UGS Sicherheit · Native Android-App

Version 1.0.0 · Kotlin / Jetpack Compose · Android 8.0 oder neuer

## Start auf deinem Android-Gerät

1. Die ZIP-Datei vollständig entpacken.
2. Die Datei `UGS-Sicherheit-Android-1.0.0-arm64.apk` aus dem Ordner `Installieren` auf das Android-Gerät kopieren und öffnen.
3. Falls Android danach fragt, die Installation für die verwendete Dateien-App erlauben.
4. Beim ersten Start **Administrator anlegen** wählen. Benutzername und Passwort bestimmst du selbst. Es gibt keine vorgegebenen Zugangsdaten. Das Passwort benötigt mindestens 12 Zeichen.
5. Unter **Einstellungen** die vollständigen Firmenangaben ergänzen. Danach Mitarbeiter und Objekte anlegen oder Personal importieren.

Die mitgelieferte APK ist eine signierte **Debug-Testversion für arm64-v8a** (moderne Android-Geräte mit 64-Bit-ARM). Sie ist kein Play-Store-Release. Das Projekt kann auch für andere Prozessorarchitekturen und Emulatoren gebaut werden. Ein Start auf einem physischen Android-Gerät konnte in der Erstellungsumgebung nicht geprüft werden; Einzelheiten stehen in `validation/PRUEFBERICHT.md`.

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

- Anmeldung, Administrator-Ersteinrichtung, eigene Passwortänderung, Benutzerverwaltung und Rollen Administrator / Personal / Lesen.
- Übersicht mit Kennzahlen, heutigen Diensten, Aufgaben und Dokumentfristen; helle und dunkle Darstellung.
- Personalakten mit Stammdaten, Vertragsdaten, Bewacher-ID, Kontaktdaten und Notizen.
- CSV-/XLSX-Import mit Spaltenzuordnung, Vorschau, Dublettenprüfung und optionaler Aktualisierung vorhandener Personalnummern.
- Texterkennung aus Ausweisfotos auf dem Gerät; erkannte Angaben können geprüft und manuell in die Personalakte übernommen werden.
- Objekte, Dienste, Arbeitszeiten einschließlich Nachtschichten, Pausen und Überschneidungsprüfung.
- Abwesenheiten, Aufgaben, Meldestatus, Gehaltsübersichten und PDF-Berichte.
- Dokumentablage für PDF und Bilder, Kategorien, Gültigkeitsdaten und Papierkorb.
- Arbeitsvertrag mit den übernommenen UGS-Vertragsvorlagen, unbefristeter Beschäftigung, Kalenderbefristung und optionaler Zweckbefristung für einen konkret beschriebenen Bewachungsauftrag.
- Aufhebungsvertrag und Kündigung innerhalb der Probezeit mit Datenprüfung und Kalenderauswahl.
- UGS-Exportzentrum mit Dienstausweis, Visitenkarte, beiden Broschüren, Stundenzettel, Fragebogen und Belehrung nach § 2a SchwarzArbG.
- Ausfüllbarer Dienstausweis mit Mitarbeitername, Bewacher-ID, Personalnummer und Gültigkeitsdaten.
- PDF-Vorschau, Speichern über die Android-Dateiauswahl und Teilen an andere Apps.
- Firmenstempel **ohne Logo**, Position durch Antippen einer PDF-Seite, Größe, optionales Datum und optional ausdrücklich bestätigte Arbeitgeberunterschrift.
- Kalender für Datumsfelder. Auf Tablets einklappbare Navigation mit sichtbaren Symbolen; auf Telefonen ein Navigationsmenü. Keine sichtbaren Scrollleisten.
- Helles und dunkles Android-App-Symbol; transparente UGS-Logos im Dashboard und in den PDF-Kopfzeilen.
- Verschlüsselte Fachdaten-Sicherungen mit eigenem Passwort, Wiederherstellung und Änderungsprotokoll.

## Abgrenzung zur Mac-/iOS-App

Dies ist eine eigenständige erste Android-Version auf Basis der bereitgestellten UGS-Quellen und Vorlagen, keine vollständig identische Portierung aller Mac-Funktionen.

| Bereich | Android 1.0.0 |
| --- | --- |
| Datenaustausch | Kein automatischer Abgleich mit Mac/iOS, kein gemeinsames Benutzerkonto. Personal kann per CSV/XLSX importiert werden. Android-Sicherungen sind für Android bestimmt. |
| E-Mail | Teilen über eine installierte Mail-App. Kein eingebauter SMTP-/IMAP-Posteingang; kein behaupteter Versand- oder Zustellnachweis. |
| Meldungen | Interne Vorbereitung und Statusdokumentation. Keine direkte gesetzliche Übermittlung von Sofort- oder Sozialversicherungsmeldungen. |
| Gehalt | Manuell erfasste Stunden, Lohnsatz, Zulagen und Abzüge. Keine automatische Lohnsteuer-/Sozialversicherungsberechnung oder gesetzliche Entgeltabrechnung. |
| Urlaub | Übersicht genehmigter Tage Montag–Freitag; Feiertage, andere Arbeitswochen, Teiljahre und Überträge werden nicht automatisch berechnet. |
| Dienstplan | Einzelne Dienste und Monatsfilter. Keine automatische Schichtoptimierung oder Übernahme ganzer Monatspläne. |
| Tarif | Manuelle Angaben aus dem Formular; keine automatische Prüfung aktueller Tarifansprüche. |
| Texterkennung | Textvorschau und manuelle Übernahme. Keine automatische Identitätsbestätigung oder MRZ-Prüfung. |
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

`Domain.kt` enthält Modelle, Formfelder und fachliche Prüfungen. `Repository.kt` und `Security.kt` verwalten Anmeldung, Datenbank, Dokumente und Sicherungen. `Contracts.kt` und `PdfService.kt` erzeugen beziehungsweise bearbeiten PDF-Dateien. Die Compose-Oberfläche liegt in `MainActivity.kt`, `Forms.kt`, `DocumentsUi.kt` und `AdministrationUi.kt`. `ImportService.kt` verarbeitet CSV, XLSX und Texterkennung.

Die mitgelieferten PDF-/JSON-Vorlagen liegen unter `app/src/main/assets`. Branding-Ressourcen liegen unter `app/src/main/res/drawable-nodpi`. Die originalen Vorlagendateien wurden übernommen; PDF-Texte werden auf Android nativ neu gesetzt.
