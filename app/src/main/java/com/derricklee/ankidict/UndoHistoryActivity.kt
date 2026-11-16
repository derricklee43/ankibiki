package com.derricklee.ankidict

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.derricklee.ankidict.databinding.ActivityUndoHistoryBinding
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 操作履歴 (cross-view undo, user go 2026-11-15): the last undo steps with Japanese labels.
 * Undo is strictly newest-first (LIFO stack, no redo in v1) — only the newest row carries the
 * 元に戻す affordance; older rows stay listed as context so the user can see what will become
 * undoable next. Opens from the Settings popup.
 */
class UndoHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUndoHistoryBinding
    private lateinit var miningRepository: MiningRepository
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUndoHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miningRepository = MiningRepository(this)
        binding.backButton.setOnClickListener { finish() }
        binding.historyNote.text = getString(R.string.undo_history_note, 50)
        loadEntries()
    }

    private fun loadEntries() {
        AppIo.io.execute {
            val entries = miningRepository.recentUndos(limit = 20)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.historyContainer.removeAllViews()
                binding.historyEmpty.visibility = if (entries.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                entries.forEachIndexed { index, entry ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, 12, 0, 12)
                    }
                    row.addView(TextView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "${entry.label}\n${timeFormat.format(java.util.Date(entry.createdAt))}"
                        textSize = 14f
                    })
                    if (index == 0) {
                        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            text = context.getString(R.string.undo_action_label)
                            setOnClickListener {
                                isEnabled = false
                                AppIo.pipeline.execute {
                                    val outcome = UndoManager.undoLatest(miningRepository)
                                    runOnUiThread {
                                        if (isFinishing || isDestroyed) return@runOnUiThread
                                        when (outcome) {
                                            UndoOutcome.UNDONE, UndoOutcome.NOTHING_LEFT -> loadEntries()
                                            else -> {
                                                Toast.makeText(this@UndoHistoryActivity, R.string.undo_guard_failed, Toast.LENGTH_LONG).show()
                                                loadEntries()
                                            }
                                        }
                                    }
                                }
                            }
                        })
                    }
                    binding.historyContainer.addView(row)
                }
            }
        }
    }
}
