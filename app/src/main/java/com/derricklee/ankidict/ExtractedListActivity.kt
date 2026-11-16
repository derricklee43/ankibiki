package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityExtractedListBinding
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout

class ExtractedListActivity : AppCompatActivity() {

    /** Occurrences = the review inbox (one row per sentence hit). Words = gojūon-sorted definitions. */
    private enum class ListTab { OCCURRENCES, WORDS, FOLDERS }

    private lateinit var binding: ActivityExtractedListBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var adapter: ExtractedOccurrenceAdapter
    private lateinit var wordAdapter: WordDefinitionAdapter
    private lateinit var folderAdapter: FolderAdapter
    private val ioExecutor = AppIo.io

    private var itemType: ItemType = ItemType.WORD
    private var languageTag: LanguageTag = LanguageTag.JAPANESE
    private var currentTab: ListTab = ListTab.OCCURRENCES

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
        wordAdapter = WordDefinitionAdapter { row ->
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
        // v11 Folders tab (user answer 2026-11-15: third tab, one folder per word).
        folderAdapter = FolderAdapter(
            onCreateFolder = { showCreateFolderDialog() },
            onOpenFolder = { folder -> showFolderWordsDialog(folder) },
            onDeleteFolder = { folder -> confirmDeleteFolder(folder) },
        )

        binding.occurrencesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.occurrencesRecyclerView.adapter = adapter
        binding.backButton.setOnClickListener { finish() }

        setupTabs()
        setupSortAndFilterChips()
    }

    override fun onResume() {
        super.onResume()
        loadActiveTab()
        // undo delivery (inbox/shelf): word actions from the detail screen finish() back here.
        UndoUi.maybeShow(binding.root, miningRepository) { loadActiveTab() }
    }

    private fun setupTabs() {
        binding.listTabs.addTab(
            binding.listTabs.newTab().setText(getString(R.string.tab_occurrences)),
        )
        binding.listTabs.addTab(
            binding.listTabs.newTab().setText(
                when (itemType) {
                    ItemType.WORD -> getString(R.string.col_word)
                    ItemType.GRAMMAR -> getString(R.string.col_grammar)
                },
            ),
        )
        binding.listTabs.addTab(
            binding.listTabs.newTab().setText(getString(R.string.tab_folders)),
        )
        binding.listTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = selectTab(
                when (tab.position) {
                    0 -> ListTab.OCCURRENCES
                    1 -> ListTab.WORDS
                    else -> ListTab.FOLDERS
                },
            )
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun selectTab(tab: ListTab) {
        currentTab = tab
        // Chips (sort overrides + visibility partitions) are inbox triage tools; the Words
        // tab pins gojūon order and ACTIVE+HIDDEN membership by spec (user 2026-11-13).
        binding.sortAndFilterScrollView.visibility = if (tab == ListTab.OCCURRENCES) View.VISIBLE else View.GONE
        binding.colSecondHeader.text = when (tab) {
            ListTab.OCCURRENCES -> getString(R.string.col_deck)
            ListTab.WORDS -> getString(R.string.col_definition)
            ListTab.FOLDERS -> ""
        }
        binding.occurrencesRecyclerView.adapter = when (tab) {
            ListTab.OCCURRENCES -> adapter
            ListTab.WORDS -> wordAdapter
            ListTab.FOLDERS -> folderAdapter
        }
        loadActiveTab()
    }

    private fun loadActiveTab() {
        when (currentTab) {
            ListTab.OCCURRENCES -> loadOccurrences()
            ListTab.WORDS -> loadWords()
            ListTab.FOLDERS -> loadFolders()
        }
    }

    override fun onDestroy() {
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

        // Word search (kanji / kana / romaji / pinyin): filters the already-loaded set in
        // memory — the list is bounded and the matcher is cheap; no SQL re-query per keystroke.
        binding.searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = renderRows()
        })
    }

    /** Last rows fetched from the repository (unfiltered); search renders against these. */
    private var lastRows: List<ExtractedOccurrenceRow> = emptyList()

    /** Same for the Words tab; repository order (gojūon) is preserved — search never re-ranks there. */
    private var lastWordRows: List<WordDefinitionRow> = emptyList()

    private fun renderRows() {
        val query = binding.searchEditText.text?.toString().orEmpty()
        when (currentTab) {
            ListTab.FOLDERS -> { /* folder rows render from their own load path */ }
            ListTab.OCCURRENCES -> {
                val rows = if (query.isBlank()) {
                    lastRows
                } else {
                    lastRows.mapNotNull { row ->
                        ItemSearch.searchTier(row.expression, row.kana, query)?.let { tier -> row to tier }
                    }.sortedBy { it.second } // exact matches first, typo-tolerant romaji below
                        .map { it.first }
                }
                adapter.submitList(rows)
                binding.emptyOccurrencesText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
            ListTab.WORDS -> {
                val rows = if (query.isBlank()) {
                    lastWordRows
                } else {
                    lastWordRows.filter { ItemSearch.matches(it.expression, it.kana, query) }
                }
                wordAdapter.submitList(rows)
                binding.emptyOccurrencesText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
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
                lastRows = rows
                renderRows() // keeps the active search query applied across refreshes
            }
        }
    }

    private fun loadWords() {
        ioExecutor.execute {
            val rows = miningRepository.getCanonicalItemDefinitions(languageTag, itemType)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastWordRows = rows
                renderRows()
            }
        }
    }

    // ---- v11 Folders tab (user answers 2026-11-15: third tab, create+toggle, clearing inbox) ----

    private fun loadFolders() {
        ioExecutor.execute {
            val folders = miningRepository.listWordFolders()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                folderAdapter.submitList(folders)
            }
        }
    }

    private fun showCreateFolderDialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.folder_name_hint)
            maxLines = 2
        }
        val toggle = android.widget.Switch(this).apply {
            text = getString(R.string.folder_skip_inbox_label)
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(
                input,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                toggle,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.folder_create_button)
            .setView(container)
            .setPositiveButton(R.string.btn_save_text) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                ioExecutor.execute {
                    miningRepository.createWordFolder(name, toggle.isChecked)
                    runOnUiThread { if (!isFinishing && !isDestroyed) loadFolders() }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** Folder contents dialog: tap a word to open its detail (the move entry lives there). */
    private fun showFolderWordsDialog(folder: WordFolder) {
        ioExecutor.execute {
            val rows = miningRepository.listWordRowsInFolder(folder.id, languageTag, itemType)
            val labels = rows.map { r ->
                buildString {
                    append(r.expression)
                    r.kana?.takeIf { it.isNotBlank() }?.let { append("　").append(it) }
                    append(" — ")
                    append(r.meaning.ifBlank { "—" })
                }
            }.toTypedArray()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(folder.name)
                    .setItems(labels) { _, which ->
                        val row = rows[which]
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
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show()
            }
        }
    }

    /** Deletion only unassigns words (doc'd in the confirm message) — occurrence history intact. */
    private fun confirmDeleteFolder(folder: WordFolder) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.folder_delete_title)
            .setPositiveButton(R.string.word_editor_delete) { _, _ ->
                ioExecutor.execute {
                    miningRepository.deleteWordFolder(folder.id)
                    runOnUiThread { if (!isFinishing && !isDestroyed) loadFolders() }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
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
