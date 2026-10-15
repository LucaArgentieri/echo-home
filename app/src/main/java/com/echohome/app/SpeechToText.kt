package com.echohome.app

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig

/**
 * Local speech-to-text (sherpa-onnx + Whisper, Italian). Offline model: it decodes one
 * finished utterance at a time, which is what the wake word -> VAD -> STT flow produces.
 * Audio only ever lives in the FloatArray passed to [transcribe]; nothing is written anywhere.
 */
class SpeechToText(assets: AssetManager) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MODEL_DIR = "whisper-base"
    }

    private val recognizer: OfflineRecognizer

    init {
        val whisper = OfflineWhisperModelConfig().apply {
            encoder = "$MODEL_DIR/base-encoder.int8.onnx"
            decoder = "$MODEL_DIR/base-decoder.int8.onnx"
            language = "it"
            task = "transcribe"
        }
        val model = OfflineModelConfig().apply {
            this.whisper = whisper
            tokens = "$MODEL_DIR/base-tokens.txt"
            numThreads = 4
        }
        recognizer = OfflineRecognizer(assets, OfflineRecognizerConfig().apply { modelConfig = model })
    }

    /** Blocking; call from a background thread. [samples] are mono floats in -1..1 at 16 kHz. */
    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            // Whisper tags non-speech as "[Musica]", "(applausi)": that is not something the user said.
            return recognizer.getResult(stream).text
                .replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        } finally {
            stream.release()
        }
    }

    fun release() = recognizer.release()
}
