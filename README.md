# Echo Home v0.3

Smart display Android per Echo Show 5: controlla Meld tramite AndroidX Media3, con wake word, comandi vocali, meteo e timer, tutto in locale.

## Obiettivo di questa versione

Validare una sola cosa prima di aggiungere wake word e speech-to-text:

1. Echo Home si connette al `MediaLibraryService` esportato da Meld.
2. Una query testuale viene inviata a Meld come `MediaItem.RequestMetadata.searchQuery`.
3. Meld risolve la query con la propria pipeline di ricerca/voice-search e avvia la riproduzione.
4. Echo Home può inviare pausa/riprendi, next e previous e leggere titolo, artista, cover e posizione.

## Come avviarlo

### 1. Prerequisiti
- Con [mise](https://mise.jdx.dev) (consigliato): `mise install` nella cartella del progetto installa JDK 17 e Android SDK (versioni in `mise.toml`), poi in ogni nuova shell `eval "$(mise env)"`. In alternativa JDK 17+ e Android SDK 36 a mano (o Android Studio).
- Un dispositivo o emulatore Android 8+ (target reale: Echo Show 5 2nd gen con LineageOS 18.1 / Android 11) con `adb` raggiungibile (`adb devices`).
- **Meld** installato (`com.meld.app`) e avviato almeno una volta, con un account/brano utilizzabile. Senza Meld funzionano comunque ora, data, timer, volume e meteo; solo i comandi musicali no.

### 2. File grandi
AAR di sherpa-onnx e modelli **non sono nel repository**: `scripts/fetch-models.sh` li scarica e li mette al loro posto (verifica SHA-256, si può rilanciare senza riscaricare). Dettaglio dei file in «File grandi (non nel repository)» più sotto.

### 3. Compila, installa, avvia
```
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.echohome.app/.MainActivity
```
Il primo `./gradlew` scarica il wrapper Gradle (verificato con SHA-256). L'APK è circa 250 MB: l'installazione sull'emulatore richiede qualche secondo.

### 4. Primo utilizzo
1. All'avvio l'app mostra orologio, data e meteo; in basso a sinistra compare «Meld non connesso» finché Meld non risponde.
2. Concedi il permesso microfono quando richiesto (serve solo per la voce).
3. Tocca l'indicatore `○` in basso a destra per parlare senza wake word; **pressione lunga** su `○` = attiva/disattiva la wake word «Hey Jarvis» / «Hey Echo» / «Alexa» (di default spenta).
4. Prova: «che ore sono», «timer cinque minuti», «che tempo fa domani», «metti i Radiohead».
5. Pannello sviluppatore con campo di ricerca testuale: pressione lunga sull'orologio.

### 5. Emulatore
Emulatore con microfono del Mac: avvialo con `-allow-host-audio`. Per simulare lo schermo dell'Echo: `adb shell wm size 960x480 && adb shell wm density 200` (ripristino: `wm size reset`, `wm density reset`). L'audio via microfono del Mac è molto degradato: non giudicare da lì la precisione del riconoscimento.

Test rapido senza voce né tastiera (solo build debug): `adb shell "am start -n com.echohome.app/.MainActivity --es echo_cmd 'timer 5 minuti'"`.

### Verifica con Meld (V0.1)
Scrivi `Radiohead` nel pannello sviluppatore e premi `Cerca e riproduci`; poi prova pausa/riprendi, next, previous, cover e titolo/artista. Chiudendo Meld a forza Echo Home deve tornare a «non connesso» e riconnettersi. `INTERNET` serve solo per cover, meteo e controllo release.

## Integrazione Meld usata

- Package APK release: `com.meld.app` (la build debug di Meld usa `com.meld.app.debug`, provata come secondo tentativo)
- Service class: `com.metrolist.music.playback.MusicService`
- Protocollo: AndroidX Media3 `MediaController` → `MediaLibraryService`
- Ricerca: `MediaItem.RequestMetadata.searchQuery`

## Cosa non c'è ancora

Launcher/kiosk, avvio al boot e ottimizzazione sull'Echo reale (V0.4); vedi issue #1.

## Gradle wrapper su macOS

Il repository non include binari preconfezionati. Al primo `./gradlew ...`, lo script scarica **solo** il wrapper JAR ufficiale Gradle 8.13 da `gradle/gradle`, ne verifica SHA-256 e poi Gradle verifica anche lo SHA-256 della distribuzione 8.13 prima dell'uso.

## V0.2: pipeline vocale

`Hey Jarvis` → beep → VAD → STT locale → intent → Meld. Tutto in locale, senza rete.

- **Wake word**: sherpa-onnx keyword spotting (`assets/kws`, modello gigaspeech-3.3M int8). **«Hey Jarvis», «Hey Echo» e «Alexa»** sono attive insieme (una riga ciascuna in `keywords.txt`; punteggio 2.0, soglia 0.2; «Alexa» ha il suo punteggio 3.0 e soglia 0.05 sulla riga). «Alexa», su 33 voci sintetiche (3 frasi × 11 voci): 20 riconosciute, e 4 falsi positivi su 110 clip di altre parole («Alessia» è la più confondibile). «Echo» da solo NON è rilevabile con questi modelli (0/10 nei test; il modello a fonemi 2/10 con falsi positivi). Di default OFF: il microfono si apre solo se la attivi (pressione lunga sull'indicatore `○`). Si ferma quando l'app non è in primo piano.
- **VAD**: Silero v5 (`assets/silero-vad`). Fine frase dopo 0,7 s di silenzio; massimo 8 s; 5 s senza parlato = annulla.
- **STT**: Whisper base int8 in italiano (`assets/whisper-base`), offline. Elabora solo la parte parlata.
- **Intent**: `IntentParser.kt`, regex + tolleranza di 1 carattere su verbi e comandi brevi. Test: `./gradlew testDebugUnitTest`.
- **Musica**: dopo la wake word Meld va in pausa; riprende da sola se il comando non è «pausa» o una ricerca.
- Un tocco sull'indicatore `○` salta la wake word.

### Privacy del microfono
Il rilevatore legge 100 ms alla volta in un buffer riusato e li scarta; nessun file, nessun log dell'audio, nessuna rete. La registrazione del comando vive solo in RAM e viene azzerata dopo la trascrizione. Il log contiene solo l'evento «wake word detected».

### File grandi (non nel repository)
Li scarica `scripts/fetch-models.sh` (la tabella resta come riferimento):

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

Fatto: volume, ora, timer, risposte parlate, meteo, wake word «Hey Jarvis»/«Hey Echo»/«Alexa», UI base da smart display. Idee future: issue #1.

- **Comandi**: `volume 40` / `alza il volume` / `più piano`, `che ore sono`, `timer dieci minuti` / `timer pasta 10 minuti` / `annulla il timer della pasta` / `annulla tutti i timer` / `quanto manca al timer` / `quanti timer ho`, `che tempo fa nei prossimi giorni` / `meteo della settimana`, `che tempo fa a Roma domani` (città in una lista fissa, offline), `un po' più forte` / `volume 35` (percentuale esatta), `che giorno è`, `muto` / `riattiva audio`, `mischia` / `non mischiare`, `ripeti brano` / `non ripetere`. Un tocco sull'indicatore mentre parla azzera la voce.
- **TTS**: voce italiana locale (Piper «paola», sherpa-onnx). Risponde a ora, volume e timer, anche per i comandi scritti; non parla per i comandi musicali. Mentre parla il rilevatore di wake word è fermo e la musica riprende solo a frase finita.
- **Timer**: fino a 4 insieme, anche con un nome («timer pasta 10 minuti»). Le scadenze sono salvate, quindi suonano anche se l'app era chiusa. Alla scadenza dice «Il timer pasta è scaduto» e poi suona fino a quando non lo fermi (tocco o «annulla timer»).
- **Meteo** (Open-Meteo, senza chiave): fisso su San Giuliano Milanese; a voce si può chiedere anche un'altra città tra una quarantina di città italiane (`che tempo fa a Roma`, coordinate nel codice: nessuna ricerca online, il testo parlato non lascia il dispositivo). Icone a emoji accanto alle condizioni (luna di notte). `che tempo fa` → temperatura e condizioni adesso, massima/minima di oggi. `che tempo farà domani` → minima, massima, condizioni e probabilità di pioggia **per ognuna delle 24 ore** (tabella a schermo; a voce un riassunto con le fasce a rischio, dal 40%). Unica chiamata di rete dell'app (`api.open-meteo.com`), con le sole coordinate della città; risultato in cache per 10 minuti e, se la rete manca, si usa l'ultimo dato.
- **Aggiornamenti**: una volta a settimana l'app chiede a GitHub (`api.github.com`, sola GET, nessun dato del dispositivo) l'ultima release pubblicata. Se è più recente della versione installata, la Home mostra «Nuovo aggiornamento: v…» finché non installi la versione nuova. Nessuna notifica push e nessun aggiornamento automatico.
- **Test veloce da terminale** (solo build debug): `adb shell "am start -n com.echohome.app/.MainActivity --es echo_cmd 'timer 5 minuti'"`.

### UI smart display
Schermata unica, nera e minimale, pensata per 960×480 (Echo Show 5), a schermo intero e sempre accesa mentre l'app è in primo piano.
- Di notte (23–6) lo schermo è attenuato e il layout si sposta di pochi pixel ogni ora (anti burn-in).
- Home minimale: data, ora grande, meteo attuale; timer e brano in riproduzione compaiono come una riga solo se attivi. Meteo, timer e musica hanno ciascuno la propria schermata a schermo intero e dopo 20 s senza tocchi (30 s la musica) si torna alla Home. Il meteo si aggiorna ogni 30 minuti (non mentre lo schermo notturno è spento). Di notte (23–6) la luminosità è al 10%, e all'1% dopo 3 minuti di quiete; un tocco o un comando la riporta su.
- **Interrompere la voce con la wake word** (sperimentale, **spento** di default; interruttore nel pannello sviluppatore, pressione lunga sull'orologio). Durante la risposta il rilevatore ascolta con il cancellatore d'eco della piattaforma, se la ROM ce l'ha; se la wake word scatta, la voce si ferma e parte un nuovo comando. Non validato: serve l'Echo.
- Al centro giorno e data; sotto copertina, titolo, artista, tempo e controlli ◀ ▶ del player.
- In basso a sinistra la riga dei messaggi (risposte, errori, «Meld non connesso»); a destra l'indicatore dell'assistente: `○` pronto, `● Ti ascolto…`, `◌ Elaboro…`. **Tocco = ascolto manuale** (e ferma l'ascolto in corso); **pressione lunga = wake word on/off** (`○` grigio = spenta).
- Pressione lunga sull'ora = pannello sviluppatore (campo di ricerca, «Apri Meld»). «Che tempo farà domani» apre la tabella oraria a tutto schermo (tocco per chiuderla).
- Per provare lo schermo dell'Echo nell'emulatore: `adb shell wm size 480x960 && adb shell wm density 200` (emulatore in landscape); `wm size reset` / `wm density reset` per tornare indietro.

## Sull'Echo reale
Lista di step per il primo avvio (flash, controlli, test del microfono e prestazioni): [docs/V0.4-primo-avvio.md](docs/V0.4-primo-avvio.md). Controlli via adb: `scripts/echo-check.sh info` e `scripts/echo-check.sh sample 60` (nessun audio registrato). Rischi del flash: issue #3.
