package com.derricklee.ankidict

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityClaimsQueueBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Claims queue (roadmap B1, user spec 2026-11-14): drain pending nameplate rulings in one place
 * instead of hunting photos. Lists every unruled `nameplate_claims` row (standalone v8 drafts +
 * inline H9v2 merge proposals), each row ruling through [NameplateRuling] — the exact path the
 * photo view uses — and the open-in-new icon deep-links the owning photo. Ruled rows leave the
 * list; the header count is `SELECT COUNT(*) WHERE verdict IS NULL`, visible at all times.
 */
class ClaimsQueueActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClaimsQueueBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var miningPipelineRunner: MiningPipelineRunner
    private lateinit var dictionaryRepository: DictionaryRepository
    private lateinit var shinjitaiRepository: ShinjitaiRepository
    private lateinit var hskRepository: HskRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val rulingInProgress = AtomicBoolean(false)
    private lateinit var adapter: ClaimsQueueAdapter
    /** Built once per visit: parsing the shipped gate lexicon is expensive; enrichment reuses it. */
    private val gateLexicon by lazy { runCatching { GateLexiconProvider.lexicon(this) }.getOrNull() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClaimsQueueBinding.inflate(layoutInflater)
        setContentView(binding.root)

        miningRepository = MiningRepository(this)
        dictionaryRepository = DictionaryRepository(this)
        shinjitaiRepository = ShinjitaiRepository(this)
        hskRepository = HskRepository(this)
        miningPipelineRunner = MiningPipelineRunner.createDefault(
            context = this,
            miningRepository = miningRepository,
            dictionaryRepository = dictionaryRepository,
            shinjitaiRepository = shinjitaiRepository,
            hskRepository = hskRepository,
        )

        adapter = ClaimsQueueAdapter(
            onRule = { row, correct -> rule(row, correct) },
            onOpenPhoto = { row ->
                startActivity(
                    PhotoDetailActivity.newIntent(this, row.mediaStoreId, row.contentUri, row.dateAdded),
                )
            },
        )
        binding.claimsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.claimsRecyclerView.adapter = adapter
        binding.backButton.setOnClickListener { finish() }

        loadPending()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    private fun loadPending() {
        ioExecutor.execute {
            val pending = miningRepository.pendingNameplateClaims()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Rows + badge render from the fast SQL alone (list stays usable if enrichment
                // is slow or the lexicon is absent).
                adapter.submitList(pending, emptyMap())
                binding.pendingCountText.text =
                    getString(R.string.claims_queue_pending_count, pending.size)
                binding.emptyClaimsText.visibility = if (pending.isEmpty()) View.VISIBLE else View.GONE
            }
            // Inline "→ remainder" previews need the shipped rule + gate lexicon — enrichment
            // after display, same computation the photo view does (absent lexicon -> no arrows).
            val lex = gateLexicon
            if (lex == null || pending.none { it.contextSentence.isNotEmpty() }) return@execute
            val remainders: Map<Long, String> = pending.mapNotNull { row ->
                if (row.contextSentence.isEmpty()) return@mapNotNull null
                val remainder = runCatching {
                    NameplateInlineRule.suggestions(row.contextSentence, lex)
                        .firstOrNull { it.plate == row.text }?.remainder
                }.getOrNull()
                remainder?.let { row.claimId to it }
            }.toMap()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submitList(miningRepository.pendingNameplateClaims(), remainders)
            }
        }
    }

    private fun rule(row: MiningRepository.PendingNameplateClaim, correct: Boolean) {
        if (!rulingInProgress.compareAndSet(false, true)) return
        adapter.actionsEnabled = false
        ioExecutor.execute {
            var failure: Exception? = null
            try {
                NameplateRuling.apply(
                    context = this@ClaimsQueueActivity,
                    miningRepository = miningRepository,
                    miningPipelineRunner = miningPipelineRunner,
                    mediaStoreId = row.mediaStoreId,
                    claim = MiningRepository.NameplateClaim(row.claimId, row.text, row.contextSentence, null),
                    correct = correct,
                )
            } catch (e: Exception) {
                failure = e
            }
            runOnUiThread {
                rulingInProgress.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.actionsEnabled = true
                if (failure != null) {
                    Toast.makeText(this, failure!!.message.orEmpty(), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(
                    this,
                    getString(if (correct) R.string.nameplate_hidden_toast else R.string.nameplate_kept_toast),
                    Toast.LENGTH_SHORT,
                ).show()
                loadPending()
            }
        }
    }
}
