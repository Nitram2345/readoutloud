# CLAUDE.md – ReadOutLoud

## Projekt

Android-App (Kotlin + Jetpack Compose) zum Vorlesen von PDF, DOCX, EPUB, ODT und HTML sowie von
Webseiten per URL. Zwei TTS-Engines: Piper on-device (sherpa-onnx) und die System-TTS-Engine.

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Emulator starten: Android Studio AVD Manager oder `emulator -avd <name>`.

## Architektur

### Kernklassen

| Datei | Zweck |
|-------|-------|
| `MainActivity.kt` | Compose-UI, Toolbar, Dokument-Picker, TTS-Callbacks, i18n, Intent-Handling (VIEW/SEND) |
| `PdfLoader.kt` | `TextLine`-Datenmodell, `DocumentData` sealed class, PDF laden + Text extrahieren, Spaltenlogik |
| `DocxLoader.kt` | DOCX → `List<TextLine>` via ZipInputStream + XmlPullParser |
| `HtmlLoader.kt` | HTML → `List<TextLine>` via Jsoup (h1–h6, p, li, td, th, dt, dd) |
| `EpubLoader.kt` | EPUB → `List<TextLine>` via ZipInputStream + Jsoup |
| `OdtLoader.kt` | ODT → `List<TextLine>` via ZipInputStream + XmlPullParser (content.xml, text:p/text:h) |
| `UrlLoader.kt` | Webseite per OkHttp holen, readability4j auf den Artikeltext reduzieren, `extractUrl()` für geteilte Links |
| `TtsController.kt` | TTS-Steuerung, Satz-Splitting, Sentence→Line-Mapping, Highlighting-Callbacks, `setLanguage()`, Engine-Auswahl |
| `TtsEngine.kt` | Interface, hinter dem Piper und System-TTS austauschbar sind |
| `PiperTtsEngine.kt` | On-device-Synthese über sherpa-onnx, Modell laden und wieder freigeben |
| `TtsService.kt` | Foreground-Service: TTS-Lifecycle, MediaSession, Notification mit Media-Controls (CoroutineScope) |
| `VoiceManager.kt` | Download, Entpacken und Löschen von Stimmenmodellen und espeak-ng-data |
| `VoiceRepository.kt` / `VoiceRepositoryImpl.kt` | Stimmenkatalog von HuggingFace, Interface plus Implementierung |
| `PiperVoiceData.kt` | Datenmodell einer Stimme |
| `VoiceDialog.kt` | Stimmenauswahl-Dialog |
| `CountryUtils.kt` | Sprach- und Ländercodes, Flaggen |
| `AppSettings.kt` | SharedPreferences, **zwei Dateien** — siehe unten |
| `ui/` | Theme und wiederverwendbare Composables |

### AppSettings: zwei Prefs-Dateien

`readoutloud` enthält speechRate, savedLineIndex, volume, isSpeaker, language, selectedVoice,
downloadedVoices — alles, was ins Backup soll.

`readoutloud_local` enthält nur `last_uri`. Getrennt, weil `<exclude>` in den Backup-Regeln auf
Dateiebene arbeitet und nicht auf Schlüsselebene. Eine `content://`-Berechtigung ist gerätelokal
und nach Restore oder Device-Transfer ohnehin ungültig.

### Datenmodell

```kotlin
data class TextLine(
    val page: Int,
    val x: Float, val y: Float,
    val pageWidth: Float, val pageHeight: Float,
    val width: Float, val height: Float,
    val text: String,
    val style: String = ""   // Heading-Level: "h1"–"h4", DOCX-StyleId, leer = Fließtext
)

sealed class DocumentData {
    abstract val textLines: List<TextLine>
    data class Pdf(val pages: List<Bitmap>, override val textLines: List<TextLine>) : DocumentData()
    data class Word(override val textLines: List<TextLine>) : DocumentData()  // auch EPUB
}
```

**Word/EPUB**: `y = paragraphIndex.toFloat()`, `page = 0`  
**PDF**: `y` = PDF-Koordinate (Ursprung unten!), `page` = Seitenindex

## UI-Komponenten

### RoundBtn
```kotlin
fun RoundBtn(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit)
```
`CircleShape`, `size(33.dp)`, `contentPadding = PaddingValues(2.dp)`.  
Für Text-Zentrierung innerhalb: `Box(Modifier.fillMaxSize(), Alignment.Center)`.  
Für emoji/Sonderzeichen-Versatz: `Modifier.offset(y = (-N).dp)` auf `Text`.

### Toolbar-Zeilen
- Zeile 1: `⬅` | `Öffnen` | `🇩🇪` (?) | Spacer | `≡` | `−` `🕛1.0×` `+`
- Zeile 2: `«30s` | `Laut Lesen/Pause` (min 110dp) | `30m` | `30s»` | Spacer | `🔊/👂` | `−` `🔊100%` `+`
- Horizontal spacing: `Arrangement.spacedBy(7.dp)`

## Bekannte Fallstricke

### DOCX-Parsing: Namespace-Processing deaktivieren
```kotlin
parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
```
Ohne das liefert `parser.name` "p" statt "w:p" → 0 Paragraphen gefunden.

### PDF-Koordinatensystem: Bottom-Origin
PDF hat Y=0 unten. Erkennung:
```kotlin
val isBottomOrigin = pageLines.first().y > pageLines.last().y
```
Beim Zeichnen des Highlights und bei `buildSpeechFromTap` berücksichtigen.

### pointerInput-Lambda einfrieren
`pointerInput(Unit)` captured Lambda nur einmal. Nach Dokument-Wechsel neues `onTap` ignoriert.  
Fix: `val currentOnTap by rememberUpdatedState(onTap)` innerhalb des Composable.

### Spaltenhistogramm: volle Breite markieren
Beim Aufbau der Spaltengrenzen nicht nur `x` markieren sondern `x` bis `x+width`:
```kotlin
for (i in from..to) occupied[i] = true
```
Sonst entstehen Scheingrenzen mitten in Text-Segmenten.

### Adaptive Icon: kein Self-Reference
`mipmap-anydpi/ic_launcher.xml` darf nicht `@mipmap/ic_launcher` als Foreground haben → zirkuläre Referenz.  
Icon-PNG liegt in `drawable/rol_icon.png`, Foreground = `@drawable/rol_icon`.

### CancellationException re-throwen
```kotlin
} catch (e: kotlinx.coroutines.CancellationException) {
    statusText = "Abgebrochen"
    throw e   // wichtig! sonst bricht Coroutine nicht korrekt ab
}
```

### ensureActive in suspend fun vs. coroutine builder
In plain `suspend fun` (außerhalb von `launch`/`async`): `coroutineContext.ensureActive()` + `import kotlin.coroutines.coroutineContext`.  
Innerhalb eines Coroutine-Builders: `ensureActive()` direkt.

### LoudnessEnhancer
Nur auf echtem Gerät wirksam (Emulator ignoriert es lautlos). Absichern:
```kotlin
try { loudnessEnhancer = LoudnessEnhancer(audioSessionId); ... }
catch (e: Exception) { Log.w("TTS", ...) }
```
`audioSessionId` via `AudioManager.generateAudioSessionId()` erzeugen, als `KEY_PARAM_SESSION_ID` an `tts.speak()` übergeben.

### AudioManager Earpiece-Routing
`isSpeakerphoneOn` + `MODE_IN_COMMUNICATION` funktioniert **nicht** für TTS (STREAM_MUSIC). Samsung ignoriert das komplett.  
Richtige Lösung: `tts.setAudioAttributes()` in `TtsController.setAudioOutput()`:
```kotlin
AudioAttributes.Builder()
    .setUsage(if (speaker) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()
```
Android audio policy routet `USAGE_VOICE_COMMUNICATION` automatisch zum Ohrhörer.  
Nach `setAudioAttributes()` laufende Utterance stoppen + neu starten.

### Volume >100% auf Samsung
`LoudnessEnhancer` braucht die echte Session-ID des internen TTS-AudioTrack. Samsung TTS ignoriert `KEY_PARAM_SESSION_ID` → falscher Session → kein Effekt.  
Lösung: `AudioManager.setStreamVolume(stream, maxVolume, 0)` für den aktuellen Stream (STREAM_MUSIC oder STREAM_VOICE_CALL). Original-Lautstärke vorher speichern, bei ≤100% oder `release()` wiederherstellen. LoudnessEnhancer als Bonus obendrauf.

### MediaSession / Lock-Screen-Controls
`Notification.VISIBILITY_PUBLIC` + `setContentIntent` (PendingIntent auf MainActivity) + `setStyle(MediaStyle().setMediaSession(...))` nötig für Lock-Screen-Controls. `mediaSession.isActive = true` nicht vergessen.

## Test-Assets

Keine mehr. `app/src/main/assets/` ist leer, die früheren DEV-Knöpfe zum Direktladen von
`test.pdf`, `test.docx` und `test.epub` sind aus `MainActivity.kt` verschwunden. Zum Testen
Dokumente über den normalen Picker öffnen.

## Abhängigkeiten (app/build.gradle.kts)

```
com.tom-roush:pdfbox-android:2.0.27.0        // PDF-Textextraktion, ohne Bouncy Castle
org.jsoup:jsoup:1.17.2                       // EPUB/HTML-Parsing
net.dankito.readability4j:readability4j:1.0.8 // Artikeltext aus Webseiten
com.squareup.okhttp3:okhttp:4.12.0           // HTTP für URL-Laden und Voice-Downloads
org.apache.commons:commons-compress:1.27.1   // espeak-ng-data entpacken (tar.bz2)
libs/sherpa-onnx-1.13.2.aar                  // Piper-Inferenz, lokale AAR
```

`packaging.resources.excludes` nötig wegen META-INF-Konflikten von PdfBox.

`jniLibs.useLegacyPackaging = false`: .so unkomprimiert und direkt aus dem APK gemappt. Kleinerer
Download **und** kleinere Installation, weil keine zweite entpackte Kopie auf der Platte landet.

Kein `abiFilters`: die sherpa-onnx-AAR liefert arm64-v8a, armeabi-v7a, x86 und x86_64. Play liefert
pro Gerät nur die passende aus. Filtern würde 32-Bit-ARM, ChromeOS und Play Games on PC ausschließen,
ohne die Downloadgröße zu senken.

**Bouncy Castle ist aus PdfBox exkludiert** → passwortgeschützte PDFs scheitern mit einer Meldung in
der Statuszeile, ohne Crash.

**readability4j 1.0.8 ist unmaintained** (letztes Release 2019) und kompiliert gegen jsoup 1.11.2,
läuft hier gegen 1.17.2. `NoSuchMethodError` zur Laufzeit ist denkbar und träte nur auf bestimmten
Seiten auf.

### espeak-ng ist GPL-3.0

`libsherpa-onnx-jni.so` enthält einkompiliertes espeak-ng (GPL-3.0-or-later), erkennbar an den
Symbolen `espeak-ng`, `espeak-ng-data`, `espeakXXXXXX`. sherpa-onnx selbst ist Apache-2.0. Vor einer
Veröffentlichung klären, ansonsten steht die Lizenzfrage offen.

## Heading-Erkennung

`headingLevel(styleId: String): Int` in `MainActivity.kt`:
- DOCX liefert StyleId wie `"Heading1"`, `"berschrift2"`
- EPUB liefert `"h1"`–`"h4"`
- `WordDocView` nutzt `headingLevel()` für Schriftgröße und Abstände

## Internationalisierung (i18n)

48 Locales (`app/src/main/res/values-*`), dazu `values/` als Default (Englisch).  
Auto-Detect: System-Sprache bei erstem Start  
TTS-Sync: `TtsController.setLanguage(lang)` synchronisiert TTS mit UI-Sprache  
UI-Refresh: `recreate()` bei Sprachwechsel für sofortige Aktualisierung

**Beim Ändern eines Strings alle 49 Dateien anfassen**, sonst fällt die Sprache auf Englisch
zurück. Das gilt auch für `privacy_url` und `privacy_description`.

## Play Store Vorbereitung

Privacy Policy: https://nitram2345.github.io/readoutloud-privacy/ (Repo: `c:\dev\readoutloud-privacy`)
Berechtigungen: `INTERNET`, `READ_EXTERNAL_STORAGE` (maxSdk 32), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
`POST_NOTIFICATIONS` wird gebraucht (Foreground-Service-Notification ab Android 13) – im Manifest und in `MainActivity.onCreate` angefordert

Die Policy liegt als `index.html`, damit die nackte Verzeichnis-URL auflöst. `privacy-policy.html`
bleibt als Weiterleitung stehen, weil `privacy_url` in allen 49 `strings.xml` dorthin zeigt.

### Paketname

`de.readoutloud` für `namespace` und `applicationId` (umbenannt von `com.readoutloud` am
2026-08-23, vor der Veröffentlichung). **`applicationId` ist nach dem ersten Play-Upload
unveränderlich.**

Die Domain `readoutloud.de` gehört uns nicht. Play erzwingt nur Eindeutigkeit, aber falls die
Domain später relevant wird, wäre sie zu registrieren.

`AndroidManifest.xml` nutzt durchgehend relative Klassennamen (`.MainActivity`, `.TtsService`), die
gegen `namespace` auflösen — bei einer weiteren Umbenennung also nichts anzupassen. Wohl aber
`app/proguard-rules.pro`, das voll qualifizierte Namen enthält und bei falschem Paket still ins
Leere greift.

### Signing

`keystore.properties` im Repo-Root, gitignored, zeigt auf einen Keystore **außerhalb** des Repos.
Grund: `git clean -xdf` löscht auch ignorierte Dateien, und ein verlorener Upload-Key bedeutet ein
Support-Ticket bei Google.

Pfade in `.properties` mit Forward-Slash schreiben, auch unter Windows — `java.util.Properties`
behandelt `\` als Escape-Zeichen.

### 12-Tester-Regel

Der Play-Console-Account ist ein Privat-Account, angelegt nach dem 13.11.2023. Produktionszugang
gibt es erst nach einem Closed Test mit mindestens 12 durchgehend angemeldeten Testern über 14 Tage.
Internal Testing zählt dafür nicht.

### Backup
Voice-Modelle (20–110 MB) sind in `backup_rules.xml` + `data_extraction_rules.xml` ausgeschlossen.
Auto Backup hat 25 MB Quota – darüber bricht das System das **komplette** Backup ab, auch die SharedPreferences.

### Cleartext
Ab targetSdk 28 ist `http://` blockiert. `UrlLoader.httpsUpgrade()` schreibt http→https um, weil der Request sonst
immer mit "CLEARTEXT communication not permitted" scheitert.

### Voice-Dateinamen sind case-sensitive
Geschrieben wird `"${voice.name}.onnx"` (groß). Nicht `lowercase()` beim Löschen/Suchen – sonst bleiben 100 MB liegen.
