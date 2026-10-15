# Echo Home

[English](README.md) · [Italiano](README.it.md)

**Turn an Amazon Echo Show 5 (2nd gen) into an offline, user-controlled smart display.** An Android app (Kotlin, plain Views, no Google services) that gives a LineageOS-flashed Echo a wake word, local speech recognition and a deterministic voice assistant for music, timers, volume, time and weather. Everything runs on the device; only music streaming and the weather forecast use the internet.

> Not affiliated with Amazon. "Alexa" is Amazon's trademark; here it is only a word a local detector listens for. Nothing is sent to Amazon, Google or any cloud assistant.

New here and want to do the same with your own Echo? Read the **[guide](docs/GUIDE.md)**.

## What it does

- **Wake word** (local): say "Alexa". Idle microphone = wake-word detector only, no transcription, no audio saved.
- **Voice commands** (local speech-to-text, regex intents, no LLM): music, volume, timers, time, date, weather. Italian only for now.
- **Music**: controls [Meld](https://github.com/FrancescoGrazioso/Meld) (YouTube Music client) through AndroidX Media3, so Echo Home is the voice and screen, Meld the player.
- **Smart-display UI**: a minimal Home (big clock, date, weather), plus full-screen Weather, Timers and Music screens that return home by themselves. Dimmed at night, anti burn-in.
- **Spoken answers** in Italian with a local voice; common answers (time, date, timers) play instantly from pre-rendered word clips.

Example commands (Italian): «che ore sono», «timer pasta dieci minuti», «che tempo fa domani», «metti i Radiohead», «alza il volume», «un po' più piano».

## Hard rules

- **Deterministic intents only.** Regex and grammar, no LLM, no general assistant.
- **Privacy first.** Audio lives in RAM buffers that are reused and zeroed; never written to disk, logged or uploaded. Logs hold events and timings only.
- **Local models** for wake word, VAD, speech-to-text and speech synthesis ([sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): KWS zipformer, Silero VAD, Whisper base int8, Piper VITS).
- **Light on a weak SoC**: Android Views (no Compose), minSdk 26, no heavy animations.

## Hardware and status

Target: Echo Show 5 2nd gen (`cronos`) running unofficial LineageOS 18.1 (Android 11). Real numbers measured on it (MT8163, 4× Cortex-A53 at 1.3 GHz, 32-bit, about 1 GB RAM):

| Step | Time |
|---|---|
| Speech-to-text (Whisper base, loaded) | about 4 s |
| Spoken answer, common cases (joined clips) | instant |
| Spoken answer, free text (Piper) | 7–8 s |
| Echo Home memory at rest | about 280 MB |

The device is slow: replies to actions (volume, next track) are shown on screen only, and the wake word sometimes needs two tries. See [issue #1](https://github.com/LucaArgentieri/echo-home/issues/1) for the live to-do list. Not yet done: kiosk/boot service, 24–48 h stability, wake word with music playing.

Planned next (see issue #1): photo backgrounds for the Home, and a recipe search that reads out a plain list of ingredients and steps.

Also runs on any Android 8+ phone or emulator (minus the real microphone and screen).

## Quick start (build from source)

Prerequisites: JDK 17 and Android SDK 36 (the repo pins them with [mise](https://mise.jdx.dev): `mise install`, then `eval "$(mise env)"` in each new shell), `adb`, and Meld installed on the device for music.

```
scripts/fetch-models.sh                          # sherpa-onnx AAR + models (not in git, SHA-256 checked)
scripts/gen-voice-clips.sh                       # optional: instant-answer word clips
./gradlew testDebugUnitTest assembleDebug        # unit tests + debug APK (~250 MB)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.echohome.app/.MainActivity
```

First run: grant the microphone permission, **long-press the bottom-right corner** of the screen (an invisible touch area, 72×40 dp) to turn the wake word on, then say "Alexa" and a command. A tap in the same corner listens once without the wake word. A thin grey line along the bottom edge means the wake word is off. Long-press the clock for the developer panel.

Test without a voice (debug builds): `adb shell "am start -n com.echohome.app/.MainActivity --es echo_cmd 'timer 5 minuti'"`.

## Documentation

- **[docs/GUIDE.md](docs/GUIDE.md)**: from a stock Echo to this setup, and how to customise it (wake word, voice, models, commands, city).
- [docs/DESIGN.md](docs/DESIGN.md): the Material 3 style reference (colour roles, type scale, shapes) used for every screen.
- [AGENTS.md](AGENTS.md): architecture and conventions (also for AI coding agents).
- [docs/revisione-privacy.md](docs/revisione-privacy.md): microphone privacy review (Italian).
- [docs/V0.4-primo-avvio.md](docs/V0.4-primo-avvio.md): first-boot checklist for the real device (Italian); `scripts/echo-check.sh` takes adb health snapshots (temperature, memory, never audio).
- [README.it.md](README.it.md): the full Italian README with per-version details.

## Credits and licences

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0), OpenAI Whisper, Silero VAD, Piper voices, [Meld](https://github.com/FrancescoGrazioso/Meld), Open-Meteo, and the community of the unofficial LineageOS build for `cronos`. Check each model's licence before redistributing the APK: the large files are deliberately not in this repository.
