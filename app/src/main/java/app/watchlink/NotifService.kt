package app.watchlink

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class NotifService : NotificationListenerService() {
    private var lastNp = ""
    private val lastAppTime = mutableMapOf<String, Long>()
    private var mc: MediaController? = null
    private val msm by lazy { getSystemService(MediaSessionManager::class.java) }
    private var npJob: Job? = null
    
    private val appNameCache = mutableMapOf<String, String>()

    private val mcb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = np()
        override fun onPlaybackStateChanged(state: PlaybackState?) = np()
    }
    private val sl = MediaSessionManager.OnActiveSessionsChangedListener { attach(it) }

    override fun onListenerConnected() {
        Watch.init(this)
        try { Watch.ensure() } catch (e: Exception) { }
        try {
            val cn = ComponentName(this, NotifService::class.java)
            msm.addOnActiveSessionsChangedListener(sl, cn)
            attach(msm.getActiveSessions(cn))
        } catch (e: Exception) { }
    }

    override fun onListenerDisconnected() {
        try { msm.removeOnActiveSessionsChangedListener(sl) } catch (e: Exception) { }
        mc?.unregisterCallback(mcb); mc = null
    }

    private fun attach(l: List<MediaController>?) {
        mc?.unregisterCallback(mcb)
        mc = l?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: l?.firstOrNull()
        mc?.registerCallback(mcb)
        np()
    }

    private fun np() {
        if (!Watch.p.getBoolean("np", true)) return
        val c = mc ?: return
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val m = c.metadata ?: return
        val t = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val a = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        
        val s = ("Now Playing: $t" + (if (a != null) ", $a" else "")).take(77)
        if (s == lastNp) return
        lastNp = s
        
        npJob?.cancel()
        
        npJob = CoroutineScope(Dispatchers.Default).launch {
            delay(5000) 
            Watch.msg(4, s, false)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        Watch.init(this)
        if (sbn.packageName !in Watch.p.getStringSet("apps", emptySet())!!) return
        val n = sbn.notification
        if ((n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT)) != 0) return
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        
        val app = appNameCache.getOrPut(sbn.packageName) {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            catch (x: Exception) { sbn.packageName }
        }
        
        val s = listOf(title, text).filter { it.isNotBlank() }.joinToString(": ").take(77)
        val now = System.currentTimeMillis()
        
        val lastTime = lastAppTime[sbn.packageName] ?: 0L
        if (now - lastTime < 3000) return 
        
        lastAppTime[sbn.packageName] = now
        Watch.msg(2, s)
    }
}
