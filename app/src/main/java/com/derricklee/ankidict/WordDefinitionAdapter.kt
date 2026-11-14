package com.derricklee.ankidict

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ItemWordDefinitionRowBinding

/** Words (definition) tab: one row per canonical item, reading + meaning, sorted by [ItemOrdering]. */
class WordDefinitionAdapter(
    private val onRowClick: (WordDefinitionRow) -> Unit,
) : RecyclerView.Adapter<WordDefinitionAdapter.ViewHolder>() {

    private var items: List<WordDefinitionRow> = emptyList()

    fun submitList(newItems: List<WordDefinitionRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWordDefinitionRowBinding.inflate(
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
        private val binding: ItemWordDefinitionRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: WordDefinitionRow) {
            binding.expressionText.text = row.expression
            binding.kanaText.text = row.kana?.takeIf { it.isNotBlank() } ?: "—"
            binding.meaningText.text = row.meaning.takeIf { it.isNotBlank() } ?: "—"
            binding.frequencyText.text = row.formatFrequencyColumn()
            val tag = row.languageTag.takeIf { it != LanguageTag.NA } ?: LanguageTag.JAPANESE
            TextLocales.apply(binding.expressionText, tag)
            binding.root.setOnClickListener { onRowClick(row) }
        }
    }
}
