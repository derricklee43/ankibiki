package com.derricklee.ankidict

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ActivityStremioEpisodesBinding
import java.io.File
import java.util.Locale

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
        binding.deleteButton.setOnClickListener { confirmDeleteSelected() }
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
            }.onFailure { e ->
                runOnUiThread {
                    binding.statusLine.text = "meta fetch failed: ${e.message?.take(120)}"
                }
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
                    // USER RULING 2026-11-26: nothing is pre-selected; the watched
                    // hint is display-only (supersedes the design default of
                    // watched && unmined pre-checked).
                    selected = false,
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
        val audioTrackSummary = TextView(this).apply {
            text = "Audio track: ${current.audioTrack?.let { "#$it" } ?: "auto (${current.subtitleLang})"}"
            textSize = 13f
            setPadding(8, 12, 8, 4)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
            addView(langInput); addView(patternInput); addView(pinInput); addView(offsetInput)
            addView(audioTrackSummary)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Settings for $scopeKey")
            .setView(container)
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
        if (scopeKey != baseId) {
            builder.setNeutralButton("Probe & Test Slice") { _, _ ->
                runMediaProbeAndSlice(scopeKey)
            }
        }
        val dialog = builder.create()
        if (scopeKey != baseId) {
            val row = rows.firstOrNull { it.episode.episodeId == scopeKey }
            if (row?.ledger != null) {
                container.addView(Button(this).apply {
                    text = getString(R.string.stremio_delete_episode_button)
                    setTextColor(0xFFB00020.toInt())
                    setOnClickListener {
                        dialog.dismiss()
                        confirmDeleteEpisodes(listOf(row.episode))
                    }
                })
            }
        }
        dialog.show()
    }

    /**
     * Stage 3 Step 1 + Step 2 on-device spike runner (S0-D audio track probe + S3.1 native
     * frame/audio slice). Resolves a playable RD URL under the phone's IP, probes tracks,
     * slices a real mid-episode cue, logs structured metrics, and shows frame + audio playback
     * + track picker so correctness is verified audibly and visually on device.
     */
    private fun runMediaProbeAndSlice(videoId: String) {
        if (working) {
            Toast.makeText(this, "pipeline busy — wait a moment", Toast.LENGTH_SHORT).show()
            return
        }
        working = true
        binding.statusLine.text = "Stage 3 probe & slice: resolving stream for $videoId…"
        val appCtx = applicationContext
        AppIo.pipeline.execute {
            val result = runCatching {
                val repo = MiningRepository(appCtx)
                try {
                    val settings = repo.stremioSettingsFor(videoId, baseId)
                    val ledger = repo.loadStremioLedgerByEpisode()[videoId]
                    val docId = ledger?.docId ?: -1L
                    val (storedHash, storedIdx) = if (docId > 0L) repo.loadStremioDocIdentity(docId) else (null to null)

                    // Pick a real mid-episode dialogue cue if mined, else a 2-minute probe window.
                    val cues = if (docId > 0L) repo.loadStremioCues(docId) else emptyList()
                    val cue = cues.drop(cues.size / 4).firstOrNull { it.endMs - it.startMs in 800L..6000L }
                        ?: cues.firstOrNull()
                        ?: SubtitleCue(120_000L, 123_000L, "(unmined episode — 02:00 test window)")

                    val ctx = VideoCueContext(
                        docId = docId,
                        videoId = videoId,
                        baseId = baseId,
                        metaType = mediaType,
                        infoHash = storedHash,
                        fileIdx = storedIdx,
                        cue = cue,
                    )
                    val res = VideoSliceService.sliceOrLoad(
                        context = appCtx,
                        repo = repo,
                        prefs = StremioPrefs(appCtx),
                        ctx = ctx,
                        settings = settings,
                        forceRefresh = true,
                        onStatus = { status ->
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    binding.statusLine.text = "Stage 3: $status"
                                }
                            }
                        },
                    )
                    val resolved = res.resolved ?: error("missing resolved stream")
                    val probe = res.probe ?: error("missing probe result")
                    val slice = res.slice ?: error("missing slice outcome")
                    Triple(Triple(resolved, probe, cue), slice, settings)
                } finally {
                    repo.close()
                }
            }
            runOnUiThread {
                working = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { (meta, slice, settings) ->
                        val (resolved, probe, cue) = meta
                        binding.statusLine.text = String.format(
                            Locale.US,
                            "Media3 OK: total=%dms (demux=%d, frame=%d, audio=%d), %.2f MB pulled",
                            slice.totalMs, slice.demuxMs, slice.frameMs, slice.audioMs,
                            slice.rxBytes / (1024.0 * 1024.0),
                        )
                        showSliceResultDialog(videoId, resolved, probe, cue, slice, settings)
                    },
                    onFailure = { e ->
                        Log.w("Stage3Spike", "failed for $videoId: ${e.message}", e)
                        binding.statusLine.text = "Stage 3 failed: ${e.message?.take(120)}"
                        AlertDialog.Builder(this)
                            .setTitle("Probe / Slice Failed")
                            .setMessage("${e.javaClass.simpleName}: ${e.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    },
                )
            }
        }
    }

    private fun showSliceResultDialog(
        videoId: String,
        resolved: StreamUrlResolver.ResolvedStream,
        probe: MediaProbeResult,
        cue: SubtitleCue,
        slice: Media3SliceEngine.SliceOutcome,
        settings: StremioSettingsRow,
    ) {
        var player: MediaPlayer? = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val bmp = BitmapFactory.decodeFile(slice.imageFile.absolutePath)
        if (bmp != null) {
            root.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
        }
        root.addView(TextView(this).apply {
            text = cue.text
            textSize = 16f
            setPadding(0, 12, 0, 8)
        })
        val playBtn = Button(this).apply {
            text = "▶ Play audio (${slice.audioBytes / 1024} KB · ${slice.audioMode})"
            setOnClickListener {
                runCatching {
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(slice.audioFile.absolutePath)
                        prepare()
                        start()
                    }
                }.onFailure { err ->
                    Toast.makeText(this@StremioEpisodesActivity, "Play failed: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(playBtn)

        val metricsText = String.format(
            Locale.US,
            "Engine: Media3 MatroskaExtractor\nFile: %s\nSource: %s (%dms)\n" +
                "Probe: %dms (%d KB) · %s %dx%d\n" +
                "Demux: %dms (%.2f MB · %d HTTP opens · seekable=%s @ %dms)\n" +
                "Frame: %dms (%d KB WebP @ %dms)\n" +
                "Audio: %dms (%d KB M4A · %d..%dms)\n" +
                "Slice total: %dms",
            resolved.displayName,
            resolved.source, resolved.resolveMs,
            probe.probeMs, probe.rxBytes / 1024, probe.videoMime ?: "?", probe.videoWidth, probe.videoHeight,
            slice.demuxMs, slice.rxBytes / (1024.0 * 1024.0), slice.httpOpens, slice.seekable, slice.seekedPtsMs,
            slice.frameMs, slice.imageBytes / 1024, slice.window.frameAtMs,
            slice.audioMs, slice.audioBytes / 1024, slice.window.startMs, slice.window.endMs,
            slice.totalMs,
        )
        root.addView(TextView(this).apply {
            text = metricsText
            textSize = 12f
            setPadding(0, 10, 0, 10)
        })

        // Audio track picker (0-based audio ordinal; Save & Re-slice persists and re-cuts immediately).
        root.addView(TextView(this).apply {
            text = "Audio track (currently sliced: ${slice.selectedTrack.displayLabel()}):"
            textSize = 12f
            setPadding(0, 4, 0, 4)
        })
        val radioGroup = RadioGroup(this)
        val trackByButtonId = HashMap<Int, Int?>()
        val autoId = View.generateViewId()
        trackByButtonId[autoId] = null
        radioGroup.addView(RadioButton(this).apply {
            id = autoId
            text = "Auto (match ${settings.subtitleLang})"
            textSize = 12f
        })
        var initialCheckedId = autoId
        for (t in probe.audioTracks) {
            val rbId = View.generateViewId()
            trackByButtonId[rbId] = t.trackIndex
            radioGroup.addView(RadioButton(this).apply {
                id = rbId
                text = t.displayLabel()
                textSize = 12f
            })
            if (t.trackIndex == slice.selectedTrack.trackIndex) {
                initialCheckedId = rbId
            }
        }
        radioGroup.check(initialCheckedId)
        root.addView(radioGroup)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Stage 3 Slice · $videoId")
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton("Save & Re-slice") { _, _ ->
                val chosenTrack: Int? = trackByButtonId[radioGroup.checkedRadioButtonId]
                miningRepository.saveStremioSettings(
                    settings.copy(scopeKey = videoId, audioTrack = chosenTrack),
                    System.currentTimeMillis(),
                )
                val label = chosenTrack?.let { "#$it" } ?: "auto"
                Toast.makeText(this, "saved audio track $label — re-slicing…", Toast.LENGTH_SHORT).show()
                runMediaProbeAndSlice(videoId)
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            runCatching { player?.release() }
            player = null
            bmp?.recycle()
        }
        dialog.show()
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
                // builtin rung after synced addons (spikes/007 D4a; prefs = no schema change)
                jimaku = JimakuClient(transport, { prefs.jimakuKey },
                    PrefsJimakuEntryCache(prefs)),
            )
            var mined = 0
            var failed = 0
            for (episode in targets) {
                val outcome = engine.runJob(episode.episodeId, mediaType, baseId, showName,
                    episodeTitle = episode.title)
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

    private fun confirmDeleteSelected() {
        if (working) return
        val targets = rows.filter { it.selected }.map { it.episode }
        if (targets.isEmpty()) {
            Toast.makeText(this, "nothing selected", Toast.LENGTH_SHORT).show()
            return
        }
        confirmDeleteEpisodes(targets)
    }

    private fun confirmDeleteEpisodes(targets: List<MetaEpisode>) {
        if (working || targets.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.stremio_delete_confirm_title)
            .setMessage(getString(R.string.stremio_delete_confirm_message, targets.size))
            .setNegativeButton(R.string.word_editor_cancel, null)
            .setPositiveButton(R.string.stremio_delete_selected) { _, _ ->
                deleteEpisodes(targets)
            }
            .show()
    }

    private fun deleteEpisodes(targets: List<MetaEpisode>) {
        if (working) return
        working = true
        val appCtx = applicationContext
        AppIo.pipeline.execute {
            var totalOccs = 0
            var totalClips = 0
            var clearedCount = 0
            for (ep in targets) {
                val summary = miningRepository.deleteMinedEpisode(appCtx, ep.episodeId)
                totalOccs += summary.occurrencesDeleted
                totalClips += summary.clipsDeleted
                if (summary.docsDeleted > 0 || summary.occurrencesDeleted > 0 || summary.clipsDeleted > 0) {
                    clearedCount++
                }
            }
            runOnUiThread {
                working = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val msg = getString(
                    R.string.stremio_delete_done,
                    clearedCount,
                    totalOccs,
                    totalClips,
                )
                binding.statusLine.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
                // unwatched carries NO label (user ruling 2026-11-26); the row stays
                // queueable regardless - hint was never a gate
                null -> if (row.watchedHint) "watched" else ""
                else -> ledger?.status.orEmpty()
            }
            holder.itemView.setOnClickListener {
                // whole row toggles (user request 2026-11-26); the CheckBox consumes
                // its own taps so no double-toggle, and the listener below keeps
                // row.selected in sync whichever path flips it
                holder.check.toggle()
            }
            holder.itemView.setOnLongClickListener {
                showSettings(scopeKey = ep.episodeId)
                true
            }
        }
    }
}
