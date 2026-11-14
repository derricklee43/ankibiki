package com.derricklee.ankidict

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ItemNameplateClaimRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Claims queue (roadmap B1): one card per pending nameplate claim. Rows carry their own action
 * buttons (正しい filled / 違う outlined / open-in-new icon) — the card itself is inert, matching
 * the repo's no-hidden-affordances rule.
 */
class ClaimsQueueAdapter(
    private val onRule: (MiningRepository.PendingNameplateClaim, Boolean) -> Unit,
    private val onOpenPhoto: (MiningRepository.PendingNameplateClaim) -> Unit,
) : RecyclerView.Adapter<ClaimsQueueAdapter.ViewHolder>() {

    private var items: List<MiningRepository.PendingNameplateClaim> = emptyList()
    private var inlineRemainders: Map<Long, String> = emptyMap()
    /** While one ruling is in flight every row's action buttons are disabled (single-write guard). */
    var actionsEnabled = true
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newItems: List<MiningRepository.PendingNameplateClaim>, remainders: Map<Long, String>) {
        items = newItems
        inlineRemainders = remainders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNameplateClaimRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemNameplateClaimRowBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: MiningRepository.PendingNameplateClaim) {
            val inline = row.contextSentence.isNotEmpty()
            binding.claimText.text = if (!inline) {
                row.text
            } else {
                buildString {
                    append("「").append(row.contextSentence).append("」から\n")
                    append("「").append(row.text).append("」をカット")
                    inlineRemainders[row.claimId]?.let { append(" → 「").append(it).append("」") }
                }
            }
            binding.claimText.typeface = if (inline) Typeface.DEFAULT else Typeface.MONOSPACE
            binding.claimText.textSize = if (inline) 12f else 14f
            TextLocales.apply(binding.claimText, row.languageTag)
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(row.dateAdded * 1000L))
            val source = binding.root.context.getString(
                if (row.source == "h9") R.string.claims_source_inline else R.string.claims_source_standalone,
            )
            binding.claimSourceText.text = "$source — 写真 $date"

            binding.correctButton.isEnabled = actionsEnabled
            binding.incorrectButton.isEnabled = actionsEnabled
            binding.openPhotoButton.isEnabled = actionsEnabled
            binding.correctButton.setOnClickListener { onRule(row, true) }
            binding.incorrectButton.setOnClickListener { onRule(row, false) }
            binding.openPhotoButton.setOnClickListener { onOpenPhoto(row) }
        }
    }
}
