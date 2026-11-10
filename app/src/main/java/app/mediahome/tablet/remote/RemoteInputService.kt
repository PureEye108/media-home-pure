package app.mediahome.tablet.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import app.mediahome.tablet.R
import app.mediahome.tablet.Ui
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * "Mouse & keyboard" for the web remote: the phone's touchpad moves an on-screen pointer, taps, scrolls and long-presses,
 * and the phone's keyboard types into the text field that has focus on the tablet.
 *
 * Android only allows this through an accessibility service, which the person turns on (script 9 or Settings > Accessibility).
 * It acts only when a PIN-checked command arrives from the web remote. It does not read the screen or listen to events:
 * it asks for the focused text field only when typing.
 */
class RemoteInputService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var cursor: View? = null
    @Volatile private var x = -1
    @Volatile private var y = -1
    private val hideCursor = Runnable { cursor?.visibility = View.GONE }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ref = WeakReference(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ref = null
        main.post { removeCursor() }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ref = null
        removeCursor()
        super.onDestroy()
    }

    private fun screen(): Pair<Int, Int> {
        val b = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    // ---- pointer -------------------------------------------------------------------------------

    fun move(dx: Int, dy: Int): Pair<Int, Int> {
        val (w, h) = screen()
        if (x < 0 || y < 0 || x >= w || y >= h) { x = w / 2; y = h / 2 }
        val (nx, ny) = InputLogic.moveCursor(x, y, dx, dy, w, h)
        x = nx; y = ny
        main.post { showCursorAt(nx, ny) }
        return nx to ny
    }

    fun tap(longPress: Boolean): Boolean {
        if (x < 0) move(0, 0)
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(p, if (longPress) 800L else 60L)
    }

    fun scroll(dx: Int, dy: Int): Boolean {
        if (x < 0) move(0, 0)
        val (w, h) = screen()
        val s = InputLogic.scrollPath(x, y, dx, dy, w, h)
        val p = Path().apply { moveTo(s[0].toFloat(), s[1].toFloat()); lineTo(s[2].toFloat(), s[3].toFloat()) }
        return gesture(p, 250L)
    }

    /** Runs one touch stroke and waits (up to 3 s) for Android to finish it. */
    private fun gesture(path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val done = CountDownLatch(1)
        var ok = false
        val started = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; done.countDown() }
            override fun onCancelled(d: GestureDescription?) { done.countDown() }
        }, main)
        if (!started) return false
        done.await(3, TimeUnit.SECONDS)
        main.post { cursor?.let { main.removeCallbacks(hideCursor); main.postDelayed(hideCursor, CURSOR_IDLE_MS) } }
        return ok
    }

    fun global(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(action)
    }

    // ---- YouTube full screen ---------------------------------------------------------------------

    /**
     * Presses YouTube's own "Full screen" button on the page in front (the same button a finger would press),
     * then "Play" if the video is paused. Waits up to [waitMs] for the page to show the button.
     */
    fun fullscreenVideo(waitMs: Long = 4_000): Boolean {
        val end = SystemClock.uptimeMillis() + waitMs
        while (true) {
            val button = rootInActiveWindow?.let { findNode(it) { l -> InputLogic.isFullscreenLabel(l) } }
            if (button != null) {
                val ok = click(button)
                Thread.sleep(900)
                rootInActiveWindow?.let { findNode(it) { l -> InputLogic.isPlayLabel(l) } }?.let { click(it) }
                return ok
            }
            if (SystemClock.uptimeMillis() > end) return false
            Thread.sleep(500)
        }
    }

    /** Runs [fullscreenVideo] in the background once the newly opened page has had time to start loading. */
    fun fullscreenVideoSoon() {
        Thread({
            try { Thread.sleep(2_500); fullscreenVideo(15_000) } catch (_: InterruptedException) { }
        }, "mediahome-fullscreen").apply { isDaemon = true }.start()
    }

    private fun label(n: AccessibilityNodeInfo): String = (n.contentDescription ?: n.text ?: "").toString()

    /** Breadth-first search of the window's nodes (at most 8000) for the first one whose label matches. */
    private fun findNode(root: AccessibilityNodeInfo, match: (String) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 8000) {
            val n = queue.removeFirst()
            val l = label(n)
            if (l.isNotEmpty() && match(l)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** Clicks a node (or its nearest clickable parent); if the page refuses, taps its middle like a finger. */
    private fun click(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        repeat(3) {
            val c = x ?: return@repeat
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            x = c.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return gesture(Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }, 60L)
    }

    // ---- keyboard ------------------------------------------------------------------------------

    fun keyboard(action: String, text: String): InputResult {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable) return InputResult.NO_FIELD
        if (action == "enter") {
            return if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) InputResult.OK else InputResult.FAILED
        }
        val current = if (node.isShowingHintText) null else node.text?.toString()
        val next = InputLogic.edit(current, action, text) ?: return InputResult.FAILED
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return InputResult.FAILED
        // put the text cursor at the end so typing continues naturally
        val sel = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.length)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
        return InputResult.OK
    }

    // ---- on-screen pointer -----------------------------------------------------------------------

    private fun showCursorAt(px: Int, py: Int) {
        val wm = getSystemService(WindowManager::class.java)
        var v = cursor
        val size = Ui.dp(this, 34)
        if (v == null) {
            v = ImageView(this).apply { setImageResource(R.drawable.ic_cursor) }
            val lp = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; x = px; y = py }
            try { wm.addView(v, lp) } catch (e: RuntimeException) { return }
            cursor = v
        } else {
            v.visibility = View.VISIBLE
            val lp = v.layoutParams as WindowManager.LayoutParams
            lp.x = px; lp.y = py
            try { wm.updateViewLayout(v, lp) } catch (e: RuntimeException) { }
        }
        main.removeCallbacks(hideCursor)
        main.postDelayed(hideCursor, CURSOR_IDLE_MS)
    }

    private fun removeCursor() {
        val v = cursor ?: return
        cursor = null
        try { getSystemService(WindowManager::class.java).removeView(v) } catch (e: RuntimeException) { }
    }

    companion object {
        /** The pointer hides after this long without movement. */
        const val CURSOR_IDLE_MS = 8_000L

        @Volatile private var ref: WeakReference<RemoteInputService>? = null

        /** The running service while it is switched on in Accessibility settings, else null. */
        val instance: RemoteInputService? get() = ref?.get()
    }
}
