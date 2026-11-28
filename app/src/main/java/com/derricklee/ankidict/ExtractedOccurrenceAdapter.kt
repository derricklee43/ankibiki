package com.derricklee.ankidict

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ItemExtractedRowBinding

class ExtractedOccurrenceAdapter(
    private val onRowClick: (ExtractedOccurrenceRow) -> Unit,
) : RecyclerView.Adapter<ExtractedOccurrenceAdapter.ViewHolder>() {

    private var items: List<ExtractedOccurrenceRow> = emptyList()

    fun submitList(newItems: List<ExtractedOccurrenceRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExtractedRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemExtractedRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: ExtractedOccurrenceRow) {
            val ctx = binding.root.context
            binding.expressionText.text = row.expression
            TextLocales.apply(binding.expressionText, row.languageTag.takeIf { it != LanguageTag.NA } ?: LanguageTag.JAPANESE)
            val spec = MediaKindUi.badgeFor(row.mediaKind)
            binding.sourceBadgeText.text = spec.labelResId?.let { ctx.getString(it) } ?: spec.fallbackLabel
            binding.sourceBadgeText.setTextColor(
                if (spec.emphasized) {
                    val tv = TypedValue()
                    if (ctx.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) tv.data
                    else Color.parseColor("#1976D2")
                } else {
                    Color.parseColor("#888888")
                },
            )
            binding.deckNameText.text = row.folderName?.takeIf { it.isNotBlank() } ?: "—"
            binding.frequencyText.text = row.formatFrequencyColumn()
            binding.root.setOnClickListener { onRowClick(row) }
        }
    }
}
