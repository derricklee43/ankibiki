package com.derricklee.ankidict

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class DecompositionTreeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = VERTICAL
    }

    fun renderTrees(
        trees: List<DecompositionNode>,
        onCharacterClick: (String) -> Unit,
    ) {
        removeAllViews()
        val density = resources.displayMetrics.density
        for ((index, root) in trees.withIndex()) {
            if (index > 0) {
                val spacer = View(context).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (8 * density).toInt())
                }
                addView(spacer)
            }
            addView(buildNodeView(root, depth = 0, prefix = "", isLast = true, onCharacterClick = onCharacterClick))
        }
    }

    private fun buildNodeView(
        node: DecompositionNode,
        depth: Int,
        prefix: String,
        isLast: Boolean,
        onCharacterClick: (String) -> Unit,
    ): View {
        val density = resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
        }

        val hasChildren = node.children.isNotEmpty()
        // Root node starts expanded so direct components are immediately visible; deeper nodes start collapsed
        var isExpanded = depth == 0

        if (depth > 0) {
            val branchGlyph = if (isLast) "└─ " else "├─ "
            val connectorView = TextView(context).apply {
                text = prefix + branchGlyph
                typeface = Typeface.MONOSPACE
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
            }
            row.addView(connectorView)
        }

        val toggleView = TextView(context).apply {
            text = when {
                !hasChildren -> "  "
                isExpanded -> "▾ "
                else -> "▸ "
            }
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 0, (4 * density).toInt(), 0)
        }
        row.addView(toggleView)

        val charLinkView = TextView(context).apply {
            text = node.character.ifBlank { "•" }
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#1976D2"))
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding((2 * density).toInt(), (2 * density).toInt(), (6 * density).toInt(), (2 * density).toInt())
            if (node.character.isNotBlank()) {
                setOnClickListener {
                    onCharacterClick(node.character)
                }
            }
        }
        row.addView(charLinkView)

        if (!node.meaning.isNullOrBlank()) {
            val meaningView = TextView(context).apply {
                text = "— ${node.meaning}"
                textSize = 14f
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                if (node.character.isNotBlank()) {
                    setOnClickListener {
                        onCharacterClick(node.character)
                    }
                }
            }
            row.addView(meaningView)
        }

        container.addView(row)

        if (hasChildren) {
            val childrenContainer = LinearLayout(context).apply {
                orientation = VERTICAL
                visibility = if (isExpanded) View.VISIBLE else View.GONE
            }
            val childPrefix = if (depth == 0) {
                ""
            } else {
                prefix + if (isLast) "   " else "│  "
            }
            for ((childIdx, child) in node.children.withIndex()) {
                val childIsLast = childIdx == node.children.lastIndex
                childrenContainer.addView(
                    buildNodeView(
                        node = child,
                        depth = depth + 1,
                        prefix = childPrefix,
                        isLast = childIsLast,
                        onCharacterClick = onCharacterClick,
                    ),
                )
            }
            container.addView(childrenContainer)

            toggleView.setOnClickListener {
                isExpanded = !isExpanded
                toggleView.text = if (isExpanded) "▾ " else "▸ "
                childrenContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            }
        }

        return container
    }
}
