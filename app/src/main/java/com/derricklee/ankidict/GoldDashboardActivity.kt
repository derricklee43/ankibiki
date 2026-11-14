package com.derricklee.ankidict

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.derricklee.ankidict.databinding.ActivityGoldDashboardBinding
import org.json.JSONObject

/**
 * Gold dashboard (roadmap B2, user spec 2026-11-14): the corpus's quality scoreboard in one
 * screen — 金 marks, the auto-apply gate's evidence accruing toward the ~200-sample checkpoint
 * (rendered from `ocr_corrections` counts), the correction-effort proxy, and the RECORDED
 * benchmark as a static table read from the committed asset (scripts/export_benchmark_record.py;
 * its single doc home is docs/extraction-pipeline-state.md). Nothing here runs OCR.
 */
class GoldDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGoldDashboardBinding
    private lateinit var miningRepository: MiningRepository
    private val ioExecutor = AppIo.io

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGoldDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miningRepository = MiningRepository(this)
        binding.backButton.setOnClickListener { finish() }
        loadStats()
    }

    override fun onResume() {
        super.onResume()
        loadStats()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun loadStats() {
        ioExecutor.execute {
            val stats = miningRepository.getDashboardStats()
            val record = runCatching {
                JSONObject(assets.open("benchmark_record.json").bufferedReader().use { it.readText() })
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderStats(stats)
                renderRecord(record)
            }
        }
    }

    private fun renderStats(stats: MiningRepository.DashboardStats) {
        // gate evidence vs the spike 003 checkpoint (~200 applied samples with ≤1 rejection
        // re-gates the rollout decision; docs/roadmap.md §2 + spikes/003 README)
        binding.gateEvidenceText.text =
            getString(R.string.dashboard_gate_evidence, stats.correctionsApplied, GATE_CHECKPOINT_SAMPLES)
        binding.gateProgress.max = GATE_CHECKPOINT_SAMPLES
        binding.gateProgress.progress = stats.correctionsApplied.coerceAtMost(GATE_CHECKPOINT_SAMPLES)
        binding.gateBreakdownText.text = getString(
            R.string.dashboard_gate_breakdown,
            stats.correctionsApplied, stats.correctionsRejected, stats.correctionsExcluded,
        )
        binding.goldCountText.text = getString(R.string.dashboard_gold_count, stats.goldPhotos)
        binding.effortText.text = getString(
            R.string.dashboard_effort, stats.goldUserEditedPhotos, stats.goldPhotos,
        )
        binding.corpusText.text = getString(R.string.dashboard_corpus, stats.totalPhotos, stats.keepPhotos)
    }

    private fun renderRecord(record: JSONObject?) {
        val rows = binding.benchmarkRows
        rows.removeAllViews()
        if (record == null) {
            rows.addView(label(getString(R.string.dashboard_record_missing)))
            return
        }
        fun pair(label: String, value: String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(label(label, weight = 1f))
            row.addView(label(value, weight = 1f, bold = true))
            rows.addView(row)
        }
        pair(getString(R.string.dashboard_bench_gold_set), record.getInt("gold_fixture_photos").toString())
        pair(getString(R.string.dashboard_bench_sentence_exact), record.getString("sentence_exact"))
        pair(getString(R.string.dashboard_bench_segmentation), record.getString("segmentation_exact"))
        pair(getString(R.string.dashboard_bench_char_acc), record.getString("char_accuracy"))
        binding.benchSourceText.text = getString(
            R.string.dashboard_bench_source, record.getString("recorded"), record.getString("source_doc"),
        )
        binding.benchCaveatText.text = record.getString("caveat")
    }

    private fun label(text: String, weight: Float = 0f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (weight > 0f) layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    companion object {
        /** Spike 003 rollout checkpoint: revisit silent-apply evidence at ~200 applied samples. */
        const val GATE_CHECKPOINT_SAMPLES = 200
    }
}
