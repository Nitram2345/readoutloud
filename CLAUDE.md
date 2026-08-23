# CLAUDE.md – ReadOutLoud

## Projekt

Android-App (Kotlin + Jetpack Compose) zum Vorlesen von PDF, DOCX, EPUB und ODT via Text-to-Speech.

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
| `MainActivity.kt` | Compose-UI, Toolbar, Dokument-Picker, TTS-Callbacks, i18n (7 Sprachen) |
| `PdfLoader.kt` | `TextLine`-Datenmodell, `DocumentData` sealed class, PDF laden + Text extrahieren, Spaltenlogik |
| `DocxLoader.kt` | DOCX → `List<TextLine>` via ZipInputStream + XmlPullParser |
| `HtmlLoader.kt` | HTML → `List<TextLine>` via Jsoup (h1–h6, p, li, td, th, dt, dd) |
| `EpubLoader.kt` | EPUB → `List<TextLine>` via ZipInputStream + Jsoup |
| `OdtLoader.kt` | ODT → `List<TextLine>` via ZipInputStream + XmlPullParser (content.xml, text:p/text:h) |
| `TtsController.kt` | TTS-Steuerung, Satz-Splitting, Sentence→Line-Mapping, Highlighting-Callbacks, `setLanguage()` |
| `TtsService.kt` | Foreground-Service: TTS-Lifecycle, MediaSession, Notification mit Media-Controls (CoroutineScope) |
| `AppSettings.kt` | SharedPreferences: lastUri, speechRate, savedLineIndex, volume, isSpeaker, language |

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

`app/src/main/assets/`:
- `test.pdf` – Spalten-Layout (mehrspaltig)
- `test2.pdf` – zweites Testdokument
- `test.docx` – Word mit Überschriften
- `test.epub` – EPUB zum Testen

DEV-Knöpfe in der App laden diese Dateien direkt.

## Abhängigkeiten (app/build.gradle.kts)

```
com.tom-roush:pdfbox-android:2.0.27.0   // PDF-Textextraktion
org.jsoup:jsoup:1.17.2                   // EPUB XHTML-Parsing
```

`packaging.resources.excludes` nötig wegen META-INF-Konflikten von PdfBox.

## Heading-Erkennung

`headingLevel(styleId: String): Int` in `MainActivity.kt`:
- DOCX liefert StyleId wie `"Heading1"`, `"berschrift2"`
- EPUB liefert `"h1"`–`"h4"`
- `WordDocView` nutzt `headingLevel()` für Schriftgröße und Abstände

## Internationalisierung (i18n)

10 Sprachen: DE, EN, EN_US, ES, FR, IT, NL, PT, SV, JA  
Sprach-Dateien: `values-{de,en,en-rUS,es,fr,it,nl,pt,sv,ja}/strings.xml`  
Auto-Detect: System-Sprache bei erstem Start  
TTS-Sync: `TtsController.setLanguage(lang)` synchronisiert TTS mit UI-Sprache  
UI-Refresh: `recreate()` bei Sprachwechsel für sofortige Aktualisierung

## Play Store Vorbereitung

Privacy Policy: https://nitram2345.github.io/readoutloud-privacy/
Berechtigungen: `READ_EXTERNAL_STORAGE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
`POST_NOTIFICATIONS` wird gebraucht (Foreground-Service-Notification ab Android 13) – im Manifest und in `MainActivity.onCreate` angefordert

### Backup
Voice-Modelle (20–110 MB) sind in `backup_rules.xml` + `data_extraction_rules.xml` ausgeschlossen.
Auto Backup hat 25 MB Quota – darüber bricht das System das **komplette** Backup ab, auch die SharedPreferences.

### Cleartext
Ab targetSdk 28 ist `http://` blockiert. `UrlLoader.httpsUpgrade()` schreibt http→https um, weil der Request sonst
immer mit "CLEARTEXT communication not permitted" scheitert.

### Voice-Dateinamen sind case-sensitive
Geschrieben wird `"${voice.name}.onnx"` (groß). Nicht `lowercase()` beim Löschen/Suchen – sonst bleiben 100 MB liegen.
