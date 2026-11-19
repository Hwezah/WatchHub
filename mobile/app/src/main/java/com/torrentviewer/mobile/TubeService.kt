package com.torrentviewer.mobile

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Tube's one player, kept outside any screen so a video carries on with the app in the background or the screen
 * off (the owner's choice: background play, persistent): it takes audio focus, pauses when headphones are pulled,
 * and holds the network awake. The video page draws it; [TubeService] keeps it alive and shows its notification.
 */
object TubeEngine {
    private var exo: ExoPlayer? = null

    fun player(context: Context): ExoPlayer = exo ?: ExoPlayer.Builder(context.applicationContext).build().also { p ->
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_NETWORK)
        exo = p
    }

    /** The service started (it goes to the foreground by itself once the video plays). */
    fun start(context: Context) {
        runCatching { context.startService(Intent(context, TubeService::class.java)) }
    }

    /** The video page closed: playback stops and the notification goes. */
    fun stop(context: Context) {
        exo?.run { stop(); clearMediaItems() }
        runCatching { context.stopService(Intent(context, TubeService::class.java)) }
    }

    internal fun release() {
        exo?.release()
        exo = null
    }
}

/**
 * Media3's session service for Tube: its media session (lock screen, headset buttons, the notification with play,
 * pause and seek) over [TubeEngine]'s player. Media3 puts it in the foreground while the video plays.
 */
class TubeService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Android may start this with the app closed (a headset's play button): its errors are kept for the crash report too.
        CrashGuard.install(applicationContext)
        val s = MediaSession.Builder(this, TubeEngine.player(this)).setId("tube").build()
        session = s
        // No controller connects from outside: the session is added by hand so its notification shows.
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiped away from recents: playing on would surprise; a paused one just goes.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        TubeEngine.release()
        super.onDestroy()
    }
}
