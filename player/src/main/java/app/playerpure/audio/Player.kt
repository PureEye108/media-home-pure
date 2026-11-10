package app.playerpure.audio

import android.os.Handler
import android.os.Looper
import android.os.Process
import app.playerpure.library.Track
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A queue entry: the same track can be queued twice, so entries have their own id. */
class QItem(val track: Track) { val uid: Long = nextUid.incrementAndGet() }
private val nextUid = AtomicLong()

/**
 * The playback engine. One audio thread decodes, converts (DSD only) and writes to the output; tracks with the
 * same format follow each other with no gap. The UI talks to it with commands and reads its state.
 */
class Player(private val dopAllowed: () -> Boolean) {

    fun interface Listener { fun onPlayerChanged() }

    val listeners = CopyOnWriteArrayList<Listener>()
    /** Called on the main thread when playback starts (so the foreground service can be started). */
    var onStartedPlaying: (() -> Unit)? = null
    var onStateSaved: ((List<Long>, Int, Long) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // ---- state the UI reads
    private var queue = ArrayList<QItem>()
    private var original: List<Long>? = null          // order before shuffle (uids)
    @Volatile private var heardUid = -1L
    @Volatile var playing = false; private set
    @Volatile var message: String? = null; private set
    @Volatile var outputName = "Tablet audio"; private set
    @Volatile var outputLine = ""; private set
    @Volatile var bitPerfect = false; private set
    @Volatile var usbActive = false; private set
    @Volatile var repeatMode = 0; private set           // 0 off, 1 all, 2 one
    @Volatile var shuffle = false; private set
    @Volatile private var pausedAtMs = 0L

    fun queue(): List<QItem> = synchronized(lock) { ArrayList(queue) }
    fun currentIndex(): Int = synchronized(lock) { queue.indexOfFirst { it.uid == heardUid } }
    fun current(): Track? = synchronized(lock) { queue.firstOrNull { it.uid == heardUid }?.track }

    // ---- commands
    private sealed class Cmd {
        class SetQueue(val tracks: List<Track>, val start: Int, val posMs: Long, val play: Boolean) : Cmd()
        class PlayAt(val index: Int) : Cmd()
        object Pause : Cmd()
        object Resume : Cmd()
        object Next : Cmd()
        object Prev : Cmd()
        class Seek(val ms: Long) : Cmd()
        class Enqueue(val tracks: List<Track>, val next: Boolean) : Cmd()
        class Remove(val index: Int) : Cmd()
        class Move(val from: Int, val to: Int) : Cmd()
        object Clear : Cmd()
        class SetOutput(val out: AudioOut) : Cmd()
        class Repeat(val mode: Int) : Cmd()
        class Shuffle(val on: Boolean) : Cmd()
        object Reconfigure : Cmd()
        object Quit : Cmd()
    }

    private val cmds = LinkedBlockingQueue<Cmd>()
    private fun post(c: Cmd) { cmds.add(c); out.interrupt() }

    fun play(tracks: List<Track>, start: Int = 0, posMs: Long = 0) = post(Cmd.SetQueue(tracks, start, posMs, true))
    fun restore(tracks: List<Track>, index: Int, posMs: Long) = post(Cmd.SetQueue(tracks, index, posMs, false))
    fun playAt(i: Int) = post(Cmd.PlayAt(i))
    fun pause() = post(Cmd.Pause)
    fun resume() = post(Cmd.Resume)
    fun toggle() = if (playing) pause() else resume()
    fun next() = post(Cmd.Next)
    fun prev() = post(Cmd.Prev)
    fun seekTo(ms: Long) = post(Cmd.Seek(ms))
    fun enqueue(tracks: List<Track>, next: Boolean) = post(Cmd.Enqueue(tracks, next))
    fun remove(i: Int) = post(Cmd.Remove(i))
    fun move(from: Int, to: Int) = post(Cmd.Move(from, to))
    fun clear() = post(Cmd.Clear)
    fun setOutput(o: AudioOut) = post(Cmd.SetOutput(o))
    fun setRepeat(mode: Int) = post(Cmd.Repeat(mode))
    fun setShuffle(on: Boolean) = post(Cmd.Shuffle(on))
    /** Output settings changed (DoP, volume mode): set the output up again at the current position. */
    fun reconfigure() = post(Cmd.Reconfigure)
    fun quit() = post(Cmd.Quit)

    // ---- audio thread state
    @Volatile private var out: AudioOut = TrackOut()
    private var dec: Dec? = null
    private var decUid = -1L
    private var decPos = 0L                  // frames read from the file so far (absolute)
    private var decEnd = -1L                 // CUE end frame, -1 = end of file
    private var fps = 1                      // decoder frames per second (DSD: bytes per channel per second)
    private var fmt: OutFormat? = null
    private var d2p = 0L
    private var d2pKey = ""
    private var chunk = 4096
    private var decBuf: ByteBuffer = directBuffer(1)
    private var pcmBuf: ByteBuffer = directBuffer(1)
    private var upBuf: ByteBuffer = directBuffer(1)
    private var written = 0L
    private var feeding = false
    private var draining = false
    private var transition: SrcFormat? = null
    private var pendBuf: ByteBuffer? = null
    private var pendOff = 0
    private var pendFrames = 0
    private var pendFrameBytes = 0
    private var failures = 0

    private class Seg(val start: Long, val uid: Long, val offsetMs: Long, val unitRate: Int)
    private val segs = ArrayList<Seg>()

    /** Position in the track being heard. */
    fun positionMs(): Long {
        if (!playing && segs.isEmpty()) return pausedAtMs
        val p = out.played()
        synchronized(lock) {
            val s = segs.lastOrNull { it.start <= p } ?: segs.firstOrNull() ?: return pausedAtMs
            return s.offsetMs + ((p - s.start).coerceAtLeast(0) * 1000 / s.unitRate.coerceAtLeast(1))
        }
    }

    fun durationMs(): Long = current()?.durationMs ?: 0

    private val thread = Thread({ loop() }, "pp-player")
    init { thread.start() }

    private fun notifyUi() = main.post { for (l in listeners) l.onPlayerChanged() }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        while (true) {
            val c = if (feeding) cmds.poll() else cmds.poll(100, TimeUnit.MILLISECONDS)
            if (c is Cmd.Quit) break
            try {
                if (c != null) { handle(c); continue }
                updateHeard()
                if (transition != null && out.queued() <= 0) finishTransition()
                else if (draining && out.queued() <= 0) {
                    draining = false; playing = false; out.setActive(false)
                    pausedAtMs = 0; saveState(); notifyUi()
                } else if (feeding) feed()
            } catch (e: Exception) {
                message = "Playback error: ${e.message ?: e}"
                stopAll()
                notifyUi()
            }
        }
        closeDec(); if (d2p != 0L) Native.d2pClose(d2p)
        out.close()
    }

    // ---------------------------------------------------------------- commands
    private fun handle(c: Cmd) {
        when (c) {
            is Cmd.SetQueue -> {
                synchronized(lock) { queue = ArrayList(c.tracks.map { QItem(it) }); original = null; shuffle = false }
                if (c.play) { message = null; startAt(c.start, c.posMs) }
                else {
                    stopAll()
                    synchronized(lock) { heardUid = queue.getOrNull(c.start)?.uid ?: -1 }
                    pausedAtMs = c.posMs
                    notifyUi()
                }
            }
            is Cmd.PlayAt -> startAt(c.index, 0)
            Cmd.Pause -> if (playing) {
                pausedAtMs = positionMs()
                playing = false; feeding = false
                out.setPaused(true); out.setActive(false)
                saveState(); notifyUi()
            }
            Cmd.Resume -> {
                if (playing) return
                if (dec != null || draining || transition != null) {
                    playing = true; feeding = dec != null && transition == null && !draining
                    out.setPaused(false); out.setActive(true)
                    main.post { onStartedPlaying?.invoke() }
                    notifyUi()
                } else {
                    val i = currentIndex().let { if (it < 0) 0 else it }
                    startAt(i, pausedAtMs)
                }
            }
            Cmd.Next -> {
                val i = currentIndex()
                val n = queue().size
                val next = when { n == 0 -> -1; i + 1 < n -> i + 1; repeatMode == 1 -> 0; else -> -1 }
                if (next >= 0) startAt(next, 0) else { stopAll(); notifyUi() }
            }
            Cmd.Prev -> {
                val i = currentIndex()
                if (positionMs() > 3000 || i <= 0) startAt(maxOf(i, 0), 0) else startAt(i - 1, 0)
            }
            is Cmd.Seek -> {
                val i = currentIndex()
                if (i >= 0) {
                    if (playing) startAt(i, c.ms) else { stopAll(); synchronized(lock) { heardUid = queue[i].uid }; pausedAtMs = c.ms; notifyUi() }
                }
            }
            is Cmd.Enqueue -> {
                synchronized(lock) {
                    val items = c.tracks.map { QItem(it) }
                    val at = if (c.next) (queue.indexOfFirst { it.uid == heardUid } + 1).coerceIn(0, queue.size) else queue.size
                    queue.addAll(at, items)
                    original = original?.let { o -> o + items.map { it.uid } }
                    if (heardUid < 0 && queue.isNotEmpty()) heardUid = queue[0].uid
                }
                notifyUi()
            }
            is Cmd.Remove -> {
                val uid = synchronized(lock) { queue.getOrNull(c.index)?.uid } ?: return
                if (uid == heardUid) {
                    val wasPlaying = playing
                    synchronized(lock) { queue.removeAt(c.index) }
                    if (wasPlaying && c.index < queue().size) startAt(c.index, 0)
                    else { stopAll(); synchronized(lock) { heardUid = queue.getOrNull(c.index.coerceAtMost(queue.size - 1))?.uid ?: -1 }; pausedAtMs = 0 }
                } else {
                    synchronized(lock) { queue.removeAt(c.index) }
                    if (uid == decUid) restartAfterQueueChange()
                }
                notifyUi()
            }
            is Cmd.Move -> {
                synchronized(lock) {
                    if (c.from !in queue.indices || c.to !in queue.indices) return
                    val it = queue.removeAt(c.from); queue.add(c.to, it)
                }
                if (decUid != heardUid) restartAfterQueueChange()
                notifyUi()
            }
            Cmd.Clear -> { stopAll(); synchronized(lock) { queue.clear(); heardUid = -1; original = null }; pausedAtMs = 0; saveState(); notifyUi() }
            is Cmd.SetOutput -> {
                val wasPlaying = playing
                val pos = positionMs()
                val i = currentIndex()
                stopAll()
                fmt = null
                try { out.close() } catch (_: Exception) {}
                out = c.out
                usbActive = c.out is UsbOut
                outputName = c.out.name
                outputLine = ""; bitPerfect = false
                if (wasPlaying && i >= 0) startAt(i, pos) else { pausedAtMs = pos; notifyUi() }
            }
            Cmd.Reconfigure -> {
                val wasPlaying = playing; val pos = positionMs(); val i = currentIndex()
                stopAll(); fmt = null
                if (wasPlaying && i >= 0) startAt(i, pos) else { pausedAtMs = pos; notifyUi() }
            }
            is Cmd.Repeat -> { repeatMode = c.mode; notifyUi() }
            is Cmd.Shuffle -> { applyShuffle(c.on); notifyUi() }
            Cmd.Quit -> {}
        }
    }

    private fun applyShuffle(on: Boolean) {
        synchronized(lock) {
            if (on == shuffle) return
            val cur = queue.indexOfFirst { it.uid == heardUid }
            if (on) {
                original = queue.map { it.uid }
                val head = queue.subList(0, cur + 1).toList()
                val tail = queue.subList(cur + 1, queue.size).shuffled()
                queue = ArrayList(head + tail)
            } else {
                val order = original
                if (order != null) {
                    val pos = order.withIndex().associate { it.value to it.index }
                    queue.sortBy { pos[it.uid] ?: Int.MAX_VALUE }
                }
                original = null
            }
            shuffle = on
        }
        if (decUid != heardUid) restartAfterQueueChange()
    }

    /** The decoder had already moved on to the next track; the queue changed, so decode the right one next. */
    private fun restartAfterQueueChange() {
        if (!playing) return
        val i = currentIndex()
        if (i >= 0) startAt(i, positionMs())
    }

    // ---------------------------------------------------------------- playback
    private fun stopAll() {
        out.flush()
        written = out.played()
        closeDec()
        pendBuf = null
        synchronized(lock) { segs.clear() }
        playing = false; feeding = false; draining = false; transition = null
        out.setActive(false)
    }

    private fun closeDec() {
        try { dec?.close() } catch (_: Exception) {}
        dec = null; decUid = -1
    }

    private fun startAt(index: Int, posMs: Long) {
        stopAll()
        val item = synchronized(lock) { queue.getOrNull(index) }
        if (item == null) { notifyUi(); return }
        synchronized(lock) { heardUid = item.uid }
        if (!openDec(item, posMs)) {
            notifyUi()
            if (++failures < 5 && index + 1 < queue().size) startAt(index + 1, 0)
            failures = 0
            return
        }
        failures = 0
        val src = SrcFormat(dec!!.rate, dec!!.channels, dec!!.bits, dec!!.dsd)
        if (!configure(src)) { closeDec(); notifyUi(); return }
        synchronized(lock) { segs.add(Seg(written, item.uid, posMs, fmt!!.unitRate)) }
        message = null
        playing = true; feeding = true
        out.setPaused(false); out.setActive(true)
        pausedAtMs = posMs
        main.post { onStartedPlaying?.invoke() }
        saveState(); notifyUi()
    }

    private fun openDec(item: QItem, posMs: Long): Boolean {
        val t = item.track
        val d = try { Decoders.open(File(t.path)) } catch (e: Exception) {
            message = "Can't play ${File(t.path).name}: ${e.message ?: e}"
            return false
        }
        fps = if (d.dsd) d.rate / 8 else d.rate
        val start = if (t.cueStart >= 0) t.cueStart * fps / 75 else 0L
        decEnd = if (t.cueEnd > 0) t.cueEnd * fps / 75 else -1L
        val target = start + posMs * fps / 1000
        if (target > 0 && !d.seek(target)) { d.close(); message = "Can't seek in ${File(t.path).name}"; return false }
        dec = d; decUid = item.uid; decPos = target
        return true
    }

    /** Sets the output up for [src] (unless it already matches) and prepares the buffers. */
    private fun configure(src: SrcFormat): Boolean {
        val f = if (out.sameAs(src) && fmt != null) fmt!! else {
            val nf = out.configure(src)
            if (nf == null) {
                message = "${out.name} can't play ${if (src.dsd) "DSD" + src.rate / 44100 else "${src.bits}-bit / ${UsbOut.fmtRate(src.rate)}"}"
                return false
            }
            written = out.played()
            synchronized(lock) { segs.clear() }
            nf
        }
        fmt = f
        val d = dec!!
        chunk = ((fps / 50) and 3.inv()).coerceAtLeast(1024)   // a multiple of 4: DSD modes send whole groups
        if (decBuf.capacity() < (chunk + 4) * d.frameBytes) decBuf = directBuffer((chunk + 4) * d.frameBytes)
        if (f.mode == Mode.DSD_TO_PCM) {
            val key = "${src.rate}/${src.channels}/${f.pcmRate}"
            if (key != d2pKey) {
                if (d2p != 0L) Native.d2pClose(d2p)
                d2p = Native.d2pOpen(src.rate, src.channels, f.pcmRate); d2pKey = key
                if (d2p == 0L) { message = "Can't convert this DSD rate"; return false }
            }
            val need = (chunk / (src.rate / (f.pcmRate * 8)) + 16) * src.channels * 4
            if (pcmBuf.capacity() < need) pcmBuf = directBuffer(need)
        }
        val srcLabel = app.playerpure.library.Track.formatLabel(d.codec, d.rate, d.bits)
        outputName = out.name
        outputLine = "$srcLabel  →  ${f.label}"
        bitPerfect = f.bitPerfect
        return true
    }

    private fun writeAll(buf: ByteBuffer, frames: Int, frameBytes: Int, from: Int = 0): Boolean {
        var off = from
        while (off < frames) {
            val r = out.write(buf, off * frameBytes, frames - off)
            if (r < 0) {
                out.failed?.let { message = it }
                stopAll(); notifyUi(); return false
            }
            written += r; off += r
            if (off < frames) {   // a command is waiting: keep the rest for later
                pendBuf = buf; pendOff = off; pendFrames = frames; pendFrameBytes = frameBytes
                return false
            }
        }
        pendBuf = null
        return true
    }

    private fun feed() {
        pendBuf?.let { b -> if (!writeAll(b, pendFrames, pendFrameBytes, pendOff)) return }
        val d = dec ?: run { feeding = false; return }
        val f = fmt ?: run { feeding = false; return }
        var max = chunk.toLong()
        if (decEnd >= 0) max = minOf(max, decEnd - decPos)
        var n = if (max > 0) d.read(decBuf, max.toInt()) else 0
        if (n < 0) { message = "Read error in ${current()?.title}"; n = 0 }
        if (n == 0) { trackEnded(); return }
        decPos += n
        when (f.mode) {
            Mode.PCM -> if (f.upmixMono) writeAll(upmix(decBuf, n), n, 8) else writeAll(decBuf, n, d.frameBytes)
            Mode.DOP, Mode.NATIVE_DSD -> {
                val g = f.mode.dsdGroup   // DoP sends pairs of DSD frames, native DSD groups of 4: pad the end with DSD silence
                while (n % g != 0) {
                    for (c in 0 until d.channels) decBuf.put(n * d.channels + c, 0x69.toByte())
                    n++
                }
                if (f.upmixMono && d.channels == 1) {
                    if (upBuf.capacity() < n * 2) upBuf = directBuffer(n * 2)
                    for (i in 0 until n) { val b = decBuf.get(i); upBuf.put(2 * i, b); upBuf.put(2 * i + 1, b) }
                    writeAll(upBuf, n, 2)
                } else writeAll(decBuf, n, d.channels)
            }
            Mode.DSD_TO_PCM -> {
                val m = Native.d2pProcess(d2p, decBuf, n, pcmBuf)
                if (m > 0) { if (f.upmixMono) writeAll(upmix(pcmBuf, m), m, 8) else writeAll(pcmBuf, m, d.channels * 4) }
            }
        }
    }

    private fun upmix(src: ByteBuffer, frames: Int): ByteBuffer {
        if (upBuf.capacity() < frames * 8) upBuf = directBuffer(frames * 8)
        for (i in 0 until frames) { val v = src.getInt(i * 4); upBuf.putInt(i * 8, v); upBuf.putInt(i * 8 + 4, v) }
        return upBuf
    }

    private fun trackEnded() {
        val cur = synchronized(lock) { queue.indexOfFirst { it.uid == decUid } }
        closeDec()
        val size = queue().size
        val next = when {
            cur < 0 -> -1
            repeatMode == 2 -> cur
            cur + 1 < size -> cur + 1
            repeatMode == 1 && size > 0 -> 0
            else -> -1
        }
        if (next < 0) { feeding = false; draining = true; return }
        val item = synchronized(lock) { queue[next] }
        if (!openDec(item, 0)) { notifyUi(); feeding = false; draining = true; return }
        val src = SrcFormat(dec!!.rate, dec!!.channels, dec!!.bits, dec!!.dsd)
        if (out.sameAs(src) && fmt != null && configure(src)) {
            synchronized(lock) { segs.add(Seg(written, item.uid, 0, fmt!!.unitRate)) }   // gapless
        } else {
            feeding = false; transition = src   // let the old format play out, then switch
        }
    }

    private fun finishTransition() {
        val src = transition ?: return
        transition = null
        val uid = decUid
        if (!configure(src)) { closeDec(); playing = false; out.setActive(false); notifyUi(); return }
        synchronized(lock) { segs.add(Seg(written, uid, 0, fmt!!.unitRate)) }
        feeding = playing
        out.setPaused(!playing); out.setActive(playing)
    }

    private var lastHeard = -2L
    private var lastSave = 0L
    private fun updateHeard() {
        val p = out.played()
        val uid = synchronized(lock) {
            val s = segs.lastOrNull { it.start <= p } ?: return
            val idx = segs.indexOf(s)
            if (idx > 0) repeat(idx) { segs.removeAt(0) }
            s.uid
        }
        if (uid != heardUid) heardUid = uid
        if (uid != lastHeard) { lastHeard = uid; saveState(); notifyUi() }
        val now = System.currentTimeMillis()
        if (playing && now - lastSave > 15_000) { lastSave = now; saveState() }
    }

    private fun saveState() {
        val cb = onStateSaved ?: return
        val ids = synchronized(lock) { queue.map { it.track.id } }
        val pos = if (playing) positionMs() else pausedAtMs
        main.post { cb(ids, currentIndex(), pos) }
    }

    /** For screen previews only: show a given output state without hardware. */
    fun demo(playing: Boolean, name: String, line: String, bitPerfect: Boolean, usb: Boolean) {
        this.playing = playing; outputName = name; outputLine = line; this.bitPerfect = bitPerfect; usbActive = usb
    }

    /** USB statistics for the DAC test screen. */
    fun usbStats(): LongArray? = (out as? UsbOut)?.stats()
    fun output(): AudioOut = out
}
