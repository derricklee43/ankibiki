package com.derricklee.ankidict

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
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityMainBinding
import com.derricklee.ankidict.databinding.ItemSettingsToggleBinding
import com.derricklee.ankidict.databinding.PopupSettingsBinding
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val PERMISSION_REQUEST_CODE = 1
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
    private lateinit var adapter: SearchResultAdapter

    private lateinit var recentPhotosAdapter: PhotoThumbnailAdapter
    private lateinit var recentWordsAdapter: ExtractedOccurrenceAdapter
    private lateinit var recentGrammarAdapter: ExtractedOccurrenceAdapter

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val syncExecutor = Executors.newSingleThreadExecutor()
    private val miningExecutor = Executors.newSingleThreadExecutor()
    private val searchGeneration = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AnkiRepository(this)
        val dictionaryRepository = DictionaryRepository(this)
        searchService = SearchService(repository, dictionaryRepository)
        syncService = HanziWordSyncService(
            repository, dictionaryRepository, IdsRepository(this), HskRepository(this), ShinjitaiRepository(this),
        )
        settingsRepository = SettingsRepository(this)
        miningRepository = MiningRepository(this)
        adapter = SearchResultAdapter(AudioRepository(this))

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
            startActivity(
                PhotoDetailActivity.newIntent(
                    context = this,
                    mediaStoreId = photo.mediaStoreId,
                    contentUri = photo.contentUri,
                    dateAdded = photo.dateAdded,
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

        binding.seeAllPhotosButton.setOnClickListener {
            startActivity(Intent(this, PhotoLibraryActivity::class.java))
        }
        binding.seeAllWordsButton.setOnClickListener {
            startActivity(ExtractedListActivity.newIntent(this, ItemType.WORD))
        }
        binding.seeAllGrammarButton.setOnClickListener {
            startActivity(ExtractedListActivity.newIntent(this, ItemType.GRAMMAR))
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

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                recentPhotosAdapter.submitList(recentPhotos)
                binding.emptyRecentPhotosText.visibility =
                    if (recentPhotos.isEmpty()) View.VISIBLE else View.GONE

                recentWordsAdapter.submitList(recentWords)
                binding.emptyRecentWordsText.visibility =
                    if (recentWords.isEmpty()) View.VISIBLE else View.GONE

                recentGrammarAdapter.submitList(recentGrammar)
                binding.emptyRecentGrammarText.visibility =
                    if (recentGrammar.isEmpty()) View.VISIBLE else View.GONE
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

        PopupWindow(popupBinding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
            .apply { isOutsideTouchable = true }
            .showAsDropDown(binding.settingsButton)
    }

    override fun onDestroy() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchExecutor.shutdownNow()
        syncExecutor.shutdown()
        miningExecutor.shutdownNow()
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
    }
}
