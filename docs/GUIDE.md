# Guide: an Echo Show 5 as your own smart display

This is how the Echo Home setup was built, written so you can reproduce it or change it to your taste. It covers the Android side (what runs on the Echo once it has a custom ROM) and how to customise it.

> **Honest scope.** Flashing the Echo is done by following the community thread for the `cronos` ROM, not this file, and it can **permanently brick the device**. The flash was done by the project owner, not by the code in this repo. Risks, sources and known ROM problems are collected in [issue #3](https://github.com/LucaArgentieri/echo-home/issues/3). The LineageOS build for `cronos` is unofficial: do not describe it as "hardened".

## 1. What you need

- An **Echo Show 5, 2nd generation** (2021, codename `cronos`). The 1st generation (`checkers`) uses different files; mixing them can brick the device.
- A computer with `adb`, JDK 17 and the Android SDK (the repo pins them with [mise](https://mise.jdx.dev)).
- A WPA2 Wi-Fi network (the ROM does not support WPA3).
- Time, and the willingness to lose the device.

## 2. Get LineageOS on the Echo (not covered here)

1. Read the whole community thread for the `cronos` ROM first (search "Echo Show 5 2nd gen LineageOS amonet cronos").
2. **Keep the Echo offline** until you start: an automatic Amazon update can close the exploit used to unlock the bootloader.
3. **Back up the partitions** before wiping anything, and store the backup outside the working machine. Without a valid backup you may not be able to return to the stock firmware.
4. Flash LineageOS 18.1 for `cronos`.

The build used here: `lineage_cronos-userdebug 11`, build of 24 June 2026.

## 3. Reach the Echo with adb

Enable developer options (tap "Build number" 7 times in About), then USB debugging. In this setup the Echo's USB-C port did not show up as a data device on the computer, so **adb over Wi-Fi** was used:

1. In developer options turn on network ADB; note the Echo's IP address (Settings → network).
2. `adb connect <ip>:5555`, then accept "Allow USB debugging?" on the Echo's screen.
3. With several devices attached: `export ANDROID_SERIAL=<ip>:5555`.

Check the hardware with `adb shell getprop ro.product.device` (expect `cronos`), `adb shell getprop ro.product.cpu.abi` (expect `armeabi-v7a`: 32-bit, so keep that ABI in the Gradle filter) and `grep MemTotal /proc/meminfo` (about 1 GB).

## 4. Install Meld (music)

Install the [Meld](https://github.com/FrancescoGrazioso/Meld) APK on the Echo (`adb install`), open it once and sign in or add something playable. Echo Home controls it over Media3; without it everything except music still works.

## 5. Build and install Echo Home

```
scripts/fetch-models.sh            # sherpa-onnx AAR + models, SHA-256 verified
scripts/gen-voice-clips.sh         # optional: instant-answer word clips (needs the TTS model, a JDK, macOS arm64 or set SHERPA_NATIVE)
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.echohome.app android.permission.RECORD_AUDIO
adb shell am start -n com.echohome.app/.MainActivity
```

The APK is about 250 MB (almost all Whisper), so installing over Wi-Fi takes minutes. If the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` the installed copy was signed with a different key: uninstall it first (this deletes its saved settings).

Reinstalling resets the app's preferences. The wake word is **off by default**: long-press the bottom-right corner of the screen (an invisible touch area) to turn it on; a thin grey line along the bottom edge means it is off.

## 6. What to expect

Measured on the real device (MT8163, 4× Cortex-A53 at 1.3 GHz, often only 2 cores online, about 1 GB RAM):

| Step | Time |
|---|---|
| Whisper base int8, model already loaded | 3.6–4.2 s per command |
| Whisper cold load | 14.5 s (so Echo Home loads it at start and never frees it) |
| Piper voice, free text | 7–8 s per sentence |
| Pre-rendered word clips (time, date, timers) | instant |
| Echo Home memory at rest | about 280 MB |

Things tried and rejected: Whisper tiny (1.7× faster but too inaccurate in Italian), a lighter Piper voice (not faster, the CPU is the limit), "Hey Jarvis"/"Hey Echo" wake words (never fired on this microphone). Big-model engines and LLMs do not fit and would not help: the bottleneck is speech recognition and synthesis, not understanding.

You can speak straight after "Alexa" ("Alexa, che ore sono" in one breath): the microphone stays open and the last 1.6 s are kept. A soft sound confirms listening, understanding or failure; the bar along the bottom edge shows the state.

## 7. Customise it

| I want to change… | Where |
|---|---|
| **Wake word** | `app/src/main/assets/kws/keywords.txt`, one phrase per line as BPE tokens with an optional boost and threshold (`▁A LE X A :3.0 #0.05 @ALEXA`). Turn a phrase into tokens with the `text2token` tool of sherpa-onnx. Test on the real microphone: synthetic clips are misleading. |
| **Commands and phrases** | `IntentParser.kt` (regex first, then 1–2 edit fuzzy matching) and a test in `IntentParserTest.kt`. Adding an intent means updating the `when` in `runCommand` and `finishSession` of `MainActivity.kt`. |
| **Wording of answers** | `Replies.kt` |
| **Language** | Whisper `language` in `SpeechToText.kt`, a Piper voice in `TextToSpeech.kt` (+ `assets/tts`), the answers in `Replies.kt`, the regexes in `IntentParser.kt`. |
| **Voice** | Any [sherpa-onnx Piper model](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models): change the model and tokens in `TextToSpeech.createEngine()`, then rerun `scripts/gen-voice-clips.sh`. Add words to `scripts/voice-clips/words.txt` to make more answers instant. |
| **Home city for the weather** | `HOME_LAT`/`HOME_LON` in `Weather.kt`; other cities by voice come from the fixed table `Cities` (no online geocoding, so the spoken text never leaves the device). |
| **Screen timings, dimming** | constants near the top of `MainActivity.kt` (`DIM_AFTER_MS`, `IDLE_BRIGHTNESS`, the return-to-Home delays, the night hours). |

Run `./gradlew testDebugUnitTest` after changing the parser or the replies. For quick checks without speaking: `adb shell "am start -n com.echohome.app/.MainActivity --es echo_cmd 'che ore sono'"` (debug builds).

## 8. Before you leave it running

- Remove everything but Echo Home and Meld; do not use root day to day.
- **Turn ADB off** when development is over; limit the network to what is needed (`api.open-meteo.com`, Meld's own traffic).
- Updates are manual only.
- Review the privacy notes in `docs/revisione-privacy.md`: no audio is ever written to disk, logged or uploaded.

## 9. Pitfalls we hit

- **Do not run `dumpsys media.audio_flinger`** on the Echo: it crashes the audio HAL (it restarts by itself, but the microphone and sound drop for a moment).
- The wake word is off after every reinstall (see section 5).
- The stock ROM has no echo canceller: the wake word while music plays is untested and may need software echo cancellation.
- The microphone is quiet and its level varies a lot (peaks from 0.02 to 0.45): normalise each utterance by its own peak before Whisper (a fixed gain clips loud speech).
- Whisper loses the first words if the recorder restarts after the wake word: keep the same `AudioRecord` open and hand the last second or two to the recorder.
- `armeabi-v7a` only: any native library or model runtime must have a 32-bit build.
- Do not judge speech accuracy from an emulator: its microphone path (host speaker to host mic) is badly distorted.

Open work and ideas: [issue #1](https://github.com/LucaArgentieri/echo-home/issues/1).
