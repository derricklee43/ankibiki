package com.derricklee.ankidict

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Allows the floating "引" button to read whatever text is currently highlighted on screen without
 * the user having to manually tap "Copy" first:
 *  1. Passively warms up Chromium WebView accessibility trees (such as AnkiDroid's card Reviewer)
 *     so WebContentsAccessibilityImpl is initialized before the user selects text.
 *  2. Tracks native and WebView text selections (single-node, right-to-left, and multi-node
 *     selections such as Japanese <ruby> furigana spans).
 *  3. Triggers ACTION_COPY on WebView virtual nodes (which invokes Chromium's mWebContents.copy()
 *     for non-editable flashcard text even when ACTION_COPY isn't listed in actionList) and clicks
 *     any visible "Copy" / "Copy text" toolbar button or Google Lens chip.
 */
class AnkiAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastWarmUpMillis = 0L

    @Volatile
    private var touchDownSelection: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectedAt = System.currentTimeMillis()
        mainHandler.post { warmUpAllWindows() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY,
            -> {
                val selected = extractSelectedFromEvent(event)?.takeIf { it.any(::isCjkCharacter) }
                    ?: findDirectSelection()
                if (!selected.isNullOrBlank()) {
                    recordSelection(selected)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> {
                warmUpEventAndWindows(event)
                findDirectSelection()?.let(::recordSelection)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastWarmUpMillis >= 600L) {
                    lastWarmUpMillis = now
                    warmUpEventAndWindows(event)
                }
                findDirectSelection()?.let(::recordSelection)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Called as soon as the user's finger touches the floating bubble (ACTION_DOWN), before an
     * outside-touch event can dismiss Android's floating text selection toolbar or clear a
     * WebView's active selection.
     */
    fun onBubbleTouchDown() {
        val direct = findDirectSelection()
        touchDownSelection = direct
        if (!direct.isNullOrBlank()) {
            recordSelection(direct)
        }
        // Trigger mWebContents.copy() on any active WebView right on ACTION_DOWN while its DOM
        // selection is guaranteed to still be intact.
        for (root in collectRoots()) {
            triggerWebViewCopyInTree(root)
        }
    }

    /**
     * Attempts to grab currently highlighted text directly from the accessibility tree, or
     * triggers a copy (via WebView ACTION_COPY or an on-screen "Copy" toolbar/chip) so the
     * highlighted text lands in the clipboard automatically.
     *
     * Returns a pair: (directSelectedTextOrNull, triggeredCopy).
     */
    fun captureCurrentSelection(): Pair<String?, Boolean> {
        val downText = touchDownSelection
        touchDownSelection = null
        if (!downText.isNullOrBlank() && downText.any(::isCjkCharacter)) {
            recordSelection(downText)
            return downText to false
        }

        val roots = collectRoots()

        // 1. Check if any node (or multi-node <ruby> range) in any interactive window has an
        //    active CJK text selection.
        for (root in roots) {
            findSelectedTextInTree(root)?.takeIf { it.any(::isCjkCharacter) }?.let {
                recordSelection(it)
                return it to false
            }
        }

        // 2. Trigger ACTION_COPY (including on WebView virtual nodes, where Chromium's
        //    WebContentsAccessibilityImpl calls mWebContents.copy() even for non-editable flashcard
        //    text) AND click any visible "Copy" / "Copy text" chip (FloatingToolbar / Google Lens).
        var triggeredCopy = false
        for (root in roots) {
            if (triggerActionCopyInTree(root)) {
                triggeredCopy = true
            }
        }
        for (root in roots) {
            if (clickCopyChipInTree(root)) {
                triggeredCopy = true
                break
            }
        }
        if (triggeredCopy) {
            return null to true
        }

        return null to false
    }

    fun findDirectSelection(): String? {
        for (root in collectRoots()) {
            findSelectedTextInTree(root)?.takeIf { it.any(::isCjkCharacter) }?.let {
                return it
            }
        }
        return null
    }

    private fun collectRoots(): List<AccessibilityNodeInfo> = buildList {
        runCatching { windows }?.getOrNull()?.forEach { window ->
            runCatching { window.root }?.getOrNull()?.let(::add)
        }
        runCatching { rootInActiveWindow }?.getOrNull()?.let(::add)
    }

    /**
     * Chromium's WebView (WebContentsAccessibilityImpl) lazily initializes its native C++ AXTree
     * only after an AccessibilityService queries the WebView node and its first virtual child.
     * Touching the WebView node ahead of time ensures selection events and ACTION_COPY work as
     * soon as the user highlights words on an AnkiDroid flashcard.
     */
    private fun warmUpEventAndWindows(event: AccessibilityEvent) {
        runCatching { event.source }?.getOrNull()?.let { src ->
            if (warmUpWebViews(src, 0)) {
                mainHandler.postDelayed({ warmUpAllWindows() }, 150L)
                return
            }
        }
        warmUpAllWindows()
    }

    private fun warmUpAllWindows() {
        for (root in collectRoots()) {
            if (warmUpWebViews(root, 0)) {
                mainHandler.postDelayed({
                    for (r in collectRoots()) warmUpWebViews(r, 0)
                }, 150L)
            }
        }
    }

    private fun warmUpWebViews(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 25 || node.packageName == packageName) return false
        val className = node.className?.toString().orEmpty()
        if (className.contains("WebView", ignoreCase = true)) {
            val count = node.childCount
            if (count > 0) {
                node.getChild(0)?.childCount
            }
            return count == 0
        }
        var needsRetry = false
        val count = node.childCount
        for (i in 0 until count) {
            if (warmUpWebViews(node.getChild(i), depth + 1)) {
                needsRetry = true
            }
        }
        return needsRetry
    }

    private fun extractSelectedFromEvent(event: AccessibilityEvent): String? {
        val source = runCatching { event.source }.getOrNull()
        if (source != null) {
            extractSingleNodeSelection(source)?.takeIf { it.any(::isCjkCharacter) }?.let { return it }
        }
        val fullText = event.text.joinToString("")
        val from = event.fromIndex
        val to = event.toIndex
        if (fullText.isNotEmpty() && from >= 0 && to >= 0 && from != to) {
            val start = minOf(from, to).coerceAtMost(fullText.length)
            val end = maxOf(from, to).coerceAtMost(fullText.length)
            if (end > start) {
                return fullText.substring(start, end)
            }
        }
        return null
    }

    private fun extractSingleNodeSelection(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: return null
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start >= 0 && end >= 0 && start != end) {
            val min = minOf(start, end).coerceAtMost(text.length)
            val max = maxOf(start, end).coerceAtMost(text.length)
            if (max > min) {
                return text.substring(min, max)
            }
        }
        return null
    }

    private fun findSelectedTextInTree(root: AccessibilityNodeInfo?): String? {
        if (root == null || root.packageName == packageName) return null

        // 1. Check single-node selection (searching children before parent so leaf CJK nodes
        //    take precedence over container titles).
        findSingleNodeSelectionInTree(root, 0)?.let { return it }

        // 2. Check multi-node WebView selection (e.g. Japanese words with <ruby> furigana where
        //    Chromium sets textSelectionStart >= 0 on the start node and textSelectionEnd >= 0
        //    on the end node).
        val multiNode = MultiNodeSelectionCollector()
        multiNode.walk(root, 0)
        return multiNode.result()?.takeIf { it.any(::isCjkCharacter) }
    }

    private fun findSingleNodeSelectionInTree(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 50 || node.packageName == packageName) return null
        val count = node.childCount
        for (i in 0 until count) {
            findSingleNodeSelectionInTree(node.getChild(i), depth + 1)?.let { return it }
        }
        return extractSingleNodeSelection(node)?.takeIf { it.any(::isCjkCharacter) }
    }

    private class MultiNodeSelectionCollector {
        private val builder = StringBuilder()
        private var collecting = false
        private var completed = false
        private var visited = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || completed || depth > 50 || ++visited > 600) return
            val text = node.text?.toString()
            val start = node.textSelectionStart
            val end = node.textSelectionEnd
            val hasHalfEndpoint = (start >= 0 && end < 0) || (end >= 0 && start < 0)

            if (!text.isNullOrEmpty() && hasHalfEndpoint) {
                val idx = (if (start >= 0) start else end).coerceIn(0, text.length)
                if (!collecting) {
                    collecting = true
                    builder.append(text.substring(idx))
                    return
                } else {
                    builder.append(text.substring(0, idx))
                    completed = true
                    return
                }
            }

            val count = node.childCount
            if (collecting && count == 0 && !text.isNullOrEmpty()) {
                builder.append(text)
            }
            for (i in 0 until count) {
                walk(node.getChild(i), depth + 1)
                if (completed) return
            }
        }

        fun result(): String? = if (completed) builder.toString() else null
    }

    private fun triggerActionCopyInTree(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 50 || node.packageName == packageName) return false
        val actions = node.actionList.map { it.id }
        if (AccessibilityNodeInfo.ACTION_COPY in actions && node.performAction(AccessibilityNodeInfo.ACTION_COPY)) {
            return true
        }
        if ( isWebViewNode(node) && triggerWebViewVirtualCopy(node, 0)) {
            return true
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (triggerActionCopyInTree(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    private fun triggerWebViewCopyInTree(node: AccessibilityNodeInfo?, depth: Int = 0): Boolean {
        if (node == null || depth > 30 || node.packageName == packageName) return false
        if (isWebViewNode(node)) {
            return triggerWebViewVirtualCopy(node, 0)
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (triggerWebViewCopyInTree(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    /**
     * Chromium's WebContentsAccessibilityImpl ignores actions on the host WebView View itself
     * (virtualViewId == HOST_VIEW_ID), but handles ACTION_COPY on its virtual child nodes by
     * calling mWebContents.copy().
     */
    private fun triggerWebViewVirtualCopy(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > 6) return false
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            if (child.performAction(AccessibilityNodeInfo.ACTION_COPY)) {
                return true
            }
            if (triggerWebViewVirtualCopy(child, depth + 1)) {
                return true
            }
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_COPY)
    }

    private fun isWebViewNode(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.contains("WebView", ignoreCase = true) == true

    private fun clickCopyChipInTree(root: AccessibilityNodeInfo): Boolean {
        if (root.packageName == packageName) return false
        val copyLabels = buildSet {
            add("copy")
            add("copy text")
            add("コピー")
            add("复制")
            add("複製")
            runCatching { add(getString(android.R.string.copy).trim().lowercase()) }
        }

        val copyViewIds = listOf(
            "android:id/copy",
            "android:id/floating_toolbar_menu_item_text",
            "com.android.internal:id/floating_toolbar_menu_item_text",
        )
        for (id in copyViewIds) {
            val candidates = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
            for (node in candidates) {
                if (node.packageName == packageName) continue
                if (id == "android:id/copy" || matchesCopyLabel(node, copyLabels)) {
                    if (clickNodeOrParent(node)) return true
                }
            }
        }

        for (query in listOf("Copy", getString(android.R.string.copy))) {
            val candidates = runCatching { root.findAccessibilityNodeInfosByText(query) }.getOrNull().orEmpty()
            for (node in candidates) {
                if (node.packageName == packageName) continue
                if (matchesCopyLabel(node, copyLabels) && clickNodeOrParent(node)) {
                    return true
                }
            }
        }

        return findAndClickCopyNode(root, copyLabels, 0)
    }

    private fun matchesCopyLabel(node: AccessibilityNodeInfo, copyLabels: Set<String>): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase()
        if (!text.isNullOrEmpty() && text in copyLabels) return true
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        if (!desc.isNullOrEmpty() && desc in copyLabels) return true
        return false
    }

    private fun findAndClickCopyNode(
        node: AccessibilityNodeInfo?,
        copyLabels: Set<String>,
        depth: Int,
    ): Boolean {
        if (node == null || depth > 35 || node.packageName == packageName) return false
        if (node.viewIdResourceName == "android:id/copy" || matchesCopyLabel(node, copyLabels)) {
            if (clickNodeOrParent(node)) return true
        }
        val count = node.childCount
        for (i in 0 until count) {
            if (findAndClickCopyNode(node.getChild(i), copyLabels, depth + 1)) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    companion object {
        @Volatile
        var instance: AnkiAccessibilityService? = null
            private set

        /** When the service last connected — the session floor for capture freshness (D1, Pixel fix). */
        @Volatile
        var connectedAt: Long = 0L
            private set

        @Volatile
        var lastSelectedText: String? = null
            private set

        @Volatile
        var lastSelectedTimestamp: Long = 0L
            private set

        fun recordSelection(text: String) {
            if (text.any(::isCjkCharacter)) {
                lastSelectedText = text
                lastSelectedTimestamp = System.currentTimeMillis()
            }
        }

        fun getLastSelectionWithTimestamp(): Pair<String?, Long> =
            lastSelectedText to lastSelectedTimestamp

        fun isServiceEnabled(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, AnkiAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabledServices.split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expected }
        }
    }
}
