package app.playerpure.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import app.playerpure.Hub
import app.playerpure.R
import app.playerpure.library.Covers
import app.playerpure.ui.MainActivity

/**
 * Keeps playback alive in the background: media notification, lock-screen and Media Home controls (MediaSession),
 * and audio focus (pauses when another app starts playing).
 */
class PlaybackService : Service(), Player.Listener {
    private lateinit var session: MediaSession
    private lateinit var am: AudioManager
    private var focus: AudioFocusRequest? = null
    private var pausedByFocus = false
    private var art: Bitmap? = null
    private var artFor: Long = -1
    private var foreground = false
    private var volumeProvider: VolumeProvider? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Hub.init(this)
        am = getSystemService(AudioManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.pp_channel_playback), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        session = MediaSession(this, "PlayerPure").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Hub.player.resume()
                override fun onPause() = Hub.player.pause()
                override fun onSkipToNext() = Hub.player.next()
                override fun onSkipToPrevious() = Hub.player.prev()
                override fun onSeekTo(pos: Long) = Hub.player.seekTo(pos)
                override fun onStop() = Hub.player.pause()
            })
            setSessionActivity(PendingIntent.getActivity(this@PlaybackService, 0, Intent(this@PlaybackService, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
        Hub.player.listeners += this
        Hub.listeners += hubListener
        onPlayerChanged()
    }

    private val hubListener: () -> Unit = { updateVolumeControl() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> Hub.player.toggle()
            ACT_NEXT -> Hub.player.next()
            ACT_PREV -> Hub.player.prev()
        }
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        try {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (_: Exception) {}
    }

    private fun pi(action: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, PlaybackService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val p = Hub.player
        val t = p.current()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.pp_ic_note)
            .setContentTitle(t?.title ?: "Player Pure")
            .setContentText(t?.let { "${it.displayArtist} · ${it.album}" } ?: "Nothing playing")
            .setSubText(if (p.bitPerfect) "Bit-perfect" else null)
            .setLargeIcon(art)
            .setOngoing(p.playing)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Previous", pi(ACT_PREV, 1)).build())
            .addAction(Notification.Action.Builder(null, if (p.playing) "Pause" else "Play", pi(ACT_TOGGLE, 2)).build())
            .addAction(Notification.Action.Builder(null, "Next", pi(ACT_NEXT, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onPlayerChanged() {
        val p = Hub.player
        val t = p.current()
        if (t != null && t.id != artFor) {
            artFor = t.id; art = null
            val id = t.id
            Thread {
                val b = try { Covers.bitmap(this, t.art, t.path, 512) } catch (_: Throwable) { null }
                main.post { if (artFor == id) { art = b; updateSession() } }
            }.start()
        }
        if (p.playing) requestFocus()
        updateSession()
        updateVolumeControl()
    }

    private fun updateSession() {
        val p = Hub.player
        val t = p.current()
        session.setMetadata(MediaMetadata.Builder().apply {
            if (t != null) {
                putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                putString(MediaMetadata.METADATA_KEY_ARTIST, t.displayArtist)
                putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
                putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, t.albumArtist)
                putLong(MediaMetadata.METADATA_KEY_DURATION, t.durationMs)
                art?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            }
        }.build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(if (p.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, p.positionMs(), if (p.playing) 1f else 0f)
            .build())
        if (foreground || p.playing) {
            if (p.playing && !foreground) goForeground()
            else getSystemService(NotificationManager::class.java).notify(1, notification())
        }
        if (!p.playing && foreground && p.queue().isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; stopSelf() }
    }

    /** With the USB DAC, the volume keys and Media Home's volume move Player Pure's volume (DAC or digital). */
    private fun updateVolumeControl() {
        val kind = Hub.volumeKind()
        if (kind == "dac" || kind == "digital") {
            val cur = (Hub.volume * 100).toInt()
            val vp = volumeProvider ?: object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, 100, cur) {
                override fun onSetVolumeTo(volume: Int) { Hub.volume = volume / 100f; currentVolume = volume }
                override fun onAdjustVolume(direction: Int) {
                    val v = (currentVolume + direction * 4).coerceIn(0, 100); Hub.volume = v / 100f; currentVolume = v
                }
            }.also { volumeProvider = it; session.setPlaybackToRemote(it) }
            if (vp.currentVolume != cur) vp.currentVolume = cur
        } else if (volumeProvider != null) {
            volumeProvider = null
            session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        }
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { pausedByFocus = false; Hub.player.pause(); abandonFocus() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (Hub.player.playing) { pausedByFocus = true; Hub.player.pause() }
                    AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocus) { pausedByFocus = false; Hub.player.resume() }
                }
            }.build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
    }

    private fun abandonFocus() { focus?.let { am.abandonAudioFocusRequest(it) }; focus = null }

    override fun onDestroy() {
        Hub.player.listeners -= this
        Hub.listeners -= hubListener
        abandonFocus()
        session.release()
        running = false
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        const val CHANNEL = "playback"
        const val ACT_TOGGLE = "app.playerpure.TOGGLE"
        const val ACT_NEXT = "app.playerpure.NEXT"
        const val ACT_PREV = "app.playerpure.PREV"
    }
}
