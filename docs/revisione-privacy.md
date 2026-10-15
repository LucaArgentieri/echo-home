# Revisione privacy del microfono (12 ottobre 2026)

Revisione del codice a mano (non una verifica sul dispositivo). Esito: **nessun problema trovato**.

## Cosa è stato controllato
- **Audio su disco**: nessun `File`, `openFileOutput`, `cacheDir` o scrittura nei sorgenti che toccano il microfono (`WakeWordListener`, `MicRecorder`, `SpeechToText`). L'unica scrittura in `filesDir` è la copia di `espeak-ng-data` (dati del TTS).
- **Audio nei log**: i `Log` contengono solo etichette e tempi («wake word fired: <parola chiave>», durate TTS, stato del ducking, esito dell'aggiornamento meteo). Nessun testo riconosciuto, nessun audio.
- **Finestra mobile di 2 s** (aggiunta il 15 ott 2026): il rilevatore conserva in RAM, in un buffer circolare, gli ultimi 2 s ascoltati per passare al registratore la parte di frase già detta quando scatta la wake word («Alexa che ore sono» detto d'un fiato). Il buffer viene sovrascritto di continuo, azzerato quando il rilevatore si ferma e non va mai su disco, log o rete. Va ricontrollato nella prossima revisione.
- **Buffer**: i buffer del rilevatore, del registratore e del TTS sono azzerati a fine uso (`fill(0)`); i campioni dell'enunciato sono azzerati dopo la trascrizione.
- **Rete**: l'unica chiamata dell'app è Open-Meteo (solo coordinate: casa o una città della lista fissa, mai il testo parlato) più le cover di Meld. Le ricerche musicali vanno a Meld/YouTube Music, come da progetto.
- **Backup**: `allowBackup="false"`. Il comando di prova `echo_cmd` è ignorato nelle build non debug.
- **Microfono a riposo**: solo il rilevatore della wake word, nessun STT. Con la funzione sperimentale «interrompi con wake word» (spenta di default) il rilevatore resta attivo anche mentre l'assistente parla, sempre solo come rilevatore.

## Da sapere
- Il testo riconosciuto compare sullo schermo (barra di stato e campo di ricerca del pannello sviluppatore) finché non viene sovrascritto: è solo in RAM.
- Le **etichette dei timer** («pasta») sono parole riconosciute e vengono salvate in `SharedPreferences` per sopravvivere al riavvio. Sono necessarie al funzionamento e non contengono audio.

## Da fare sull'Echo
- Dopo il primo avvio: controllare con `adb logcat` che non compaiano testi riconosciuti.
- Prima della release: ADB e root disattivati (vedi issue #1, Sicurezza).
