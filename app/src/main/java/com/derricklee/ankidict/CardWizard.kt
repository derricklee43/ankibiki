package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ActivityCardPreviewBinding
import com.derricklee.ankidict.databinding.ActivityDeckPickerBinding
import com.derricklee.ankidict.databinding.ActivityFieldMappingBinding
import com.derricklee.ankidict.databinding.ItemCardMappingRowBinding
import com.derricklee.ankidict.databinding.ItemWizardSectionRowBinding
import com.derricklee.ankidict.databinding.ItemWizardNestedRowBinding
import com.derricklee.ankidict.databinding.ItemWizardRowBinding
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

private sealed interface Row {
    data class Deck(val info: AnkiRepository.AnkiDeckInfo) : Row
    data class CurrentModel(val info: AnkiRepository.AnkiModelInfo) : Row
    data class OtherToggle(val expanded: Boolean) : Row
    data class OtherModel(val info: AnkiRepository.AnkiModelInfo) : Row
}

/** Intent keys shared by the three wizard screens (docs/card-creation-wizard-plan.md). */
internal object WizardKeys {
    const val EXTRA_LANGUAGE_TAG = "wizard_language_tag"
    const val EXTRA_ITEM_TYPE = "wizard_item_type"
    const val EXTRA_EXPRESSION = "wizard_expression"
    const val EXTRA_DECK_ID = "wizard_deck_id"
    const val EXTRA_DECK_NAME = "wizard_deck_name"
    const val EXTRA_MODEL_ID = "wizard_model_id"
    const val EXTRA_MODEL_NAME = "wizard_model_name"
    const val EXTRA_DRAFT_JSON = "wizard_draft_json"
    const val EXTRA_ROWS_JSON = "wizard_rows_json"
    const val EXTRA_DUP_COUNT = "wizard_dup_count"
}

/**
 * Screen 0: choose destination deck AND card type explicitly every run (Q1).
 * Deck list from the provider (spike P2: empties included, counts shown);
 * model list with field names (spike P3/P4).
 */
class DeckPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeckPickerBinding
    private lateinit var ankiRepository: AnkiRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var decks: List<AnkiRepository.AnkiDeckInfo> = emptyList()
    private var models: List<AnkiRepository.AnkiModelInfo> = emptyList()
    private var selectedDeck: Long? = null
    private var selectedModel: Long? = null
    private var currentModelId = -1L
    private var otherExpanded = false
    private lateinit var adapter: PickAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeckPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ankiRepository = AnkiRepository(this)

        binding.screenTitle.setText(R.string.wizard_deck_title)
        binding.stepHint.setText(R.string.wizard_deck_hint)
        binding.backButton.setOnClickListener { finish() }
        binding.pickList.layoutManager = LinearLayoutManager(this)
        adapter = PickAdapter()
        binding.pickList.adapter = adapter

        if (!ankiRepository.isAnkiDroidAvailable()) {
            binding.stepHint.setText(R.string.wizard_no_ankidroid)
            return
        }
        ioExecutor.execute {
            val d = runCatching { ankiRepository.listDecks() }.getOrDefault(emptyList())
            val m = runCatching { ankiRepository.listModels() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                decks = d; models = m
                currentModelId = runCatching { ankiRepository.currentModelId() }.getOrDefault(-1L)
                adapter.rebuild()
            }
        }

        binding.continueButton.setOnClickListener {
            val did = selectedDeck ?: return@setOnClickListener
            val mid = selectedModel ?: return@setOnClickListener
            startActivity(
                Intent(this, FieldMappingActivity::class.java)
                    .putExtra(WizardKeys.EXTRA_LANGUAGE_TAG, intent.getStringExtra(WizardKeys.EXTRA_LANGUAGE_TAG))
                    .putExtra(WizardKeys.EXTRA_ITEM_TYPE, intent.getStringExtra(WizardKeys.EXTRA_ITEM_TYPE))
                    .putExtra(WizardKeys.EXTRA_EXPRESSION, intent.getStringExtra(WizardKeys.EXTRA_EXPRESSION))
                    .putExtra(WizardKeys.EXTRA_DECK_ID, did)
                    .putExtra(WizardKeys.EXTRA_DECK_NAME, decks.first { it.deckId == did }.name)
                    .putExtra(WizardKeys.EXTRA_MODEL_ID, mid)
                    .putExtra(WizardKeys.EXTRA_MODEL_NAME, models.first { it.modelId == mid }.name),
            )
        }
    }

    private fun updateContinue() {
        binding.continueButton.isEnabled = selectedDeck != null && selectedModel != null
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Deck rows globally; card-type rows nest (indented, elevated bg) under the selected deck only. */
    private inner class PickAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun rebuild() {
            val out = mutableListOf<Row>()
            decks.forEach { deck ->
                out += Row.Deck(deck)
                if (deck.deckId != selectedDeck) return@forEach
                val current = models.firstOrNull { it.modelId == currentModelId }
                if (current != null) out += Row.CurrentModel(current)
                out += Row.OtherToggle(otherExpanded)
                if (otherExpanded) {
                    models.filter { it.modelId != currentModelId }.forEach { out += Row.OtherModel(it) }
                }
            }
            rows = out
            notifyDataSetChanged()
            updateContinue()
        }

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Deck -> 0
            is Row.CurrentModel, is Row.OtherModel -> 1
            is Row.OtherToggle -> 2
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> DeckHolder(ItemWizardRowBinding.inflate(inflater, parent, false))
                1 -> ModelHolder(ItemWizardNestedRowBinding.inflate(inflater, parent, false))
                else -> SectionHolder(ItemWizardSectionRowBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Deck -> {
                    holder as DeckHolder
                    holder.b.rowTitle.text = row.info.name
                    holder.b.rowSubtitle.text = row.info.counts.joinToString("/")
                    holder.b.rowRadio.isChecked = row.info.deckId == selectedDeck
                    holder.b.root.setOnClickListener {
                        if (selectedDeck != row.info.deckId) {
                            selectedDeck = row.info.deckId
                            // auto-select AnkiDroid's last-used type; user overrides via the
                            // collapsed "Other card types" section
                            selectedModel = models.firstOrNull { it.modelId == currentModelId }?.modelId
                            otherExpanded = false
                        }
                        rebuild()
                    }
                }
                is Row.CurrentModel -> {
                    holder as ModelHolder
                    holder.b.rowTitle.text = row.info.name
                    holder.b.rowSubtitle.setText(R.string.wizard_last_used)
                    holder.b.rowRadio.isChecked = row.info.modelId == selectedModel
                    holder.b.root.setOnClickListener {
                        selectedModel = row.info.modelId
                        notifyDataSetChanged()
                        updateContinue()
                    }
                }
                is Row.OtherModel -> {
                    holder as ModelHolder
                    holder.b.rowTitle.text = row.info.name
                    holder.b.rowSubtitle.text = "${row.info.fieldNames.size} flds"
                    holder.b.rowRadio.isChecked = row.info.modelId == selectedModel
                    holder.b.root.setOnClickListener {
                        selectedModel = row.info.modelId
                        notifyDataSetChanged()
                        updateContinue()
                    }
                }
                is Row.OtherToggle -> {
                    holder as SectionHolder
                    holder.b.sectionTitle.setText(R.string.wizard_other_card_types)
                    holder.b.sectionChevron.rotation = if (row.expanded) 0f else -90f
                    holder.b.root.setOnClickListener {
                        otherExpanded = !otherExpanded
                        rebuild()
                    }
                }
            }
        }

        private inner class DeckHolder(val b: ItemWizardRowBinding) : RecyclerView.ViewHolder(b.root)
        private inner class ModelHolder(val b: ItemWizardNestedRowBinding) : RecyclerView.ViewHolder(b.root)
        private inner class SectionHolder(val b: ItemWizardSectionRowBinding) : RecyclerView.ViewHolder(b.root)
    }
}

/** Screen 1: field-mapping template configuration (bindings only, Q7/affix rules). */
class FieldMappingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFieldMappingBinding
    private lateinit var ankiRepository: AnkiRepository
    private lateinit var miningRepository: MiningRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var deckId = -1L
    private var deckName = ""
    private var modelId = -1L
    private var modelName = ""
    private var fieldNames: List<String> = emptyList()
    private var sentences: List<String> = emptyList()
    private var sentenceIndex = 0
    private var detail: ExtractedItemDetail? = null
    private var dupCount = 0
    private val rowBindingByOrd = mutableMapOf<Int, ItemCardMappingRowBinding>()

    // ephemeral per-visit state: label (null = unbound blank/custom), prefix, suffix
    data class RowState(var label: String? = null, var prefix: String = "", var suffix: String = "")
    private val state = mutableMapOf<Int, RowState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFieldMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ankiRepository = AnkiRepository(this)
        miningRepository = MiningRepository(this)

        deckId = intent.getLongExtra(WizardKeys.EXTRA_DECK_ID, -1L)
        deckName = intent.getStringExtra(WizardKeys.EXTRA_DECK_NAME).orEmpty()
        modelId = intent.getLongExtra(WizardKeys.EXTRA_MODEL_ID, -1L)
        modelName = intent.getStringExtra(WizardKeys.EXTRA_MODEL_NAME).orEmpty()

        binding.screenTitle.setText(R.string.wizard_mapping_title)
        binding.backButton.setOnClickListener { finish() }
        binding.targetContextText.text =
            getString(R.string.wizard_mapping_context, intent.getStringExtra(WizardKeys.EXTRA_EXPRESSION), deckName, modelName)

        ioExecutor.execute {
            val tag = LanguageTag.valueOf(intent.getStringExtra(WizardKeys.EXTRA_LANGUAGE_TAG) ?: LanguageTag.JAPANESE.name)
            val type = ItemType.valueOf(intent.getStringExtra(WizardKeys.EXTRA_ITEM_TYPE) ?: ItemType.WORD.name)
            val expr = intent.getStringExtra(WizardKeys.EXTRA_EXPRESSION).orEmpty()
            val d = miningRepository.getItemDetail(tag, type, expr)
            val fields = runCatching { ankiRepository.fieldNames(modelId) }.getOrDefault(emptyList())
            val saved = miningRepository.loadCardTemplate(deckId, modelId)
            val dups = if (fields.isNotEmpty()) runCatching {
                ankiRepository.findDuplicateNotes(modelId, d?.expression ?: expr).size
            }.getOrDefault(0) else 0
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                detail = d; fieldNames = fields; dupCount = dups
                sentences = d?.matchedSentences?.map { it.sentence }?.distinct()?.take(3) ?: emptyList()
                saved.forEach { s -> state[s.ordinal] = RowState(label = s.fieldLabel) }
                buildSentenceDropdown()
                buildRows()
            }
        }

        binding.saveTemplateButton.setOnClickListener { finishToPreview(save = true) }
        binding.continueButton.setOnClickListener { finishToPreview(save = false) }
    }

    private fun buildSentenceDropdown() {
        if (sentences.isEmpty()) {
            binding.sentenceDropdownButton.visibility = View.GONE
            return
        }
        updateSentenceButtonText()
        binding.sentenceDropdownButton.setOnClickListener { anchor ->
            val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
            sentences.forEachIndexed { i, s ->
                popup.menu.add(0, i, i, "Sentence ${i + 1}: ${s.take(28)}")
            }
            popup.setOnMenuItemClickListener { item ->
                sentenceIndex = item.itemId
                updateSentenceButtonText()
                refreshResolvedValues()
                true
            }
            popup.show()
        }
    }

    private fun updateSentenceButtonText() {
        binding.sentenceDropdownButton.text = "Sentence ${sentenceIndex + 1}: ${sentences.getOrNull(sentenceIndex)?.take(24)}"
    }

    private fun currentDraft(): CardDraft? {
        val d = detail ?: return null
        val sentence = sentences.getOrNull(sentenceIndex) ?: return null
        val photoId = d.matchedSentences.firstOrNull { it.sentence == sentence }?.photoId
        val words = if (photoId != null && photoId >= 0) {
            miningRepository.getSentenceCandidates(photoId, sentence)
                .filter { it.itemType == ItemType.WORD }.map { it.expression }.distinct()
        } else emptyList()
        return CardDraft.of(d, sentence, words)
    }

    private fun buildRows() {
        rowBindingByOrd.clear(); binding.mappingRows.removeAllViews()
        val draft = currentDraft()
        for ((ord, name) in fieldNames.withIndex()) {
            val row = ItemCardMappingRowBinding.inflate(layoutInflater, binding.mappingRows, false)
            row.ankiFieldLabel.text = name
            val st = state.getOrPut(ord) { RowState() }
            applyLabel(row, st, draft, ord)
            row.mappingDropdownButton.setOnClickListener { anchor -> showFieldMenu(anchor, row, st, ord) }
            row.prefixInput.afterTextChanged { st.prefix = it.orEmpty() }
            row.suffixInput.afterTextChanged { st.suffix = it.orEmpty() }
            rowBindingByOrd[ord] = row
            binding.mappingRows.addView(row.root)
        }
    }

    private fun applyLabel(row: ItemCardMappingRowBinding, st: RowState, draft: CardDraft?, ord: Int) {
        row.mappingDropdownButton.text = st.label ?: getString(R.string.wizard_blank_custom)
        if (st.label == null) {
            row.composeRow.visibility = View.GONE
            return
    }
        row.composeRow.visibility = View.VISIBLE
        val value = resolved(st.label, draft)
        row.resolvedValue.text = "{$value}"
    }

    private fun resolved(label: String?, draft: CardDraft?): String {
        val field = ExportField.fromLabel(label ?: return "")
        val values = draft?.let { DraftAdapter.resolve(it) } ?: emptyMap()
        return field?.let { values[it] }.orEmpty()
    }

    private fun refreshResolvedValues() {
        val draft = currentDraft()
        state.forEach { (ord, st) ->
            rowBindingByOrd[ord]?.let { row -> applyLabel(row, st, draft, ord) }
        }
    }

    private fun showFieldMenu(anchor: View, row: ItemCardMappingRowBinding, st: RowState, ord: Int) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        ExportField.labels.forEachIndexed { i, label -> popup.menu.add(0, i, i, label) }
        val blankId = ExportField.labels.size
        popup.menu.add(0, blankId, blankId, getString(R.string.wizard_blank_custom))
        popup.setOnMenuItemClickListener { item ->
            st.label = if (item.itemId == blankId) null else ExportField.labels[item.itemId]
            applyLabel(row, st, currentDraft(), ord)
            true
        }
        popup.show()
    }

    private fun finishToPreview(save: Boolean) {
        val draft = currentDraft() ?: run {
            Toast.makeText(this, R.string.wizard_no_sentence_available, Toast.LENGTH_LONG).show(); return
        }
        val rows = JSONArray()
        for (ord in fieldNames.indices) {
            val st = state[ord] ?: RowState()
            rows.put(
                JSONObject().put("ord", ord).put("field", st.label ?: JSONObject.NULL)
                    .put("prefix", st.prefix).put("suffix", st.suffix),
            )
        }
        if (save) {
            val mappings = fieldNames.indices.map { CardTemplateMapping(it, state[it]?.label) }
            runCatching { miningRepository.saveCardTemplate(deckId, modelId, mappings) }
                .onFailure {
                    Toast.makeText(this, getString(R.string.wizard_template_save_failed, it.message), Toast.LENGTH_LONG).show()
                    return
                }
        }
        startActivity(
            Intent(this, CardPreviewActivity::class.java)
                .putExtra(WizardKeys.EXTRA_LANGUAGE_TAG, intent.getStringExtra(WizardKeys.EXTRA_LANGUAGE_TAG))
                .putExtra(WizardKeys.EXTRA_ITEM_TYPE, intent.getStringExtra(WizardKeys.EXTRA_ITEM_TYPE))
                .putExtra(WizardKeys.EXTRA_EXPRESSION, intent.getStringExtra(WizardKeys.EXTRA_EXPRESSION))
                .putExtra(WizardKeys.EXTRA_DECK_ID, deckId)
                .putExtra(WizardKeys.EXTRA_DECK_NAME, deckName)
                .putExtra(WizardKeys.EXTRA_MODEL_ID, modelId)
                .putExtra(WizardKeys.EXTRA_MODEL_NAME, modelName)
                .putExtra(WizardKeys.EXTRA_DRAFT_JSON, draft.toJson())
                .putExtra(WizardKeys.EXTRA_ROWS_JSON, rows.toString())
                .putExtra(WizardKeys.EXTRA_DUP_COUNT, dupCount),
        )
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        miningRepository.close()
        super.onDestroy()
    }
}

/** Screen 2: composed fields + rendered card preview + the only write path (confirm). */
class CardPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCardPreviewBinding
    private lateinit var ankiRepository: AnkiRepository
    private lateinit var miningRepository: MiningRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var writing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ankiRepository = AnkiRepository(this)
        miningRepository = MiningRepository(this)

        val deckId = intent.getLongExtra(WizardKeys.EXTRA_DECK_ID, -1L)
        val deckName = intent.getStringExtra(WizardKeys.EXTRA_DECK_NAME).orEmpty()
        val modelId = intent.getLongExtra(WizardKeys.EXTRA_MODEL_ID, -1L)
        val modelName = intent.getStringExtra(WizardKeys.EXTRA_MODEL_NAME).orEmpty()
        val draft = CardDraft.fromJson(intent.getStringExtra(WizardKeys.EXTRA_DRAFT_JSON).orEmpty())
        val rows = parseRows(intent.getStringExtra(WizardKeys.EXTRA_ROWS_JSON))
        val dupCount = intent.getIntExtra(WizardKeys.EXTRA_DUP_COUNT, 0)

        binding.screenTitle.setText(R.string.wizard_preview_title)
        binding.backButton.setOnClickListener { finish() }
        binding.cancelButton.setOnClickListener { finish() }

        val warnings = mutableListOf<String>()
        if (dupCount > 0) warnings += getString(R.string.wizard_duplicate_warning, dupCount)
        if (draft?.photoUri == null &&
            (0 until rows.length()).any { rows.optJSONObject(it)?.optString("field") == ExportField.PHOTO.label }
        ) {
            warnings += getString(R.string.wizard_photo_unavailable)
        }
        if (warnings.isNotEmpty()) {
            binding.warningsText.text = warnings.joinToString("\n")
            binding.warningsText.visibility = View.VISIBLE
        }

        val resolved = draft?.let { d -> DraftAdapter.resolve(d) } ?: emptyMap()
        val composed = composeFields(rows, resolved, fieldCount = rows.length())
        val fieldNames = ankiRepository.fieldNames(modelId)
        for (i in 0 until rows.length()) {
            val label = fieldNames.getOrNull(i)
                ?: rows.optJSONObject(i)?.let { "field " + it.getInt("ord") } ?: "field $i"
            addLabeledField(binding.fieldsContainer, label, composed.getOrElse(i) { "" })
        }

        ioExecutor.execute {
            val preview = ankiRepository.previewNewNote(modelId, composed)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (preview.isNullOrEmpty()) {
                    addLabeledField(binding.previewContainer, "", getString(R.string.wizard_preview_unavailable))
                } else {
                    preview.forEach { (cardName, qa) ->
                        addLabeledField(binding.previewContainer, cardName, qa["q"].orEmpty())
                        addLabeledField(binding.previewContainer, "→", qa["a"].orEmpty())
                    }
                }
            }
        }

        binding.confirmCreateButton.setOnClickListener {
            if (writing || draft == null) return@setOnClickListener
            writing = true
            binding.confirmCreateButton.isEnabled = false
            ioExecutor.execute {
                val outcome = runCatching {
                    val photoFields = composePhotoFields(rows, resolved, draft)
                    ankiRepository.createNote(modelId, deckId, photoFields, setOf("ankibiki"))
                        ?.also { noteId ->
                            val readback = ankiRepository.readNoteFields(noteId)
                            require(readback != null) { "readback failed" }
                            val tag = LanguageTag.valueOf(draft.languageTag)
                            val type = ItemType.valueOf(draft.itemType)
                            miningRepository.updateExtractedItemAnkiMatch(
                                tag, type, draft.expression,
                                AnkiCardMatch(AnkiDroidAccountGateway.DEFAULT_ACCOUNT_ID, deckId, deckName, noteId),
                            )
                        } ?: error("AnkiDroid did not create the note")
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    outcome.fold(
                        onSuccess = {
                            Toast.makeText(this, R.string.wizard_created_linked, Toast.LENGTH_LONG).show()
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .putExtra(MainActivity.EXTRA_INITIAL_QUERY, draft.expression)
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                                    ),
                            )
                            finishAffinity()
                        },
                        onFailure = { e ->
                            writing = false
                            binding.confirmCreateButton.isEnabled = true
                            Toast.makeText(this, getString(R.string.wizard_create_failed, e.message), Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
        }
    }

    private fun parseRows(json: String?): JSONArray =
        runCatching { JSONArray(json ?: "[]") }.getOrDefault(JSONArray())

    private fun composeFields(rows: JSONArray, resolved: Map<ExportField, String>, fieldCount: Int): List<String> =
        (0 until fieldCount).map { ord ->
            val o = rows.optJSONObject(ord) ?: return@map ""
            val label = if (o.isNull("field")) null else o.getString("field")
            val field = ExportField.fromLabel(label)
            if (field == null || field == ExportField.PHOTO) ""
            else DraftAdapter.compose(resolved[field].orEmpty(), o.optString("prefix"), o.optString("suffix"))
        }

    /** Photo import happens only on the confirmed write (Q3: unreadable -> blank + warning). */
    private fun composePhotoFields(rows: JSONArray, resolved: Map<ExportField, String>, draft: CardDraft): List<String> =
        (0 until rows.length()).map { i ->
            val o = rows.optJSONObject(i) ?: return@map ""
            val label = if (o.isNull("field")) null else o.getString("field")
            val field = ExportField.fromLabel(label)
            if (field == ExportField.PHOTO) {
                val stored = draft.photoUri?.let {
                    ankiRepository.addMediaFile(it, "ankibiki_${draft.expression.hashCode().toUInt()}.jpg", "image/jpeg")
                }
                if (stored == null) "" else "<img src=\"$stored\">"
            } else {
                composeFields(rows, resolved, rows.length())[i]
            }
        }

    private fun addLabeledField(container: ViewGroup, label: String, htmlValue: String) {
        val tv = TextView(this)
        tv.setTextIsSelectable(true)
        tv.textSize = 14f
        tv.setPadding(0, 6, 0, 6)
        val prefix = if (label.isNotEmpty()) "$label: " else ""
        tv.text = if (htmlValue.contains('<')) Html.fromHtml(prefix + htmlValue, Html.FROM_HTML_MODE_LEGACY)
        else "$prefix$htmlValue"
        container.addView(tv)
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow()
        miningRepository.close()
        super.onDestroy()
    }
}

/** tiny TextView watcher helper (kept local to the wizard). */
internal fun android.widget.EditText.afterTextChanged(fn: (String?) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = fn(s?.toString())
    })
}
