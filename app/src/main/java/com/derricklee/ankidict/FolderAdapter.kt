package com.derricklee.ankidict

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * v11 Folders tab (user 2026-11-15). Row 0 is always "＋ 新しいフォルダ" (create);
 * the rest are folders: name + word count, a skip-inbox badge when set, tap to view the
 * folder's words, long-press to delete (words are only unassigned, never destroyed).
 */
class FolderAdapter(
    private val onCreateFolder: () -> Unit,
    private val onOpenFolder: (WordFolder) -> Unit,
    private val onDeleteFolder: (WordFolder) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var folders: List<WordFolder> = emptyList()

    fun submitList(newFolders: List<WordFolder>) {
        folders = newFolders
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = folders.size + 1

    override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_CREATE else TYPE_FOLDER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (16 * ctx.resources.displayMetrics.density).toInt()
            val padV = (12 * ctx.resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isClickable = true
            val tv = android.util.TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) background = ctx.getDrawable(tv.resourceId)
        }
        val name = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 16f
        }
        val trailing = TextView(ctx).apply {
            textSize = 13f
        }
        row.addView(name)
        row.addView(trailing)
        return RowHolder(row, name, trailing)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val h = holder as RowHolder
        val ctx = h.itemView.context
        if (position == 0) {
            h.name.text = ctx.getString(R.string.folder_create_button)
            h.name.setTypeface(null, Typeface.BOLD)
            h.name.setTextColor(0xFF1565C0.toInt())
            h.trailing.text = ""
            h.itemView.setOnLongClickListener(null)
            h.itemView.setOnClickListener { onCreateFolder() }
            return
        }
        val folder = folders[position - 1]
        h.name.text = folder.name
        h.name.setTypeface(null, Typeface.NORMAL)
        val trailing = StringBuilder()
        if (folder.skipInbox) trailing.append(ctx.getString(R.string.folder_skip_badge)).append(" · ")
        trailing.append(ctx.getString(R.string.folder_word_count, folder.wordCount))
        h.trailing.text = trailing.toString()
        h.itemView.setOnClickListener { onOpenFolder(folder) }
        h.itemView.setOnLongClickListener {
            onDeleteFolder(folder)
            true
        }
    }

    private class RowHolder(
        itemView: View,
        val name: TextView,
        val trailing: TextView,
    ) : RecyclerView.ViewHolder(itemView)

    companion object {
        private const val TYPE_CREATE = 0
        private const val TYPE_FOLDER = 1
    }
}
