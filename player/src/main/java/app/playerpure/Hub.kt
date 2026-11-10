package app.playerpure

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.playerpure.audio.DsdOut
import app.playerpure.audio.Mode
import app.playerpure.audio.Native
import app.playerpure.audio.Player
import app.playerpure.audio.TrackOut
import app.playerpure.audio.UsbLink
import app.playerpure.audio.UsbOut
import app.playerpure.library.Library
import java.util.concurrent.CopyOnWriteArrayList

/** App-wide state: the player, the library, settings and the USB DAC connection. */
@android.annotation.SuppressLint("StaticFieldLeak")   // holds the application context only
object Hub {
    const val ACTION_USB_PERMISSION = "app.playerpure.USB_PERMISSION"

    lateinit var app: Context; private set
    lateinit var prefs: SharedPreferences; private set
    lateinit var player: Player; private set
    lateinit var library: Library; private set
    private val main = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArrayList<() -> Unit>()   // DAC status, scanning, settings
    private var ready = false

    @Volatile var dacStatus = "No USB DAC connected"; private set
    @Volatile var dacName: String? = null; private set
    @Volatile var link: UsbLink? = null; private set
    @Volatile var waitingPermission = false; private set
    @Volatile var lastDacReport: String? = null; private set

    fun changed() = main.post { for (l in listeners) l() }

    @Synchronized fun init(ctx: Context) {
        if (ready) return
        ready = true
        app = ctx.applicationContext
        prefs = app.getSharedPreferences("player_pure", Context.MODE_PRIVATE)
        Native.load()
        library = Library.get(app)
        library.mountedRoots = Library.roots(app).map { it.path }.toSet()
        player = Player { dsdOut == DsdOut.DOP }
        player.onStartedPlaying = { PlaybackServiceStarter.start(app) }
        player.onStateSaved = { ids, index, pos ->
            prefs.edit().putString(KEY_QUEUE, ids.joinToString(",")).putInt(KEY_INDEX, index).putLong(KEY_POS, pos).apply()
        }
        // put back the last queue (paused)
        Thread {
            val ids = prefs.getString(KEY_QUEUE, "")!!.split(',').mapNotNull { it.toLongOrNull() }
            if (ids.isNotEmpty()) {
                val tracks = try { library.byIds(ids) } catch (_: Exception) { emptyList() }
                if (tracks.isNotEmpty()) player.restore(tracks, prefs.getInt(KEY_INDEX, 0).coerceIn(0, tracks.size - 1), prefs.getLong(KEY_POS, 0))
            }
        }.start()
        // media mount events need the "file" scheme, USB events must not have it: two receivers
        val usb = IntentFilter().apply { addAction(ACTION_USB_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        val media = IntentFilter().apply { addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED); addAction(Intent.ACTION_MEDIA_EJECT); addDataScheme("file") }
        val flags = if (Build.VERSION.SDK_INT >= 33) Context.RECEIVER_NOT_EXPORTED else 0   // system broadcasts still arrive
        app.registerReceiver(usbReceiver, usb, flags)
        app.registerReceiver(mediaReceiver, media, flags)
        connectDac(ask = false)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val d: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (i.action) {
                ACTION_USB_PERMISSION -> { waitingPermission = false; connectDac(ask = false) }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (d != null && UsbLink.isAudio(d)) connectDac(ask = false)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (d != null && link?.device?.deviceName == d.deviceName) dacGone()
            }
        }
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            library.mountedRoots = Library.roots(app).map { it.path }.toSet()
            changed()
        }
    }

    /** Finds a USB DAC and, if allowed, makes it the output. [ask] shows Android's permission prompt if needed. */
    fun connectDac(ask: Boolean) {
        if (link != null) return
        if (!prefs.getBoolean(KEY_USE_USB, true)) { dacStatus = "USB DAC output is switched off"; changed(); return }
        val um = app.getSystemService(UsbManager::class.java)
        val d = UsbLink.findDac(app)
        if (d == null || um == null) { dacName = null; dacStatus = "No USB DAC connected"; changed(); return }
        dacName = listOfNotNull(d.manufacturerName, d.productName).joinToString(" ").ifBlank { "USB DAC" }
        if (!um.hasPermission(d)) {
            dacStatus = "$dacName connected: allow Player Pure to use it"
            if (ask && !waitingPermission) {
                waitingPermission = true
                val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_USB_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE)
                um.requestPermission(d, pi)
            }
            changed(); return
        }
        Thread {
            try {
                val l = UsbLink.open(app, d)
                link = l
                lastDacReport = l.report()
                dacStatus = "${l.name}: Player Pure's own USB driver (bit-perfect)"
                val out = UsbOut(l, { dsdOut }, { dsdSwap }) { msg -> main.post { dacFailed(msg) } }
                // Never push a stored volume into the DAC on connect (it could be far louder than what the DAC was set to):
                // read the DAC's own level and move the slider to it instead.
                l.readVolumeDb()?.let { db -> l.volumeRangeDb?.let { r -> prefs.edit().putFloat(KEY_VOLUME, dbToLevel(db, r.first, r.second)).apply() }; dacVolumeDb = db }
                dacMuted = l.readMute()
                applyVolume(out, write = false)
                player.setOutput(out)
            } catch (e: Exception) {
                dacStatus = "$dacName: ${e.message}"
                lastDacReport = "Could not open $dacName: ${e.message}"
            }
            changed()
        }.start()
    }

    private fun dacFailed(msg: String) {
        link = null
        dacStatus = msg
        player.pause()
        player.setOutput(TrackOut())
        changed()
    }

    private fun dacGone() {
        if (link == null) return
        link = null
        dacVolumeDb = null; dacMuted = null
        dacStatus = "The DAC was disconnected"
        player.pause()
        player.setOutput(TrackOut())
        changed()
    }

    /** Hands the DAC back to Android (other apps can use it again). */
    fun releaseDac() {
        if (link == null) return
        link = null
        dacVolumeDb = null; dacMuted = null
        player.setOutput(TrackOut())
        dacStatus = "$dacName: released to Android"
        changed()
    }

    // ------------------------------------------------------------ DAC volume and mute (read back from the DAC)
    @Volatile var dacVolumeDb: Double? = null; private set
    @Volatile var dacMuted: Boolean? = null; private set
    @Volatile private var lastRead = 0L

    /** Reads the DAC's volume and mute again (in the background, at most every 1.5 s). */
    fun refreshDac(force: Boolean = false) {
        val l = link ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRead < 1500) return
        lastRead = now
        Thread {
            try {
                val v = l.readVolumeDb(); val m = l.readMute()
                if (v != dacVolumeDb || m != dacMuted) { dacVolumeDb = v; dacMuted = m; changed() }
            } catch (_: Exception) {}
        }.start()
    }

    fun hasDacMute(): Boolean = (player.output() as? UsbOut)?.link?.hasMute == true

    fun setDacMute(on: Boolean) {
        val l = (player.output() as? UsbOut)?.link ?: return
        Thread { try { l.setMute(on); dacMuted = l.readMute() ?: on } catch (_: Exception) {}; changed() }.start()
    }

    /** What the DAC's own display would show. Used by the DAC page and the web remote. */
    data class DacDisplay(
        val connected: Boolean, val name: String, val input: String, val rate: String, val unit: String, val format: String,
        val volume: String, val volumeDb: Double?, val muted: Boolean, val hasVolume: Boolean, val hasMute: Boolean,
        val badge: String, val bitPerfect: Boolean, val state: String, val nativeDsd: Boolean,
    )

    /** Screenshots only: a fixed display for a DAC that isn't really connected. */
    @Volatile var demoDac: DacDisplay? = null

    fun dacDisplay(): DacDisplay {
        demoDac?.let { return it }
        val p = player
        val out = p.output() as? UsbOut
        val f = out?.format
        val l = link
        val loc = java.util.Locale.ROOT
        var rate: String; var unit = ""; val format: String
        when {
            l == null -> { rate = "NO DAC"; format = dacStatus }
            f == null -> { rate = "—"; format = "Waiting for music" }
            f.mode == Mode.NATIVE_DSD || f.mode == Mode.DOP -> {
                val dsdRate = f.unitRate * 8
                rate = "DSD" + (dsdRate / 44100); unit = String.format(loc, "%.4f MHz", dsdRate / 1e6)
                format = if (f.mode == Mode.NATIVE_DSD) "Native DSD" else "DSD over PCM (DoP)"
            }
            else -> {
                rate = if (f.rate % 1000 == 0) "${f.rate / 1000}" else String.format(loc, "%.1f", f.rate / 1000.0); unit = "kHz"
                format = "${f.bits}-bit PCM" + if (f.mode == Mode.DSD_TO_PCM) " (converted from DSD)" else ""
            }
        }
        val db = if (l != null) dacVolumeDb else null
        val badge = when { l == null -> ""; f == null -> "IDLE"; p.bitPerfect -> "BIT-PERFECT"; else -> "NOT BIT-PERFECT" }
        val state = when {
            l == null -> ""
            f == null -> "Player Pure holds the DAC; nothing is streaming yet."
            p.playing -> "Playing: ${p.current()?.title ?: ""}"
            else -> "Paused: the DAC receives silence at this rate"
        }
        return DacDisplay(
            connected = l != null, name = l?.name ?: dacName ?: "", input = if (l != null) "USB" else "—",
            rate = rate, unit = unit, format = format,
            volume = when { l == null -> ""; db != null -> String.format(loc, "%.1f dB", db); else -> "on the DAC" },
            volumeDb = db, muted = l != null && dacMuted == true, hasVolume = l?.hasVolume == true, hasMute = l?.hasMute == true,
            badge = badge, bitPerfect = l != null && f != null && p.bitPerfect, state = state,
            nativeDsd = l?.dac?.hasNativeDsd == true,
        )
    }

    /** Moves the DAC's own volume by [stepDb] (for example -1 or +1 dB). False if the DAC has no USB volume. */
    fun nudgeDacVolume(stepDb: Double): Boolean {
        val r = link?.volumeRangeDb ?: return false
        if ((player.output() as? UsbOut)?.link?.hasVolume != true) return false
        val db = (dacVolumeDb ?: r.second) + stepDb
        volume = dbToLevel(db.coerceIn(r.first, r.second), r.first, r.second)
        return true
    }

    fun dbToLevel(db: Double, min: Double, max: Double): Float =
        if (db <= min) 0f else (1.0 - (max - db) / 60.0).coerceIn(0.0, 1.0).toFloat()

    // ------------------------------------------------------------ settings
    const val KEY_DOP = "dop"            // before 0.1.1: true = DoP
    const val KEY_DSD_OUT = "dsd_out"
    const val KEY_DSD_SWAP = "dsd_swap"
    const val KEY_SOFT_VOLUME = "soft_volume"
    const val KEY_VOLUME = "volume"
    const val KEY_USE_USB = "use_usb"
    const val KEY_QUEUE = "queue"
    const val KEY_INDEX = "queue_index"
    const val KEY_POS = "queue_pos"
    const val KEY_SORT = "album_sort"

    /** DSD to the USB DAC: converted to PCM (default, works with every DAC), native DSD, or DoP. */
    var dsdOut: DsdOut
        get() = prefs.getString(KEY_DSD_OUT, null)?.let { n -> DsdOut.entries.firstOrNull { it.name == n } }
            ?: if (prefs.getBoolean(KEY_DOP, false)) DsdOut.DOP else DsdOut.PCM
        set(v) { prefs.edit().putString(KEY_DSD_OUT, v.name).apply(); player.reconfigure(); changed() }

    /** Native DSD byte order: false = oldest byte first (XMOS standard), true = newest first. */
    var dsdSwap: Boolean
        get() = prefs.getBoolean(KEY_DSD_SWAP, false)
        set(v) { prefs.edit().putBoolean(KEY_DSD_SWAP, v).apply(); player.reconfigure(); changed() }

    var softVolume: Boolean
        get() = prefs.getBoolean(KEY_SOFT_VOLUME, false)
        set(v) { prefs.edit().putBoolean(KEY_SOFT_VOLUME, v).apply(); (player.output() as? UsbOut)?.let { applyVolume(it) }; player.reconfigure(); changed() }

    var useUsb: Boolean
        get() = prefs.getBoolean(KEY_USE_USB, true)
        set(v) { prefs.edit().putBoolean(KEY_USE_USB, v).apply(); if (v) connectDac(ask = true) else releaseDac(); changed() }

    /** 0..1. On the DAC's own volume control if it has one, else digital (only if allowed), else not available. */
    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, 1f)
        set(v) {
            prefs.edit().putFloat(KEY_VOLUME, v.coerceIn(0f, 1f)).apply()
            (player.output() as? UsbOut)?.let { applyVolume(it) }
            changed()
        }

    /** What the volume slider controls right now, or null when there is nothing to control. */
    fun volumeKind(): String? {
        val out = player.output() as? UsbOut ?: return "system"
        return when { out.link.hasVolume -> "dac"; softVolume -> "digital"; else -> null }
    }

    private fun applyVolume(out: UsbOut, write: Boolean = true) {
        val v = volume
        if (out.link.hasVolume) {
            out.gainQ16 = 65536
            if (write) Thread { try { out.link.setVolume(v); dacVolumeDb = out.link.readVolumeDb() } catch (_: Exception) {}; changed() }.start()
        }
        else out.gainQ16 = if (softVolume) (v * v * 65536).toInt().coerceIn(0, 65536) else 65536
    }

    // ------------------------------------------------------------ library scanning
    @Volatile var scanning: Library.Progress? = null; private set

    fun scan() {
        if (scanning != null) return
        val p = Library.Progress()
        scanning = p
        changed()
        Thread {
            val roots = Library.roots(app).filter { prefs.getBoolean("scan:" + it.path, true) }
            library.mountedRoots = Library.roots(app).map { it.path }.toSet()
            try { library.scan(roots, p) } catch (_: Exception) {}
            scanning = null
            changed()
        }.apply { priority = Thread.MIN_PRIORITY }.start()
        val tick = object : Runnable { override fun run() { if (scanning != null) { changed(); main.postDelayed(this, 700) } } }
        main.postDelayed(tick, 700)
    }
}

/** Starts the playback service in the foreground (a no-op if Android doesn't allow it right now). */
object PlaybackServiceStarter {
    fun start(ctx: Context) {
        try { ctx.startForegroundService(Intent(ctx, app.playerpure.audio.PlaybackService::class.java)) } catch (_: Exception) {}
    }
}
