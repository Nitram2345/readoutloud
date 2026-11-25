package de.readoutloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class TtsService : Service() {

    inner class TtsBinder : Binder() {
        fun getController(): TtsController? = ttsController
        fun getVoiceManager(): VoiceManager = voiceManager
        var onControllerReady: ((TtsController) -> Unit)? = null
    }

    private val binder = TtsBinder()
    private var tts: TextToSpeech? = null
    var ttsController: TtsController? = null
        private set
    private lateinit var voiceManager: VoiceManager
    private lateinit var piperEngine: PiperTtsEngine
    private lateinit var mediaSession: MediaSession
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Re-arm lock-screen media widget when screen turns on
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) return
            val playing = ttsController?.isPlaying?.value == true
            mediaSession.isActive = true
            updatePlaybackState(playing)
            if (ttsController != null) showNotification(playing)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        val settings = AppSettings(this)
        voiceManager = VoiceManager(this, VoiceRepositoryImpl(), settings)
        piperEngine = PiperTtsEngine(this, voiceManager)

        serviceScope.launch {
            val am = getSystemService(AudioManager::class.java)
            val sessionId = am.generateAudioSessionId()
            tts = TextToSpeech(this@TtsService) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.GERMAN
                    ttsController = TtsController(tts!!, sessionId, am, voiceManager, piperEngine).also { ctrl ->
                        // Single source of truth: observe play state, drive
                        // MediaSession + notification from every emission.
                        CoroutineScope(Dispatchers.Main + serviceJob).launch {
                            ctrl.isPlaying.collectLatest { playing ->
                                updatePlaybackState(playing)
                                showNotification(playing)
                            }
                        }
                        binder.onControllerReady?.invoke(ctrl)
                        binder.onControllerReady = null
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY          -> ttsController?.resume()
            ACTION_PAUSE         -> ttsController?.pause()
            ACTION_SKIP_FORWARD  -> ttsController?.skipForward(30)
            ACTION_SKIP_BACKWARD -> ttsController?.skipBackward(30)
            ACTION_SPEED_UP      -> changeSpeed(+1)
            ACTION_SPEED_DOWN    -> changeSpeed(-1)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Release ORT session (~80MB native memory) under memory pressure.
        // TtsController retries via espeakDataReady flow when next needed.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (ttsController?.isPlaying?.value != true) {
                piperEngine.releaseModel()
                android.util.Log.d("TtsService", "Released Piper model at TRIM_MEMORY level=$level")
            }
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        // A detached (paused) notification outlives the service; its buttons would hit a dead controller.
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        unregisterReceiver(screenOnReceiver)
        mediaSession.release()
        ttsController?.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // Foreground only while speaking, as Play requires for mediaPlayback. When paused, the
    // notification is detached: it stays visible with its controls but can be swiped away.
    private fun showNotification(playing: Boolean) {
        val notif = buildNotification(playing)
        if (playing) {
            try {
                startForegroundCompat(notif)
                return
            } catch (e: IllegalStateException) {
                // Android 12+: ForegroundServiceStartNotAllowedException when playback is resumed
                // from the background, e.g. by a Bluetooth media button. Speech still runs.
                android.util.Log.w("TtsService", "startForeground not allowed", e)
            }
        } else {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "ReadOutLoud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay()           { ttsController?.resume() }
                override fun onPause()          { ttsController?.pause() }
                override fun onSkipToNext()     { ttsController?.skipForward(30) }
                override fun onSkipToPrevious() { ttsController?.skipBackward(30) }

                // Samsung One UI routes hardware/BT media buttons via KeyEvent, not direct callbacks
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    val ke: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    if (ke?.action == KeyEvent.ACTION_DOWN) {
                        when (ke.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { ttsController?.resume(); return true }
                            KeyEvent.KEYCODE_MEDIA_PAUSE      -> { ttsController?.pause();  return true }
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { ttsController?.skipForward(30);  return true }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND       -> { ttsController?.skipBackward(30); return true }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            }, Handler(Looper.getMainLooper()))
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "ReadOutLoud")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "")
                    .build()
            )
            isActive = true
        }
        updatePlaybackState(false)
    }

    private fun updatePlaybackState(playing: Boolean) {
        val actions = PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f
                )
                .setActions(actions)
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "ReadOutLoud")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, if (playing) "▶" else "⏸")
                .build()
        )
    }

    private fun changeSpeed(delta: Int) {
        val rate = ttsController?.currentRate ?: 1.0f
        val idx = SPEED_STEPS.indexOfFirst { it >= rate - 0.01f }.coerceAtLeast(0)
        val newRate = SPEED_STEPS[(idx + delta).coerceIn(0, SPEED_STEPS.lastIndex)]
        ttsController?.setRate(newRate)
        showNotification(ttsController?.isPlaying?.value == true)
    }

    private fun rateLabel(rate: Float): String {
        val s = "%.2f".format(rate).trimEnd('0').trimEnd('.')
        return "$s×"
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, TtsService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // Service is not an Activity, so getString() ignores the user-selected
    // language. Build a context wrapped in the chosen locale.
    private fun localizedContext(): Context {
        val lang = AppSettings(this).language
        val locale = Locale.forLanguageTag(lang.replace("_", "-"))
        val config = Configuration(resources.configuration).apply { setLocale(locale) }
        return createConfigurationContext(config)
    }

    private fun buildNotification(playing: Boolean): Notification {
        val ctx = localizedContext()
        val rate = ttsController?.currentRate ?: 1.0f
        val rateIdx = SPEED_STEPS.indexOfFirst { it >= rate - 0.01f }.coerceAtLeast(0)
        val lowerLabel = rateLabel(SPEED_STEPS[(rateIdx - 1).coerceAtLeast(0)])
        val upperLabel = rateLabel(SPEED_STEPS[(rateIdx + 1).coerceAtMost(SPEED_STEPS.lastIndex)])

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Action layout (expanded): [0]speed− [1]−30s [2]▶/⏸ [3]+30s [4]speed+
        // Compact view [1,2,3]: −30s | ▶/⏸ | +30s
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ReadOutLoud  ${rateLabel(rate)}")
            .setContentText(if (playing) ctx.getString(R.string.notif_reading) else ctx.getString(R.string.notif_paused))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .addAction(                                                    // [0] speed−
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                    lowerLabel,
                    actionPendingIntent(ACTION_SPEED_DOWN, 4)
                ).build()
            )
            .addAction(                                                    // [1] −30s
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_rew),
                    "−30s",
                    actionPendingIntent(ACTION_SKIP_BACKWARD, 1)
                ).build()
            )
            .addAction(                                                    // [2] play/pause
                Notification.Action.Builder(
                    Icon.createWithResource(this,
                        if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                    if (playing) ctx.getString(R.string.pause) else ctx.getString(R.string.notif_play),
                    actionPendingIntent(if (playing) ACTION_PAUSE else ACTION_PLAY, if (playing) 2 else 6)
                ).build()
            )
            .addAction(                                                    // [3] +30s
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_ff),
                    "+30s",
                    actionPendingIntent(ACTION_SKIP_FORWARD, 3)
                ).build()
            )
            .addAction(                                                    // [4] speed+
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    upperLabel,
                    actionPendingIntent(ACTION_SPEED_UP, 5)
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Vorlesen", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Läuft im Hintergrund während des Vorlesens" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PLAY          = "de.readoutloud.PLAY"
        const val ACTION_PAUSE         = "de.readoutloud.PAUSE"
        const val ACTION_SKIP_FORWARD  = "de.readoutloud.SKIP_FORWARD"
        const val ACTION_SKIP_BACKWARD = "de.readoutloud.SKIP_BACKWARD"
        const val ACTION_SPEED_UP      = "de.readoutloud.SPEED_UP"
        const val ACTION_SPEED_DOWN    = "de.readoutloud.SPEED_DOWN"
        private val SPEED_STEPS        = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private const val CHANNEL_ID   = "tts_playback"
        private const val NOTIF_ID     = 1
    }
}
