package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityExtractedListBinding
import java.util.concurrent.Executors

class ExtractedListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExtractedListBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var adapter: ExtractedOccurrenceAdapter
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var itemType: ItemType = ItemType.WORD
    private var languageTag: LanguageTag = LanguageTag.JAPANESE
    private var sortOrder: OccurrenceSortOrder = OccurrenceSortOrder.NEWEST_FIRST

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
    }

    override fun onResume() {
        super.onResume()
        loadOccurrences()
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun loadOccurrences() {
        val query = OccurrenceQuery(
            languageTag = languageTag,
            itemType = itemType,
            sortOrder = sortOrder,
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
