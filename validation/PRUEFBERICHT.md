# Prüfbericht · UGS Sicherheit Android 1.0.0

Stand: 15.09.2026

## Ergebnis

- Android-Debug-APK für **arm64-v8a** erfolgreich gebaut.
- Android-Test-APK mit den Geräte-Integrationstests erfolgreich kompiliert.
- **19 JVM-Tests bestanden**, keine Fehler, keine übersprungenen Tests.
- Android Lint: **0 Fehler, 8 Warnungen**. Verbleibende Hinweise betreffen Bibliotheksversionen, TLS-Hilfsklassen einer PDF-Abhängigkeit (die App hat keine Internetberechtigung), monochrome Launcher-Symbole und KTX-Stilvorschläge. Es wurde keine Lint-Baseline verwendet, um Fehler zu verbergen.
- APK-Signatur erfolgreich verifiziert: APK Signature Scheme v2, Android-Debug-Zertifikat.
- ZIP-Ausrichtung mit 16-KB-Prüfung erfolgreich.
- Alle drei enthaltenen ARM64-Bibliotheken besitzen PT_LOAD-Ausrichtung von mindestens 16 KB: SQLCipher, ML Kit OCR und AndroidX Graphics Path.
- Das zusammengeführte Manifest enthält **keine INTERNET-Berechtigung**. Dokumente werden ausschließlich nach ausdrücklicher Nutzeraktion an andere Apps beziehungsweise Dateianbieter übergeben.
- Zwölf übernommene PDF-/JSON-/Signaturressourcen stimmen bytegenau mit den bereitgestellten Mac-/iOS-Ressourcen überein; siehe `shared-templates.json`.

## Automatisiert geprüfte Fälle

`RulesTest` (12): Nachtschicht und Nettozeit, Überschneidung über Mitternacht, direkt anschließende Dienste, ungültige Pausen, Schaltjahr und inklusive Befristungsenden, Probezeit-Zugang und Zweiwochenfrist, optionale Textsegmente, unbekannte Vorlagenfelder, CSV-Anführungszeichen/Zeilenumbrüche, doppelte Personalnummern, Wochenenden und ungültige Abwesenheitszeiträume.

`ContractTemplatesTest` (5): Vollständige Auflösung der Arbeitsvertragsvorlage, Pflichtangaben und Prüfung für Bewachungszwecke, Vorbeschäftigungserklärung bei Kalenderbefristung, Aufhebungsvertragsvarianten sowie Belehrungsvorlage. Diese Tests verwenden die tatsächlichen mitgelieferten JSON-Vorlagen.

`ExcelImportTest` (2): Verknüpfung zum ersten Tabellenblatt, Shared Strings, leere Spalten, Excel-Datum einschließlich Schaltjahrkorrektur sowie Ablehnung von XML-DTDs.

## Noch nicht auf einem Gerät verifiziert

Der Android-Emulator konnte in der Erstellungsumgebung nicht booten. Hardwarebeschleunigung war nicht verfügbar; sowohl der Start mit Softwaregrafik als auch der Start ohne Grafikbeschleunigung endeten vor dem Android-Start mit einem Emulator-Prozessabbruch.

Deshalb wurden **keine erfolgreiche App-Ausführung, keine UI-Tests und keine visuelle PDF-Prüfung auf Android behauptet**. Die nativen Geräte-Integrationstests liegen im Projekt bereit, wurden hier jedoch nicht ausgeführt. Sie prüfen eine isolierte SQLCipher-Datenbank, Anmeldung, Rollen, verschlüsselte Sicherung/Wiederherstellung, Datenbankintegrität und die nativen PDF-Exporte einschließlich Dienstausweis, Probezeitkündigung und Stempel. Die Testdaten sind synthetisch.

Die wichtigsten noch ausstehenden Geräteprüfungen sind Ersteinrichtung und erneute Anmeldung, Speichern und Wiederöffnen einer Personalakte, Dateiauswahl/Teilen, OCR, native PDF-Darstellung sowie Sicherung und Wiederherstellung.

## Build-Konfiguration

Kotlin 2.2.21 · Compose BOM 2025.11.01 · AGP 8.13.2 · Gradle 8.13 · JDK 17 · compileSdk/targetSdk 36 · minSdk 26.

Verwendeter abschließender Build:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug -PugsAbi=arm64-v8a -Pkotlin.incremental=false
```

Der saubere vorherige Build wurde ebenfalls erfolgreich ausgeführt. Die zusätzliche Abschaltung inkrementeller Kotlin-Kompilierung diente hier zur Vermeidung veralteter Build-Zwischenstände in der Erstellungsumgebung. Ein frisches Android-Studio-Projekt enthält diese Zwischenstände nicht.

Die beigefügte APK ist eine Debug-Testversion. Sie ist nicht als produktives Play-Store-Paket signiert. Die Funktionsunterschiede zur Mac-/iOS-App und die Grenzen der Vertrags-, Gehalts- und Urlaubsfunktionen sind in `README.md` beschrieben.
