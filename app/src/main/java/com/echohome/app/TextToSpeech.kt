package com.echohome.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors

/**
 * Local Italian text-to-speech (sherpa-onnx + Piper "paola" voice). The model loads on first use
 * on a background thread. Audio is synthesised and played from RAM only.
 */
class TextToSpeech(private val context: Context) {

    companion object {
        private const val DIR = "tts"
        private const val DATA_VERSION = "1" // bump when assets/tts/espeak-ng-data changes
    }

    private val executor = Executors.newSingleThreadExecutor()
    // Joined clips must never wait behind Piper (loading it takes ~30 s on the Echo).
    private val clipExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: OfflineTts? = null
    private val clips = VoiceClips(context.assets)
    @Volatile private var track: AudioTrack? = null

    /** Loads the voice in the background so the first answer does not pay for it. */
    fun warmUp() {
        executor.execute { runCatching { if (engine == null) engine = createEngine() } }
    }

    /** Speaks [text]; [onDone] runs on the main thread when the audio has finished (or failed). */
    fun speak(text: String, onDone: () -> Unit) {
        clipExecutor.execute {
            val started = System.nanoTime()
            // Common answers (time, date, timers, weather) are joined from pre-rendered words: instant.
            val joined = runCatching { clips.compose(text) }.getOrNull()
            if (joined != null) {
                Log.i("EchoHome", "tts: clips ${(System.nanoTime() - started) / 1_000_000} ms")
                runCatching { play(joined.samples, joined.sampleRate) }
                mainHandler.post(onDone)
            } else {
                speakWithPiper(text, onDone)
            }
        }
    }

    private fun speakWithPiper(text: String, onDone: () -> Unit) {
        executor.execute {
            try {
                val started = System.nanoTime()
                val tts = engine ?: createEngine().also { engine = it }
                val loaded = System.nanoTime()
                val audio = tts.generate(pronounceable(text), 0, 1.0f)
                val generated = System.nanoTime()
                // Timings only (for tuning on the real device): never the text or the audio.
                Log.i("EchoHome", "tts: load ${(loaded - started) / 1_000_000} ms, " +
                    "generate ${(generated - loaded) / 1_000_000} ms for ${"%.1f".format(audio.samples.size / audio.sampleRate.toFloat())} s of audio")
                play(audio.samples, audio.sampleRate)
            } catch (t: Throwable) {
                Log.w("EchoHome", "tts failed: ${t.javaClass.simpleName}: ${t.message}")
                // No voice available: the answer is still on screen.
            }
            mainHandler.post(onDone)
        }
    }

    /** The Italian voice reads "timer" letter by letter; spell it the way it sounds. */
    private fun pronounceable(text: String) = text.replace(Regex("\\btimer\\b", RegexOption.IGNORE_CASE), "taimer")

    /** Cuts the current speech short. */
    fun stop() {
        runCatching { track?.stop() }
    }

    fun release() {
        stop()
        executor.execute { engine?.release(); engine = null }
        executor.shutdown()
        clipExecutor.shutdown()
    }

    private fun createEngine(): OfflineTts {
        val vits = OfflineTtsVitsModelConfig().apply {
            model = "$DIR/it_IT-paola-medium.onnx"
            tokens = "$DIR/tokens.txt"
            dataDir = copyEspeakData()
        }
        val model = OfflineTtsModelConfig().apply {
            this.vits = vits
            numThreads = 4
        }
        return OfflineTts(context.assets, OfflineTtsConfig().apply { this.model = model })
    }

    /** espeak-ng needs real files: copy assets/tts/espeak-ng-data to the app's private storage once. */
    private fun copyEspeakData(): String {
        val root = File(context.filesDir, "tts")
        val target = File(root, "espeak-ng-data")
        val marker = File(root, ".version")
        if (marker.exists() && marker.readText() == DATA_VERSION && target.isDirectory) return target.absolutePath

        target.deleteRecursively()
        copyAssetDir("$DIR/espeak-ng-data", target)
        marker.writeText(DATA_VERSION)
        return target.absolutePath
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) { // a file
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (name in children) copyAssetDir("$assetPath/$name", File(dest, name))
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = audioTrack
        try {
            audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            audioTrack.play()
            // Wait for the end of playback (or an early stop()).
            while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING &&
                audioTrack.playbackHeadPosition < samples.size
            ) Thread.sleep(20)
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
            track = null
            samples.fill(0f)
        }
    }
}
