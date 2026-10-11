package com.echohome.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.net.URL
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        // Release first, then the debug build (applicationIdSuffix ".debug").
        private val MELD_PACKAGES = listOf("com.meld.app", "com.meld.app.debug")
        private const val MELD_SERVICE = "com.metrolist.music.playback.MusicService"

        private const val RECONNECT_DELAY_MS = 3_000L
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val REQUEST_RECORD_AUDIO = 1
        private const val BEEP_SETTLE_MS = 350L
        private const val VOLUME_STEP = 10
        private const val RING_INTERVAL_MS = 900L
        private const val WEATHER_CACHE_MS = 10 * 60 * 1000L
        private const val WEATHER_PANEL_MS = 90_000L
        private const val RING_MAX_BEEPS = 25
        private const val PREFS = "echohome"
        private const val PREF_WAKE = "wake_enabled"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiExecutor = Executor { mainHandler.post(it) }
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var meldPackage: String? = null
    private var visible = false

    // Bumped on every artwork request so stale downloads are dropped.
    private var artworkRequest = 0
    private var artworkKey: String? = null

    private lateinit var statusView: TextView
    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var progressView: TextView
    private lateinit var queryInput: EditText
    private lateinit var playButton: Button
    private lateinit var pauseButton: Button
    private lateinit var nextButton: Button
    private lateinit var previousButton: Button
    private lateinit var speakButton: Button
    private lateinit var wakeButton: Button
    private lateinit var timerView: TextView
    private lateinit var weatherView: TextView

    // Voice input: mic -> RAM -> Whisper -> IntentParser. The model loads lazily on first use.
    // Needs `assets`, which only exists after the Activity is attached: created in onCreate.
    private lateinit var recorder: MicRecorder
    private lateinit var wakeListener: WakeWordListener
    private lateinit var kitchenTimer: KitchenTimer
    private lateinit var tts: TextToSpeech

    // Timer alarm: beeps every RING_INTERVAL_MS until dismissed (voice, tap on the timer, or timeout).
    private var ringing = false
    private var ringCount = 0
    private val ringTick = object : Runnable {
        override fun run() {
            if (!ringing) return
            beep()
            if (++ringCount >= RING_MAX_BEEPS) stopRinging() else mainHandler.postDelayed(this, RING_INTERVAL_MS)
        }
    }

    // Wake word ("Hey Jarvis") is opt-in: the mic is only open while this is on.
    private var wakeEnabled = false
    private var voiceBusy = false           // a wake/command session is running
    private var sessionWasPlaying = false   // music was paused for the session and may need resuming
    private val sttExecutor = Executors.newSingleThreadExecutor()
    private var stt: SpeechToText? = null

    private val reconnectRunnable = Runnable { connectToMeld() }

    private val searchTimeout = Runnable {
        statusView.text = "Meld non ha risposto alla ricerca (nessun risultato?)"
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            renderProgress()
            renderTimer()
            if (visible) mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            releaseController()
            renderDisconnected("Meld · non connesso (riconnessione…)")
            scheduleReconnect()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            mainHandler.removeCallbacks(searchTimeout)
            renderMetadata(mediaMetadata)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) mainHandler.removeCallbacks(searchTimeout)
            renderState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) = renderState()

        override fun onPlayerError(error: PlaybackException) {
            mainHandler.removeCallbacks(searchTimeout)
            statusView.text = "Errore player: ${error.errorCodeName}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = MicRecorder(assets)
        wakeListener = WakeWordListener(assets)
        tts = TextToSpeech(applicationContext)
        kitchenTimer = KitchenTimer(getSharedPreferences(PREFS, MODE_PRIVATE), mainHandler) { startRinging() }
        wakeEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_WAKE, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(buildUi())
        renderDisconnected("Meld · non connesso")
        handleDebugCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugCommand(intent)
    }

    /**
     * Debug builds only: `adb shell am start -n com.echohome.app/.MainActivity --es echo_cmd "timer 5 minuti"`
     * runs a text command as if it were typed. Ignored in release builds.
     */
    private fun handleDebugCommand(launch: Intent?) {
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        val cmd = launch?.getStringExtra("echo_cmd")
        if (!debuggable || cmd.isNullOrBlank()) return
        mainHandler.postDelayed({ runCommand(cmd) }, 500)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        connectToMeld()
        mainHandler.post(progressTicker)
        startWake()
        kitchenTimer.resume()
    }

    override fun onStop() {
        visible = false
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(searchTimeout)
        wakeListener.stop() // mic is released whenever the screen is not in front
        releaseController()
        super.onStop()
    }

    override fun onDestroy() {
        recorder.stop()
        recorder.release()
        wakeListener.stop()
        wakeListener.release()
        tts.release()
        artworkExecutor.shutdownNow()
        weatherExecutor.shutdownNow()
        sttExecutor.execute { stt?.release(); stt = null }
        sttExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout {
        fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(18), dp(28), dp(18))
            setBackgroundColor(Color.rgb(12, 12, 12))
        }

        val title = TextView(this).apply {
            text = "Echo Home v0.3"
            textSize = 22f
            setTextColor(Color.WHITE)
        }

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(8))
        }

        coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(30, 30, 30))
        }
        titleView = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        artistView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.LTGRAY)
            maxLines = 1
        }
        progressView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, 0)
            addView(titleView)
            addView(artistView)
            addView(progressView)
        }
        val nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(coverView, LinearLayout.LayoutParams(dp(90), dp(90)))
            addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        queryInput = EditText(this).apply {
            hint = "Cerca (es. Radiohead)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine()
            // No fullscreen keyboard in landscape: it would hide the whole UI.
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    runCommand(text.toString())
                    true
                } else false
            }
        }

        playButton = Button(this).apply {
            text = "Cerca e riproduci"
            setOnClickListener { runCommand(queryInput.text.toString()) }
        }
        pauseButton = Button(this).apply {
            text = "Pausa"
            setOnClickListener { togglePlayPause() }
        }
        previousButton = Button(this).apply {
            text = "Prev"
            setOnClickListener { controller?.seekToPreviousMediaItem() }
        }
        nextButton = Button(this).apply {
            text = "Next"
            setOnClickListener { controller?.seekToNextMediaItem() }
        }
        val openMeldButton = Button(this).apply {
            text = "Apri Meld"
            setOnClickListener { openMeld() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val buttonParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
        controls.addView(playButton, buttonParams)
        controls.addView(previousButton, buttonParams)
        controls.addView(pauseButton, buttonParams)
        controls.addView(nextButton, buttonParams)

        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        root.addView(title, LinearLayout.LayoutParams(match, wrap))
        root.addView(statusView, LinearLayout.LayoutParams(match, wrap))
        timerView = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.rgb(255, 193, 7))
            visibility = View.GONE
            setOnClickListener { dismissTimer() } // tap = stop the alarm / cancel the countdown
        }
        root.addView(timerView, LinearLayout.LayoutParams(match, wrap))
        weatherView = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(144, 202, 249))
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { visibility = View.GONE } // tap to dismiss
        }
        root.addView(weatherView, LinearLayout.LayoutParams(match, wrap))
        root.addView(nowPlaying, LinearLayout.LayoutParams(match, 0, 1f))
        root.addView(queryInput, LinearLayout.LayoutParams(match, dp(52)))
        root.addView(controls, LinearLayout.LayoutParams(match, wrap))
        speakButton = Button(this).apply {
            text = "Parla"
            isEnabled = false
            setOnClickListener { toggleListening() }
        }
        wakeButton = Button(this).apply {
            setOnClickListener { toggleWake() }
        }
        renderWakeButton()
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(speakButton, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(8) })
            addView(wakeButton, LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = dp(8) })
            addView(openMeldButton, LinearLayout.LayoutParams(wrap, wrap))
        }
        root.addView(bottomRow, LinearLayout.LayoutParams(wrap, wrap))
        return root
    }

    // ---- Connection -------------------------------------------------------

    private fun findMeldPackage(): String? = MELD_PACKAGES.firstOrNull { pkg ->
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun connectToMeld() {
        if (controller != null || controllerFuture != null) return

        val pkg = findMeldPackage()
        meldPackage = pkg
        if (pkg == null) {
            renderDisconnected("Meld non installato (package: ${MELD_PACKAGES.first()})")
            scheduleReconnect()
            return
        }

        statusView.text = "Connessione al MusicService di Meld…"
        val token = SessionToken(this, ComponentName(pkg, MELD_SERVICE))
        val future = MediaController.Builder(this, token)
            .setListener(controllerListener)
            .buildAsync()
        controllerFuture = future

        future.addListener({
            if (controllerFuture !== future) {
                // Activity stopped (or reconnected) while connecting.
                if (future.isDone && !future.isCancelled) {
                    runCatching { future.get().release() }
                }
                return@addListener
            }
            try {
                val connected = future.get()
                controller = connected
                connected.addListener(playerListener)
                setControlsEnabled(true)
                renderMetadata(connected.mediaMetadata)
                renderState()
            } catch (t: Throwable) {
                controllerFuture = null
                renderDisconnected("Connessione Meld fallita: ${t.cause?.message ?: t.message}")
                scheduleReconnect()
            }
        }, uiExecutor)
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        if (visible) mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun releaseController() {
        controller?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
    }

    // ---- Commands ---------------------------------------------------------

    /** Text field entry point: known commands run as such, anything else is a plain music search. */
    private fun runCommand(text: String, fromVoice: Boolean = false, detail: String = ""): EchoIntent? {
        val intent = IntentParser.parse(text)
        // Only music commands need Meld; timer, volume and time work without it.
        val needsMeld = when (intent) {
            EchoIntent.MusicPause, EchoIntent.MusicResume, EchoIntent.MusicNext, EchoIntent.MusicPrevious,
            is EchoIntent.MusicSearch, null -> true
            else -> false
        }
        if (needsMeld && controller == null) {
            statusView.text = "Meld non connesso"
            return null
        }
        when (intent) {
            EchoIntent.MusicPause -> controller?.pause()
            EchoIntent.MusicResume -> controller?.play()
            EchoIntent.MusicNext -> controller?.seekToNextMediaItem()
            EchoIntent.MusicPrevious -> controller?.seekToPreviousMediaItem()
            is EchoIntent.MusicSearch -> playSearch(intent.query)
            is EchoIntent.VolumeSet -> setVolumePercent(intent.percent)
            EchoIntent.VolumeUp -> setVolumePercent(volumePercent() + VOLUME_STEP)
            EchoIntent.VolumeDown -> setVolumePercent(volumePercent() - VOLUME_STEP)
            EchoIntent.WeatherNow -> showWeather(tomorrow = false)
            EchoIntent.WeatherTomorrow -> showWeather(tomorrow = true)
            is EchoIntent.TimerCreate -> {
                kitchenTimer.start(intent.seconds)
                renderTimer()
                reply(Replies.timerSet(intent.seconds))
            }
            EchoIntent.TimerCancel -> {
                val had = dismissTimer()
                reply(if (had) Replies.TIMER_CANCELLED else Replies.NO_TIMER)
            }
            EchoIntent.Time -> {
                val now = java.util.Calendar.getInstance()
                reply(Replies.time(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE)))
            }
            // Typed text falls back to a plain search; a voice transcript must be a real command.
            null -> if (fromVoice) {
                statusView.text = "Non ho capito: “$text” ($detail)"
            } else {
                playSearch(text)
            }
        }
        return intent
    }

    // ---- Voice: wake word -> beep -> VAD + STT -> intent -> Meld ----------

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun renderWakeButton() {
        wakeButton.text = if (wakeEnabled) "Hey Jarvis: ON" else "Hey Jarvis: OFF"
    }

    private fun toggleWake() {
        wakeEnabled = !wakeEnabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_WAKE, wakeEnabled).apply()
        renderWakeButton()
        if (wakeEnabled) {
            if (hasMicPermission()) startWake() else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        } else {
            wakeListener.stop()
        }
    }

    /** Idle state: only the wake word detector runs. No-op unless enabled, visible and idle. */
    private fun startWake() {
        if (!wakeEnabled || voiceBusy || !visible || !hasMicPermission() || wakeListener.isListening) return
        wakeListener.start { mainHandler.post { beginCommand(fromWake = true) } }
    }

    /** "Parla" button: starts a command session, or ends the current one early. */
    private fun toggleListening() {
        if (recorder.isRecording) {
            recorder.stop() // the recorder thread reports back through onUtterance
            return
        }
        if (!hasMicPermission()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        beginCommand(fromWake = false)
    }

    private fun beginCommand(fromWake: Boolean) {
        if (voiceBusy || !visible) return
        android.util.Log.i("EchoHome", if (fromWake) "wake word detected" else "manual listen")
        voiceBusy = true
        wakeListener.stop() // the command recorder needs the mic

        // Quiet the music so it does not drown the command (cheaper than echo cancellation).
        val c = controller
        sessionWasPlaying = c != null && (c.isPlaying || c.playWhenReady)
        if (sessionWasPlaying) c?.pause()

        statusView.text = "● Ti ascolto…"
        speakButton.text = "Fine"
        val startRecording = {
            if (voiceBusy) recorder.start { result -> mainHandler.post { onUtterance(result) } }
        }
        if (fromWake) {
            beep()
            mainHandler.postDelayed(startRecording, BEEP_SETTLE_MS) // do not record our own beep
        } else {
            startRecording()
        }
    }

    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            mainHandler.postDelayed({ tone.release() }, 400)
        }
    }

    /** Back to idle: put the music back if the command did not take over playback, then listen again. */
    private fun endSession(executed: EchoIntent?) = whenSpeechDone { finishSession(executed) }

    private fun finishSession(executed: EchoIntent?) {
        voiceBusy = false
        speakButton.text = "Parla"
        speakButton.isEnabled = controller != null
        val c = controller
        if (sessionWasPlaying && c != null) {
            when (executed) {
                EchoIntent.MusicPause, is EchoIntent.MusicSearch -> Unit // stay paused / search starts playback
                // Anything that does not itself change playback: put the music back.
                EchoIntent.MusicNext, EchoIntent.MusicPrevious, EchoIntent.MusicResume,
                is EchoIntent.VolumeSet, EchoIntent.VolumeUp, EchoIntent.VolumeDown, EchoIntent.Time,
                is EchoIntent.TimerCreate, EchoIntent.TimerCancel, EchoIntent.WeatherNow, EchoIntent.WeatherTomorrow, null -> c.play()
            }
        }
        sessionWasPlaying = false
        startWake()
    }

    private fun onUtterance(result: MicRecorder.Result) {
        val samples = result.speech
        if (samples.isEmpty()) {
            statusView.text = "Nessun parlato rilevato (${result.reason}, ${"%.1f".format(result.totalSeconds)}s, picco ${"%.2f".format(result.peak)})"
            endSession(null)
            return
        }
        statusView.text = "◌ Elaboro…"
        speakButton.text = "Parla"
        speakButton.isEnabled = false
        // Diagnostics only (lengths and loudest sample): never the audio itself.
        val speechSec = "%.1f".format(samples.size / SpeechToText.SAMPLE_RATE.toFloat())
        val peak = "%.2f".format(samples.maxOf { kotlin.math.abs(it) })
        val audioInfo = "parlato ${speechSec}s su ${"%.1f".format(result.totalSeconds)}s, picco $peak, ${result.reason}"
        sttExecutor.execute {
            val started = System.nanoTime()
            val text = try {
                val engine = stt ?: SpeechToText(assets).also { stt = it }
                engine.transcribe(samples)
            } catch (t: Throwable) {
                mainHandler.post {
                    statusView.text = "Errore STT: ${t.message}"
                    endSession(null)
                }
                return@execute
            } finally {
                samples.fill(0f)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            mainHandler.post {
                queryInput.setText(text)
                statusView.text = if (text.isBlank()) "Nessun parlato rilevato ($audioInfo, STT $ms ms)" else "“$text” ($audioInfo, STT $ms ms)"
                val executed = if (text.isNotBlank()) runCommand(text, fromVoice = true, detail = "$audioInfo, STT $ms ms") else null
                endSession(executed)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (wakeEnabled) startWake() else toggleListening()
        } else {
            statusView.text = "Permesso microfono negato"
        }
    }

    private fun playSearch(rawQuery: String) {
        val query = rawQuery.trim()
        val activeController = controller
        if (query.isBlank() || activeController == null) return

        statusView.text = "Cerco “$query”…"
        mainHandler.removeCallbacks(searchTimeout)
        mainHandler.postDelayed(searchTimeout, SEARCH_TIMEOUT_MS)

        val mediaItem = MediaItem.Builder()
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setSearchQuery(query)
                    .build()
            )
            .build()

        activeController.setMediaItem(mediaItem)
        activeController.prepare()
        activeController.play()
    }

    // Music volume is the system STREAM_MUSIC one, which is what Meld plays on.
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return Math.round(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
    }

    private fun setVolumePercent(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = percent.coerceIn(0, 100)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(clamped * max / 100f), 0)
        reply(Replies.volume(volumePercent()))
    }

    // ---- Spoken answers -----------------------------------------------------

    private var speakingCount = 0
    private var afterSpeech: (() -> Unit)? = null

    /** Shows [text] and says it out loud. */
    private fun reply(text: String) {
        statusView.text = text
        speak(text)
    }

    private fun speak(text: String, then: () -> Unit = {}) {
        speakingCount++
        wakeListener.stop() // never let the wake word detector hear our own voice
        tts.speak(text) {
            speakingCount--
            then()
            if (speakingCount == 0) {
                afterSpeech?.let { afterSpeech = null; it() }
                startWake() // no-op while a voice session is still running
            }
        }
    }

    /** Runs [block] now, or as soon as the current speech has finished. */
    private fun whenSpeechDone(block: () -> Unit) {
        if (speakingCount == 0) block() else afterSpeech = block
    }

    // ---- Weather (San Giuliano Milanese, Open-Meteo) --------------------------

    private val weatherExecutor = Executors.newSingleThreadExecutor()
    private var lastForecast: Forecast? = null
    private var lastForecastAt = 0L
    private val hideWeather = Runnable { weatherView.visibility = View.GONE }

    /** Pending work (network) that must finish before a voice session may end, like speech. */
    private fun beginPending() { speakingCount++ }

    private fun endPending() {
        speakingCount--
        if (speakingCount == 0) {
            afterSpeech?.let { afterSpeech = null; it() }
            startWake()
        }
    }

    private fun showWeather(tomorrow: Boolean) {
        statusView.text = "Leggo il meteo…"
        beginPending()
        val cached = lastForecast?.takeIf { System.currentTimeMillis() - lastForecastAt < WEATHER_CACHE_MS }
        weatherExecutor.execute {
            val forecast = cached ?: try {
                WeatherClient.fetch()
            } catch (_: Exception) {
                lastForecast // stale data is better than none
            }
            mainHandler.post {
                if (forecast == null) {
                    reply(Replies.WEATHER_UNAVAILABLE)
                } else {
                    if (forecast !== lastForecast || cached == null) {
                        lastForecast = forecast
                        lastForecastAt = System.currentTimeMillis()
                    }
                    renderWeather(forecast, tomorrow)
                    reply(if (tomorrow) Replies.weatherTomorrow(forecast) else Replies.weatherNow(forecast))
                }
                endPending()
            }
        }
    }

    private fun renderWeather(f: Forecast, tomorrow: Boolean) {
        weatherView.text = if (tomorrow) {
            "${WeatherClient.CITY} · domani · min ${f.tomorrowMin}° · max ${f.tomorrowMax}° · ${Replies.condition(f.tomorrowCode)}\n" +
                "Probabilità di pioggia per ora:\n" + Replies.rainTable(f.tomorrowRainByHour)
        } else {
            "${WeatherClient.CITY} · adesso ${f.nowTemp}° · ${Replies.condition(f.nowCode)} · oggi min ${f.todayMin}° max ${f.todayMax}°"
        }
        weatherView.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideWeather)
        mainHandler.postDelayed(hideWeather, WEATHER_PANEL_MS)
    }

    // ---- Timer ------------------------------------------------------------

    private fun startRinging() {
        ringing = true
        ringCount = 0
        statusView.text = Replies.TIMER_DONE
        renderTimer()
        mainHandler.removeCallbacks(ringTick)
        // Say it once, then the beeps keep going until dismissed.
        speak(Replies.TIMER_DONE) { if (ringing) mainHandler.post(ringTick) }
    }

    private fun stopRinging() {
        ringing = false
        mainHandler.removeCallbacks(ringTick)
        renderTimer()
    }

    /** Stops the alarm and/or cancels the countdown. True if there was anything to stop. */
    private fun dismissTimer(): Boolean {
        val wasRinging = ringing
        if (wasRinging) stopRinging()
        return kitchenTimer.cancel() || wasRinging
    }

    private fun renderTimer() {
        when {
            ringing -> {
                timerView.text = "⏱ Timer scaduto · tocca per fermare"
                timerView.visibility = View.VISIBLE
            }
            kitchenTimer.isRunning -> {
                val total = (kitchenTimer.remainingMs() + 999) / 1000
                timerView.text = "⏱ %d:%02d".format(total / 60, total % 60)
                timerView.visibility = View.VISIBLE
            }
            else -> timerView.visibility = View.GONE
        }
    }

    private fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying || c.playWhenReady) c.pause() else c.play()
    }

    private fun openMeld() {
        val pkg = meldPackage ?: findMeldPackage()
        val launchIntent: Intent? = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            statusView.text = "Impossibile aprire Meld: app non installata"
        }
    }

    // ---- Rendering --------------------------------------------------------

    private fun renderDisconnected(message: String) {
        statusView.text = message
        setControlsEnabled(false)
        titleView.text = "Nessun brano in riproduzione"
        artistView.text = ""
        progressView.text = ""
        showCover(null, null)
    }

    private fun renderState() {
        val c = controller ?: return
        statusView.text = when {
            c.playerError != null -> "Errore player: ${c.playerError?.errorCodeName}"
            c.isPlaying -> "● Meld · in riproduzione"
            c.playbackState == Player.STATE_BUFFERING -> "◌ Meld · buffering…"
            else -> "○ Meld · connesso"
        }
        pauseButton.text = if (c.isPlaying || c.playWhenReady) "Pausa" else "Riprendi"
    }

    private fun renderMetadata(metadata: MediaMetadata) {
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
        val artist = (metadata.artist ?: metadata.albumArtist)?.toString()?.takeIf { it.isNotBlank() }
        titleView.text = title ?: "Nessun brano in riproduzione"
        artistView.text = artist.orEmpty()
        showCover(metadata.artworkData, metadata.artworkUri)
        renderProgress()
    }

    private fun renderProgress() {
        val c = controller
        if (c == null || c.currentMediaItem == null) {
            progressView.text = ""
            return
        }
        val duration = c.duration
        progressView.text = if (duration == C.TIME_UNSET || duration <= 0) {
            formatTime(c.currentPosition)
        } else {
            "${formatTime(c.currentPosition)} / ${formatTime(duration)}"
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun setControlsEnabled(enabled: Boolean) {
        playButton.isEnabled = enabled
        pauseButton.isEnabled = enabled
        nextButton.isEnabled = enabled
        previousButton.isEnabled = enabled
        speakButton.isEnabled = enabled
    }

    // ---- Cover ------------------------------------------------------------

    private fun showCover(data: ByteArray?, uri: Uri?) {
        val key = when {
            data != null -> "bytes:${data.size}:${data.contentHashCode()}"
            uri != null -> uri.toString()
            else -> null
        }
        if (key == artworkKey) return
        artworkKey = key
        val request = ++artworkRequest

        if (key == null) {
            coverView.setImageDrawable(null)
            return
        }

        artworkExecutor.execute {
            val bitmap: Bitmap? = try {
                when {
                    data != null -> BitmapFactory.decodeByteArray(data, 0, data.size)
                    uri!!.scheme == "http" || uri.scheme == "https" ->
                        URL(uri.toString()).openStream().use { BitmapFactory.decodeStream(it) }
                    else -> contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (request == artworkRequest) coverView.setImageBitmap(bitmap)
            }
        }
    }
}
