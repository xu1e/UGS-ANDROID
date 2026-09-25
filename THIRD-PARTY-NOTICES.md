# Drittanbieter und Vorlagen

UGS-Logos, Signatur und Vertrags-/PDF-Vorlagen stammen aus den vom Nutzer bereitgestellten UGS-Projekten und Unterlagen. Ihre ursprünglichen Rechte bleiben erhalten.

| Bestandteil | Quelle | Lizenz / Hinweis |
| --- | --- | --- |
| Kotlin | https://github.com/JetBrains/kotlin | Apache 2.0 |
| AndroidX / Jetpack Compose | https://android.googlesource.com/platform/frameworks/support/ | Apache 2.0 |
| 27 ausgewählte Material-Icons | AndroidX `material-icons-extended-android` 1.7.8, offizielles Quellenarchiv | Unveränderte Quelldateien mit Copyright-Headern unter `app/src/main/java/androidx`; Apache 2.0. Herkunft und SHA-256 in `validation/material-icons-provenance.json`. Nur verwendete Icons sind enthalten, um die APK klein zu halten. |
| SQLCipher Android | https://github.com/sqlcipher/sqlcipher-android | SQLCipher Community Edition, BSD-Lizenzbedingungen des Projekts |
| PDFBox Android | https://github.com/TomRoush/PdfBox-Android | Apache 2.0 |
| Google ML Kit | https://developers.google.com/ml-kit/terms | Google ML Kit Nutzungsbedingungen; gebündelte Texterkennung. Die UGS-App selbst besitzt keine Internetberechtigung. |
| Noto Sans | https://github.com/notofonts/noto-fonts | SIL Open Font License 1.1, beigefügt unter `licenses/NotoSans-OFL.txt` |
| JUnit | https://junit.org/junit4/ | Eclipse Public License 1.0, nur Tests |
| JSON-java | https://github.com/stleary/JSON-java | Public Domain, nur Tests |

Gradle bezieht die Bibliotheken aus Google Maven und Maven Central. Die jeweiligen Artefakte können weitere Hinweise und transitive Abhängigkeiten enthalten. Die vollständigen Gradle-Koordinaten stehen in `app/build.gradle.kts`.
