package com.jarvis.agent.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.agent.core.AppEntry
import com.jarvis.agent.core.Bounds
import com.jarvis.agent.core.DeviceController
import com.jarvis.agent.core.ScreenNode
import com.jarvis.agent.core.ScreenSnapshot
import com.jarvis.agent.core.ScrollDirection

/**
 * The hands and eyes of the agent.
 *
 * Reading the screen is a flattening walk of the accessibility tree; acting is either a
 * node action (click / set text) or, when the node refuses, a raw gesture at its centre.
 */
class JarvisAccessibilityService : AccessibilityService(), DeviceController {

    companion object {
        private const val TAG = "JarvisA11y"
        private const val MAX_NODES = 400

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    /** Live nodes from the most recent [screen] call, so we can act on what we reported. */
    private val nodeRegistry = HashMap<Int, AccessibilityNodeInfo>()

    @Volatile
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        JarvisRuntime.init(applicationContext)
        JarvisRuntime.log("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) lastPackage = pkg
    }

    override fun onInterrupt() {
        JarvisRuntime.log("Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        nodeRegistry.clear()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- reading the screen

    override fun screen(): ScreenSnapshot {
        nodeRegistry.clear()
        val root = rootInActiveWindow ?: return ScreenSnapshot(lastPackage, emptyList())
        val nodes = ArrayList<ScreenNode>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var nextId = 0

        while (queue.isNotEmpty() && nodes.size < MAX_NODES) {
            val node = queue.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            val viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
            val className = node.className?.toString()
            val editable = node.isEditable || className?.contains("EditText") == true

            val interesting = text != null || desc != null || node.isClickable ||
                    editable || node.isScrollable
            if (interesting && rect.width() > 0 && rect.height() > 0) {
                val id = nextId++
                nodeRegistry[id] = node
                nodes.add(
                    ScreenNode(
                        id = id,
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
                        bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
                    )
                )
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return ScreenSnapshot(root.packageName?.toString() ?: lastPackage, nodes)
    }

    // ---------------------------------------------------------------- acting on the screen

    override fun tap(node: ScreenNode): Boolean {
        val live = nodeRegistry[node.id]
        if (live != null && safeRefresh(live)) {
            var target: AccessibilityNodeInfo? = live
            var hops = 0
            while (target != null && !target.isClickable && hops < 6) {
                target = target.parent
                hops++
            }
            if (target != null && target.isClickable &&
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
        }
        return tapAt(node.bounds.centerX, node.bounds.centerY)
    }

    override fun setText(node: ScreenNode, text: String): Boolean {
        val live = nodeRegistry[node.id] ?: return false
        if (!safeRefresh(live)) return false

        live.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        live.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        if (live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true

        // Some chat inputs refuse ACTION_SET_TEXT; the clipboard route usually works.
        return pasteViaClipboard(live, text)
    }

    private fun pasteViaClipboard(node: AccessibilityNodeInfo, text: String): Boolean = try {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jarvis", text))
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    } catch (e: Exception) {
        Log.w(TAG, "clipboard paste failed", e)
        false
    }

    override fun scroll(direction: ScrollDirection): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val cx = w / 2f
        val cy = h / 2f
        val dx = w * 0.35f
        val dy = h * 0.30f

        // A scroll gesture moves the finger opposite to the content direction.
        val (from, to) = when (direction) {
            ScrollDirection.DOWN -> Pair(cx to cy + dy, cx to cy - dy)
            ScrollDirection.UP -> Pair(cx to cy - dy, cx to cy + dy)
            ScrollDirection.LEFT -> Pair(cx - dx to cy, cx + dx to cy)
            ScrollDirection.RIGHT -> Pair(cx + dx to cy, cx - dx to cy)
        }
        return swipe(from.first, from.second, to.first, to.second, 320L)
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    override fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    override fun pressEnter(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                return focused.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
            }
        }
        // Android 8/9 have no IME-enter action; the plan should tap the send button instead.
        JarvisRuntime.log("press_enter is not available on this Android version")
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
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                AppEntry(label, pkg)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    override fun launchPackage(packageName: String): Boolean = try {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "launch failed for $packageName", e)
        false
    }

    override fun openSystemSettings(): Boolean = try {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- gestures

    private fun tapAt(x: Int, y: Int): Boolean {
        if (x <= 0 && y <= 0) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    private fun safeRefresh(node: AccessibilityNodeInfo): Boolean = try {
        node.refresh()
    } catch (e: Exception) {
        false
    }
}
