package com.derricklee.ankidict

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.derricklee.ankidict.databinding.ActivityMainBinding
import com.derricklee.ankidict.databinding.ItemSettingsToggleBinding
import com.derricklee.ankidict.databinding.PopupSettingsBinding

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val PERMISSION_REQUEST_CODE = 1
private const val SEARCH_DEBOUNCE_MS = 250L
private const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AnkiRepository
    private lateinit var searchService: SearchService
    private lateinit var syncService: HanziWordSyncService
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var adapter: SearchResultAdapter

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val syncExecutor = Executors.newSingleThreadExecutor()
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

        checkAccessAndMaybeSearch()
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

        PopupWindow(popupBinding.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
            .apply { isOutsideTouchable = true }
            .showAsDropDown(binding.settingsButton)
    }

    override fun onDestroy() {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        searchExecutor.shutdownNow()
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
        binding.statusText.visibility = android.view.View.VISIBLE
    }

    private fun hideStatus() {
        binding.statusText.visibility = android.view.View.GONE
    }
}
