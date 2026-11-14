package com.derricklee.ankidict

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
            binding.expressionText.text = row.expression
            TextLocales.apply(binding.expressionText, row.languageTag.takeIf { it != LanguageTag.NA } ?: LanguageTag.JAPANESE)
            binding.deckNameText.text = row.deckName?.takeIf { it.isNotBlank() } ?: "—"
            binding.frequencyText.text = row.formatFrequencyColumn()
            binding.root.setOnClickListener { onRowClick(row) }
        }
    }
}
