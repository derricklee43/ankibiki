package com.derricklee.ankidict

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityMainBinding
import com.derricklee.ankidict.databinding.ItemSettingsActionBinding
import com.derricklee.ankidict.databinding.ItemSettingsToggleBinding
import com.derricklee.ankidict.databinding.PopupSettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.provider.OpenableColumns
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

private const val PERMISSION_REQUEST_CODE = 1
private const val MEDIA_PERMISSION_REQUEST_CODE = 2
private const val SEARCH_DEBOUNCE_MS = 250L
private const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
private const val PREVIEW_ROW_LIMIT = 3

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AnkiRepository
    private lateinit var searchService: SearchService
    private lateinit var syncService: HanziWordSyncService
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var miningRepository: MiningRepository
    private lateinit var miningPipelineRunner: MiningPipelineRunner
    private lateinit var adapter: SearchResultAdapter

    private lateinit var recentPhotosAdapter: PhotoThumbnailAdapter
    private lateinit var recentWordsAdapter: ExtractedOccurrenceAdapter
    private lateinit var recentGrammarAdapter: ExtractedOccurrenceAdapter
    private var currentRecentPhotos: List<ScrapedPhoto> = emptyList()

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private val searchExecutor = AppIo.io
    private val syncExecutor = AppIo.pipeline
    private val miningExecutor = AppIo.io
    private val scanExecutor = AppIo.pipeline
    private val searchGeneration = AtomicInteger(0)

    // Corpus recovery (docs/recovery.md): export/restore of the self-verifying bundle.
    private val recoveryManager by lazy { RecoveryManager(this, miningRepository) }
    private var recoveryBusy = false
    private val exportBundleLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let { startRecoveryExport(it) } }
    private val restoreBundleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { uriStr -> planRecoveryRestore(uriStr, queryDisplayName(uriStr)) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AnkiRepository(this)
        val dictionaryRepository = DictionaryRepository(this)
        val hskRepository = HskRepository(this)
        val shinjitaiRepository = ShinjitaiRepository(this)
        val idsRepository = IdsRepository(this)
        val heisigRadicalRepository = HeisigRadicalRepository(
            idsRepository = idsRepository,
            dictionaryRepository = dictionaryRepository,
            ankiRepository = repository,
        )
        searchService = SearchService(repository, dictionaryRepository, heisigRadicalRepository)
        syncService = HanziWordSyncService(
            repository, dictionaryRepository, idsRepository, hskRepository, shinjitaiRepository,
        )
        settingsRepository = SettingsRepository(this)
        miningRepository = MiningRepository(this)
        // v9 one-shot maintenance: photos mined before the claims ledger existed get PENDING
        // audit rows (standalone drafts + H9v2 inline proposals) from stored artifacts —
        // offline proxy + bundled lexicon, no re-OCR. Idempotent by UNIQUE constraint; the flag
        // only avoids the scan on every launch.
        if (!getSharedPreferences("maintenance", Context.MODE_PRIVATE)
                .getBoolean("nameplate_backfill_v9", false)
        ) {
            miningExecutor.execute {
                runCatching {
                    miningRepository.backfillNameplateClaims(
                        inlineLexicon = runCatching { GateLexiconProvider.lexicon(this) }.getOrNull(),
                    )
                }
                getSharedPreferences("maintenance", Context.MODE_PRIVATE).edit()
                    .putBoolean("nameplate_backfill_v9", true).apply()
            }
        }
        miningPipelineRunner = MiningPipelineRunner.createDefault(
            context = this,
            miningRepository = miningRepository,
            dictionaryRepository = dictionaryRepository,
            shinjitaiRepository = shinjitaiRepository,
            hskRepository = hskRepository,
        )
        adapter = SearchResultAdapter(AudioRepository(this)) { clickedChar ->
            binding.bottomNav.selectedItemId = R.id.nav_lookup
            binding.searchBox.setText(clickedChar)
            binding.searchBox.setSelection(clickedChar.length)
        }

        binding.resultsList.layoutManager = LinearLayoutManager(this)
        binding.resultsList.adapter = adapter

        binding.searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                scheduleSearch(s?.toString().orEmpty())
            }
        })

        binding.settingsButton.setOnClickListener { showSettingsMenu() }
        binding.syncButton.setOnClickListener { runSync() }

        setupBottomNavAndMiningTab()

        if (settingsRepository.isFloatingBubbleEnabled() && Settings.canDrawOverlays(this)) {
            FloatingBubbleService.startOrStop(this, true)
            if (!AnkiAccessibilityService.isServiceEnabled(this)) {
                Toast.makeText(this, R.string.accessibility_permission_needed, Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }

        checkAccessAndMaybeSearch()
        handleInitialQueryIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInitialQueryIntent(intent)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (binding.bottomNav.selectedItemId == R.id.nav_mine) {
            binding.lookupContainer.visibility = View.GONE
            binding.mineContainer.visibility = View.VISIBLE
            refreshMiningDashboard()
        } else {
            binding.lookupContainer.visibility = View.VISIBLE
            binding.mineContainer.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.mineContainer.visibility == View.VISIBLE) {
            refreshMiningDashboard()
        }
    }

    private fun handleInitialQueryIntent(intent: Intent?) {
        val initialQuery = intent?.getStringExtra(EXTRA_INITIAL_QUERY)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_INITIAL_QUERY)
        binding.bottomNav.selectedItemId = R.id.nav_lookup
        binding.searchBox.setText(initialQuery)
        binding.searchBox.setSelection(initialQuery.length)
    }

    private fun setupBottomNavAndMiningTab() {
        recentPhotosAdapter = PhotoThumbnailAdapter(horizontalStrip = false) { photo ->
            val allIds = currentRecentPhotos.map { it.mediaStoreId }.toLongArray()
            startActivity(
                PhotoDetailActivity.newIntent(
                    context = this,
                    mediaStoreId = photo.mediaStoreId,
                    contentUri = photo.contentUri,
                    dateAdded = photo.dateAdded,
                    photoIds = allIds,
                ),
            )
        }
        binding.recentPhotosRecyclerView.layoutManager = GridLayoutManager(this, PREVIEW_ROW_LIMIT)
        binding.recentPhotosRecyclerView.adapter = recentPhotosAdapter

        recentWordsAdapter = ExtractedOccurrenceAdapter { row ->
            openFullListAndDetail(row)
        }
        binding.recentWordsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.recentWordsRecyclerView.adapter = recentWordsAdapter

        recentGrammarAdapter = ExtractedOccurrenceAdapter { row ->
            openFullListAndDetail(row)
        }
        binding.recentGrammarRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.recentGrammarRecyclerView.adapter = recentGrammarAdapter

        binding.scanButton.setOnClickListener {
            runPhotoScan()
        }
        binding.seeAllPhotosButton.setOnClickListener {
            startActivity(Intent(this, PhotoLibraryActivity::class.java))
        }
        binding.seeAllWordsButton.setOnClickListener {
            startActivity(ExtractedListActivity.newIntent(this, ItemType.WORD))
        }
        binding.seeAllGrammarButton.setOnClickListener {
            startActivity(ExtractedListActivity.newIntent(this, ItemType.GRAMMAR))
        }
        binding.claimsQueueButton.setOnClickListener {
            startActivity(Intent(this, ClaimsQueueActivity::class.java))
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_lookup -> {
                    binding.lookupContainer.visibility = View.VISIBLE
                    binding.mineContainer.visibility = View.GONE
                    true
                }
                R.id.nav_mine -> {
                    hideKeyboard()
                    binding.lookupContainer.visibility = View.GONE
                    binding.mineContainer.visibility = View.VISIBLE
                    refreshMiningDashboard()
                    true
                }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_lookup
    }

    private fun openFullListAndDetail(row: ExtractedOccurrenceRow) {
        val listIntent = ExtractedListActivity.newIntent(
            context = this,
            itemType = row.itemType,
            languageTag = row.languageTag,
        )
        val detailIntent = WordGrammarDetailActivity.newIntent(
            context = this,
            occurrenceId = row.occurrenceId,
            languageTag = row.languageTag,
            itemType = row.itemType,
            expression = row.expression,
        )
        startActivities(arrayOf(listIntent, detailIntent))
    }

    private fun refreshMiningDashboard() {
        miningExecutor.execute {
            val recentPhotos = miningRepository.getRecentPhotos(limit = PREVIEW_ROW_LIMIT)
            val recentWords = miningRepository.getActiveOccurrences(
                OccurrenceQuery(
                    languageTag = LanguageTag.JAPANESE,
                    itemType = ItemType.WORD,
                    sortOrder = OccurrenceSortOrder.NEWEST_FIRST,
                    limit = PREVIEW_ROW_LIMIT,
                ),
            )
            val recentGrammar = miningRepository.getActiveOccurrences(
                OccurrenceQuery(
                    languageTag = LanguageTag.JAPANESE,
                    itemType = ItemType.GRAMMAR,
                    sortOrder = OccurrenceSortOrder.NEWEST_FIRST,
                    limit = PREVIEW_ROW_LIMIT,
                ),
            )
            val pendingClaims = miningRepository.countPendingNameplateClaims()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                currentRecentPhotos = recentPhotos
                recentPhotosAdapter.submitList(recentPhotos)
                binding.emptyRecentPhotosText.visibility =
                    if (recentPhotos.isEmpty()) View.VISIBLE else View.GONE

                recentWordsAdapter.submitList(recentWords)
                binding.emptyRecentWordsText.visibility =
                    if (recentWords.isEmpty()) View.VISIBLE else View.GONE

                recentGrammarAdapter.submitList(recentGrammar)
                binding.emptyRecentGrammarText.visibility =
                    if (recentGrammar.isEmpty()) View.VISIBLE else View.GONE

                // claims-queue entry badge (B1): visible while rulings are pending.
                binding.claimsQueueCard.visibility = if (pendingClaims > 0) View.VISIBLE else View.GONE
                if (pendingClaims > 0) {
                    binding.claimsQueueCountText.text =
                        getString(R.string.claims_queue_pending_count, pendingClaims)
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        currentFocus?.windowToken?.let { token ->
            imm.hideSoftInputFromWindow(token, 0)
        }
    }

    private fun runSync() {
        binding.syncButton.isEnabled = false
        Toast.makeText(this, R.string.sync_running, Toast.LENGTH_SHORT).show()

        syncExecutor.execute {
            val outcome = runCatching { syncService.sync() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.syncButton.isEnabled = true
                outcome.fold(
                    onSuccess = { result ->
                        Toast.makeText(
                            this,
                            getString(R.string.sync_result, result.hanziCreated, result.wordsUpdated, result.hanziUpdated),
                            Toast.LENGTH_LONG,
                        ).show()
                        scheduleSearch(binding.searchBox.text?.toString().orEmpty())
                    },
                    onFailure = { error ->
                        Toast.makeText(this, getString(R.string.sync_failed, error.message), Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
    }

    // Iterates SearchSource.entries so a new source added later needs no UI changes here.
    private fun showSettingsMenu() {
        val popupBinding = PopupSettingsBinding.inflate(layoutInflater)
        var recoveryPopup: PopupWindow? = null
        for (source in SearchSource.entries) {
            val rowBinding = ItemSettingsToggleBinding.inflate(layoutInflater, popupBinding.togglesContainer, true)
            rowBinding.toggleLabelText.text = source.label
            rowBinding.toggleSwitch.isChecked = settingsRepository.isEnabled(source)
            rowBinding.toggleSwitch.setOnCheckedChangeListener { _, isChecked ->
                settingsRepository.setEnabled(source, isChecked)
                scheduleSearch(binding.searchBox.text?.toString().orEmpty())
            }
        }

        val bubbleRow = ItemSettingsToggleBinding.inflate(layoutInflater, popupBinding.togglesContainer, true)
        bubbleRow.toggleLabelText.text = getString(R.string.floating_bubble_toggle)

        val exportRow = ItemSettingsActionBinding.inflate(layoutInflater, popupBinding.togglesContainer, true)
        exportRow.actionLabel.setText(R.string.recovery_export_label)
        exportRow.root.setOnClickListener {
            recoveryPopup?.dismiss()
            if (!recoveryBusy) {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportBundleLauncher.launch("ankibiki-corpus-$ts.zip")
            }
        }
        val restoreRow = ItemSettingsActionBinding.inflate(layoutInflater, popupBinding.togglesContainer, true)
        restoreRow.actionLabel.setText(R.string.recovery_restore_label)
        restoreRow.root.setOnClickListener {
            recoveryPopup?.dismiss()
            if (!recoveryBusy) {
                restoreBundleLauncher.launch(arrayOf("application/zip"))
            }
        }
        // gold dashboard (roadmap B2): quality scoreboard entry, settings menu
        val dashboardRow = ItemSettingsActionBinding.inflate(layoutInflater, popupBinding.togglesContainer, true)
        dashboardRow.actionLabel.setText(R.string.dashboard_open)
        dashboardRow.root.setOnClickListener {
            recoveryPopup?.dismiss()
            startActivity(Intent(this, GoldDashboardActivity::class.java))
        }
        // a11y capture health (roadmap D1): read-only status row — a silent WebView
        // selection-capture regression must surface here, not as an "app is broken" debug day
        val captureRow = ItemSettingsActionBinding.inflate(layoutInflater, popupBinding.togglesContainer, true)
        captureRow.root.isClickable = false
        captureRow.root.setOnClickListener(null)
        val health = A11yCaptureHealth
        val time = if (health.lastSuccessAt > 0)
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(health.lastSuccessAt)) else null
        captureRow.actionLabel.text = buildString {
            when {
                health.triggerCount == 0 -> append(getString(R.string.capture_health_none))
                health.lastStatus == CaptureStatus.FRESH -> append(getString(R.string.capture_health_ok, health.freshCount, health.triggerCount))
                else -> append(getString(R.string.capture_health_degraded, health.freshCount, health.triggerCount))
            }
            if (time != null) append(" — ${getString(R.string.capture_health_last, time)}")
            health.ankiPackageVersion?.let { append(" · AnkiDroid $it") }
        }
        bubbleRow.toggleSwitch.isChecked =
            settingsRepository.isFloatingBubbleEnabled() && Settings.canDrawOverlays(this)
        bubbleRow.toggleSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked && !Settings.canDrawOverlays(this)) {
                buttonView.isChecked = false
                Toast.makeText(this, R.string.overlay_permission_needed, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
                return@setOnCheckedChangeListener
            }
            settingsRepository.setFloatingBubbleEnabled(isChecked)
            FloatingBubbleService.startOrStop(this, isChecked)
            if (isChecked && !AnkiAccessibilityService.isServiceEnabled(this)) {
                Toast.makeText(this, R.string.accessibility_permission_needed, Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }

        recoveryPopup = PopupWindow(
            popupBinding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true,
        ).apply { isOutsideTouchable = true }
        recoveryPopup?.showAsDropDown(binding.settingsButton)
    }

    private fun startRecoveryExport(dest: Uri) {
        if (recoveryBusy) return
        recoveryBusy = true
        Toast.makeText(this, R.string.recovery_export_progress, Toast.LENGTH_SHORT).show()
        scanExecutor.execute {
            val outcome = runCatching { recoveryManager.exportBundle(dest) }
            runOnUiThread {
                recoveryBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    onSuccess = { s ->
                        showRecoveryDialog(
                            getString(R.string.recovery_export_title),
                            getString(
                                R.string.recovery_export_done, s.suggestedName,
                                s.rows["scraped_photos"], s.rows["extracted_items"],
                                s.rows["item_occurrences"], s.photosIncluded, s.photosMissing,
                            ),
                        )
                    },
                    onFailure = { e ->
                        showRecoveryDialog(getString(R.string.recovery_error_title), e.message.orEmpty())
                    },
                )
            }
        }
    }

    private fun planRecoveryRestore(src: Uri, name: String?) {
        if (recoveryBusy) return
        recoveryBusy = true
        Toast.makeText(this, R.string.recovery_restore_progress, Toast.LENGTH_SHORT).show()
        scanExecutor.execute {
            val outcome = runCatching { recoveryManager.validateBundle(src, name) }
            runOnUiThread {
                recoveryBusy = false
                if (isFinishing || isDestroyed) {
                    recoveryManager.discardStaged()
                    return@runOnUiThread
                }
                outcome.fold(
                    onSuccess = { plan -> confirmRecoveryRestore(plan) },
                    onFailure = { e ->
                        recoveryManager.discardStaged()
                        showRecoveryDialog(getString(R.string.recovery_error_title), e.message.orEmpty())
                    },
                )
            }
        }
    }

    private fun confirmRecoveryRestore(plan: RecoveryManager.RestorePlan) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recovery_restore_title)
            .setMessage(
                getString(
                    R.string.recovery_restore_confirm, plan.bundleName, plan.userVersion,
                    plan.rows["scraped_photos"], plan.rows["extracted_items"],
                    plan.rows["item_occurrences"], plan.photosAvailable,
                ),
            )
            .setNegativeButton(R.string.recovery_cancel) { _, _ -> recoveryManager.discardStaged() }
            .setPositiveButton(R.string.recovery_restore_go) { _, _ -> applyRecoveryRestore(plan) }
            .show()
    }

    private fun applyRecoveryRestore(plan: RecoveryManager.RestorePlan) {
        if (recoveryBusy) return
        recoveryBusy = true
        Toast.makeText(this, R.string.recovery_restore_apply_progress, Toast.LENGTH_SHORT).show()
        scanExecutor.execute {
            // On success this kills the process and an alarm relaunches the app.
            val outcome = runCatching { recoveryManager.applyRestore(plan) }
            runOnUiThread {
                recoveryBusy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                outcome.fold(
                    onSuccess = {
                        // Fresh activity over the swapped database; CLEAR_TASK discards
                        // every stale MiningRepository. Android 15 blocks background
                        // relaunch tricks, so this happens from the foreground dialog.
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            startActivity(launch)
                        } else {
                            showRecoveryDialog(getString(R.string.recovery_export_title),
                                getString(R.string.recovery_restart_manually))
                        }
                    },
                    onFailure = { e ->
                        recoveryManager.discardStaged()
                        showRecoveryDialog(getString(R.string.recovery_error_title), e.message.orEmpty())
                    },
                )
            }
        }
    }

    private fun showRecoveryDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.recovery_dismiss, null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun runPhotoScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                MEDIA_PERMISSION_REQUEST_CODE,
            )
            return
        }

        binding.scanButton.isEnabled = false
        binding.scanButton.setText(R.string.scan_in_progress)

        scanExecutor.execute {
            val outcome = runCatching { miningPipelineRunner.runScanBatch() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.scanButton.isEnabled = true
                binding.scanButton.setText(R.string.btn_scan_photos)
                outcome.fold(
                    onSuccess = { summary ->
                        refreshMiningDashboard()
                        val msg = if (summary.scannedCount == 0) {
                            getString(R.string.scan_no_new_photos)
                        } else {
                            getString(
                                R.string.scan_complete_toast,
                                summary.scannedCount,
                                summary.keptCount,
                                summary.newOccurrencesCount,
                            )
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            getString(R.string.scan_failed_toast, error.message ?: "unknown"),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        syncExecutor.shutdown()
        adapter.stopAudio()
        super.onDestroy()
    }

    private fun scheduleSearch(query: String) {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        val runnable = Runnable { runSearch(query) }
        pendingSearch = runnable
        searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    private fun checkAccessAndMaybeSearch() {
        if (!repository.isAnkiDroidAvailable()) {
            showStatus(getString(R.string.status_ankidroid_missing))
            return
        }
        if (!hasReadWritePermission()) {
            showStatus(getString(R.string.status_permission_needed))
            requestPermissions(arrayOf(READ_WRITE_PERMISSION), PERMISSION_REQUEST_CODE)
            return
        }
        hideStatus()
    }

    private fun hasReadWritePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(this, READ_WRITE_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                hideStatus()
                scheduleSearch(binding.searchBox.text?.toString().orEmpty())
            } else {
                showStatus(getString(R.string.status_permission_denied))
            }
        } else if (requestCode == MEDIA_PERMISSION_REQUEST_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                runPhotoScan()
            }
        }
    }

    private fun runSearch(query: String) {
        if (!repository.isAnkiDroidAvailable() || !hasReadWritePermission()) return
        val generation = searchGeneration.incrementAndGet()
        if (query.isBlank()) {
            adapter.submitList(emptyList())
            return
        }
        val enabledSources = settingsRepository.enabledSources()
        searchExecutor.execute {
            val results = searchService.search(query, enabledSources)
            runOnUiThread {
                if (isFinishing || isDestroyed || searchGeneration.get() != generation) return@runOnUiThread
                adapter.submitList(results)
            }
        }
    }

    private fun showStatus(message: String) {
        binding.statusText.text = message
        binding.statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.statusText.visibility = View.GONE
    }

    companion object {
        const val EXTRA_INITIAL_QUERY = "extra_initial_query"

        fun newLookupIntent(context: Context, query: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_INITIAL_QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}
