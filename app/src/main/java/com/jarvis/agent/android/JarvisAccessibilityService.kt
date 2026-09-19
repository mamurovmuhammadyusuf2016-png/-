package com.jarvis.agent.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.jarvis.agent.core.AppEntry
import com.jarvis.agent.core.Bounds
import com.jarvis.agent.core.DeviceController
import com.jarvis.agent.core.ScreenNode
import com.jarvis.agent.core.ScreenSnapshot
import com.jarvis.agent.core.ScrollDirection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hands and eyes of the agent.
 *
 * Three things here are load-bearing and were each a real bug:
 *  - a gesture that was *dispatched* is not a button that was *pressed*, so taps are
 *    confirmed by waiting for the gesture callback, not by the dispatch return value;
 *  - list rows are recycled views, so a node is re-checked against the text it had when it
 *    was captured before being clicked, otherwise a scrolled list opens the wrong chat;
 *  - the tree is walked depth-first, because the planner only ever sees the first 40
 *    elements and breadth-first fills those with layout containers instead of content.
 */
class JarvisAccessibilityService : AccessibilityService(), DeviceController {

    companion object {
        private const val TAG = "JarvisA11y"
        private const val MAX_NODES = 500
        /** A snapshot older than this is re-read even if nothing reported a change. */
        private const val CACHE_TTL_MS = 400L
        private const val GESTURE_TIMEOUT_MS = 2500L

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    /** A snapshot and the live nodes it was built from, swapped together so they never disagree. */
    private class Frame(
        val snapshot: ScreenSnapshot,
        val nodes: Map<Int, AccessibilityNodeInfo>,
        val generation: Int,
        val takenAt: Long
    )

    @Volatile
    private var frame: Frame? = null
    private val generation = AtomicInteger(0)

    @Volatile
    private var lastPackage: String? = null

    @Volatile
    private var appCache: List<AppEntry>? = null

    private val packageWatcher = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            appCache = null
        }
    }
    private var packageWatcherRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        JarvisRuntime.init(applicationContext)
        registerPackageWatcher()
        JarvisRuntime.log("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) lastPackage = pkg
        // Anything on screen moved: the cached snapshot is no longer trustworthy.
        generation.incrementAndGet()
    }

    override fun onInterrupt() {
        JarvisRuntime.log("Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        frame = null
        if (packageWatcherRegistered) {
            try {
                unregisterReceiver(packageWatcher)
            } catch (e: Exception) {
                // already gone
            }
            packageWatcherRegistered = false
        }
        super.onDestroy()
    }

    private fun registerPackageWatcher() {
        if (packageWatcherRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        try {
            // API 34 refuses an unflagged registration; these are protected system
            // broadcasts, so nothing outside the system may reach it.
            ContextCompat.registerReceiver(
                this,
                packageWatcher,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            packageWatcherRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "package watcher not registered", e)
        }
    }

    /** Anything we did ourselves also changes the screen. */
    private fun invalidate() {
        generation.incrementAndGet()
    }

    // ---------------------------------------------------------------- reading the screen

    override fun screen(): ScreenSnapshot {
        val cached = frame
        val now = SystemClock.uptimeMillis()
        if (cached != null &&
            cached.generation == generation.get() &&
            now - cached.takenAt < CACHE_TTL_MS
        ) {
            return cached.snapshot
        }
        return capture().snapshot
    }

    override fun foregroundPackage(): String? =
        rootInActiveWindow?.packageName?.toString() ?: lastPackage

    private fun capture(): Frame {
        val stamp = generation.get()
        val root = rootInActiveWindow
        if (root == null) {
            val empty = Frame(ScreenSnapshot(lastPackage, emptyList()), emptyMap(), stamp, SystemClock.uptimeMillis())
            frame = empty
            return empty
        }

        val nodes = ArrayList<ScreenNode>()
        val registry = HashMap<Int, AccessibilityNodeInfo>()
        // How many nodes already share this id, so sibling rows (OTP cells, list items)
        // that all carry one viewId still get distinct keys.
        val occurrences = HashMap<String, Int>()
        var nextId = 0
        var truncated = false

        // Depth-first, so ids follow reading order and the first N are a coherent slice of
        // the screen rather than a pile of top-level containers.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (nodes.size >= MAX_NODES) {
                truncated = true
                break
            }
            val node = stack.removeLast()
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val className = node.className?.toString()
            val editable = node.isEditable || className?.contains("EditText") == true
            val secret = node.isPassword
            val text = if (secret) "«скрытый текст»" else node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() }

            val interesting = text != null || desc != null || node.isClickable ||
                editable || node.isScrollable
            if (interesting && rect.width() > 0 && rect.height() > 0) {
                val id = nextId++
                registry[id] = node
                val identity = viewId ?: className ?: "view"
                val occurrence = occurrences[identity] ?: 0
                occurrences[identity] = occurrence + 1
                nodes.add(
                    ScreenNode(
                        id = id,
                        occurrence = occurrence,
                        text = text,
                        contentDescription = desc,
                        viewId = viewId,
                        className = className,
                        packageName = node.packageName?.toString(),
                        clickable = node.isClickable,
                        editable = editable,
                        scrollable = node.isScrollable,
                        focused = node.isFocused,
                        enabled = node.isEnabled,
                        password = secret,
                        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
                    )
                )
            }

            // Push children in reverse so the first child is processed first.
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        val snapshot = ScreenSnapshot(
            packageName = root.packageName?.toString() ?: lastPackage,
            nodes = nodes,
            truncated = truncated
        )
        val built = Frame(snapshot, registry, stamp, SystemClock.uptimeMillis())
        frame = built
        return built
    }

    /** The live node for a snapshot node, but only if it is still the same thing. */
    private fun liveNode(node: ScreenNode): AccessibilityNodeInfo? {
        val live = frame?.nodes?.get(node.id) ?: return null
        if (!safeRefresh(live)) return null
        // A RecyclerView rebinds the same view object to a different row, and refresh()
        // still succeeds — so check it is still showing what we matched on.
        val sameId = (live.viewIdResourceName ?: "") == (node.viewId ?: "")
        if (!sameId) return null
        if (node.password) return live
        val sameText = (live.text?.toString() ?: "") == (node.text ?: "")
        val sameDesc = (live.contentDescription?.toString() ?: "") == (node.contentDescription ?: "")
        return if (sameText || sameDesc) live else null
    }

    // ---------------------------------------------------------------- acting on the screen

    override fun tap(node: ScreenNode): Boolean {
        val live = liveNode(node)
        if (live != null) {
            val target = clickableAncestor(live, node)
            if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                invalidate()
                return true
            }
        }
        val ok = tapAt(node.bounds.centerX, node.bounds.centerY)
        invalidate()
        return ok
    }

    /**
     * Walks up for a clickable ancestor, refusing one that is far bigger than the element
     * itself — otherwise a tap on a chat row can land on the screen-sized container.
     */
    private fun clickableAncestor(
        live: AccessibilityNodeInfo,
        node: ScreenNode
    ): AccessibilityNodeInfo? {
        if (live.isClickable) return live
        val ownArea = (node.bounds.width.toLong() * node.bounds.height).coerceAtLeast(1L)
        var current: AccessibilityNodeInfo? = live.parent
        var hops = 0
        val rect = Rect()
        while (current != null && hops < 5) {
            if (current.isClickable) {
                current.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height()
                return if (area <= ownArea * 6) current else null
            }
            current = current.parent
            hops++
        }
        return null
    }

    override fun setText(node: ScreenNode, text: String): Boolean {
        val live = liveNode(node) ?: frame?.nodes?.get(node.id)?.takeIf { safeRefresh(it) } ?: return false

        live.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        if (live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            invalidate()
            return true
        }

        // Some chat inputs refuse ACTION_SET_TEXT; the clipboard route usually works.
        val ok = pasteViaClipboard(live, text)
        invalidate()
        return ok
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = cm.primaryClip
        val clip = ClipData.newPlainText("jarvis", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        cm.setPrimaryClip(clip)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // Select everything first so the paste replaces instead of splicing into the caret.
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                node.text?.length ?: 0
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // Do not leave what the user dictated sitting in the system clipboard.
        try {
            if (previous != null) cm.setPrimaryClip(previous)
            else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (e: Exception) {
            // best effort
        }
        pasted
    } catch (e: Exception) {
        Log.w(TAG, "clipboard paste failed", e)
        false
    }

    override fun scroll(direction: ScrollDirection): Boolean {
        // Acting on the scrollable container beats a blind swipe: a swipe at the screen
        // centre is "reply" in Telegram and "archive" in a chat list.
        val scrollable = largestScrollable()
        if (scrollable != null) {
            val action = if (direction == ScrollDirection.UP || direction == ScrollDirection.LEFT) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (scrollable.performAction(action)) {
                invalidate()
                return true
            }
        }

        val rect = Rect()
        if (scrollable != null) {
            scrollable.getBoundsInScreen(rect)
        } else {
            val metrics = resources.displayMetrics
            rect.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        if (rect.width() <= 0 || rect.height() <= 0) return false

        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        val dx = rect.width() * 0.32f
        val dy = rect.height() * 0.32f

        // A scroll gesture moves the finger opposite to the content direction.
        val ok = when (direction) {
            ScrollDirection.DOWN -> swipe(cx, cy + dy, cx, cy - dy)
            ScrollDirection.UP -> swipe(cx, cy - dy, cx, cy + dy)
            ScrollDirection.LEFT -> swipe(cx - dx, cy, cx + dx, cy)
            ScrollDirection.RIGHT -> swipe(cx + dx, cy, cx - dx, cy)
        }
        invalidate()
        return ok
    }

    private fun largestScrollable(): AccessibilityNodeInfo? {
        val current = frame ?: capture()
        val best = current.snapshot.nodes
            .filter { it.scrollable && it.enabled }
            .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }
            ?: return null
        val live = current.nodes[best.id] ?: return null
        return if (safeRefresh(live)) live else null
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK).also { invalidate() }

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME).also { invalidate() }

    override fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS).also { invalidate() }

    override fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val ok = focused.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                if (ok) invalidate()
                return ok
            }
        }
        // Android 8-10 have no IME-enter action; the caller must tap the send button.
        JarvisRuntime.log("Enter недоступен на этой версии Android — нужна кнопка на экране")
        return false
    }

    override fun sleep(millis: Long) {
        try {
            Thread.sleep(millis.coerceIn(0L, 15_000L))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---------------------------------------------------------------- apps

    override fun installedApps(): List<AppEntry> {
        appCache?.let { return it }
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                AppEntry(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
        appCache = apps
        JarvisRuntime.log("Вижу приложений: ${apps.size}")
        if (apps.isEmpty()) {
            JarvisRuntime.log(
                "Список приложений пуст — Android не даёт их видеть. Проверьте, что у Jarvis " +
                    "не отозвано разрешение на доступ к другим приложениям."
            )
        }
        return apps
    }

    override fun launchPackage(packageName: String): Boolean = try {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            invalidate()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "launch failed for $packageName", e)
        false
    }

    override fun openSystemSettings(): Boolean = try {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        invalidate()
        true
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- gestures

    private fun tapAt(x: Int, y: Int): Boolean {
        val metrics = resources.displayMetrics
        val px = x.coerceIn(1, (metrics.widthPixels - 1).coerceAtLeast(1)).toFloat()
        val py = y.coerceIn(1, (metrics.heightPixels - 1).coerceAtLeast(1)).toFloat()
        val path = Path().apply { moveTo(px, py) }
        return runGesture(GestureDescription.StrokeDescription(path, 0, 60))
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - 1).coerceAtLeast(1).toFloat()
        val maxY = (metrics.heightPixels - 1).coerceAtLeast(1).toFloat()
        val path = Path().apply {
            moveTo(x1.coerceIn(1f, maxX), y1.coerceIn(1f, maxY))
            lineTo(x2.coerceIn(1f, maxX), y2.coerceIn(1f, maxY))
        }
        return runGesture(GestureDescription.StrokeDescription(path, 0, 300))
    }

    /**
     * Dispatches a gesture and waits for the result.
     *
     * `dispatchGesture` returning true only means the gesture was accepted for delivery —
     * treating that as success is what made the agent announce "сообщение отправлено" for
     * taps that never landed.
     */
    private fun runGesture(stroke: GestureDescription.StrokeDescription): Boolean = try {
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var completed = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(description: GestureDescription?) {
                    completed = false
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) {
            false
        } else {
            latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed
        }
    } catch (e: Exception) {
        Log.w(TAG, "gesture failed", e)
        false
    }

    private fun safeRefresh(node: AccessibilityNodeInfo): Boolean = try {
        node.refresh()
    } catch (e: Exception) {
        false
    }
}
