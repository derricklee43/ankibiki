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
 * Kanji meaning and story from the NihongoShark Kanji deck without leaving the current app.
 */
class ProcessTextActivity : AppCompatActivity() {

    private data class KanjiPopupItem(
        val character: String,
        val meaning: String,
        val story: String,
    )

    private lateinit var binding: ActivityProcessTextBinding
    private val lookupExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessTextBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener { finish() }
        binding.kanjiPopupList.layoutManager = LinearLayoutManager(this)

        val selectedText = (
            intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)
            )?.toString().orEmpty()
        val characters = selectedText.filter(::isCjkCharacter).map { it.toString() }.distinct()

        if (characters.isEmpty()) {
            showStatus(getString(R.string.process_text_no_kanji))
            return
        }

        val repository = AnkiRepository(this)
        if (!repository.isAnkiDroidAvailable()) {
            showStatus(getString(R.string.status_ankidroid_missing))
            return
        }
        if (!hasReadWritePermission()) {
            showStatus(getString(R.string.status_permission_denied))
            return
        }

        lookupExecutor.execute {
            val items = characters.map { ch ->
                val card = repository.findNoteByCharacter(ch, NIHONGO_SHARK_KANJI_MODEL_ID)?.toCard()
                KanjiPopupItem(
                    character = ch,
                    meaning = card?.meaning?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.process_text_not_in_deck),
                    story = card?.mnemonic.orEmpty(),
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.kanjiPopupList.adapter = KanjiPopupAdapter(items)
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

    private class KanjiPopupAdapter(private val items: List<KanjiPopupItem>) :
        RecyclerView.Adapter<KanjiPopupAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProcessTextKanjiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(private val binding: ItemProcessTextKanjiBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: KanjiPopupItem) {
                binding.kanjiCharText.text = item.character
                binding.kanjiMeaningText.text = item.meaning
                if (item.story.isBlank()) {
                    binding.kanjiStoryText.visibility = View.GONE
                } else {
                    binding.kanjiStoryText.visibility = View.VISIBLE
                    binding.kanjiStoryText.text = item.story
                }
            }
        }
    }
}
