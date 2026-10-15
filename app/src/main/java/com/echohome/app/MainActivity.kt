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
import android.view.WindowManager
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
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
        // Meld returns up to ~75 local + ~20 online songs whatever the page size; Media3 throws if the
        // answer is longer than the page asked for (it crashed Meld at 10), so ask for plenty.
        private const val SEARCH_PAGE_SIZE = 500
        private const val PROGRESS_INTERVAL_MS = 1_000L
        private const val REQUEST_RECORD_AUDIO = 1
        private const val BEEP_SETTLE_MS = 350L
        private const val VOLUME_STEP = 10
        private const val RING_INTERVAL_MS = 900L
        private const val WEATHER_CACHE_MS = 10 * 60 * 1000L
        private const val WEATHER_REFRESH_MS = 30 * 60 * 1000L
        private const val WEATHER_RETRY_MIN_MS = 15_000L
        private const val WEATHER_RETRY_MAX_MS = 5 * 60 * 1000L
        private const val SCREEN_MS = 20_000L
        private const val DUCK_VOLUME = 0.25f
        private const val NIGHT_BRIGHTNESS = 0.1f // 0..1 of the panel, only for this window
        private const val DEEP_NIGHT_BRIGHTNESS = 0.01f // night and idle: the clock is only just visible
        private const val IDLE_BRIGHTNESS = 0.3f // daytime, after DIM_AFTER_MS without activity
        private const val DIM_AFTER_MS = 3 * 60 * 1000L
        private const val COVER_MAX_PX = 400 // the cover view is 170dp
        private const val STATUS_MS = 6_000L
        private const val MUSIC_SCREEN_MS = 30_000L
        private const val RING_MAX_BEEPS = 25
        private const val PREFS = "echohome"
        private const val PREF_WAKE = "wake_enabled"
        private const val PREF_BARGE_IN = "barge_in"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiExecutor = Executor { mainHandler.post(it) }
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    private var controllerFuture: ListenableFuture<out MediaController>? = null
    private var controller: MediaController? = null
    private var meldPackage: String? = null
    private var visible = false

    // Bumped on every artwork request so stale downloads are dropped.
    private var artworkRequest = 0
    private var searchRequest = 0 // latest music search, older answers are ignored
    private var artworkKey: String? = null

    private lateinit var statusView: TextView
    private lateinit var coverView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var progressView: TextView
    private lateinit var queryInput: EditText
    private lateinit var playButton: Button
    private lateinit var pauseButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var previousButton: ImageButton
    private lateinit var timerRows: List<Pair<TextView, TextView>> // (name, time) per timer on the Timers screen
    private lateinit var weatherTitleView: TextView
    private lateinit var weatherTempView: TextView
    private lateinit var weatherCondView: TextView
    private lateinit var weatherDetailView: TextView
    private lateinit var timerHintView: TextView
    private lateinit var timerCancelView: TextView
    private lateinit var homeTimerView: TextView // active timer, as a line on Home
    private lateinit var homeMusicView: TextView // current track, as a line on Home
    private lateinit var clockView: TextView
    private lateinit var dayView: TextView
    private lateinit var weatherTopView: TextView // current weather on Home
    private lateinit var assistantView: TextView
    private lateinit var debugPanel: LinearLayout

    private enum class AssistantState { IDLE, LISTENING, THINKING }
    private var assistantState = AssistantState.IDLE
    private var connectionMessage: String? = null // Meld status line that disappears once connected

    // Voice input: mic -> RAM -> Whisper -> IntentParser. The model loads lazily on first use.
    // Needs `assets`, which only exists after the Activity is attached: created in onCreate.
    private lateinit var recorder: MicRecorder
    private lateinit var wakeListener: WakeWordListener
    private lateinit var kitchenTimer: KitchenTimer
    private lateinit var tts: TextToSpeech

    // Timer alarm: beeps every RING_INTERVAL_MS until dismissed (voice, tap on the timer, or timeout).
    private var ringing = false
    private val ringLabels = mutableListOf<String?>() // timers that fired and are not dismissed yet
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
    // Experimental: say the wake word while the assistant talks to cut it short. Off until tested on the Echo.
    private var bargeInEnabled = false
    private var bargeInActive = false // the wake listener is running in barge-in mode
    private var bargeInPending = false // the wake word interrupted speech; a command session follows
    private var voiceBusy = false           // a wake/command session is running
    private var sessionWasPlaying = false   // music was paused for the session and may need resuming
    private val sttExecutor = Executors.newSingleThreadExecutor()

    // Whisper loads in ~15 s on the Echo, so it is prepared at start and kept (never on the command path).
    private var stt: SpeechToText? = null

    private val reconnectRunnable = Runnable { connectToMeld() }

    private val searchTimeout = Runnable {
        statusView.text = "Meld non ha risposto alla ricerca (nessun risultato?)"
    }

    // Ticks every second only while something counts seconds (a timer, the Timers screen, the player
    // progress); otherwise once a minute for the clock. Fewer wake-ups = less CPU and heat.
    private val progressTicker = object : Runnable {
        override fun run() {
            val playingOnMusic = screen == Screen.MUSIC && controller?.isPlaying == true
            val secondsMatter = playingOnMusic || screen == Screen.TIMERS || kitchenTimer.isRunning || ringing
            if (screen == Screen.MUSIC) renderProgress()
            if (secondsMatter) renderTimer()
            renderClock()
            if (visible) {
                val delay = if (secondsMatter) PROGRESS_INTERVAL_MS else 60_000L - System.currentTimeMillis() % 60_000L + 50L
                mainHandler.postDelayed(this, delay)
            }
        }
    }

    /** Re-evaluates the tick rate right away (after a screen change, a new timer, play/pause). */
    private fun kickTicker() {
        mainHandler.removeCallbacks(progressTicker)
        if (visible) mainHandler.post(progressTicker)
    }

    private val controllerListener = object : MediaBrowser.Listener {
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
            kickTicker()
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
        tts.warmUp()
        sttExecutor.execute { if (stt == null) runCatching { stt = SpeechToText(assets) } }
        kitchenTimer = KitchenTimer(getSharedPreferences(PREFS, MODE_PRIVATE), mainHandler) { startRinging(it) }
        wakeEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_WAKE, false)
        bargeInEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_BARGE_IN, false)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // smart display: stays on while in front
        setContentView(buildUi())
        renderClock()
        noteActivity()
        renderAssistant()
        renderDisconnected("Meld · non connesso")
        handleDebugCommand(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
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
        mainHandler.post(weatherRefresh)
        startWake()
        kitchenTimer.resume()
    }

    override fun onStop() {
        visible = false
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.removeCallbacks(weatherRefresh)
        mainHandler.removeCallbacks(weatherRetry)
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(searchTimeout)
        unduckMusic()
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

    private fun buildUi(): FrameLayout {
        fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val white = Color.WHITE
        val grey = Color.rgb(170, 170, 170)
        val amber = Color.rgb(255, 193, 7)
        val blue = Color.rgb(144, 202, 249)
        val light = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)

        fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }
        fun icon(res: Int, sizeDp: Int, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(10) }
            setOnClickListener { onClick() }
        }
        fun homeButton() = text(24f, grey).apply {
            text = "‹ Home"
            setPadding(0, dp(4), dp(24), dp(12))
            setOnClickListener { showScreen(Screen.HOME) }
        }
        // Every screen is a full-height column; only one is visible at a time.
        fun panel(vararg children: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            children.forEach { addView(it) }
        }

        // ---- Home: the hub. Big clock, date, current weather; timer and music only as a line when active.
        clockView = text(92f, white).apply {
            typeface = light
            gravity = Gravity.CENTER
            setOnLongClickListener { // hidden developer panel
                debugPanel.visibility = if (debugPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                true
            }
        }
        dayView = text(20f, grey).apply { letterSpacing = 0.15f; gravity = Gravity.CENTER } // "LUNEDÌ 12 OTTOBRE"
        weatherTopView = text(24f, grey).apply {
            text = "–°"
            gravity = Gravity.CENTER
            setOnClickListener { openWeatherScreen() }
        }
        homeTimerView = text(26f, amber).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { showScreen(Screen.TIMERS) }
        }
        homeMusicView = text(20f, grey).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            setOnClickListener { showScreen(Screen.MUSIC) }
        }

        // Hidden developer panel (long-press the clock): search field and shortcuts.
        queryInput = EditText(this).apply {
            hint = "Cerca (es. Radiohead)"
            setHintTextColor(Color.GRAY)
            setTextColor(white)
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
        val bargeInButton = Button(this).apply {
            fun label() { text = "Interrompi con wake word: " + if (bargeInEnabled) "sì" else "no" }
            label()
            setOnClickListener {
                bargeInEnabled = !bargeInEnabled
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_BARGE_IN, bargeInEnabled).apply()
                label()
            }
        }
        val openMeldButton = Button(this).apply {
            text = "Apri Meld"
            setOnClickListener { openMeld() }
        }
        debugPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(queryInput, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(playButton)
            addView(bargeInButton)
            addView(openMeldButton)
        }
        val homePanel = panel(
            dayView, clockView, weatherTopView, homeTimerView, homeMusicView, debugPanel,
        ).apply {
            gravity = Gravity.CENTER
            listOf(dayView, clockView, weatherTopView, homeTimerView, homeMusicView, debugPanel).forEach {
                it.layoutParams = LinearLayout.LayoutParams(match, wrap)
            }
            (weatherTopView.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            (homeTimerView.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
            (homeMusicView.layoutParams as LinearLayout.LayoutParams).topMargin = dp(6)
            (debugPanel.layoutParams as LinearLayout.LayoutParams).topMargin = dp(10)
            setPadding(dp(28), 0, dp(28), 0)
            visibility = View.VISIBLE
        }

        // ---- Weather: only the weather.
        weatherTitleView = text(22f, grey).apply { gravity = Gravity.CENTER }
        weatherTempView = text(96f, white).apply { typeface = light; gravity = Gravity.CENTER }
        weatherCondView = text(36f, blue).apply { gravity = Gravity.CENTER }
        weatherDetailView = text(26f, grey).apply { gravity = Gravity.CENTER }
        val weatherBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(weatherTitleView, LinearLayout.LayoutParams(match, wrap))
            addView(weatherTempView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
            addView(weatherCondView, LinearLayout.LayoutParams(match, wrap))
            addView(weatherDetailView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(12) })
        }
        val weatherPanel = panel(homeButton(), weatherBody).apply {
            setPadding(dp(28), dp(8), dp(28), 0)
            weatherBody.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
        }

        // ---- Timers: only the timers (one big, or up to four stacked).
        timerRows = List(KitchenTimer.MAX_TIMERS) {
            Pair(
                text(20f, grey).apply { gravity = Gravity.CENTER; visibility = View.GONE },
                text(48f, amber).apply { typeface = light; gravity = Gravity.CENTER; visibility = View.GONE },
            )
        }
        timerHintView = text(24f, grey).apply { gravity = Gravity.CENTER }
        timerCancelView = text(24f, white).apply {
            text = "Annulla timer"
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(45, 45, 45))
            setPadding(dp(28), dp(10), dp(28), dp(10))
            setOnClickListener { cancelAllFromScreen() }
        }
        val timerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            timerRows.forEach { (label, time) ->
                addView(label, LinearLayout.LayoutParams(match, wrap))
                addView(time, LinearLayout.LayoutParams(match, wrap))
            }
            addView(timerHintView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
            addView(timerCancelView, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(14) })
            setOnClickListener { if (ringing) { stopRinging(); showScreen(Screen.HOME) } } // tap = stop the alarm
        }
        val timersPanel = panel(homeButton(), timerBody).apply {
            setPadding(dp(28), dp(8), dp(28), 0)
            timerBody.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
        }

        // ---- Music: only the player.
        coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(28, 28, 28))
        }
        titleView = text(32f, white, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        artistView = text(24f, grey).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        progressView = text(18f, Color.rgb(120, 120, 120))
        previousButton = icon(R.drawable.ic_prev, 64) { controller?.seekToPreviousMediaItem() }
        pauseButton = icon(R.drawable.ic_play, 76) { togglePlayPause() }
        nextButton = icon(R.drawable.ic_next, 64) { controller?.seekToNextMediaItem() }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previousButton)
            addView(pauseButton)
            addView(nextButton)
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, 0, 0)
            addView(titleView, LinearLayout.LayoutParams(match, wrap))
            addView(artistView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
            addView(progressView, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(4) })
            addView(controls, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dp(10) })
        }
        val nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(coverView, LinearLayout.LayoutParams(dp(170), dp(170)))
            addView(textColumn, LinearLayout.LayoutParams(0, match, 1f))
        }
        val musicPanel = panel(homeButton(), nowPlaying).apply {
            setPadding(dp(28), dp(8), dp(28), 0)
            nowPlaying.layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
        }

        screens = mapOf(
            Screen.HOME to homePanel, Screen.WEATHER to weatherPanel,
            Screen.TIMERS to timersPanel, Screen.MUSIC to musicPanel,
        )
        val content = FrameLayout(this).apply {
            screens.values.forEach { addView(it, FrameLayout.LayoutParams(match, match)) }
        }

        // Bottom bar on every screen: message line on the left, assistant state on the right
        // (tap = listen, long-press = wake word on/off).
        statusView = text(16f, grey).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        assistantView = text(28f, white).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(16), dp(6), 0, dp(6))
            setOnClickListener { toggleListening() }
            setOnLongClickListener { toggleWake(); true }
        }
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), 0, dp(28), dp(6))
            addView(statusView, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(assistantView)
        }

        statusView.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(e: android.text.Editable?) = showStatus()
        })

        dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(match, 0, 1f))
            addView(bottomBar, LinearLayout.LayoutParams(match, wrap))
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(dashboard, FrameLayout.LayoutParams(match, match))
        }
    }

    // ---- Screens ----------------------------------------------------------

    private enum class Screen { HOME, WEATHER, TIMERS, MUSIC }

    private lateinit var screens: Map<Screen, View>
    private var screen = Screen.HOME
    private val goHome = Runnable { if (ringing) scheduleGoHome() else showScreen(Screen.HOME) }

    /** One feature on screen at a time; anything but Home falls back to Home after a quiet spell. */
    private fun showScreen(target: Screen) {
        screen = target
        noteActivity()
        if (target == Screen.WEATHER) showStatus() else mainHandler.postDelayed(hideStatus, STATUS_MS)
        screens.forEach { (s, v) -> v.visibility = if (s == target) View.VISIBLE else View.GONE }
        renderTimer()
        renderProgress()
        scheduleGoHome()
        kickTicker()
    }

    private val hideStatus = Runnable { if (screen != Screen.WEATHER) statusView.visibility = View.INVISIBLE }

    /** The message line is for the Weather screen; elsewhere it only flashes for a few seconds. */
    private fun showStatus() {
        statusView.visibility = if (statusView.text.isEmpty()) View.INVISIBLE else View.VISIBLE
        mainHandler.removeCallbacks(hideStatus)
        mainHandler.postDelayed(hideStatus, STATUS_MS)
    }

    private fun scheduleGoHome() {
        mainHandler.removeCallbacks(goHome)
        if (screen == Screen.HOME) return
        mainHandler.postDelayed(goHome, if (screen == Screen.MUSIC) MUSIC_SCREEN_MS else SCREEN_MS)
    }

    /** Any touch counts as activity: keeps the current screen up a little longer. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            scheduleGoHome()
            noteActivity()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- Dashboard rendering ----------------------------------------------

    private val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY)
    private val dayFormat = java.text.SimpleDateFormat("EEEE", java.util.Locale.ITALY)
    private val dateFormat = java.text.SimpleDateFormat("d MMMM", java.util.Locale.ITALY)

    private lateinit var dashboard: View

    private var nightMode = false
    private var idleDim = false

    private val dimNow = Runnable {
        if (screen == Screen.HOME && !ringing && assistantState == AssistantState.IDLE) {
            idleDim = true
            applyBrightness()
        } else {
            noteActivity()
        }
    }

    /** Touch, command or voice: undim and restart the countdown to the daytime dimmer. */
    private fun noteActivity() {
        mainHandler.removeCallbacks(dimNow)
        mainHandler.postDelayed(dimNow, DIM_AFTER_MS)
        if (idleDim) {
            idleDim = false
            applyBrightness()
            // The refresh is skipped while the night screen sleeps: catch up when someone shows up.
            if (System.currentTimeMillis() - lastForecastAt > WEATHER_REFRESH_MS) refreshWeather()
        }
    }

    private fun isNightHour(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 20 || hour < 6
    }

    private fun applyBrightness() {
        val level = when {
            nightMode && idleDim -> DEEP_NIGHT_BRIGHTNESS // nobody is looking: practically off, a touch brings it back
            nightMode -> NIGHT_BRIGHTNESS
            idleDim -> IDLE_BRIGHTNESS
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = window.attributes.apply { screenBrightness = level }
    }

    private fun renderClock() {
        val now = java.util.Date()
        // Night: dim everything and nudge the layout a few px each hour, against burn-in. No animation.
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = hour >= 23 || hour < 6
        dashboard.alpha = if (night) 0.35f else 1f
        if (night != nightMode) { // the panel itself gets dimmer too, not only the colours
            nightMode = night
            applyBrightness()
        }
        dashboard.translationX = (hour % 4 - 2) * resources.displayMetrics.density * 2
        dashboard.translationY = (hour % 3 - 1) * resources.displayMetrics.density * 2
        clockView.text = timeFormat.format(now)
        dayView.text = (dayFormat.format(now) + " " + dateFormat.format(now)).uppercase(java.util.Locale.ITALY)
    }

    /** ○ idle (wake word armed), ● listening, ◌ working. A dim ○ means the wake word is off. */
    private fun renderAssistant() {
        assistantView.text = when (assistantState) {
            AssistantState.IDLE -> "○"
            AssistantState.LISTENING -> "● Ti ascolto…"
            AssistantState.THINKING -> "◌ Elaboro…"
        }
        assistantView.setTextColor(
            if (assistantState == AssistantState.IDLE && !wakeEnabled) Color.rgb(90, 90, 90) else Color.WHITE,
        )
    }

    private fun setAssistant(state: AssistantState) {
        assistantState = state
        noteActivity()
        renderAssistant()
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
        connectionMessage = statusView.text.toString()
        val token = SessionToken(this, ComponentName(pkg, MELD_SERVICE))
        // A MediaBrowser is a MediaController that can also ask Meld for search results (see playSearch).
        val future = MediaBrowser.Builder(this, token)
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
            is EchoIntent.MusicShuffle, is EchoIntent.MusicRepeat, is EchoIntent.MusicSearch, null -> true
            else -> false
        }
        if (needsMeld && controller == null) {
            statusView.text = "Meld non connesso"
            return null
        }
        routeScreen(intent)
        when (intent) {
            EchoIntent.MusicPause -> controller?.pause()
            EchoIntent.MusicResume -> controller?.play()
            EchoIntent.MusicNext -> controller?.let {
                if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); note(Replies.NEXT) } else note(Replies.NO_NEXT)
            }
            EchoIntent.MusicPrevious -> controller?.let {
                it.seekToPreviousMediaItem()
                note(Replies.PREVIOUS)
            }
            is EchoIntent.MusicSearch -> playSearch(intent.query)
            is EchoIntent.MusicShuffle -> {
                controller?.shuffleModeEnabled = intent.enabled
                note(Replies.shuffle(intent.enabled))
            }
            is EchoIntent.MusicRepeat -> {
                controller?.repeatMode = if (intent.enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                note(Replies.repeat(intent.enabled))
            }
            is EchoIntent.VolumeSet -> {
                if (intent.percent == 0 && volumePercent() > 0) volumeBeforeMute = volumePercent()
                setVolumePercent(intent.percent)
            }
            EchoIntent.VolumeUnmute -> setVolumePercent(volumeBeforeMute)
            is EchoIntent.VolumeNudge -> setVolumePercent(volumePercent() + if (intent.up) VOLUME_STEP / 2 else -VOLUME_STEP / 2)
            EchoIntent.VolumeUp -> setVolumePercent(volumePercent() + VOLUME_STEP)
            EchoIntent.VolumeDown -> setVolumePercent(volumePercent() - VOLUME_STEP)
            EchoIntent.WeatherNow -> showWeather(WeatherWhen.NOW)
            EchoIntent.WeatherTomorrow -> showWeather(WeatherWhen.TOMORROW)
            EchoIntent.WeatherWeek -> showWeather(WeatherWhen.WEEK)
            is EchoIntent.WeatherIn -> {
                val place = Cities.find(intent.city)
                if (place == null) reply(Replies.unknownCity(intent.city)) else showWeather(intent.`when`, place)
            }
            is EchoIntent.TimerCreate -> {
                if (kitchenTimer.start(intent.seconds, intent.label) == null) {
                    reply(Replies.TOO_MANY_TIMERS)
                } else {
                    renderTimer()
                    kickTicker()
                    reply(Replies.timerSet(intent.seconds, intent.label))
                }
            }
            is EchoIntent.TimerCancel -> reply(cancelTimers(intent))
            EchoIntent.Date -> {
                val now = java.util.Date()
                reply(Replies.date(
                    dayFormat.format(now), java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH),
                    java.text.SimpleDateFormat("MMMM", java.util.Locale.ITALY).format(now),
                ))
            }
            is EchoIntent.TimerRemaining -> {
                val found = if (intent.label == null) kitchenTimer.active() else kitchenTimer.find(intent.label)
                reply(when {
                    found.isNotEmpty() -> Replies.timersRemaining(found.map { it.label to secondsLeft(it) })
                    intent.label != null -> Replies.timerNotFound(intent.label)
                    else -> Replies.NO_TIMER
                })
            }
            EchoIntent.Time -> {
                val now = java.util.Calendar.getInstance()
                reply(Replies.time(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE)))
            }
            // Typed text falls back to a plain search; a voice transcript must be a real command.
            null -> if (fromVoice) {
                statusView.text = "Non ho capito: “$text” ($detail)"
            } else {
                showScreen(Screen.MUSIC)
                playSearch(text)
            }
        }
        return intent
    }

    /** Each command brings up its own screen; weather does it when the forecast arrives. */
    private fun routeScreen(intent: EchoIntent?) {
        when (intent) {
            EchoIntent.MusicPause, EchoIntent.MusicResume, EchoIntent.MusicNext, EchoIntent.MusicPrevious,
            is EchoIntent.MusicShuffle, is EchoIntent.MusicRepeat, is EchoIntent.MusicSearch -> showScreen(Screen.MUSIC)
            is EchoIntent.TimerCreate, is EchoIntent.TimerRemaining -> showScreen(Screen.TIMERS)
            is EchoIntent.TimerCancel, EchoIntent.Time, EchoIntent.Date -> showScreen(Screen.HOME)
            else -> Unit
        }
    }

    // ---- Voice: wake word -> beep -> VAD + STT -> intent -> Meld ----------

    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleWake() {
        wakeEnabled = !wakeEnabled
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_WAKE, wakeEnabled).apply()
        renderAssistant()
        statusView.text = if (wakeEnabled) "Wake word attiva: «Hey Jarvis», «Hey Echo», «Alexa»" else "Wake word disattivata"
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

    /** Idle again: if the wake word cut the speech short, go straight to a new command; otherwise listen for the wake word. */
    private fun resumeListening() {
        if (bargeInPending && !voiceBusy) {
            bargeInPending = false
            beginCommand(fromWake = true)
        } else {
            startWake()
        }
    }

    /** While the assistant speaks: listen for the wake word with echo cancellation (only if enabled). */
    private fun startBargeInListener() {
        if (!bargeInEnabled || !wakeEnabled || !visible || !hasMicPermission() || recorder.isRecording) return
        bargeInActive = true
        wakeListener.start(bargeIn = true) {
            mainHandler.post {
                bargeInActive = false
                android.util.Log.i("EchoHome", "barge-in: wake word during speech")
                bargeInPending = true
                tts.stop() // the speech callbacks then end the session and resumeListening() starts the command
            }
        }
    }

    /** "Parla" button: starts a command session, or ends the current one early. */
    private fun toggleListening() {
        if (speakingCount > 0) { // tap while the assistant talks = be quiet
            tts.stop()
            return
        }
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

        statusView.text = ""
        setAssistant(AssistantState.LISTENING)
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
        setAssistant(AssistantState.IDLE)
        val c = controller
        if (sessionWasPlaying && c != null) {
            when (executed) {
                EchoIntent.MusicPause, is EchoIntent.MusicSearch -> Unit // stay paused / search starts playback
                // Anything that does not itself change playback: put the music back.
                EchoIntent.MusicNext, EchoIntent.MusicPrevious, EchoIntent.MusicResume,
                is EchoIntent.MusicShuffle, is EchoIntent.MusicRepeat, EchoIntent.VolumeUnmute, EchoIntent.Date, is EchoIntent.TimerRemaining,
                is EchoIntent.VolumeSet, EchoIntent.VolumeUp, EchoIntent.VolumeDown, is EchoIntent.VolumeNudge, EchoIntent.Time,
                is EchoIntent.TimerCreate, is EchoIntent.TimerCancel, EchoIntent.WeatherNow, EchoIntent.WeatherTomorrow, EchoIntent.WeatherWeek, is EchoIntent.WeatherIn, null -> c.play()
            }
        }
        sessionWasPlaying = false
        resumeListening()
    }

    private fun onUtterance(result: MicRecorder.Result) {
        val samples = result.speech
        if (samples.isEmpty()) {
            statusView.text = "Nessun parlato rilevato (${result.reason}, ${"%.1f".format(result.totalSeconds)}s, picco ${"%.2f".format(result.peak)})"
            endSession(null)
            return
        }
        setAssistant(AssistantState.THINKING)
        // Diagnostics only (lengths and loudest sample): never the audio itself.
        val speechSec = "%.1f".format(samples.size / SpeechToText.SAMPLE_RATE.toFloat())
        val peak = "%.2f".format(samples.maxOf { kotlin.math.abs(it) })
        val audioInfo = "parlato ${speechSec}s su ${"%.1f".format(result.totalSeconds)}s, picco $peak, ${result.reason}"
        sttExecutor.execute {
            val started = System.nanoTime()
            val text = try {
                val coldStart = stt == null
                val engine = stt ?: SpeechToText(assets).also { stt = it }
                val loadedMs = (System.nanoTime() - started) / 1_000_000
                val decoded = engine.transcribe(samples)
                // Timings only, never the text or the audio.
                android.util.Log.i("EchoHome", "stt: cold=$coldStart load=${loadedMs}ms total=${(System.nanoTime() - started) / 1_000_000}ms audio=${speechSec}s")
                decoded
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

    /**
     * Asks Meld for the matching songs and plays the first one. Meld's own "voice search" gives up
     * on artist-only queries ("Radiohead") and the old queue keeps playing, so we pick the song
     * ourselves and hand Meld its media id; the voice search stays as the fallback.
     */
    private fun playSearch(rawQuery: String) {
        val query = rawQuery.trim()
        val activeController = controller
        if (query.isBlank() || activeController == null) return

        statusView.text = "Cerco “$query”…"
        mainHandler.removeCallbacks(searchTimeout)
        mainHandler.postDelayed(searchTimeout, SEARCH_TIMEOUT_MS)

        val browser = activeController as? MediaBrowser
        if (browser == null) {
            playVoiceSearch(activeController, query)
            return
        }
        val request = ++searchRequest
        val future = browser.getSearchResult(query, 0, SEARCH_PAGE_SIZE, null)
        future.addListener({
            if (request != searchRequest || controller !== browser) return@addListener // superseded
            val result = runCatching { future.get() }.getOrNull()
            val first = result?.takeIf { it.resultCode == LibraryResult.RESULT_SUCCESS }?.value?.firstOrNull()
            if (first == null) {
                playVoiceSearch(browser, query)
            } else {
                browser.setMediaItem(first)
                browser.prepare()
                browser.play()
            }
        }, uiExecutor)
    }

    private fun playVoiceSearch(c: MediaController, query: String) {
        val mediaItem = MediaItem.Builder()
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setSearchQuery(query).build())
            .build()
        c.setMediaItem(mediaItem)
        c.prepare()
        c.play()
    }

    // Music volume is the system STREAM_MUSIC one, which is what Meld plays on.
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private var volumeBeforeMute = 30 // restored by "riattiva l'audio"

    // Fine volume: the system step plus Meld's player volume as a trim (see VolumeMath).
    private fun canTrim() = controller?.isCommandAvailable(Player.COMMAND_SET_VOLUME) == true

    private fun currentTrim(): Float = volumeBeforeDuck ?: controller?.volume ?: 1f

    private fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return VolumeMath.percent(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), max, currentTrim())
    }

    private fun setVolumePercent(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val (step, trim) = VolumeMath.split(percent, max, canTrim())
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        if (canTrim()) {
            if (volumeBeforeDuck != null) volumeBeforeDuck = trim else controller?.volume = trim // ducked: restored later
        }
        note(Replies.volume(volumePercent()))
    }

    // ---- Spoken answers -----------------------------------------------------

    private var speakingCount = 0
    private var afterSpeech: (() -> Unit)? = null

    /** Shows [text] only: confirmations of actions are not worth seconds of speech on the Echo's slow CPU. */
    private fun note(text: String) {
        statusView.text = text
    }

    /** Shows [text] and says it out loud. */
    private fun reply(text: String) {
        statusView.text = text
        speak(text)
    }

    // While the assistant talks the music plays quietly instead of staying paused (the wake word
    // listener is off then, so there is no echo problem). Needs COMMAND_SET_VOLUME from Meld.
    private var volumeBeforeDuck: Float? = null

    private fun duckMusic() {
        val c = controller ?: return
        if (volumeBeforeDuck != null) return
        if (!c.isCommandAvailable(Player.COMMAND_SET_VOLUME)) {
            android.util.Log.i("EchoHome", "duck: Meld does not allow volume changes")
            return
        }
        if (!(c.isPlaying || c.playWhenReady) && !sessionWasPlaying) return
        volumeBeforeDuck = c.volume
        c.volume = DUCK_VOLUME
        android.util.Log.i("EchoHome", "duck: music volume ${volumeBeforeDuck} -> $DUCK_VOLUME")
        if (sessionWasPlaying && !c.isPlaying) c.play() // the session paused it to record the command
    }

    private fun unduckMusic() {
        val before = volumeBeforeDuck ?: return
        volumeBeforeDuck = null
        controller?.volume = before
        android.util.Log.i("EchoHome", "duck: music volume back to $before, playing=${controller?.isPlaying}")
    }

    private fun speak(text: String, then: () -> Unit = {}) {
        if (speakingCount == 0) duckMusic()
        speakingCount++
        wakeListener.stop() // the normal detector must not hear our own voice
        bargeInActive = false
        startBargeInListener()
        tts.speak(text) {
            speakingCount--
            then()
            if (speakingCount == 0) {
                if (bargeInActive) {
                    wakeListener.stop() // back to the normal detector (resumeListening starts it)
                    bargeInActive = false
                }
                unduckMusic()
                afterSpeech?.let { afterSpeech = null; it() }
                resumeListening() // no-op while a voice session is still running
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
    private var weatherMode = WeatherWhen.NOW
    private var weatherPlace: String? = null // null = home; set while the screen shows another city

    /** Pending work (network) that must finish before a voice session may end, like speech. */
    private fun beginPending() { speakingCount++ }

    private fun endPending() {
        speakingCount--
        if (speakingCount == 0) {
            afterSpeech?.let { afterSpeech = null; it() }
            resumeListening()
        }
    }

    /** [place] null = home. Other cities are fetched every time and never replace the home forecast. */
    private fun showWeather(mode: WeatherWhen, place: City? = null) {
        statusView.text = "Leggo il meteo…"
        beginPending()
        val away = place?.takeIf { it.name != WeatherClient.CITY }
        val cached = if (away != null) null else lastForecast?.takeIf { System.currentTimeMillis() - lastForecastAt < WEATHER_CACHE_MS }
        weatherExecutor.execute {
            val forecast = cached ?: try {
                if (away != null) WeatherClient.fetch(away.lat, away.lon) else WeatherClient.fetch()
            } catch (_: Exception) {
                if (away != null) null else lastForecast // stale home data is better than none
            }
            mainHandler.post {
                if (forecast == null) {
                    reply(Replies.WEATHER_UNAVAILABLE)
                } else {
                    if (away == null && (forecast !== lastForecast || cached == null)) {
                        lastForecast = forecast
                        lastForecastAt = System.currentTimeMillis()
                    }
                    renderWeather(forecast, mode, away?.name)
                    val answer = when (mode) {
                        WeatherWhen.NOW -> Replies.weatherNow(forecast)
                        WeatherWhen.TOMORROW -> Replies.weatherTomorrow(forecast)
                        WeatherWhen.WEEK -> Replies.weatherWeek(forecast)
                    }
                    reply(if (away != null) Replies.inCity(away.name) + ": " + answer.replaceFirstChar { it.lowercase() } else answer)
                }
                endPending()
            }
        }
    }

    private val weatherRefresh = object : Runnable {
        override fun run() {
            if (!(nightMode && idleDim)) refreshWeather() // no network while the dark screen idles
            if (visible) mainHandler.postDelayed(this, WEATHER_REFRESH_MS)
        }
    }

    // A failed refresh (network not ready right after boot/start) is retried soon, not in 30 minutes.
    private var weatherRetryMs = WEATHER_RETRY_MIN_MS
    private val weatherRetry = Runnable { refreshWeather() }

    /** Updates the header temperature (silent). Same Open-Meteo call as the voice answers. */
    private fun refreshWeather() {
        mainHandler.removeCallbacks(weatherRetry)
        weatherExecutor.execute {
            val f = try {
                WeatherClient.fetch()
            } catch (e: Exception) {
                android.util.Log.i("EchoHome", "weather: refresh failed (${e.javaClass.simpleName})")
                null
            }
            mainHandler.post {
                if (f != null) {
                    lastForecast = f
                    lastForecastAt = System.currentTimeMillis()
                    weatherRetryMs = WEATHER_RETRY_MIN_MS
                } else if (visible) {
                    mainHandler.postDelayed(weatherRetry, weatherRetryMs)
                    weatherRetryMs = minOf(weatherRetryMs * 2, WEATHER_RETRY_MAX_MS)
                }
                renderWeatherHeader()
                if (f != null && screen == Screen.WEATHER && weatherPlace == null) renderWeather(f, weatherMode)
            }
        }
    }

    private fun renderWeatherHeader() {
        val f = lastForecast
        weatherTopView.text = if (f == null) "–°" else "${f.nowTemp}° ${Replies.icon(f.nowCode, isNightHour())} ${Replies.condition(f.nowCode)}"
    }

    /** Fills the weather screen and brings it up. */
    private fun renderWeather(f: Forecast, mode: WeatherWhen, place: String? = null) {
        weatherMode = mode
        weatherPlace = place
        renderWeatherHeader()
        val week = mode == WeatherWhen.WEEK
        weatherTempView.visibility = if (week) View.GONE else View.VISIBLE
        weatherCondView.visibility = if (week) View.GONE else View.VISIBLE
        weatherTitleView.text = "${place ?: WeatherClient.CITY} · " + when (mode) {
            WeatherWhen.NOW -> "adesso"
            WeatherWhen.TOMORROW -> "domani"
            WeatherWhen.WEEK -> "prossimi giorni"
        }
        weatherDetailView.typeface = if (week) android.graphics.Typeface.MONOSPACE else android.graphics.Typeface.DEFAULT
        weatherDetailView.textSize = if (week) 22f else 26f
        // The day table is one left-aligned block, centred as a whole, so the columns line up.
        weatherDetailView.gravity = if (week) Gravity.START else Gravity.CENTER
        weatherDetailView.layoutParams = (weatherDetailView.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (week) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        }
        when (mode) {
            WeatherWhen.TOMORROW -> {
                weatherTempView.text = "${f.tomorrowMin}° / ${f.tomorrowMax}°"
                weatherCondView.text = "${Replies.icon(f.tomorrowCode)} ${Replies.condition(f.tomorrowCode)}"
                weatherDetailView.text = Replies.rainSummary(f.tomorrowRainByHour)
            }
            WeatherWhen.NOW -> {
                weatherTempView.text = "${f.nowTemp}°"
                weatherCondView.text = "${Replies.icon(f.nowCode, isNightHour())} ${Replies.condition(f.nowCode)}"
                weatherDetailView.text = "Oggi min ${f.todayMin}° · max ${f.todayMax}°"
            }
            WeatherWhen.WEEK -> weatherDetailView.text = Replies.weatherWeekRows(f).joinToString("\n")
        }
        showScreen(Screen.WEATHER)
    }

    /** Tap on Home weather: show what we have now and refresh in the background. */
    private fun openWeatherScreen() {
        weatherPlace = null
        lastForecast?.let { renderWeather(it, WeatherWhen.NOW) } ?: run {
            weatherTitleView.text = "${WeatherClient.CITY} · adesso"
            weatherTempView.visibility = View.VISIBLE
            weatherCondView.visibility = View.VISIBLE
            weatherTempView.text = "–°"
            weatherCondView.text = ""
            weatherDetailView.text = "Meteo non ancora disponibile"
            showScreen(Screen.WEATHER)
        }
        refreshWeather()
    }

    // ---- Timer ------------------------------------------------------------

    private fun secondsLeft(t: KitchenTimer.Timer) = ((kitchenTimer.remainingMs(t) + 999) / 1000).toInt()

    private fun startRinging(t: KitchenTimer.Timer) {
        ringing = true
        ringCount = 0
        ringLabels += t.label
        statusView.text = Replies.timerDone(t.label)
        renderTimer()
        showScreen(Screen.TIMERS)
        mainHandler.removeCallbacks(ringTick)
        // Say it once, then the beeps keep going until dismissed.
        speak(Replies.timerDone(t.label)) { if (ringing) mainHandler.post(ringTick) }
    }

    private fun stopRinging() {
        ringing = false
        ringLabels.clear()
        mainHandler.removeCallbacks(ringTick)
        renderTimer()
    }

    /** Voice "annulla il timer ...": returns the spoken answer. Stops the alarm first if it is ringing. */
    private fun cancelTimers(intent: EchoIntent.TimerCancel): String {
        val wasRinging = ringing
        if (wasRinging) stopRinging()
        val reply = when {
            intent.all -> if (kitchenTimer.cancelAll() > 0 || wasRinging) Replies.TIMERS_CANCELLED else Replies.NO_TIMER
            intent.label != null -> {
                val found = kitchenTimer.find(intent.label)
                found.forEach { kitchenTimer.cancel(it) }
                if (found.isNotEmpty()) Replies.timerCancelled(intent.label) else Replies.timerNotFound(intent.label)
            }
            wasRinging -> Replies.TIMER_CANCELLED
            else -> kitchenTimer.active().firstOrNull()?.let {
                kitchenTimer.cancel(it)
                Replies.timerCancelled(it.label)
            } ?: Replies.NO_TIMER
        }
        renderTimer()
        return reply
    }

    private fun cancelAllFromScreen() {
        if (ringing) stopRinging()
        kitchenTimer.cancelAll()
        renderTimer()
        showScreen(Screen.HOME)
    }

    private fun clockText(total: Int) =
        if (total >= 3600) "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(total / 60, total % 60)

    /** Home shows one line (the soonest timer, "+N" if more); the Timers screen lists them all. */
    private fun renderTimer() {
        val active = kitchenTimer.active()
        timerRows.forEachIndexed { i, (label, time) ->
            val t = active.getOrNull(i)
            val shown = t != null && !ringing
            label.visibility = if (shown && t!!.label != null) View.VISIBLE else View.GONE
            time.visibility = if (shown) View.VISIBLE else View.GONE
            if (shown) {
                label.text = t!!.label?.replaceFirstChar { it.uppercase() }
                time.text = clockText(secondsLeft(t))
                val n = active.size
                time.textSize = when (n) { 1 -> 110f; 2 -> 64f; 3 -> 46f; else -> 38f }
                label.textSize = if (n == 1) 24f else 18f
            }
        }
        when {
            ringing -> {
                // Show who rang in the first row, big.
                val (label, time) = timerRows[0]
                label.visibility = View.GONE
                time.visibility = View.VISIBLE
                time.textSize = 72f
                time.text = ringLabels.filterNotNull().distinct().joinToString(" · ").ifEmpty { "Timer" }.replaceFirstChar { it.uppercase() } + " scaduto!"
                timerHintView.text = "Tocca per fermare"
                timerCancelView.visibility = View.GONE
            }
            active.isNotEmpty() -> {
                timerHintView.text = ""
                timerCancelView.text = if (active.size > 1) "Annulla tutti" else "Annulla timer"
                timerCancelView.visibility = View.VISIBLE
            }
            else -> {
                timerHintView.text = "Nessun timer · di’: “timer pasta 10 minuti”"
                timerCancelView.visibility = View.GONE
            }
        }
        homeTimerView.visibility = if (ringing || active.isNotEmpty()) View.VISIBLE else View.GONE
        homeTimerView.text = when {
            ringing -> "⏱ Timer scaduto"
            active.isEmpty() -> ""
            else -> {
                val first = active.first()
                "⏱ " + (first.label?.let { "$it " } ?: "") + clockText(secondsLeft(first)) +
                    if (active.size > 1) " +${active.size - 1}" else ""
            }
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
        connectionMessage = message
        setControlsEnabled(false)
        titleView.text = "Niente in riproduzione"
        artistView.text = ""
        progressView.text = ""
        showCover(null, null)
        homeMusicView.visibility = View.GONE
    }

    private fun renderState() {
        val c = controller ?: return
        if (c.playerError != null) {
            statusView.text = "Errore player: ${c.playerError?.errorCodeName}"
        } else if (statusView.text.toString() == connectionMessage) {
            statusView.text = "" // connected again: drop the "Meld non connesso" line
        }
        pauseButton.setImageResource(if (c.isPlaying || c.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        renderHomeMusic()
    }

    /** Home mentions the track only while music is playing. */
    private fun renderHomeMusic() {
        val c = controller
        val playing = c != null && c.currentMediaItem != null && (c.isPlaying || c.playWhenReady)
        homeMusicView.visibility = if (playing) View.VISIBLE else View.GONE
        val artist = artistView.text.toString()
        homeMusicView.text = "♪ " + titleView.text + if (artist.isNotEmpty()) " · $artist" else ""
    }

    private fun renderMetadata(metadata: MediaMetadata) {
        val title = metadata.title?.toString()?.takeIf { it.isNotBlank() }
        val artist = (metadata.artist ?: metadata.albumArtist)?.toString()?.takeIf { it.isNotBlank() }
        titleView.text = title ?: "Niente in riproduzione"
        artistView.text = artist.orEmpty()
        showCover(metadata.artworkData, metadata.artworkUri)
        renderProgress()
        renderHomeMusic()
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
                val bytes = data ?: when {
                    uri!!.scheme == "http" || uri.scheme == "https" -> URL(uri.toString()).openStream().use { it.readBytes() }
                    else -> contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                bytes?.let { decodeCover(it) }
            } catch (_: Exception) {
                null
            }
            mainHandler.post {
                if (request == artworkRequest) coverView.setImageBitmap(bitmap)
            }
        }
    }

    /** Decodes at about [COVER_MAX_PX]: a 1000 px cover would cost 4 MB for a 170dp view. */
    private fun decodeCover(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= COVER_MAX_PX && bounds.outHeight / (sample * 2) >= COVER_MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
