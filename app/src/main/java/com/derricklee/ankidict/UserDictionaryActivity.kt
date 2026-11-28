package com.derricklee.ankidict

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * User dictionary screen (docs/user-dictionary-design.md §3.7, RULED 2026-11-26;
 * retroactive exact-match reparse docs/user-dictionary-reparse-design.md, RULED
 * 2026-11-27 R1a-R7a). List of the user's own word/name entries; 追加 opens the
 * add/edit dialog (expression required; reading optional, D6a; meaning; notes;
 * checkbox to search exact-matching sentences and reparse them on AppIo.pipeline
 * — shown on create & edit, default checked on create, R1a/R2a). Rows tap to
 * edit, carry a 削除 button with confirm + USER_WORD_DELETE undo (D5a).
 */
class UserDictionaryActivity : AppCompatActivity() {

    private lateinit var miningRepository: MiningRepository
    private val ioExecutor = AppIo.io
    private lateinit var listContainer: LinearLayout
    private lateinit var addButton: MaterialButton
    private lateinit var statusView: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        miningRepository = MiningRepository(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            // Android 15+ forces edge-to-edge; without a toolbar this root would
            // draw under the status bar (found on device 2026-11-26 - the add
            // button sat beneath the clock and swallowed taps)
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
                val i = insets.getInsets(bars)
                v.setPadding(24 + i.left, 24 + i.top, 24 + i.right, 24 + i.bottom)
                insets
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.user_dictionary_title)
            textSize = 20f
            setPadding(8, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addButton = MaterialButton(this, null, com.google.android.material.R.style.Widget_Material3_Button_OutlinedButton).apply {
            text = getString(R.string.user_word_add)
            setOnClickListener { showEntryDialog(null) }
        }
        header.addView(addButton)
        root.addView(header)

        statusView = TextView(this).apply {
            text = getString(R.string.user_word_reparsing)
            textSize = 13f
            setPadding(8, 8, 8, 4)
            visibility = View.GONE
        }
        root.addView(statusView)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 16)
        }
        scroll.addView(listContainer)
        root.addView(scroll)
        setContentView(root)
        loadEntries()
    }

    private fun loadEntries() {
        ioExecutor.execute {
            val entries = miningRepository.userWordEntries(LanguageTag.JAPANESE)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                listContainer.removeAllViews()
                if (entries.isEmpty()) {
                    listContainer.addView(TextView(this).apply {
                        text = getString(R.string.user_dictionary_empty)
                        textSize = 14f
                        setPadding(8, 24, 8, 24)
                    })
                }
                for (entry in entries) {
                    listContainer.addView(buildRow(entry))
                }
            }
        }
    }

    private fun buildRow(entry: UserWord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 14, 8, 14)
            isClickable = true
            isFocusable = true
        }
        row.setBackgroundResource(android.R.drawable.list_selector_background)
        // NB: local must NOT be named `text` — inside MaterialButton/TextView
        // .apply{} blocks the val shadows the setter (skill pitfall, compile-caught)
        val rowText = StringBuilder(entry.expression)
        if (!entry.readingKana.isNullOrBlank()) rowText.append("（").append(entry.readingKana).append("）")
        rowText.append("  —  ").append(entry.meaning.ifBlank { "—" })
        row.addView(TextView(this).apply {
            textSize = 15f
            text = rowText.toString()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val deleteButton = MaterialButton(
            this, null, com.google.android.material.R.style.Widget_Material3_Button_TextButton,
        ).apply {
            text = getString(R.string.word_editor_delete)
            setOnClickListener { confirmDelete(entry) }
        }
        row.addView(deleteButton)
        row.setOnClickListener { showEntryDialog(entry) }
        return row
    }

    private fun confirmDelete(entry: UserWord) {
        if (busy) return
        AlertDialog.Builder(this)
            .setTitle(R.string.user_word_delete_title)
            .setMessage(getString(R.string.user_word_delete_message) + "\n\n" + entry.expression)
            .setNegativeButton(R.string.word_editor_cancel, null)
            .setPositiveButton(R.string.word_editor_delete) { _, _ ->
                busy = true
                ioExecutor.execute {
                    val removed = miningRepository.deleteUserWord(LanguageTag.JAPANESE, entry.expression)
                    runOnUiThread {
                        busy = false
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (removed) {
                            Toast.makeText(this, R.string.user_word_saved, Toast.LENGTH_SHORT).show()
                            loadEntries()
                            UndoUi.maybeShow(listContainer, miningRepository) { loadEntries() }
                        }
                    }
                }
            }
            .show()
    }

    /** Add (existing == null) or edit; re-tapping a row prefills (row -> dialog). */
    private fun showEntryDialog(existing: UserWord?) {
        if (busy) return
        val expressionInput = EditText(this).apply {
            hint = getString(R.string.user_word_expression_hint)
            setText(existing?.expression.orEmpty())
            isSingleLine = true
            // expression is the row's PK half; rename would fork the entry —
            // change it by deleting + re-adding (undoable both ways)
            isEnabled = existing == null
        }
        val readingInput = EditText(this).apply {
            hint = getString(R.string.user_word_reading_hint)
            setText(existing?.readingKana.orEmpty())
            isSingleLine = true
        }
        val meaningInput = EditText(this).apply {
            hint = getString(R.string.user_word_meaning_hint)
            setText(existing?.meaning.orEmpty())
            isSingleLine = true
        }
        val notesInput = EditText(this).apply {
            hint = getString(R.string.user_word_notes_hint)
            setText(existing?.notes.orEmpty())
            isSingleLine = true
        }
        // R1a: shown on both create and edit; R2a: default checked on create, unchecked on edit.
        val reparseCheckBox = CheckBox(this).apply {
            text = getString(R.string.user_word_reparse_checkbox)
            isChecked = existing == null
            setPadding(0, 12, 0, 4)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 8)
            addView(expressionInput)
            addView(readingInput)
            addView(meaningInput)
            addView(notesInput)
            addView(reparseCheckBox)
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.user_word_add else R.string.definition_edit_title)
            .setView(container)
            .setNegativeButton(R.string.word_editor_cancel, null)
            .setPositiveButton(R.string.btn_save_text) { _, _ ->
                val expr = expressionInput.text.toString().trim()
                if (expr.isEmpty()) return@setPositiveButton
                val reading = readingInput.text.toString().trim().ifEmpty { null }
                val meaning = meaningInput.text.toString().trim()
                val notes = notesInput.text.toString().trim().ifEmpty { null }
                val shouldReparse = reparseCheckBox.isChecked
                busy = true
                addButton.isEnabled = false
                if (!shouldReparse) {
                    ioExecutor.execute {
                        miningRepository.upsertUserWord(
                            LanguageTag.JAPANESE,
                            expr,
                            reading,
                            meaning,
                            notes,
                        )
                        runOnUiThread {
                            busy = false
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            addButton.isEnabled = true
                            Toast.makeText(this, R.string.user_word_saved, Toast.LENGTH_SHORT).show()
                            loadEntries()
                        }
                    }
                } else {
                    // Serial corpus lane + process-wide MiningPipelineHolder repo so
                    // rotation/teardown (onDestroy closing this Activity's repo) never
                    // aborts an in-flight reparse.
                    statusView.visibility = View.VISIBLE
                    val appCtx = applicationContext
                    AppIo.pipeline.execute {
                        val result = runCatching {
                            MiningPipelineHolder.of(appCtx).saveUserWord(
                                languageTag = LanguageTag.JAPANESE,
                                expression = expr,
                                readingKana = reading,
                                meaning = meaning,
                                notes = notes,
                                reparseMatches = true,
                            )
                        }
                        runOnUiThread {
                            busy = false
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            addButton.isEnabled = true
                            statusView.visibility = View.GONE
                            val summary = result.getOrNull()
                            val msg = when {
                                summary == null -> getString(R.string.user_word_reparse_failed)
                                summary.matchedSentences == 0 -> getString(R.string.user_word_reparse_none)
                                summary.changedSentences == 0 -> getString(
                                    R.string.user_word_reparse_unchanged,
                                    summary.matchedSentences,
                                )
                                else -> getString(
                                    R.string.user_word_reparse_done,
                                    summary.changedSentences,
                                    summary.addedOccurrences,
                                    summary.removedOccurrences,
                                )
                            }
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            loadEntries()
                            if (summary != null && summary.hasCorpusChanges) {
                                UndoUi.maybeShow(listContainer, miningRepository) { loadEntries() }
                            }
                        }
                    }
                }
            }
            .show()
    }

    override fun onDestroy() {
        miningRepository.close()
        super.onDestroy()
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, UserDictionaryActivity::class.java)
    }
}
