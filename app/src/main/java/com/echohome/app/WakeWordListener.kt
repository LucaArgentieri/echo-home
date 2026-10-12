package com.echohome.app

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Always-on wake word detector (sherpa-onnx keyword spotter, no speech-to-text).
 *
 * Privacy: audio is read in 100 ms chunks into one reused buffer, fed to the detector and
 * overwritten by the next chunk. Nothing is stored, logged or sent. The mic is released as
 * soon as the wake word fires or [stop] is called, so the command recorder can take it over.
 *
 * Keywords live in assets/kws/keywords.txt (BPE tokens, one per line): "Hey Jarvis", "Hey Echo" and "Alexa"
 * (the last with its own boost/threshold on the line: ":3.0 #0.05").
 * A bare "Echo" is not detectable with this small model (0/10 in tests); "Hey Echo" is.
 */
class WakeWordListener(private val assets: AssetManager) {

    companion object {
        private const val DIR = "kws"
        private const val SAMPLE_RATE = SpeechToText.SAMPLE_RATE
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var spotter: KeywordSpotter? = null

    val isListening get() = running

    /** [onWake] runs on the listener thread; the listener has already stopped listening then. */
    @SuppressLint("MissingPermission")
    fun start(onWake: () -> Unit) {
        if (running) return
        running = true
        thread = Thread({ listen(onWake) }, "wake-word").also { it.start() }
    }

    /** Stops listening and waits until the microphone is released. Not callable from the listener thread. */
    fun stop() {
        running = false
        thread?.takeIf { it !== Thread.currentThread() }?.join(2000)
        thread = null
    }

    private fun newSpotter(): KeywordSpotter {
        spotter?.let { return it }
        val model = OnlineModelConfig().apply {
            transducer = OnlineTransducerModelConfig().apply {
                encoder = "$DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                decoder = "$DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                joiner = "$DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
            }
            tokens = "$DIR/tokens.txt"
            numThreads = 1
            modelType = "zipformer2"
        }
        val config = KeywordSpotterConfig().apply {
            modelConfig = model
            keywordsFile = "$DIR/keywords.txt"
            keywordsScore = 2.0f
            keywordsThreshold = 0.2f
            maxActivePaths = 4
            numTrailingBlanks = 1
        }
        return KeywordSpotter(assets, config).also { spotter = it }
    }

    @SuppressLint("MissingPermission")
    private fun listen(onWake: () -> Unit) {
        val chunk = ShortArray(SAMPLE_RATE / 10)
        val floats = FloatArray(chunk.size)
        var record: AudioRecord? = null
        var woke = false
        try {
            val detector = newSpotter()
            val stream = detector.createStream("")
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, chunk.size * 4),
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) return
                record.startRecording()
                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    for (i in 0 until n) floats[i] = chunk[i] / 32768f
                    stream.acceptWaveform(if (n == floats.size) floats else floats.copyOf(n), SAMPLE_RATE)
                    while (detector.isReady(stream)) {
                        detector.decode(stream)
                        val keyword = detector.getResult(stream).keyword
                        if (keyword.isNotEmpty()) {
                            Log.i("EchoHome", "wake word fired: $keyword") // label only, never audio
                            detector.reset(stream)
                            woke = true
                            break
                        }
                    }
                    if (woke) break
                }
            } finally {
                stream.release()
            }
        } catch (_: Exception) {
            // Mic or model unavailable: just stop listening.
        } finally {
            running = false
            runCatching { record?.stop() }
            record?.release()
            chunk.fill(0)
            floats.fill(0f)
        }
        if (woke) onWake()
    }

    /** Frees the model. Call after [stop]. */
    fun release() {
        spotter?.release()
        spotter = null
    }
}
