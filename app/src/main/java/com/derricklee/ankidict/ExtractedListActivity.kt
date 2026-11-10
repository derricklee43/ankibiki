package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityExtractedListBinding
import com.google.android.material.chip.Chip
import java.util.concurrent.Executors

class ExtractedListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExtractedListBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var adapter: ExtractedOccurrenceAdapter
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var itemType: ItemType = ItemType.WORD
    private var languageTag: LanguageTag = LanguageTag.JAPANESE

    // Tri-state togglable sort directions (DESC -> ASC -> OFF)
    // Default: sort by occurrence count descending first, then date descending.
    private var countSortDir: SortDirection = SortDirection.DESC
    private var dateSortDir: SortDirection = SortDirection.DESC
    private var rankSortDir: SortDirection = SortDirection.OFF
    private val sortPriorityOrder = mutableListOf(
        SortField.OCCURRENCE_COUNT,
        SortField.DATE_ADDED,
        SortField.CORPUS_RANK,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExtractedListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        itemType = intent.getStringExtra(EXTRA_ITEM_TYPE)
            ?.let { runCatching { ItemType.valueOf(it) }.getOrNull() }
            ?: ItemType.WORD
        languageTag = intent.getStringExtra(EXTRA_LANGUAGE_TAG)
            ?.let { runCatching { LanguageTag.valueOf(it) }.getOrNull() }
            ?: LanguageTag.JAPANESE

        binding.listTitleText.text = when (itemType) {
            ItemType.WORD -> getString(R.string.extracted_words_title)
            ItemType.GRAMMAR -> getString(R.string.extracted_grammar_title)
        }
        binding.colFirstHeader.text = when (itemType) {
            ItemType.WORD -> getString(R.string.col_word)
            ItemType.GRAMMAR -> getString(R.string.col_grammar)
        }

        miningRepository = MiningRepository(this)
        adapter = ExtractedOccurrenceAdapter { row ->
            startActivity(
                WordGrammarDetailActivity.newIntent(
                    context = this,
                    occurrenceId = row.occurrenceId,
                    languageTag = row.languageTag,
                    itemType = row.itemType,
                    expression = row.expression,
                ),
            )
        }

        binding.occurrencesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.occurrencesRecyclerView.adapter = adapter
        binding.backButton.setOnClickListener { finish() }

        setupSortAndFilterChips()
    }

    override fun onResume() {
        super.onResume()
        loadOccurrences()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupSortAndFilterChips() {
        updateSortChipAppearance()

        binding.sortCountChip.setOnClickListener {
            countSortDir = cycleDirection(countSortDir, defaultFirst = SortDirection.DESC)
            promoteFieldPriority(SortField.OCCURRENCE_COUNT, countSortDir)
            updateSortChipAppearance()
            loadOccurrences()
        }

        binding.sortDateChip.setOnClickListener {
            dateSortDir = cycleDirection(dateSortDir, defaultFirst = SortDirection.DESC)
            promoteFieldPriority(SortField.DATE_ADDED, dateSortDir)
            updateSortChipAppearance()
            loadOccurrences()
        }

        binding.sortRankChip.setOnClickListener {
            rankSortDir = cycleDirection(rankSortDir, defaultFirst = SortDirection.ASC)
            promoteFieldPriority(SortField.CORPUS_RANK, rankSortDir)
            updateSortChipAppearance()
            loadOccurrences()
        }

        binding.filterActiveChip.setOnCheckedChangeListener { _, _ -> loadOccurrences() }
        binding.filterHiddenChip.setOnCheckedChangeListener { _, _ -> loadOccurrences() }
        binding.filterArchiveChip.setOnCheckedChangeListener { _, _ -> loadOccurrences() }
    }

    private fun cycleDirection(current: SortDirection, defaultFirst: SortDirection): SortDirection {
        val second = if (defaultFirst == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC
        return when (current) {
            SortDirection.OFF -> defaultFirst
            defaultFirst -> second
            else -> SortDirection.OFF
        }
    }

    private fun promoteFieldPriority(field: SortField, dir: SortDirection) {
        if (dir != SortDirection.OFF) {
            sortPriorityOrder.remove(field)
            sortPriorityOrder.add(0, field)
        }
    }

    private fun updateSortChipAppearance() {
        styleSortChip(binding.sortCountChip, getString(R.string.sort_count), countSortDir)
        styleSortChip(binding.sortDateChip, getString(R.string.sort_date), dateSortDir)
        styleSortChip(binding.sortRankChip, getString(R.string.sort_rank), rankSortDir)
    }

    private fun styleSortChip(chip: Chip, baseLabel: String, dir: SortDirection) {
        chip.text = when (dir) {
            SortDirection.DESC -> "$baseLabel ↓"
            SortDirection.ASC -> "$baseLabel ↑"
            SortDirection.OFF -> baseLabel
        }
        chip.alpha = if (dir == SortDirection.OFF) 0.55f else 1.0f
    }

    private fun buildCurrentVisibilities(): Set<ItemVisibility> {
        val vis = mutableSetOf<ItemVisibility>()
        if (binding.filterActiveChip.isChecked) vis.add(ItemVisibility.ACTIVE)
        if (binding.filterHiddenChip.isChecked) vis.add(ItemVisibility.HIDDEN)
        if (binding.filterArchiveChip.isChecked) vis.add(ItemVisibility.ARCHIVED)
        if (vis.isEmpty()) {
            vis.add(ItemVisibility.ACTIVE)
        }
        return vis
    }

    private fun buildCurrentSortCriteria(): List<Pair<SortField, SortDirection>> {
        return sortPriorityOrder.map { field ->
            val dir = when (field) {
                SortField.OCCURRENCE_COUNT -> countSortDir
                SortField.DATE_ADDED -> dateSortDir
                SortField.CORPUS_RANK -> rankSortDir
            }
            field to dir
        }
    }

    private fun loadOccurrences() {
        val query = OccurrenceQuery(
            languageTag = languageTag,
            itemType = itemType,
            sortOrder = OccurrenceSortOrder.OCCURRENCE_COUNT_DESC,
            sortCriteria = buildCurrentSortCriteria(),
            visibilities = buildCurrentVisibilities(),
            limit = null,
        )
        ioExecutor.execute {
            val rows = miningRepository.getActiveOccurrences(query)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submitList(rows)
                binding.emptyOccurrencesText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    companion object {
        private const val EXTRA_ITEM_TYPE = "extra_item_type"
        private const val EXTRA_LANGUAGE_TAG = "extra_language_tag"

        fun newIntent(
            context: Context,
            itemType: ItemType,
            languageTag: LanguageTag = LanguageTag.JAPANESE,
        ): Intent = Intent(context, ExtractedListActivity::class.java).apply {
            putExtra(EXTRA_ITEM_TYPE, itemType.name)
            putExtra(EXTRA_LANGUAGE_TAG, languageTag.name)
        }
    }
}
