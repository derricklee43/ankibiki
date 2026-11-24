package com.derricklee.ankidict

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ActivityStremioConsoleBinding

/**
 * Stremio Mining Console entry screen (design §3): mirrors the cloud library locally.
 * AUTH first run: link flow shows the code; the user approves in any signed-in Stremio
 * client (S0-A). No device polling spam: sync happens only on button press.
 */
class StremioConsoleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStremioConsoleBinding
    private lateinit var miningRepository: MiningRepository
    private lateinit var prefs: StremioPrefs
    private lateinit var client: StremioClient
    private var rows = ArrayList<StremioLibraryItem>()
    private lateinit var adapter: ConsoleAdapter
    @Volatile private var syncing = false
    @Volatile private var linkDialogCancelled = false
    private var linkDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStremioConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        miningRepository = MiningRepository(this)
        prefs = StremioPrefs(this)
        client = StremioClient(UrlStremioTransport())

        adapter = ConsoleAdapter()
        binding.consoleList.layoutManager = LinearLayoutManager(this)
        binding.consoleList.adapter = adapter

        binding.refreshButton.setOnClickListener { startSync() }
        binding.rdTokenButton.setOnClickListener { promptRdToken() }
        renderHeader()
        AppIo.io.execute {
            val cached = miningRepository.loadStremioLibrary()
            runOnUiThread {
                rows.clear(); rows.addAll(cached); adapter.notifyDataSetChanged()
            }
        }
    }

    private fun renderHeader() {
        val auth = prefs.authKey != null
        val rd = prefs.rdToken
        binding.headerText.text = buildString {
            append(if (auth) "Stremio: linked" else "Stremio: not linked")
            append("  |  RD: ")
            append(if (rd == null) "not configured" else StremioPrefs.mask(rd))
            if (prefs.lastSyncAt > 0) {
                append("\nlast sync ")
                append(android.text.format.DateFormat.getTimeFormat(this@StremioConsoleActivity)
                    .format(prefs.lastSyncAt))
            }
        }
    }

    private fun startSync() {
        if (syncing) return
        syncing = true
        Toast.makeText(this, "syncing…", Toast.LENGTH_SHORT).show()
        val existing = prefs.authKey
        if (existing != null) {
            fetchAndStore(existing)
            return
        }
        // link flow first: network off-main, dialog ON main (AlertDialog cannot be
        // created on a worker thread -- the v1 crash "Can't create handler inside
        // thread" was exactly this; user-reported 2026-11-23)
        AppIo.io.execute {
            val created = runCatching { client.linkCreate() }
                .getOrElse { e ->
                    doneOnUiThread("link create failed: ${e.message?.take(140)}")
                    return@execute
                }
            runOnUiThread { showLinkDialog(created.first, created.second) }
        }
    }

    private fun showLinkDialog(code: String, link: String) {
        linkDialogCancelled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("Link Stremio account ($code)")
            .setMessage("Open this in a browser signed into your Stremio account " +
                "(it redirects to stremio.com/login?code=$code):\n\n$link\n\n" +
                "Or scan the QR at the qrcode URL shown in logcat. This screen " +
                "keeps polling; you can also approve later and hit 同期 again.")
            .setOnCancelListener { linkDialogCancelled = true }
            .create()
        linkDialog = dialog
        dialog.show()
        AppIo.io.execute {
            var confirmed: String? = null
            for (i in 0 until 40) {
                Thread.sleep(3_000)
                confirmed = client.linkPoll(code)
                if (confirmed != null || linkDialogCancelled) break
            }
            val key = confirmed
            runOnUiThread {
                if (linkDialog === dialog && dialog.isShowing()) dialog.dismiss()
            }
            linkDialog = null
            if (key != null) {
                prefs.authKey = key
                fetchAndStore(key)
            } else {
                doneOnUiThread("link cancelled/unconfirmed")
            }
        }
    }

    private fun fetchAndStore(authKey: String) {
        AppIo.io.execute {
            try {
                val library = client.fetchLibrary(authKey)
                val addons = client.fetchAddonCollection(authKey)
                val now = System.currentTimeMillis()
                miningRepository.replaceStremioLibrary(library, now)
                miningRepository.replaceStremioAddons(addons, now)
                for (item in library) {
                    miningRepository.saveStremioWatchSnapshot(item.baseId, item.state, now)
                }
                prefs.lastSyncAt = now
                runOnUiThread {
                    rows.clear(); rows.addAll(library); adapter.notifyDataSetChanged()
                    renderHeader()
                    syncing = false
                    Toast.makeText(this, "synced: ${library.size} titles, ${addons.size} addons",
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                doneOnUiThread("sync failed: ${e.message?.take(140)}")
            }
        }
    }

    private fun doneOnUiThread(message: String) {
        runOnUiThread {
            syncing = false
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            renderHeader()
        }
    }

    private fun promptRdToken() {
        val input = EditText(this).apply {
            hint = "Real-Debrid API token (account page → Info → API key)"
            setText(prefs.rdToken.orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("Real-Debrid token")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                prefs.rdToken = input.text.toString().trim().takeIf { it.isNotEmpty() }
                renderHeader()
            }
            .setNegativeButton("Clear") { _, _ ->
                prefs.rdToken = null
                renderHeader()
            }
            .show()
    }

    private inner class ConsoleAdapter : RecyclerView.Adapter<ConsoleAdapter.VH>() {
        inner class VH(row: View) : RecyclerView.ViewHolder(row) {
            val title: TextView = row.findViewById(R.id.stremioRowTitle)
            val meta: TextView = row.findViewById(R.id.stremioRowMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_stremio_library_row, parent, false),
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = rows[position]
            holder.title.text = item.name
            val state = item.state
            val resume = if (state != null && state.timeOffset > 1) " · resume @ ${fmtOffset(state.timeOffset)}" else ""
            val watched = if ((state?.timesWatched ?: 0) > 0 ||
                (state?.flaggedWatched ?: 0) > 0) " · watched-mark" else ""
            holder.meta.text = "${item.type}$resume$watched" +
                (if (state?.videoId != null) " · active ${state.videoId}" else "")
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@StremioConsoleActivity, StremioEpisodesActivity::class.java).apply {
                    putExtra("base_id", item.baseId)
                    putExtra("type", item.type)
                    putExtra("name", item.name)
                })
            }
        }

        private fun fmtOffset(raw: Long): String {
            // S0-A pins the unit; heuristic: treat > 100_000 as ms (web) else seconds.
            val seconds = if (raw > 100_000) raw / 1000 else raw
            return "%d:%02d".format(seconds / 60, seconds % 60)
        }
    }
}
