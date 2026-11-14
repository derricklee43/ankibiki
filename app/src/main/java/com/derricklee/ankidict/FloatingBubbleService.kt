package com.derricklee.ankidict

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.derricklee.ankidict.databinding.ActivityProcessTextBinding
import com.derricklee.ankidict.databinding.ItemProcessTextKanjiBinding
import kotlin.math.abs

private const val CHANNEL_ID = "ankibiki_floating_bubble"
private const val NOTIFICATION_ID = 1001
private const val ACTION_STOP_BUBBLE = "com.derricklee.ankidict.STOP_BUBBLE"
private const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

/**
 * Displays a small draggable "引" bubble over other apps (such as Google Lens, where Android's
 * ACTION_PROCESS_TEXT and Share menus aren't available). Copy any Japanese text to the clipboard,
 * tap the "引" bubble, and a floating Kanji meaning + story card pops up right over the current app.
 */
class FloatingBubbleService : Service() {

    private data class KanjiPopupItem(
        val character: String,
        val meaning: String,
        val story: String,
        val decompositionTree: DecompositionNode? = null,
    )

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var popupView: View? = null
    private var clipChangedListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val lookupExecutor = AppIo.io

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        startForegroundWithNotification()
        if (Settings.canDrawOverlays(this)) {
            showBubble()
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_BUBBLE) {
            SettingsRepository(this).setFloatingBubbleEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        dismissPopup()
        removeBubble()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.floating_bubble_toggle),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
            action = ACTION_STOP_BUBBLE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.bubble_notification_stop),
                    stopPendingIntent,
                ).build(),
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showBubble() {
        if (bubbleView != null) return

        val sizePx = dpToPx(52)
        val label = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_original)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(this@FloatingBubbleService, R.drawable.bg_floating_bubble)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dpToPx(6).toFloat()
        }

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - sizePx - dpToPx(12)
            y = resources.displayMetrics.heightPixels / 3
        }

        val touchSlop = dpToPx(8)
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        label.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    AnkiAccessibilityService.instance?.onBubbleTouchDown()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        dragged = true
                    }
                    if (dragged) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager.updateViewLayout(label, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        togglePopup()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(label, params)
        bubbleView = label
    }

    private fun removeBubble() {
        bubbleView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        bubbleView = null
    }

    private fun togglePopup() {
        if (popupView != null) {
            dismissPopup()
        } else {
            captureSelectionAndShowPopup()
        }
    }

    private fun captureSelectionAndShowPopup() {
        val a11y = AnkiAccessibilityService.instance
        if (a11y != null) {
            val (directText, triggeredCopyChip) = a11y.captureCurrentSelection()
            if (!directText.isNullOrBlank()) {
                showClipboardPopup(directText, triggeredCopy = false)
                return
            }
            if (triggeredCopyChip) {
                // Give AnkiDroid WebView / Google Lens a brief moment to write to the clipboard
                // while it still holds window focus, then open the popup.
                bubbleView?.postDelayed({ showClipboardPopup(null, triggeredCopy = true) }, 160L)
                return
            }
        }
        showClipboardPopup(null, triggeredCopy = false)
    }

    private fun showClipboardPopup(preCapturedText: String?, triggeredCopy: Boolean) {
        dismissPopup()

        val themedContext = ContextThemeWrapper(this, R.style.Theme_AnkiDict)
        val binding = ActivityProcessTextBinding.inflate(LayoutInflater.from(themedContext))

        // Wrap in a full-screen translucent backdrop so tapping outside closes the popup.
        val backdrop = object : FrameLayout(themedContext) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                    dismissPopup()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { dismissPopup() }
        }

        val cardWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        val maxCardHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
        val cardParams = FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        binding.root.isClickable = true
        binding.kanjiPopupList.layoutManager = LinearLayoutManager(themedContext)
        binding.closeButton.setOnClickListener { dismissPopup() }
        backdrop.addView(binding.root, cardParams)

        // Do NOT set FLAG_NOT_FOCUSABLE here: receiving window focus is what allows this overlay
        // to read ClipboardManager.primaryClip on Android 10+ and handle the Back key.
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            dimAmount = 0.25f
        }

        var lastResolvedText: String? = null
        fun refreshFromSources() {
            if (popupView !== backdrop) return
            val resolved = resolveBestText(preCapturedText, triggeredCopy)
            if (lastResolvedText != null && (resolved == lastResolvedText || resolved.isEmpty())) {
                return
            }
            lastResolvedText = resolved
            populatePopup(binding, resolved, maxCardHeight)
        }

        if (!preCapturedText.isNullOrBlank() && preCapturedText.any(::isCjkCharacter)) {
            refreshFromSources()
        } else {
            val clipboard = getSystemService(ClipboardManager::class.java)
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                backdrop.post { refreshFromSources() }
            }
            clipChangedListener = listener
            runCatching { clipboard?.addPrimaryClipChangedListener(listener) }

            backdrop.viewTreeObserver.addOnWindowFocusChangeListener(object : ViewTreeObserver.OnWindowFocusChangeListener {
                override fun onWindowFocusChanged(hasFocus: Boolean) {
                    if (hasFocus) {
                        backdrop.viewTreeObserver.removeOnWindowFocusChangeListener(this)
                        refreshFromSources()
                        // Retry briefly after focus in case an async WebView copy completes just after focus arrives.
                        backdrop.postDelayed({ refreshFromSources() }, 160L)
                    }
                }
            })
            backdrop.postDelayed({ refreshFromSources() }, 250L)
        }

        windowManager.addView(backdrop, overlayParams)
        popupView = backdrop
    }

    private fun resolveBestText(preCapturedText: String?, triggeredCopy: Boolean): String {
        if (!preCapturedText.isNullOrBlank() && preCapturedText.any(::isCjkCharacter)) {
            return preCapturedText
        }
        val (clipText, clipTimestamp) = readClipboardTextWithTimestamp()
        val cjkClip = clipText.takeIf { it.any(::isCjkCharacter) }
        val (a11yText, a11yTimestamp) = AnkiAccessibilityService.getLastSelectionWithTimestamp()
        val cjkA11y = a11yText?.takeIf { it.any(::isCjkCharacter) }

        // D1 capture-health: classify the a11y selection against the trigger clock BEFORE
        // source arbitration decides the answer — the health ledger tracks the a11y path,
        // not whether some other source happened to rescue this particular lookup.
        A11yCaptureHealth.recordTrigger(System.currentTimeMillis(), a11yTimestamp)
        if (A11yCaptureHealth.ankiPackageVersion == null) {
            runCatching {
                packageManager.getPackageInfo("com.ichi2.anki", 0).versionName
            }.getOrNull()?.let { A11yCaptureHealth.noteAnkiVersion(it) }
        }

        return when {
            cjkClip != null && cjkA11y != null -> {
                if (triggeredCopy && clipTimestamp >= a11yTimestamp - 2000L) {
                    cjkClip
                } else if (a11yTimestamp > clipTimestamp) {
                    cjkA11y
                } else {
                    cjkClip
                }
            }
            cjkClip != null -> cjkClip
            cjkA11y != null -> cjkA11y
            else -> ""
        }
    }

    private fun readClipboardTextWithTimestamp(): Pair<String, Long> {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return "" to 0L
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return "" to 0L
        if (clip.itemCount == 0) return "" to 0L
        val text = clip.getItemAt(0).coerceToText(this)?.toString().orEmpty()
        val timestamp = clip.description?.timestamp ?: 0L
        return text to timestamp
    }

    private fun populatePopup(binding: ActivityProcessTextBinding, text: String, maxHeightPx: Int) {
        val characters = text.filter(::isCjkCharacter).map { it.toString() }.distinct()
        if (characters.isEmpty()) {
            if (!AnkiAccessibilityService.isServiceEnabled(this)) {
                showStatus(binding, getString(R.string.process_text_enable_accessibility))
                binding.popupStatusText.setOnClickListener {
                    dismissPopup()
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }
                }
            } else {
                binding.popupStatusText.setOnClickListener(null)
                showStatus(binding, getString(R.string.process_text_no_kanji))
            }
            return
        }

        val repository = AnkiRepository(this)
        val canReadAnki = repository.isAnkiDroidAvailable() &&
            ContextCompat.checkSelfPermission(this, READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

        binding.popupStatusText.visibility = View.GONE
        binding.kanjiPopupList.visibility = View.VISIBLE

        val dictRepo = DictionaryRepository(this)
        val idsRepo = IdsRepository(this)
        val heisigRepo = HeisigRadicalRepository(
            idsRepository = idsRepo,
            dictionaryRepository = dictRepo,
            ankiRepository = if (canReadAnki) repository else null,
        )

        lookupExecutor.execute {
            val items = characters.map { ch ->
                val card = if (canReadAnki) {
                    repository.findNoteByCharacter(ch, NIHONGO_SHARK_KANJI_MODEL_ID)?.toCard()
                } else {
                    null
                }
                val radicalEntry = heisigRepo.lookupRadical(ch)
                val resolvedMeaning = card?.meaning?.takeIf { it.isNotBlank() }
                    ?: radicalEntry?.heisigKeyword?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.process_text_not_in_deck)
                val resolvedStory = card?.mnemonic?.takeIf { it.isNotBlank() }
                    ?: radicalEntry?.radicalMeaning.orEmpty()
                val tree = heisigRepo.buildDecompositionTree(ch)
                KanjiPopupItem(
                    character = ch,
                    meaning = resolvedMeaning,
                    story = resolvedStory,
                    decompositionTree = tree,
                )
            }
            binding.root.post {
                binding.kanjiPopupList.adapter = KanjiPopupAdapter(items) { clickedChar ->
                    populatePopup(binding, clickedChar, maxHeightPx)
                }
                binding.kanjiPopupList.post {
                    if (binding.root.height > maxHeightPx) {
                        val lp = binding.root.layoutParams
                        lp.height = maxHeightPx
                        binding.root.layoutParams = lp
                    }
                }
            }
        }
    }

    private fun showStatus(binding: ActivityProcessTextBinding, message: String) {
        binding.popupStatusText.text = message
        binding.popupStatusText.visibility = View.VISIBLE
        binding.kanjiPopupList.visibility = View.GONE
    }

    private fun dismissPopup() {
        clipChangedListener?.let { listener ->
            val clipboard = getSystemService(ClipboardManager::class.java)
            runCatching { clipboard?.removePrimaryClipChangedListener(listener) }
        }
        clipChangedListener = null
        popupView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        popupView = null
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private class KanjiPopupAdapter(
        private val items: List<KanjiPopupItem>,
        private val onCharacterLinkClick: (String) -> Unit,
    ) : RecyclerView.Adapter<KanjiPopupAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemProcessTextKanjiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding, onCharacterLinkClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(
            private val binding: ItemProcessTextKanjiBinding,
            private val onCharacterLinkClick: (String) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: KanjiPopupItem) {
                binding.kanjiCharText.text = item.character
                binding.kanjiMeaningText.text = item.meaning
                if (item.story.isBlank()) {
                    binding.kanjiStoryText.visibility = View.GONE
                } else {
                    binding.kanjiStoryText.visibility = View.VISIBLE
                    binding.kanjiStoryText.text = item.story
                }
                val tree = item.decompositionTree
                if (tree != null && tree.children.isNotEmpty()) {
                    binding.kanjiDecompositionTree.visibility = View.VISIBLE
                    binding.kanjiDecompositionTree.renderTrees(listOf(tree), onCharacterLinkClick)
                } else {
                    binding.kanjiDecompositionTree.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        fun startOrStop(context: Context, enabled: Boolean) {
            val intent = Intent(context, FloatingBubbleService::class.java)
            if (enabled && Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
