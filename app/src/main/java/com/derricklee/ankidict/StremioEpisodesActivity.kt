package com.derricklee.ankidict

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ActivityStremioEpisodesBinding

/**
 * Episode screen (design §3): checkboxes pre-checked watched-unmined (S0-B bitfield;
 * unopinionated per D5 — the user checks whatever they want, warnings-free), per-show
 * gear settings (D1), whole-episode extraction onto the serial pipeline lane.
 */
class StremioEpisodesActivity : AppCompatActivity() {

    private data class EpisodeRow(
        val episode: MetaEpisode,
        val watchedHint: Boolean,
        var selected: Boolean,
        val ledger: StremioLedgerRow?,
        val isResumeSlot: Boolean,
    )

    private lateinit var binding: ActivityStremioEpisodesBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var prefs: StremioPrefs
    private val rows = ArrayList<EpisodeRow>()
    private lateinit var adapter: EpisodeAdapter
    private var baseId = ""
    private var mediaType = "series"
    private var showName = ""
    @Volatile private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStremioEpisodesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miningRepository = MiningRepository(this)
        prefs = StremioPrefs(this)
        baseId = intent.getStringExtra("base_id").orEmpty()
        mediaType = intent.getStringExtra("type") ?: "series"
        showName = intent.getStringExtra("name").orEmpty()
        binding.showTitle.text = showName

        adapter = EpisodeAdapter()
        binding.episodeList.layoutManager = LinearLayoutManager(this)
        binding.episodeList.adapter = adapter
        binding.gearButton.setOnClickListener { showSettings(scopeKey = baseId) }
        binding.extractButton.setOnClickListener { extractSelected() }
        loadEpisodes()
    }

    private fun loadEpisodes() {
        AppIo.io.execute {
            val addons = miningRepository.loadStremioAddons()
            val metaAddon = addons.firstMetaAddon()
            val library = miningRepository.loadStremioLibrary()
                .firstOrNull { it.baseId == baseId }
            val episodes = runCatching {
                metaAddon?.let {
                    AddonResources(UrlStremioTransport()).metaEpisodes(it, mediaType, baseId)
                } ?: emptyList()
            }.getOrDefault(emptyList())
            val bitfield = WatchedBitfield.decode(
                library?.state?.watchedBlob, episodes.map { it.episodeId },
            )
            val ledgerByEpisode = miningRepository.loadStremioLedgerByEpisode()
            val resumeVideoId = library?.state?.videoId
            val built = episodes.map { ep ->
                val ledger = ledgerByEpisode[ep.episodeId]
                val watched = bitfield.getVideo(ep.episodeId)
                EpisodeRow(
                    episode = ep,
                    watchedHint = watched,
                    // design default: watched && unmined pre-checked; hint only, never a gate
                    selected = watched && ledger?.status != StremioLedgerStatus.MINED,
                    ledger = ledger,
                    isResumeSlot = resumeVideoId == ep.episodeId,
                )
            }
            runOnUiThread {
                rows.clear(); rows.addAll(built); adapter.notifyDataSetChanged()
                binding.statusLine.text = when {
                    metaAddon == null -> "no meta addon cached — Refresh the console first"
                    built.isEmpty() -> "no episodes found (S0-C kill branch: check addon)"
                    else -> "${built.count { it.watchedHint }}/${built.size} watched"
                }
            }
        }
    }

    private fun showSettings(scopeKey: String) {
        val current = miningRepository.readStremioSettings(scopeKey)
            ?: miningRepository.stremioSettingsFor(scopeKey, scopeKey)
        val langInput = EditText(this).apply {
            hint = "subtitle language code (jpn/eng/...)"
            setText(current.subtitleLang)
        }
        val patternInput = EditText(this).apply {
            hint = "release-name pattern (e.g. group name; blank = first)"
            setText(current.subtitlePattern.orEmpty())
        }
        val pinInput = EditText(this).apply {
            hint = "exact subtitle filename pin (blank = none)"
            setText(current.subtitlePin.orEmpty())
        }
        val offsetInput = EditText(this).apply {
            hint = "offset ms (stored for media phase; no effect on text)"
            setText(current.offsetMs.toString())
        }
        AlertDialog.Builder(this)
            .setTitle("Settings for $scopeKey")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(langInput); addView(patternInput); addView(pinInput); addView(offsetInput)
            })
            .setPositiveButton("Save") { _, _ ->
                miningRepository.saveStremioSettings(
                    StremioSettingsRow(
                        scopeKey = scopeKey,
                        subtitleLang = langInput.text.toString().trim().ifEmpty { "jpn" },
                        subtitlePattern = patternInput.text.toString().trim().takeIf { it.isNotEmpty() },
                        subtitlePin = pinInput.text.toString().trim().takeIf { it.isNotEmpty() },
                        audioTrack = current.audioTrack,
                        offsetMs = offsetInput.text.toString().toIntOrNull() ?: 0,
                    ),
                    System.currentTimeMillis(),
                )
                Toast.makeText(this, "settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractSelected() {
        if (working) return
        val targets = rows.filter { it.selected }.map { it.episode }
        if (targets.isEmpty()) {
            Toast.makeText(this, "nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        working = true
        Toast.makeText(this, "queuing ${targets.size} episodes…", Toast.LENGTH_SHORT).show()
        AppIo.pipeline.execute {
            val transport = UrlStremioTransport()
            val engine = StremioExtractionEngine(
                transport = transport,
                repo = miningRepository,
                configFor = ::miningConfigFor,
                addonsProvider = { miningRepository.loadStremioAddons() },
            )
            var mined = 0
            var failed = 0
            for (episode in targets) {
                val outcome = engine.runJob(episode.episodeId, mediaType, baseId)
                if (outcome.status == StremioLedgerStatus.MINED) mined++ else failed++
                val idx = rows.indexOfFirst { it.episode.episodeId == episode.episodeId }
                if (idx >= 0) runOnUiThread { adapter.notifyItemChanged(idx) }
            }
            runOnUiThread {
                working = false
                binding.statusLine.text = "extracted $mined, failed $failed — sentences are in the inbox now"
                loadEpisodes()
            }
        }
    }

    /** The console's only bridge to the extraction engines: the language pipeline config. */
    private fun miningConfigFor(tag: LanguageTag): PipelineConfig? =
        MiningPipelineHolder.of(this)?.configFor(tag)

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        inner class VH(row: View) : RecyclerView.ViewHolder(row) {
            val check: CheckBox = row.findViewById(R.id.episodeCheck)
            val title: TextView = row.findViewById(R.id.episodeTitle)
            val status: TextView = row.findViewById(R.id.episodeStatus)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_stremio_episode_row, parent, false),
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            val ep = row.episode
            holder.title.text = buildString {
                ep.season?.let { append("S$it") }
                ep.episode?.let { append("E$it") }
                append("  ").append(ep.title)
                if (row.isResumeSlot) append("   ⏵ resume here (reference only)")
            }
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = row.selected
            holder.check.setOnCheckedChangeListener { _, checked -> row.selected = checked }
            val ledger = row.ledger
            holder.status.text = when (ledger?.status) {
                StremioLedgerStatus.MINED -> "mined ${ledger.cueCount} cues"
                StremioLedgerStatus.FAILED -> "failed: ${ledger.error?.take(60)} (re-queueable)"
                StremioLedgerStatus.QUEUED, StremioLedgerStatus.FETCHING,
                StremioLedgerStatus.PARSING -> "in progress…"
                null -> if (row.watchedHint) "watched" else "unwatched (still queueable)"
                else -> ledger?.status.orEmpty()
            }
            holder.itemView.setOnLongClickListener {
                showSettings(scopeKey = ep.episodeId)
                true
            }
        }
    }
}
