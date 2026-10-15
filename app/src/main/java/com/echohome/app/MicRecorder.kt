package com.echohome.app

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Records one utterance in RAM (16 kHz mono) and ends it by itself when the speaker stops,
 * using Silero VAD. Needs RECORD_AUDIO. Nothing is saved: only the speech part is handed to
 * [start]'s callback, once, and every buffer is zeroed afterwards.
 */
class MicRecorder(
    private val assets: AssetManager,
    private val maxSeconds: Int = 8,
    private val noSpeechSeconds: Int = 5,
    private val endSilenceSeconds: Float = 0.7f,
) {
    companion object {
        /**
         * The Echo's microphone level varies a lot (speech peaks anywhere from 0.02 to 0.45 of full
         * scale). The VAD and the wake word get a modest fixed gain; the utterance for Whisper is
         * normalised by its own peak afterwards, so loud speech is never clipped.
         */
        const val MIC_GAIN = 3f
        private const val STT_TARGET_PEAK = 0.8f
        private const val STT_MAX_GAIN = 30f

        fun toFloat(sample: Short): Float = (sample / 32768f * MIC_GAIN).coerceIn(-1f, 1f)

        /** Scales [samples] in place so the loudest one reaches [STT_TARGET_PEAK] (never attenuates, caps the gain). */
        fun normalise(samples: FloatArray) {
            val peak = samples.maxOfOrNull { kotlin.math.abs(it) } ?: return
            if (peak <= 0f) return
            val gain = (STT_TARGET_PEAK / peak).coerceIn(1f, STT_MAX_GAIN)
            if (gain != 1f) for (i in samples.indices) samples[i] *= gain
        }
    }

    /** [speech] is empty when nobody spoke; [reason] is a short label for the UI. */
    class Result(val speech: FloatArray, val totalSeconds: Float, val reason: String, val peak: Float)

    @Volatile private var recording = false
    private var vad: Vad? = null

    val isRecording get() = recording

    @SuppressLint("MissingPermission")
    fun start(handoff: WakeWordListener.Handoff? = null, onDone: (Result) -> Unit) {
        if (recording) { handoff?.discard(); return }
        recording = true
        Thread({ onDone(capture(handoff)) }, "mic-recorder").start()
    }

    /** Manual stop (the "Fine" button): whatever speech was heard so far is used. */
    fun stop() {
        recording = false
    }

    private fun newVad(): Vad {
        vad?.let { it.reset(); return it }
        val silero = SileroVadModelConfig().apply {
            model = "silero-vad/silero_vad_v5.onnx"
            threshold = 0.35f
            minSilenceDuration = endSilenceSeconds
            minSpeechDuration = 0.15f
            maxSpeechDuration = maxSeconds.toFloat()
        }
        val config = VadModelConfig().apply {
            sileroVadModelConfig = silero
            sampleRate = SpeechToText.SAMPLE_RATE
            numThreads = 1
        }
        return Vad(assets, config).also { vad = it }
    }

    @SuppressLint("MissingPermission")
    private fun capture(handoff: WakeWordListener.Handoff?): Result {
        val rate = SpeechToText.SAMPLE_RATE
        val chunk = ShortArray(rate / 10) // 100 ms
        val floats = FloatArray(chunk.size)
        var total = 0
        var peak = 0f
        // Raw copy of the utterance (RAM only, zeroed below): the VAD cuts a little too tight.
        val prefixLen = handoff?.prefix?.size ?: 0
        val raw = FloatArray(prefixLen + rate * (maxSeconds + 1))
        handoff?.prefix?.copyInto(raw) // what was heard just before the wake word fired (wake word + start of the command)
        total = prefixLen
        var speech = FloatArray(0)
        var reason = "microfono non disponibile"
        var record: AudioRecord? = null
        try {
            val detector = newVad()
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            record = handoff?.record ?: AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunk.size * 4),
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                if (handoff == null) record.startRecording()
                reason = "tempo massimo"
                while (recording) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) { reason = "microfono interrotto"; break }
                    for (i in 0 until n) {
                        floats[i] = toFloat(chunk[i])
                        peak = maxOf(peak, kotlin.math.abs(chunk[i] / 32768f)) // raw level, for diagnostics
                    }
                    for (i in 0 until minOf(n, raw.size - total)) raw[total + i] = chunk[i] / 32768f // ungained, normalised later
                    detector.acceptWaveform(if (n == floats.size) floats else floats.copyOf(n))
                    total += n

                    if (!detector.empty()) { reason = "fine frase"; break }
                    if (total - prefixLen >= rate * noSpeechSeconds && !detector.isSpeechDetected()) {
                        reason = "nessun parlato"; break
                    }
                    if (total - prefixLen >= rate * maxSeconds) break
                }
                if (detector.empty()) detector.flush() // manual stop / time limit: close the open segment
                if (!detector.empty()) {
                    val segment = detector.front()
                    // Pre/post-roll so word onsets and endings are not clipped.
                    // With a hand-over the whole prefix is kept: it holds the wake word and any command start.
                    val from = if (prefixLen > 0) 0 else maxOf(0, segment.start - rate / 2)
                    val to = minOf(minOf(total, raw.size), prefixLen + segment.start + segment.samples.size + rate * 3 / 10)
                    speech = if (to > from) raw.copyOfRange(from, to) else segment.samples.copyOf()
                    normalise(speech)
                }
                detector.clear()
                detector.reset()
                if (!recording && reason == "tempo massimo") reason = "stop manuale"
            }
        } catch (_: Exception) {
            speech = FloatArray(0)
            reason = "errore microfono"
        } finally {
            recording = false
            runCatching { record?.stop() }
            record?.release()
            chunk.fill(0)
            floats.fill(0f)
            raw.fill(0f)
        }
        return Result(speech, (total - prefixLen) / rate.toFloat(), reason, peak)
    }

    fun release() {
        vad?.release()
        vad = null
    }
}
