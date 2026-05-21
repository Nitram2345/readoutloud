package com.readoutloud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()

        serviceScope.launch {
            val am = getSystemService(AudioManager::class.java)
            val sessionId = am.generateAudioSessionId()
            tts = TextToSpeech(this@TtsService) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.GERMAN
                    ttsController = TtsController(tts!!, sessionId, am).also { ctrl ->
                        ctrl.onPlayStateChanged = { playing ->
                            updatePlaybackState(playing)
                            if (playing) {
                                startForeground(NOTIF_ID, buildNotification(true))
                            } else {
                                stopForeground(STOP_FOREGROUND_DETACH)
                                notify(false)
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
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        serviceJob.cancel()
        mediaSession.release()
        ttsController?.release()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "ReadOutLoud").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay()            { ttsController?.resume() }
                override fun onPause()           { ttsController?.pause() }
                override fun onSkipToNext()      { ttsController?.skipForward(30) }
                override fun onSkipToPrevious()  { ttsController?.skipBackward(30) }
            })
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
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .build()
        )
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, TtsService::class.java).apply { this.action = action },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun notify(playing: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(playing))
    }

    private fun buildNotification(playing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ReadOutLoud")
            .setContentText(if (playing) "Liest vor…" else "Pausiert")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_rew),
                "−30s", actionPendingIntent(ACTION_SKIP_BACKWARD, 1)
            ).build())
            .addAction(if (playing)
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                    "Pause", actionPendingIntent(ACTION_PAUSE, 2)
                ).build()
            else
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_play),
                    "Spielen", actionPendingIntent(ACTION_PLAY, 2)
                ).build()
            )
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_ff),
                "+30s", actionPendingIntent(ACTION_SKIP_FORWARD, 3)
            ).build())
            .setOngoing(playing)
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
        private const val CHANNEL_ID   = "tts_playback"
        private const val NOTIF_ID     = 1
    }
}
