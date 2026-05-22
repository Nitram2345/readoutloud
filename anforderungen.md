# ReadOutLoud – Anforderungen

## Zweck

Android-App zum Vorlesen von Dokumenten (PDF, DOCX, EPUB) per Text-to-Speech.

---

## Unterstützte Formate

| Format | Vorschau         | TTS |
|--------|------------------|-----|
| PDF    | Gerenderte Seiten (Bitmap) | ✓ |
| DOCX   | Formatierter Text | ✓ |
| EPUB   | Formatierter Text | ✓ |

---

## Funktionen

### Dokument öffnen
- Datei-Picker: PDF, DOCX, EPUB
- Letztes Dokument beim App-Start automatisch laden
- Ladebalken während des Imports
- Laden abbrechen ("Abbrechen"-Knopf)
- Fehlermeldung wenn Datei nicht mehr vorhanden

### Vorlesen (TTS)
- Sprache: Deutsch
- Satzweise Ausgabe mit Highlighting der aktuellen Zeile(n)
- "Laut Lesen" startet ab getippter Stelle (PDF: Tipp auf Seite, DOCX/EPUB: Tipp auf Absatz)
- "Pause" pausiert und speichert aktuelle Position
- Fortsetzen ("Laut Lesen") setzt an pausierter Stelle fort
- Beim nächsten App-Start: automatisch zur gespeicherten Position scrollen, bereit zum Fortsetzen
- Benachrichtigung (Notification) mit Media-Controls (Play/Pause, ±30s) im Sperrbildschirm und Notification-Shade
- TTS läuft als Foreground-Service (`TtsService`) → bleibt im Hintergrund aktiv

### Lautstärke
- Bereich: 10% – 150%, Schritte à 10%
- Über 100%: `LoudnessEnhancer` mit dB-Gain (Gerät-abhängig)
- Lautstärke wird gespeichert (persistent)

### Geschwindigkeit
- Bereich: 1.0× – 3.0×, Schritte à 0.1
- Knöpfe "−" und "+" in der Toolbar (rund)
- Tachometer-Emoji 🕛 vor der Zahl
- Geschwindigkeit wird gespeichert (persistent)
- Änderung wirkt sofort (auch während des Vorlesens)

### Navigation
- "⬅"-Knopf (rund): pausiert, speichert Position, schließt App und stoppt Service
- «30s / 30s»: springen um ~30 Sekunden zurück/vor
- PDF: Auto-Scroll zur aktuellen Seite beim Vorlesen
- DOCX/EPUB: Auto-Scroll zum aktuellen Absatz
- PDF: Seitennummer-Anzeige (oben rechts, halbtransparent)
- Fortschrittsanzeige in Statuszeile: `📍 X% · Seite N/M` (PDF) bzw. `📍 X%` (DOCX/EPUB)

### Sleep-Timer
- "30m"-Knopf (rund): startet 30-Minuten-Timer, pausiert TTS bei Ablauf
- Während Timer läuft: Knopf zeigt verbleibende Minuten + ✕ (Abbrechen)

### Audio-Ausgabe
- 🔊/👂-Knopf (rund, untere Zeile): schaltet zwischen Lautsprecher und Ohrhörer um
- Einstellung wird gespeichert (persistent)
- Wirkung nur auf echtem Gerät (Emulator hat keinen Earpiece)

### Überschriften (DOCX, EPUB)
- Erkennung von Heading 1–4 (Deutsch + Englisch)
- Größere Schrift + Fettdruck + mehr Abstand nach oben
- Papier-Hintergrund (0xFFFAF8F5)

### Internationalisierung
- 7 Sprachen: DE, EN, NL, IT, FR, PT, SV
- Sprachauswahl: Flaggen-Button (🇩🇪/🇬🇧/🇳🇱/🇮🇹/🇫🇷/🇵🇹/🇸🇪)
- Auto-Detect: System-Sprache bei erstem Start
- TTS-Sync: TTS-Sprache wechselt mit UI-Sprache
- Help-Dialog: "?"-Knopf mit Bedienungsanleitung + Datenschutz-Link

### PDF-Spalten
- Automatische Spaltenerkennung per Histogramm
- Tipp in linke Spalte → liest linke Spalte, dann rechte
- Korrekte Lesereihenfolge über Seiten hinweg

---

## UI-Elemente (Toolbar)

Alle runden Knöpfe (`RoundBtn`): `CircleShape`, 33×33 dp, 2dp Content-Padding.

```
Zeile 1: [⬅] [Öffnen] [🇩🇪] [?] ···Spacer··· [≡] [−] [🕛1.0×] [+]
Zeile 2: [«30s] [Laut Lesen / Pause (min. 110dp)] [30m] [30s»] ···Spacer··· [🔊/👂] [−] [🔊100%] [+]
Horizontal spacing: 7dp
```

Runde Knöpfe: ⬅, 🇩🇪 (Sprache), ?, ≡ (Inhaltsverzeichnis), «30s, 30m, 30s», 🔊/👂, −(×2), +(×2)  
Rechteckig: Öffnen, Laut Lesen/Pause, Abbrechen

---

## Technische Rahmenbedingungen

- Min SDK: 26 (Android 8)
- Sprache: Kotlin + Jetpack Compose
- TTS: Android `TextToSpeech` API
- PDF-Rendering: Android `PdfRenderer`
- PDF-Textextraktion: PdfBox Android (`com.tom-roush:pdfbox-android`)
- DOCX-Parsing: `ZipInputStream` + `XmlPullParser` (kein Namespace-Processing)
- EPUB-Parsing: `ZipInputStream` + `XmlPullParser` + Jsoup
- Persistenz: `SharedPreferences`

---

## Offen / Ideen

- "Öffnen mit" Intent-Handler (EPUB/PDF direkt aus anderen Apps)
- Scrollposition pro Datei merken (aktuell nur eine gespeicherte Position)
- DOC-Format (älteres Word-Format)
- Zuletzt geöffnete Dateien (Liste)
