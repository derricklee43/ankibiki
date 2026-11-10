package com.derricklee.ankidict

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ActivityProcessTextBinding
import com.derricklee.ankidict.databinding.ItemProcessTextKanjiBinding
import java.util.concurrent.Executors

private const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

/**
 * Floating popup launched from Android's text-selection toolbar (ACTION_PROCESS_TEXT) in any
 * external app. Extracts each distinct CJK character from the highlighted text and shows its
 * Kanji/Radical meaning, story, and interactive decomposition tree without leaving the current app.
 */
class ProcessTextActivity : AppCompatActivity() {

    private data class KanjiPopupItem(
        val character: String,
        val meaning: String,
        val story: String,
        val decompositionTree: DecompositionNode? = null,
    )

    private lateinit var binding: ActivityProcessTextBinding
    private lateinit var repository: AnkiRepository
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var idsRepository: IdsRepository
    private lateinit var heisigRadicalRepository: HeisigRadicalRepository
    private val lookupExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessTextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { finish() }
        binding.kanjiPopupList.layoutManager = LinearLayoutManager(this)

        repository = AnkiRepository(this)
        dictionaryRepository = DictionaryRepository(this)
        idsRepository = IdsRepository(this)
        heisigRadicalRepository = HeisigRadicalRepository(
            idsRepository = idsRepository,
            dictionaryRepository = dictionaryRepository,
            ankiRepository = repository,
        )

        val selectedText = (
            intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            )?.toString().orEmpty()
        AnkiAccessibilityService.recordSelection(selectedText)
        val characters = selectedText.filter(::isCjkCharacter).map { it.toString() }.distinct()

        if (characters.isEmpty()) {
            showStatus(getString(R.string.process_text_no_kanji))
            return
        }

        loadCharactersIntoPopup(characters)
    }

    private fun loadCharactersIntoPopup(characters: List<String>) {
        val canReadAnki = repository.isAnkiDroidAvailable() && hasReadWritePermission()
        lookupExecutor.execute {
            val items = characters.map { ch ->
                val card = if (canReadAnki) {
                    repository.findNoteByCharacter(ch, NIHONGO_SHARK_KANJI_MODEL_ID)?.toCard()
                } else {
                    null
                }
                val radicalEntry = heisigRadicalRepository.lookupRadical(ch)
                val resolvedMeaning = card?.meaning?.takeIf { it.isNotBlank() }
                    ?: radicalEntry?.heisigKeyword?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.process_text_not_in_deck)
                val resolvedStory = card?.mnemonic?.takeIf { it.isNotBlank() }
                    ?: radicalEntry?.radicalMeaning.orEmpty()
                val tree = heisigRadicalRepository.buildDecompositionTree(ch)
                KanjiPopupItem(
                    character = ch,
                    meaning = resolvedMeaning,
                    story = resolvedStory,
                    decompositionTree = tree,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.kanjiPopupList.adapter = KanjiPopupAdapter(items) { clickedChar ->
                    loadCharactersIntoPopup(listOf(clickedChar))
                }
            }
        }
    }

    override fun onDestroy() {
        lookupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun hasReadWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(this, READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun showStatus(message: String) {
        binding.popupStatusText.text = message
        binding.popupStatusText.visibility = View.VISIBLE
        binding.kanjiPopupList.visibility = View.GONE
    }

    private class KanjiPopupAdapter(
        private val items: List<KanjiPopupItem>,
        private val onCharacterLinkClick: (String) -> Unit,
    ) : RecyclerView.Adapter<KanjiPopupAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProcessTextKanjiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding, onCharacterLinkClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(
            private val binding: ItemProcessTextKanjiBinding,
            private val onCharacterLinkClick: (String) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: KanjiPopupItem) {
                binding.kanjiCharText.text = item.character
                binding.kanjiMeaningText.text = item.meaning
                if (item.story.isBlank()) {
                    binding.kanjiStoryText.visibility = View.GONE
                } else {
                    binding.kanjiStoryText.visibility = View.VISIBLE
                    binding.kanjiStoryText.text = item.story
                }
                val tree = item.decompositionTree
                if (tree != null && tree.children.isNotEmpty()) {
                    binding.kanjiDecompositionTree.visibility = View.VISIBLE
                    binding.kanjiDecompositionTree.renderTrees(listOf(tree), onCharacterLinkClick)
                } else {
                    binding.kanjiDecompositionTree.visibility = View.GONE
                }
            }
        }
    }
}
