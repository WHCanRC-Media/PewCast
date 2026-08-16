package org.whcanrc.pewcast.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.whcanrc.pewcast.MainActivity
import org.whcanrc.pewcast.R

class PlaybackService : Service() {

    companion object {
        const val ACTION_STOP = "org.whcanrc.pewcast.action.STOP"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "playback"
    }

    val engine = AudioEngine()

    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var mediaSession: MediaSessionCompat

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /**
     * Identity of the session currently in progress. The service outlives the
     * activity, so this has to live here — otherwise an activity recreated during
     * playback comes back with no way to address the session it left running.
     */
    @Volatile var clientId: String? = null
    @Volatile var serverAddress: String = ""

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "PewCast:Playback")
            .apply { setReferenceCounted(false) }

        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "PewCast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPause() { stopPlayback() }
                override fun onStop() { stopPlayback() }
            })
        }
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        if (wifiLock.isHeld) wifiLock.release()
        mediaSession.release()
        super.onDestroy()
    }

    /**
     * Starts the audio engine and returns the local UDP port it bound to, so
     * the caller can complete the server handshake. Call [commitPlayback] after
     * the handshake succeeds, or [abortPlayback] if it fails.
     */
    fun preparePlayback(host: String, targetBufferMs: Int): Int? {
        // A session can still be live here if the UI lost track of it (activity
        // recreated without adopting it). Tear it down and start fresh rather than
        // failing, which would leave the button permanently dead until force-stop.
        if (_isPlaying.value) teardown()
        engine.setTargetBufferMs(targetBufferMs)
        if (!engine.start(host, 0)) return null
        return engine.getListenPort()
    }

    /** Promote to a foreground service and publish the media session. */
    fun commitPlayback(clientId: String, serverAddress: String) {
        if (_isPlaying.value) return
        this.clientId = clientId
        this.serverAddress = serverAddress
        wifiLock.acquire()
        mediaSession.isActive = true
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        startForeground(NOTIFICATION_ID, buildNotification())
        _isPlaying.value = true
    }

    /** Tear down the engine after a failed handshake (no foreground was started). */
    fun abortPlayback() {
        teardown()
    }

    fun stopPlayback() {
        teardown()
        stopSelf()
    }

    /** Stop audio and drop session state, without stopping the service itself. */
    private fun teardown() {
        val wasPlaying = _isPlaying.value
        engine.stop()
        if (wifiLock.isHeld) wifiLock.release()
        if (wasPlaying) {
            mediaSession.isActive = false
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            _isPlaying.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        clientId = null
        serverAddress = ""
    }

    private fun updatePlaybackState(state: Int) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_PAUSE)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Live")
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
