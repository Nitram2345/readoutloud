<img src="rol.jpg" alt="ReadOutLoud icon" width="96" align="right">

# ReadOutLoud

An Android app that reads documents aloud. Open a PDF, a Word file, an EPUB or a web page, and it
speaks the text while highlighting the sentence it is on.

Speech runs on the device. Once a voice has been downloaded, no network connection is needed to
read anything.

## Features

- Reads PDF, DOCX, EPUB, ODT and HTML files, plus any web page by URL
- Sentence highlighting that follows the speech, in the rendered page for PDFs and in a document
  view for everything else
- Tap any line to start reading from there
- Resumes at the position where you stopped
- Playback controls in the notification area and on the lock screen
- Skip 30 seconds back and forth, a 30 minute sleep timer
- Adjustable speed and volume, with a boost past 100 %
- Output switchable between loudspeaker and earpiece
- Opens documents shared from other apps, and links shared from a browser

## Supported formats

| Format | Notes |
|---|---|
| PDF | Text extraction via PDFBox, with column detection for multi-column layouts. Rendered page shown alongside |
| DOCX | Headings recognised from style IDs |
| EPUB | XHTML chapters parsed in spine order |
| ODT | `content.xml`, paragraphs and headings |
| HTML | Local files |
| Web pages | Fetched by URL and reduced to the article text |

Encrypted PDFs are not supported: Bouncy Castle is excluded from the PDFBox dependency to keep the
app small.

## Speech

Two engines are available.

**Piper** is built in and runs on the device through sherpa-onnx. Voices are downloaded on demand
from the [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) repository, between
roughly 20 and 110 MB each. Pronunciation data comes from the sherpa-onnx release assets and is
fetched once. After that, synthesis is entirely offline.

**The Android system TTS engine** is used as an alternative, whichever engine the device provides.

## Languages

The interface is translated into 48 locales. The system language is detected on first start and can
be changed in the app; the speech language follows the interface language.

## Requirements

- Android 8.0 (API 26) or newer
- Roughly 150 MB of free space if you use several Piper voices

## Building

```bash
./gradlew assembleDebug     # debug APK
./gradlew installDebug      # build and install on a connected device
./gradlew bundleRelease     # signed release bundle for Google Play
./gradlew testDebugUnitTest # unit tests
```

A release build needs a `keystore.properties` in the repository root. It is gitignored, and without
it the release build still runs, it just comes out unsigned.

```properties
storeFile=/absolute/path/to/upload.keystore
storePassword=...
keyAlias=upload
keyPassword=...
```

Use forward slashes in `storeFile` even on Windows: `java.util.Properties` treats a backslash as an
escape character.

## Project layout

Everything lives in `app/src/main/java/de/readoutloud/`.

| Area | Files |
|---|---|
| UI | `MainActivity.kt`, `VoiceDialog.kt`, `ui/` |
| Document loading | `PdfLoader.kt`, `DocxLoader.kt`, `EpubLoader.kt`, `OdtLoader.kt`, `HtmlLoader.kt`, `UrlLoader.kt` |
| Speech | `TtsController.kt`, `TtsService.kt`, `TtsEngine.kt`, `PiperTtsEngine.kt` |
| Voices | `VoiceManager.kt`, `VoiceRepository.kt`, `VoiceRepositoryImpl.kt`, `PiperVoiceData.kt` |
| Settings | `AppSettings.kt` |

`PdfLoader.kt` also holds the `TextLine` and `DocumentData` types that every loader produces, which
is what lets the speech layer stay independent of the file format.

`CLAUDE.md` documents the architecture in more detail, along with the platform quirks that shaped
it — PDF coordinate origins, DOCX namespace handling, audio routing on Samsung devices and so on.
Read it before changing anything in those areas.

## Third-party components

| Component | Purpose | License |
|---|---|---|
| [PDFBox for Android](https://github.com/TomRoush/PdfBox-Android) | PDF text extraction | Apache-2.0 |
| [jsoup](https://jsoup.org/) | HTML and EPUB parsing | MIT |
| [readability4j](https://github.com/dankito/Readability4J) | Article extraction from web pages | Apache-2.0 |
| [OkHttp](https://square.github.io/okhttp/) | HTTP client | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Unpacking downloaded voice data | Apache-2.0 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | On-device speech synthesis | Apache-2.0 |
| [espeak-ng](https://github.com/espeak-ng/espeak-ng) | Phonemisation, linked into the sherpa-onnx native library | GPL-3.0-or-later |
| [Piper voices](https://huggingface.co/rhasspy/piper-voices) | Downloaded at runtime, not shipped | Varies per voice |

## Privacy

No data is collected, and there is no account, no analytics and no advertising. The app contacts
exactly three kinds of host: Hugging Face for voices, GitHub for pronunciation data, and whatever
web page you ask it to read. Documents are never uploaded.

Full text: <https://nitram2345.github.io/readoutloud-privacy/>

## Status

Pre-release. Not yet published on Google Play.

## License

No license has been chosen for this repository yet, so the default applies: all rights reserved.
Note that this needs resolving before any public release, because espeak-ng is linked into the
native speech library under GPL-3.0-or-later.
