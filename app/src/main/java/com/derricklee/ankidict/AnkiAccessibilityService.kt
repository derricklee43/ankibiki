package com.derricklee.ankidict

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Allows the floating "引" button to read whatever text is currently highlighted on screen without
 * the user having to manually tap "Copy" first:
 *  1. Tracks native text selections (TYPE_VIEW_TEXT_SELECTION_CHANGED / node textSelectionStart..End).
 *  2. For custom canvas selectors like Google Lens / Circle to Search (which don't expose a native
 *     TextView selection but show a "Copy" / "Copy text" chip on screen while text is highlighted),
 *     programmatically clicks that "Copy" chip so Google Lens copies the selection automatically.
 */
class AnkiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            extractSelectedFromEvent(event)?.takeIf { it.any(::isCjkCharacter) }?.let {
                lastSelectedText = it
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Attempts to grab currently highlighted text directly from the accessibility tree, or if in
     * an overlay like Google Lens where a "Copy" chip is visible on screen, triggers it so the
     * highlighted text lands in the clipboard automatically.
     *
     * Returns a pair: (directSelectedTextOrNull, triggeredCopyChip).
     */
    fun captureCurrentSelection(): Pair<String?, Boolean> {
        val roots = buildList {
            windows?.forEach { window -> window.root?.let(::add) }
            rootInActiveWindow?.let(::add)
        }

        // 1. Check if any node in any interactive window has an active text selection range.
        for (root in roots) {
            findSelectedTextInTree(root)?.takeIf { it.isNotBlank() }?.let {
                return it to false
            }
        }

        // 2. Check if any node supports ACTION_COPY directly and is focused/selected.
        for (root in roots) {
            if (triggerActionCopyInTree(root)) {
                return null to true
            }
        }

        // 3. Google Lens / Circle to Search fallback: when text is highlighted on an image, Lens
        //    displays a "Copy" or "Copy text" button chip. Clicking it programmatically copies
        //    the current highlight into the system clipboard in one step.
        for (root in roots) {
            if (clickCopyChipInTree(root)) {
                return null to true
            }
        }

        return lastSelectedText to false
    }

    private fun extractSelectedFromEvent(event: AccessibilityEvent): String? {
        val source = event.source
        if (source != null) {
            val text = source.text?.toString()
            val start = source.textSelectionStart
            val end = source.textSelectionEnd
            if (text != null && start >= 0 && end > start && end <= text.length) {
                return text.substring(start, end)
            }
        }
        val fullText = event.text.joinToString("")
        val from = event.fromIndex
        val to = event.toIndex
        if (fullText.isNotEmpty() && from >= 0 && to > from && to <= fullText.length) {
            return fullText.substring(from, to)
        }
        return null
    }

    private fun findSelectedTextInTree(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        val text = node.text?.toString()
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (text != null && start >= 0 && end > start && end <= text.length) {
            return text.substring(start, end)
        }
        for (i in 0 until node.childCount) {
            findSelectedTextInTree(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun triggerActionCopyInTree(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val actions = node.actionList.map { it.id }
        if (AccessibilityNodeInfo.ACTION_COPY in actions && node.performAction(AccessibilityNodeInfo.ACTION_COPY)) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (triggerActionCopyInTree(node.getChild(i))) return true
        }
        return false
    }

    private fun clickCopyChipInTree(root: AccessibilityNodeInfo): Boolean {
        val candidates = root.findAccessibilityNodeInfosByText("Copy")
        for (node in candidates) {
            if (node.packageName == packageName) continue
            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (label.equals("Copy", ignoreCase = true) || label.equals("Copy text", ignoreCase = true)) {
                if (clickNodeOrParent(node)) return true
            }
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 4) {
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

        @Volatile
        var lastSelectedText: String? = null
    }
}
