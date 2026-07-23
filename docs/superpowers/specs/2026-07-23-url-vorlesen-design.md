# URLs öffnen und vorlesen — Design

**Datum:** 2026-07-23
**Status:** Genehmigt

## Ziel

Der Nutzer kann eine Webseite in ReadOutLoud öffnen und vorlesen lassen — über
das Android-Teilen-Menü (aus dem Browser) und über "Öffnen mit" beim Antippen
eines Links.

## Flow

```
Browser
  ├─ "Teilen"  ─────────────► ACTION_SEND  (text/plain, EXTRA_TEXT = URL)
  └─ Link antippen ─────────► ACTION_VIEW  (http/https Uri)
                                   │
                                   ▼
                        URL aus Intent ziehen
                                   │
                                   ▼
                 OkHttp GET  (Redirects auflösen)
                                   │
                                   ▼
                 Readability4J  (Artikel extrahieren)
                                   │
                                   ▼
        geteilter Extractor  →  List<TextLine>  →  DocumentData.Word
                                   │
                                   ▼
                     bestehender Vorlese-Flow
```

## Komponenten

### 1. AndroidManifest.xml

Zwei neue intent-filter an `MainActivity`:

- **ACTION_SEND** — App erscheint im Teilen-Menü.
  ```xml
  <intent-filter>
      <action android:name="android.intent.action.SEND" />
      <category android:name="android.intent.category.DEFAULT" />
      <data android:mimeType="text/plain" />
  </intent-filter>
  ```
- **ACTION_VIEW http/https** — App erscheint in "Öffnen mit" bei Link-Tap.
  `category.BROWSABLE` ist Pflicht, damit Links aus anderen Apps/Browsern die
  App anbieten.
  ```xml
  <intent-filter>
      <action android:name="android.intent.action.VIEW" />
      <category android:name="android.intent.category.DEFAULT" />
      <category android:name="android.intent.category.BROWSABLE" />
      <data android:scheme="http" />
      <data android:scheme="https" />
  </intent-filter>
  ```

`INTERNET`-Permission ist bereits vorhanden.

### 2. app/build.gradle.kts

Neue Dependency:
```kotlin
implementation("net.dankito.readability4j:readability4j:1.0.8")
```
OkHttp (`com.squareup.okhttp3:okhttp:4.12.0`) und Jsoup sind bereits vorhanden.

### 3. HtmlLoader.kt (Refactor)

Extraktions-Kern in eine geteilte Funktion ziehen:
```kotlin
fun extractLinesFromElement(root: org.jsoup.nodes.Element): List<TextLine>
```
`extractHtmlLines(context, uri)` ruft sie nach `Jsoup.parse(...)` auf. Der
URL-Pfad ruft sie mit dem Readability-Artikel-Element auf. Kein
Verhaltenswechsel für lokale HTML-/EPUB-Dateien (Selektor
`h1..h6,p,li,td,th,dt,dd` und `TextLine`-Mapping bleiben gleich).

### 4. UrlLoader.kt (neu)

```kotlin
suspend fun loadUrl(url: String): DocumentData.Word   // Dispatchers.IO
```

Ablauf:
1. **Fetch** via OkHttp mit `.followRedirects(true).followSslRedirects(true)`,
   User-Agent und Timeout. HTTP-3xx werden automatisch aufgelöst
   (t.co, bit.ly, http→https, News-Weiterleitungen).
2. **End-URL** aus `response.request.url` — dient als Basis-URI für Readability
   (korrekte Auflösung relativer Links).
3. **Meta-Refresh** — eine Ebene manuell nachladen, falls das HTML ein
   `<meta http-equiv="refresh" ... url=...>` enthält (häufig bei
   Consent-/Google-Seiten).
4. **Readability** — `Readability4J(finalUrl, html).parse()` liefert
   `articleContent` (Element) und `title`.
5. **Extraktion** — `articleContent` durch `extractLinesFromElement(...)`;
   `title` als erste Zeile mit `style = "h1"` voranstellen (falls vorhanden).
6. Rückgabe als `DocumentData.Word`.

### 5. MainActivity.kt

Intent-Handling erweitern (im bestehenden `LaunchedEffect` bzw.
Intent-Auswertung):

- `ACTION_SEND`: `EXTRA_TEXT` lesen, erste `https?://\S+` per Regex ziehen.
- `ACTION_VIEW`: `intent.data` — bei Scheme `http`/`https` → als URL behandeln,
  sonst weiter über `loadUri` (lokale `content://`-Datei).
- Neue lokale `loadUrl(url: String)`-Funktion, Spiegel von `loadUri`:
  `isLoading`/`statusText`/`onPrimeFrom`/Fehlerbehandlung, ruft
  `UrlLoader.loadUrl(...)`.
- `settings.lastUri` speichert die http-Uri. Der Restore-`LaunchedEffect`
  routet per Scheme: `http`/`https` → `loadUrl`, sonst → `loadUri`.

## Fehlerbehandlung

Kein Netz / Timeout / 404 / leerer Artikel → bestehender `status_error`-String.
Keine neuen String-Ressourcen (spart Übersetzung in 49 Locales).

## Test

JVM-Unit-Test (`app/src/test/...`) — Jsoup und Readability4J laufen pur auf der
JVM, kein Gerät nötig:
- URL-Regex-Extraktion aus geteiltem Text ("Titel\nhttps://…" → URL).
- Readability auf Sample-HTML mit `<nav>`/`<footer>` + `<article>`:
  Navigation/Footer verschwinden, Artikel-Absätze bleiben in `List<TextLine>`.

## Bewusste Vereinfachungen (ponytail)

- **launchMode `standard`** — Teilen/Öffnen bei laufender App startet eine neue
  Activity-Instanz statt `onNewIntent`. Ceiling: `singleTop` + `onNewIntent`,
  falls Stacking stört.
- **JS-Redirects** werden nicht aufgelöst (bräuchte Browser-Engine). Nur
  HTTP-3xx + eine Ebene Meta-Refresh.
