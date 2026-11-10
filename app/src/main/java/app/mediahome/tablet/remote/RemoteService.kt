package app.mediahome.tablet.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.storage.StorageManager
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import app.mediahome.tablet.Lang
import app.mediahome.tablet.Prefs
import app.mediahome.tablet.R
import app.mediahome.tablet.SettingsActivity
import java.net.Inet4Address
import java.net.NetworkInterface

/** Runs the web remote's HTTP server while the remote is switched on (Media Home > Settings > Web remote). */
class RemoteService : Service() {
    private var server: MiniHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (server?.isRunning != true) {
            val api = RemoteApi(
                MediaBridge(applicationContext),
                { Remote.pin(applicationContext) },
                { Lang.remotePage(applicationContext, assets.open("remote.html").use { it.readBytes() }) },
                files = FileStore { Remote.storageRoots(applicationContext) },
                onClient = { Remote.clients.seen(it, SystemClock.elapsedRealtime()) },
            )
            val s = MiniHttpServer(Remote.PORT, RemoteApi.STREAMING_PATHS) { api.handle(it) }
            try {
                s.start()
                server = s
                Remote.lastError = null
            } catch (e: Exception) {
                Remote.lastError = e.message
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        Remote.clients.reset()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.remote_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_network)
            .setContentTitle(getString(R.string.remote_on_title))
            .setContentText(Remote.address(this))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "web_remote"
        private const val NOTIFICATION_ID = 8080
    }
}

/** Empty listener: Android requires one so Media Home may see which app is playing and control it. */
class MediaListener : NotificationListenerService()

/** Web remote settings, PIN and address. */
object Remote {
    const val PORT = 8080
    @Volatile var lastError: String? = null

    fun enabled(ctx: Context): Boolean = Prefs.of(ctx).getBoolean(Prefs.REMOTE, false)

    /** Phones and PCs using the remote (see [ClientPresence]). */
    val clients = ClientPresence()

    fun clientConnected(): Boolean = clients.connected(SystemClock.elapsedRealtime())

    /** Home screen: show the sign-in QR code in the Browser tile while no remote is connected (on by default). */
    fun qrOnHome(ctx: Context): Boolean = Prefs.of(ctx).getBoolean(Prefs.REMOTE_QR_HOME, true)

    /** The PIN: made up at random (4 digits) the first time it is needed; the user can change it (4 to 8 digits). */
    fun pin(ctx: Context): String {
        val prefs = Prefs.of(ctx)
        prefs.getString(Prefs.REMOTE_PIN, null)?.let { if (validPin(it)) return it }
        val made = newPin(java.security.SecureRandom())
        prefs.edit().putString(Prefs.REMOTE_PIN, made).apply()
        return made
    }

    fun validPin(pin: String): Boolean = PIN_FORMAT.matches(pin)

    /** Saves a new PIN (false if it isn't 4 to 8 digits). Phones signed in with the old PIN have to sign in again. */
    fun setPin(ctx: Context, pin: String): Boolean {
        val p = pin.trim()
        if (!validPin(p)) return false
        Prefs.of(ctx).edit().putString(Prefs.REMOTE_PIN, p).apply()
        return true
    }

    private val PIN_FORMAT = Regex("^[0-9]{4,8}$")
    private val WEAK = setOf("0000", "1111", "2222", "3333", "4444", "5555", "6666", "7777", "8888", "9999", "1234", "4321", "1212", "0123")

    /** A random 4-digit PIN that is not one of the obvious ones. */
    fun newPin(rnd: java.util.Random): String {
        while (true) {
            val p = String.format(java.util.Locale.ROOT, "%04d", rnd.nextInt(10_000))
            if (p !in WEAK) return p
        }
    }

    /** Set only by the screen-render tests (they have no network). */
    @Volatile var ipOverride: String? = null

    /** The tablet's Wi-Fi (or other local) IPv4 address. */
    fun ipAddress(): String? = ipOverride ?: try {
        val candidates = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it } }
            .filter { (_, a) -> a.isSiteLocalAddress }
        (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull())?.second?.hostAddress
    } catch (e: Exception) {
        null
    }

    fun address(ctx: Context): String = ipAddress()?.let { "http://$it:$PORT" } ?: ctx.getString(R.string.remote_no_network)

    /**
     * The link inside the QR code: the remote's address with the PIN after "#". The part after "#" is never
     * sent over the network; the page reads it, signs in, and removes it from the address bar.
     */
    fun link(ctx: Context): String? = ipAddress()?.let { "http://$it:$PORT/#pin=" + Uri.encode(pin(ctx)) }

    /** The tablet's own storage and every mounted USB drive, for the Files tab. */
    fun storageRoots(ctx: Context): List<FileStore.Root> {
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return emptyList()
        return sm.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED }.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            if (v.isPrimary) FileStore.Root("internal", ctx.getString(R.string.files_root_tablet), dir, removable = false)
            else FileStore.Root("usb-" + (v.uuid ?: dir.name), v.getDescription(ctx) ?: ctx.getString(R.string.files_root_usb), dir, removable = v.isRemovable)
        }
    }

    fun syncService(ctx: Context) {
        val i = Intent(ctx, RemoteService::class.java)
        if (enabled(ctx)) ctx.startForegroundService(i) else ctx.stopService(i)
    }
}
