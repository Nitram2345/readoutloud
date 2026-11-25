# Play Console – Vorschläge für die Formulare

Stand 2026-11-25, abgeleitet aus dem Code (Manifest, `UrlLoader`, `VoiceRepositoryImpl`,
`VoiceManager`, `TtsService`). Bezeichnungen der Console auf Englisch, weil die deutsche
Oberfläche je nach Konto unterschiedlich übersetzt.

## Grundlage: was die App tatsächlich überträgt

| Ziel | Wann | Was geht raus |
|---|---|---|
| `huggingface.co` | Nutzer öffnet Stimmenauswahl / lädt Stimme | GET auf Katalog und Modelldatei, keine Nutzerdaten |
| `github.com` | Erster Piper-Einsatz | GET auf `espeak-ng-data`, keine Nutzerdaten |
| beliebige Website | Nutzer gibt URL ein oder teilt Link | GET auf genau diese URL, User-Agent `Mozilla/5.0 (Android) ReadOutLoud` |

Kein eigener Server, kein Analytics-, Crash- oder Werbe-SDK, kein Konto, keine Advertising-ID.
Dokumente verlassen das Gerät nie. Android Auto Backup sichert die Einstellungen in das
Google-Konto des Nutzers — das gilt laut Google nicht als Erhebung durch den Entwickler.

---

## App access

**All functionality is available without special access.** Kein Login.

## Ads

**No, my app does not contain ads.**

## Content rating (IARC)

- E-Mail: deine Kontakt-Adresse
- Category: **All Other App Types** (Utility; „Reference, News, or Educational" ginge auch,
  ändert am Ergebnis nichts)
- Violence, Sexuality, Language, Controlled substances, Crude humor, Gambling: **No**
- Users interact or exchange content: **No**
- Shares user location: **No**
- Digital purchases: **No**
- Unrestricted internet access / web browser: **Yes** — das URL-Vorlesen öffnet beliebige
  Seiten. Ehrlich beantworten; das ergibt nur den Hinweis „Unrestricted Internet", keine
  höhere Altersstufe.

Erwartetes Ergebnis: PEGI 3 / USK 0 / ESRB Everyone.

## Target audience and content

- Age groups: **13–15, 16–17, 18 and over**
- **Kein „unter 13"** — sonst greift die Families-Policy mit Zusatzauflagen, und beliebige
  Webinhalte wären für Kinder ohnehin ein Problem.
- Could your app unintentionally appeal to children? **No**

## Data safety

- Does your app collect or share any of the required user data types? **No**

  Begründung: „Collect" heißt laut Google, Daten gehen vom Gerät **an den Entwickler** oder
  dessen Dienstleister. Es gibt keinen Server des Entwicklers. Der Abruf einer vom Nutzer
  gewählten Webseite ist eine vom Nutzer ausgelöste Übertragung an ein Ziel seiner Wahl, wie
  bei einem Browser. Die Stimmen-Downloads übertragen keine Nutzerdaten.

- Falls die Console trotzdem nach Sicherheit fragt:
  - Data encrypted in transit: **Yes** (HTTPS; `http://` wird auf `https://` umgeschrieben)
  - Users can request deletion: entfällt, nichts gespeichert. Konto-Löschung: entfällt, kein Konto.

Passt zur Policy unter `https://nitram2345.github.io/readoutloud-privacy/` („no data collected").

## Advertising ID

**No** — die App nutzt keine Advertising-ID. Nach dem nächsten Build im zusammengeführten
Manifest gegenprüfen, dass keine Bibliothek `com.google.android.gms.permission.AD_ID` einschleppt
(`app/build/intermediates/merged_manifests/release/.../AndroidManifest.xml`). Nach dem
Abhängigkeitsstand sollte keine das tun.

## Government apps / Financial features / Health / News

- Government app: **No**
- Financial features: **My app doesn't provide any financial features**
- Health: **None**
- News app: **No** — liest Webseiten vor, veröffentlicht aber keine Nachrichten

## Foreground service permissions

Typ: **Media playback** (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`)

Beschreibung (Englisch, für das Feld):

> ReadOutLoud reads documents and web pages aloud with text-to-speech. The media playback
> foreground service keeps speech running when the user leaves the app or turns off the screen,
> and provides play/pause, skip and speed controls in the notification and on the lock screen.
> Playback is always started by the user tapping "Read aloud". Without the service, Android
> would stop speech as soon as the app is in the background, which defeats the purpose of a
> read-aloud app.

Impact if deferred or interrupted: **Playback stops immediately, user-noticeable.**

Video (30–60 s, als nicht gelistetes YouTube-Video verlinken):
1. App öffnen, PDF öffnen (`quick-guide-gplv3.pdf`)
2. „Laut lesen" tippen — Vorlesen läuft, Satz wird markiert
3. Home-Taste — Vorlesen läuft weiter, Notification mit Controls zeigen
4. Bildschirm aus und wieder an — Lock-Screen-Controls zeigen, dort Pause / Play tippen
5. Zurück in die App, Pause

**Achtung, Code-Befund:** `TtsService` geht schon beim App-Start in den Vordergrund (der erste
Wert von `isPlaying` ist `false` und löst `startForegroundCompat` aus) und bleibt es auch im
Pause-Zustand — `stopForeground` wird nirgends aufgerufen. Google erwartet für `mediaPlayback`,
dass der Service nur während der Wiedergabe im Vordergrund läuft. Gängige Praxis: beim Pausieren
`stopForeground(STOP_FOREGROUND_DETACH)`, die Notification bleibt dann sichtbar, ist aber
wegwischbar. Senkt das Ablehnungsrisiko bei der Prüfung.

## Store settings

- App category: **Productivity** (Alternative: Books & Reference)
- Contact email: Pflichtfeld, wird öffentlich angezeigt — eigene Adresse für die App erwägen
- Website: `https://github.com/Nitram2345/readoutloud`
