package com.readoutloud

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
import kotlinx.coroutines.launch
import java.util.Locale

class TtsService : Service() {

    inner class TtsBinder : Binder() {
        fun getController(): TtsController? = ttsController
        var onControllerReady: ((TtsController) -> Unit)? = null
    }

    private val binder = TtsBinder()
    private var tts: TextToSpeech? = null
    var ttsController: TtsController? = null
        private set
    private lateinit var mediaSession: MediaSession
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val pendingStateUpdates = mutableListOf<Runnable>()

    // Re-arm lock-screen media widget when screen turns on
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_ON) return
            val playing = ttsController?.isPlaying == true
            mediaSession.isActive = true
            updatePlaybackState(playing)
            if (ttsController != null) startForegroundCompat(buildNotification(playing))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        serviceScope.launch {
            val am = getSystemService(AudioManager::class.java)
            val sessionId = am.generateAudioSessionId()
            tts = TextToSpeech(this@TtsService) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.GERMAN
                    ttsController = TtsController(tts!!, sessionId, am).also { ctrl ->
                        ctrl.onPlayStateChanged = { playing ->
                            updatePlaybackState(playing)
                            if (!playing) {
                                // Fallback: if onSpeakStop is late, update after 300ms
                                val r = Runnable { startForegroundCompat(buildNotification(false)) }
                                pendingStateUpdates.add(r)
                                uiHandler.postDelayed(r, 300)
                            }
                        }
                        ctrl.onSpeakStart = { startForegroundCompat(buildNotification(true)) }
                        ctrl.onSpeakStop = {
                            pendingStateUpdates.forEach { uiHandler.removeCallbacks(it) }
                            pendingStateUpdates.clear()
                            startForegroundCompat(buildNotification(false))
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

    override fun onDestroy() {
        serviceJob.cancel()
        unregisterReceiver(screenOnReceiver)
        mediaSession.release()
        ttsController?.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
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
        pendingStateUpdates.forEach { uiHandler.removeCallbacks(it) }
        pendingStateUpdates.clear()
        applyPlaybackState(playing)
    }

    private fun applyPlaybackState(playing: Boolean) {
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
        startForegroundCompat(buildNotification(ttsController?.isPlaying == true))
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

    private fun buildNotification(playing: Boolean): Notification {
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
            .setContentText(if (playing) "Liest vor…" else "Pausiert")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
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
                    if (playing) "Pause" else "Spielen",
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
        const val ACTION_PLAY          = "com.readoutloud.PLAY"
        const val ACTION_PAUSE         = "com.readoutloud.PAUSE"
        const val ACTION_SKIP_FORWARD  = "com.readoutloud.SKIP_FORWARD"
        const val ACTION_SKIP_BACKWARD = "com.readoutloud.SKIP_BACKWARD"
        const val ACTION_SPEED_UP      = "com.readoutloud.SPEED_UP"
        const val ACTION_SPEED_DOWN    = "com.readoutloud.SPEED_DOWN"
        private val SPEED_STEPS        = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        private const val CHANNEL_ID   = "tts_playback"
        private const val NOTIF_ID     = 1
    }
}
