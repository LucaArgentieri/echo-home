# AGENTS.md — Echo Home

Context for AI coding agents working on this repository. Human-facing docs are in `README.md` (Italian). Ideas for later are in GitHub issue #1.

## What this is
Echo Home turns an **Amazon Echo Show 5 (2nd gen, codename `cronos`, LineageOS 18.1 / Android 11)** into an autonomous, user-controlled smart display. It is **not** an Alexa clone and must not use Amazon/Google/other cloud assistants. The finished device runs without a Pi, Home Assistant, NAS, phone or any local server. Internet is allowed only where it is natural (music streaming, weather).

Two apps live on the device:
- **Echo Home** (this repo): launcher/dashboard, wake word, mic handling, VAD, local STT, deterministic intent parser, weather, timer, volume, TTS, music control, assistant-state UI.
- **Meld** (`com.meld.app`, https://github.com/FrancescoGrazioso/Meld): the music engine (YouTube Music, Media3). Do not reimplement a player and do not fork Meld unless Media3 cannot do something essential.

Language: user-facing strings and voice replies are **Italian**; code, comments and commits are English.

## Hard requirements (from the owner)
- **Deterministic intents only** (regex/grammar). No LLM, no generalist assistant.
- **Privacy**: idle microphone = wake-word detector only (no STT). Audio lives only in RAM buffers that are reused or zeroed; never written to disk, logged or uploaded. Logs may contain events/labels/timings, never audio or recognised text.
- Local models for wake word, VAD, STT, TTS. Weather is the only network call (plus Meld's cover art).
- Keep it light for a weak SoC (MediaTek): no heavy frameworks, no heavy animations, Android Views (no Compose), no Google services, minSdk 26, targetSdk 30.
- Work in small verifiable milestones; each must produce an installable debug APK. Prefer simple, readable code over abstraction.
- Do not describe the LineageOS build for `cronos` as "hardened".

## Status (as of the last commit)
| Milestone | State |
|---|---|
| V0.1 Echo Home ↔ Meld via Media3 | Done, verified on an Android 11 emulator |
| V0.2 wake word → beep → VAD → STT → intent → Meld | Done in code; accuracy **not** validated on real hardware |
| V0.3 volume, time, timer, weather, TTS, wake phrases, base UI | Done in code and emulator |
| V0.4 real-device optimisation (CPU/RAM/heat, 24–48 h, kiosk, boot, foreground service) | **Not started** (needs LineageOS on the Echo; the device still runs Fire OS) |

Known limits: wake word with music playing, real STT accuracy and real performance are untested (the emulator mic path is badly degraded). The wake words are **"Hey Jarvis" / "Hey Echo" / "Alexa"** ("Alexa": 20/33 synthetic clips, 4/110 false hits, mostly "Alessia"; per-keyword `:3.0 #0.05` in `keywords.txt`); a bare "Echo" is not detectable with the sherpa-onnx KWS models (0/10 in offline tests).

## Architecture (all in `app/src/main/java/com/echohome/app/`)
| File | Role |
|---|---|
| `MainActivity.kt` | The only Activity (`singleTask`, landscape, immersive). Builds the UI in code (`buildUi()`): four full-screen panels (HOME hub, WEATHER, TIMERS, MUSIC), one visible at a time via `showScreen()`; commands pick their screen in `routeScreen()`, anything but HOME returns to HOME after 20 s (30 s for music) without touches. Connects to Meld, orchestrates the voice session, executes intents. ~1000 lines: grows by section (`// ----` headers). |
| `IntentParser.kt` | Pure Kotlin. `EchoIntent` sealed interface + `IntentParser.parse(text)`: regex first, then 1-edit-distance fuzzy matching for verbs/short commands. Handles music (incl. shuffle/repeat), volume (incl. unmute), time, date, timer (named, several at once, remaining/cancel by name), weather (now, tomorrow, next days). Returns `null` for unknown text. |
| `Replies.kt` | Pure Kotlin. Italian wording for answers; `describeDuration`, `rainSummary`, `rainTable`, WMO weather-code text. |
| `Weather.kt` | `WeatherClient` (Open-Meteo, **fixed to San Giuliano Milanese**, no key) + `WeatherParser` (org.json). |
| `KitchenTimer.kt` | Up to 4 named countdowns; deadlines stored as wall-clock in SharedPreferences (one line per timer), re-armed on start. |
| `WakeWordListener.kt` | `AudioRecord` → sherpa-onnx `KeywordSpotter`; mic released when it fires or on `stop()`. Keywords in `assets/kws/keywords.txt` (BPE tokens, one per line). |
| `MicRecorder.kt` | Records one utterance with Silero VAD (end after 0.7 s silence, max 8 s, 5 s no-speech timeout), adds 0.5 s pre-roll / 0.3 s post-roll, returns speech only. |
| `SpeechToText.kt` | sherpa-onnx offline Whisper **base int8**, language `it`; strips non-speech tags like `[Musica]`. |
| `TextToSpeech.kt` | sherpa-onnx Piper VITS "paola" (Italian) → `AudioTrack`. Copies `espeak-ng-data` from assets to `filesDir` once. Logs timings only. |

Voice session (in `MainActivity`): wake word (or tap on the assistant indicator) → `beginCommand` stops the wake listener, pauses Meld (remembering it was playing), beeps, records with VAD → Whisper → `IntentParser` → `runCommand` → `endSession`. `endSession` waits for TTS/network (`speakingCount`, `beginPending/endPending`, `whenSpeechDone`) before resuming music and restarting the wake listener, so the detector never hears the assistant's own voice. While the assistant speaks, Meld's player volume is ducked to 25% (`duckMusic`/`unduckMusic`, via `MediaController.volume`); during command recording the music stays paused.

Meld integration: `MediaController` on `SessionToken(ComponentName("com.meld.app"|"com.meld.app.debug", "com.metrolist.music.playback.MusicService"))`. Music search: the controller is a `MediaBrowser`; `playSearch` calls `getSearchResult` (Meld's `onGetSearchResult`, local + YouTube songs) and plays the first item by media id. Meld's own voice search (`MediaItem` with `RequestMetadata.searchQuery` -> `onSetMediaItems` -> `VoiceSearchMatcher`) is only the fallback: it drops artist-only queries (score 0.5 < 0.6 threshold) and the old queue keeps playing. Keep `SEARCH_PAGE_SIZE` large: Meld ignores the page size and Media3 crashes Meld if the answer is longer. Only music intents need Meld; timer/volume/time/weather work without it.

## Models and big files (NOT in git — see `.gitignore` and the README table)
`app/libs/sherpa-onnx-1.13.8.aar` (from the sherpa-onnx v1.13.8 release; not on Maven Central) and under `app/src/main/assets/`: `whisper-base/`, `silero-vad/`, `kws/` (only `keywords.txt` is tracked), `tts/` (Piper paola int8 + a minimal Italian-only `espeak-ng-data`). The debug APK is ~250 MB. Do not commit these.
Gotchas learned: the sherpa KWS `-mobile` model fails (Reshape error) — use the non-mobile `gigaspeech-3.3M` model; `▁J` is not a token, "Jarvis" works as `▁JA R VI S`.

## Build, test, run
Toolchain is pinned in `mise.toml` (JDK 17 + Android SDK). In a new shell: `eval "$(mise env)"`. Fresh clone: `scripts/fetch-models.sh` first (AAR + models, SHA-256 pinned in `scripts/models.sha256`; CI in `.github/workflows/build.yml` does the same).
```
./gradlew testDebugUnitTest assembleDebug        # JVM tests + debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Unit tests cover the parser, replies and weather parsing (`app/src/test/`; org.json is a test dependency because android.jar's is a stub). Everything else is verified on the emulator (AVD `echo30`, Android 11 arm64, started with `-allow-host-audio`).

**Fast testing (debug builds only)** — run any text command without the keyboard:
```
adb shell "am start -n com.echohome.app/.MainActivity --es echo_cmd 'timer 5 minuti'"
```
Use single quotes *inside* the double quotes (adb splits arguments on spaces). Emulate the Echo screen: `adb shell wm size 480x960 && adb shell wm density 200` (emulator is locked to landscape), reset with `wm size reset` / `wm density reset`.

`scripts/echo-check.sh` (adb health snapshot / per-minute CSV of temperature and memory, never audio) and `docs/V0.4-primo-avvio.md` (Italian step list for the real Echo) support milestone V0.4.

## Conventions and pitfalls
- Match the surrounding style; keep comments short and about *why*. No new abstractions without need.
- `when` over `EchoIntent` must stay exhaustive: adding an intent means updating `runCommand` and `finishSession` in `MainActivity`, plus tests.
- Anything that opens the microphone must go through `WakeWordListener`/`MicRecorder`; keep buffers in RAM and zero them. Never add audio logging.
- Do not start the wake listener while TTS is playing or a session is running (`startWake()` already guards `voiceBusy`/`isListening`).
- zsh does not word-split unquoted variables (`${=var}` when needed). The emulator is slow: avoid scripting keyboard typing; prefer `echo_cmd`.
- The mic path of the emulator (Mac speaker → host mic → emulator) distorts audio: do not judge STT or wake-word accuracy from it. Offline checks on clean TTS audio (macOS `say` + the sherpa-onnx JVM jars) are reliable for models; real accuracy must be measured on the Echo.
- GitHub issue #1 is the project todo: tick items when done and add new ideas there (`gh issue view 1 --json body -q .body`, edit a file, `gh issue edit 1 --body-file`).
- Commit locally per milestone with a clear message; pushing to GitHub (`LucaArgentieri/echo-home`, branch `main`) only when the owner asks.

## Security notes for the final device
Disable ADB after development, no root for daily use, only Echo Home + Meld installed, no public web server, no cloud microphone, no persistent audio, restrict network to what is needed (api.open-meteo.com and Meld's own traffic), manual updates only.
