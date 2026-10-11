# Echo Home v0.3

Primo prototipo Android per controllare Meld da una UI esterna tramite AndroidX Media3.

## Obiettivo di questa versione

Validare una sola cosa prima di aggiungere wake word e speech-to-text:

1. Echo Home si connette al `MediaLibraryService` esportato da Meld.
2. Una query testuale viene inviata a Meld come `MediaItem.RequestMetadata.searchQuery`.
3. Meld risolve la query con la propria pipeline di ricerca/voice-search e avvia la riproduzione.
4. Echo Home può inviare pausa/riprendi, next e previous e leggere titolo, artista, cover e posizione.

## Requisiti

- Android Studio recente
- JDK 17+
- Android SDK 36 (cmdline-tools, platform 36, build-tools)
- Un device/emulatore Android 8+; il target reale sarà LineageOS 18.1 / Android 11 sull'Echo Show 5 2nd gen
- Meld release installato con package `com.meld.app`

## Build APK debug

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (`adb install -r`).

## Test rapido

1. Apri questa cartella in Android Studio.
2. Attendi il Gradle Sync.
3. Installa Meld sullo stesso device/emulatore e avvialo almeno una volta.
4. Esegui Echo Home.
5. Lo stato deve diventare `Meld · connesso`.
6. Scrivi ad esempio `Radiohead` e premi `Cerca e riproduci`.
7. Verifica `Pausa`/`Riprendi`, `Next`, `Prev`, cover e titolo/artista.
8. Chiudi Meld a forza: Echo Home deve tornare a «non connesso» e riconnettersi.

Nota: `INTERNET` serve solo per scaricare la cover dall'URL pubblicato da Meld.

## Integrazione Meld usata

- Package APK release: `com.meld.app` (la build debug di Meld usa `com.meld.app.debug`, provata come secondo tentativo)
- Service class: `com.metrolist.music.playback.MusicService`
- Protocollo: AndroidX Media3 `MediaController` → `MediaLibraryService`
- Ricerca: `MediaItem.RequestMetadata.searchQuery`

## Cosa NON c'è ancora

- meteo
- timer
- launcher/kiosk mode

Queste funzionalità vengono aggiunte solo dopo aver validato la comunicazione Echo Home ↔ Meld.

## Gradle wrapper su macOS

Il repository non include binari preconfezionati. Al primo `./gradlew ...`, lo script scarica **solo** il wrapper JAR ufficiale Gradle 8.13 da `gradle/gradle`, ne verifica SHA-256 e poi Gradle verifica anche lo SHA-256 della distribuzione 8.13 prima dell'uso.

## V0.2: pipeline vocale

`Hey Jarvis` → beep → VAD → STT locale → intent → Meld. Tutto in locale, senza rete.

- **Wake word**: sherpa-onnx keyword spotting (`assets/kws`, modello gigaspeech-3.3M int8). **«Hey Jarvis» e «Hey Echo»** sono attive insieme (una riga ciascuna in `keywords.txt`; punteggio 2.0, soglia 0.2). «Echo» da solo NON è rilevabile con questi modelli (0/10 nei test; il modello a fonemi 2/10 con falsi positivi). Pulsante `Wake word: ON/OFF` (di default OFF: il microfono si apre solo se lo attivi). Si ferma quando l'app non è in primo piano.
- **VAD**: Silero v5 (`assets/silero-vad`). Fine frase dopo 0,7 s di silenzio; massimo 8 s; 5 s senza parlato = annulla.
- **STT**: Whisper base int8 in italiano (`assets/whisper-base`), offline. Elabora solo la parte parlata.
- **Intent**: `IntentParser.kt`, regex + tolleranza di 1 carattere su verbi e comandi brevi. Test: `./gradlew testDebugUnitTest`.
- **Musica**: dopo la wake word Meld va in pausa; riprende da sola se il comando non è «pausa» o una ricerca.
- Il pulsante `Parla` salta la wake word.

### Privacy del microfono
Il rilevatore legge 100 ms alla volta in un buffer riusato e li scarta; nessun file, nessun log dell'audio, nessuna rete. La registrazione del comando vive solo in RAM e viene azzerata dopo la trascrizione. Il log contiene solo l'evento «wake word detected».

### File grandi (non nel repository)
Metterli prima di compilare:

| Cosa | Da dove | Dove |
|---|---|---|
| `sherpa-onnx-1.13.8.aar` | release `v1.13.8` di `k2-fsa/sherpa-onnx` | `app/libs/` |
| Whisper base: `base-encoder.int8.onnx`, `base-decoder.int8.onnx`, `base-tokens.txt` | release `asr-models`, `sherpa-onnx-whisper-base.tar.bz2` | `app/src/main/assets/whisper-base/` |
| `silero_vad_v5.onnx` | release `asr-models` | `app/src/main/assets/silero-vad/` |
| TTS: `it_IT-paola-medium.onnx`, `tokens.txt`, `espeak-ng-data/` (solo italiano, ~1,7 MB) | release `tts-models`, `vits-piper-it_IT-paola-medium-int8.tar.bz2`; dall'`espeak-ng-data` completo tenere `intonations phondata phondata-manifest phonindex phontab it_dict lang/ voices/` | `app/src/main/assets/tts/` |
| KWS: `encoder/decoder/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx`, `tokens.txt` | release `kws-models`, `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2` (la variante `-mobile` NON funziona) | `app/src/main/assets/kws/` |

L'APK debug pesa circa 250 MB, quasi tutto per Whisper base.

### Cosa è stato provato e cosa no
- Su audio pulito (voce sintetica) Whisper base riconosce correttamente pausa/riprendi/prossima/precedente; «Hey Jarvis» scatta su 4 registrazioni su 7 senza falsi positivi su 8 frasi diverse.
- Nell'emulatore l'audio passa dal microfono del Mac e arriva molto degradato: l'accuratezza reale e la wake word con musica in riproduzione vanno misurate **sull'Echo**.

## V0.3 (in corso)

Fatto: volume, ora, timer, risposte parlate, meteo, wake word «Hey Jarvis»/«Hey Echo», UI base da smart display. Idee future: issue #1.

- **Comandi**: `volume 40` / `alza il volume` / `più piano`, `che ore sono`, `timer dieci minuti` / `annulla timer`.
- **TTS**: voce italiana locale (Piper «paola», sherpa-onnx). Risponde a ora, volume e timer, anche per i comandi scritti; non parla per i comandi musicali. Mentre parla il rilevatore di wake word è fermo e la musica riprende solo a frase finita.
- **Timer**: uno solo; la scadenza è salvata, quindi suona anche se l'app era chiusa. Alla scadenza dice «Il timer è scaduto» e poi suona fino a quando non lo fermi (tocco o «annulla timer»).
- **Meteo** (Open-Meteo, senza chiave): fisso su San Giuliano Milanese. `che tempo fa` → temperatura e condizioni adesso, massima/minima di oggi. `che tempo farà domani` → minima, massima, condizioni e probabilità di pioggia **per ognuna delle 24 ore** (tabella a schermo; a voce un riassunto con le fasce a rischio, dal 40%). Unica chiamata di rete dell'app (`api.open-meteo.com`), con le sole coordinate della città; risultato in cache per 10 minuti e, se la rete manca, si usa l'ultimo dato.
- **Test veloce da terminale** (solo build debug): `adb shell "am start -n com.echohome.app/.MainActivity --es echo_cmd 'timer 5 minuti'"`.

### UI smart display
Schermata unica, nera e minimale, pensata per 960×480 (Echo Show 5), a schermo intero e sempre accesa mentre l'app è in primo piano.
- In alto ora grande (con il timer accanto, se attivo) e a destra temperatura e condizioni; il meteo si aggiorna ogni 30 minuti (tocco = aggiorna ora).
- Al centro giorno e data; sotto copertina, titolo, artista, tempo e controlli ◀ ▶ del player.
- In basso a sinistra la riga dei messaggi (risposte, errori, «Meld non connesso»); a destra l'indicatore dell'assistente: `○` pronto, `● Ti ascolto…`, `◌ Elaboro…`. **Tocco = ascolto manuale** (e ferma l'ascolto in corso); **pressione lunga = wake word on/off** (`○` grigio = spenta).
- Pressione lunga sull'ora = pannello sviluppatore (campo di ricerca, «Apri Meld»). «Che tempo farà domani» apre la tabella oraria a tutto schermo (tocco per chiuderla).
- Per provare lo schermo dell'Echo nell'emulatore: `adb shell wm size 480x960 && adb shell wm density 200` (emulatore in landscape); `wm size reset` / `wm density reset` per tornare indietro.
