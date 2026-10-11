package com.roboyi.rtspdemo

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.Gravity
import android.view.WindowManager
import android.view.ViewConfiguration
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.roboyi.rtsp.CameraDetails
import com.roboyi.rtsp.CameraDevice
import com.roboyi.rtsp.CameraSource
import com.roboyi.rtsp.CameraSearchHandle
import com.roboyi.rtsp.CameraSearchOptions
import com.roboyi.rtsp.CameraSearchPhase
import com.roboyi.rtsp.CameraSearchEnd
import com.roboyi.rtsp.CameraSearchUpdate
import com.roboyi.rtsp.DecoderMode
import com.roboyi.rtsp.DigitalZoom
import com.roboyi.rtsp.OnvifCredentials
import com.roboyi.rtsp.OnvifCommandResult
import com.roboyi.rtsp.OnvifControlSnapshot
import com.roboyi.rtsp.OnvifController
import com.roboyi.rtsp.OnvifFloatRange
import com.roboyi.rtsp.OnvifImagingConfiguration
import com.roboyi.rtsp.OnvifImagingSettings
import com.roboyi.rtsp.OnvifIntRange
import com.roboyi.rtsp.OnvifMediaProfile
import com.roboyi.rtsp.OnvifSearcher
import com.roboyi.rtsp.OnvifVideoEncoderConfiguration
import com.roboyi.rtsp.RoboYiPlayer
import com.roboyi.rtsp.RoboYiPlayerFragment
import com.roboyi.rtsp.RecordingAudioConfig
import com.roboyi.rtsp.RecordingAudioSource
import com.roboyi.rtsp.VideoCropRegion
import com.roboyi.rtsp.WatermarkSpec
import com.roboyi.rtsp.license.RoboYiLicenseException
import com.roboyi.rtspdemo.domain.ConnectionEntry
import com.roboyi.rtspdemo.domain.RtspAddress
import com.roboyi.rtspdemo.domain.WatermarkPosition
import com.roboyi.rtspdemo.domain.WatermarkTarget
import com.roboyi.rtspdemo.data.InspectionXml
import com.roboyi.rtspdemo.data.InspectionStore
import com.roboyi.rtspdemo.data.InspectionSession
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.joinAll

/** 页面负责交互和播放器生命周期；持久化、文件规则和设备发现分别交给独立组件。 */
class MainActivity : AppCompatActivity() {
    private enum class CaptureFilter { ALL, VIDEO, IMAGE }

    private data class PreviewSlot(
        val container: FrameLayout,
        val tile: FrameLayout,
        val label: TextView,
        val selectionOverlay: View,
        val focusIndicator: View,
        val fragment: RoboYiPlayerFragment,
        var url: String = "",
        var playback: PlaybackPhase = PlaybackPhase.STOPPED,
        var streamInfo: String = "",
        var fillMode: Boolean = false,
        var digitalZoomFactor: Float = DigitalZoom.MIN_FACTOR,
        var cropRegion: VideoCropRegion = VideoCropRegion.FULL_FRAME
    )

    private lateinit var viewModel: MainViewModel
    private val inspection by lazy { InspectionRecording(applicationContext) {
        val size = Regex("""^(\d+)x(\d+)""").find(player?.streamInfoText().orEmpty())
        (size?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1280) to
            (size?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 } ?: 720)
    } }
    private val inspectionUi by lazy { InspectionUi(this) { updateCurrentWatermark() } }
    private var pendingFolderAction: (() -> Unit)? = null
    private var recordingEndAction: (() -> Unit)? = null
    private var lastInspectionSpec: WatermarkSpec? = null
    private val pendingDefectImages = mutableListOf<Pair<Long, () -> Unit>>()
    private val inspectionJobs = mutableSetOf<kotlinx.coroutines.Job>()
    private fun launchInspectionJob(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        val job = lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY, block = block)
        inspectionJobs += job
        job.invokeOnCompletion { inspectionJobs.remove(job) }
        job.start()
    }
    private fun finishDefectImages(force: Boolean = false) {
        val ready = pendingDefectImages.filter { force || android.os.SystemClock.elapsedRealtime() >= it.first }
        pendingDefectImages.removeAll(ready.toSet())
        ready.forEach { it.second() }
    }
    private val inspectionTick = object : Runnable {
        override fun run() {
            if (isDestroyed || !::viewModel.isInitialized) return
            finishDefectImages()
            if (inspection.session != null && viewModel.state.recordingFile != null) {
                val remaining = inspection.remainingMs()
                if (remaining != null) {
                    recordButton.text = "片尾 ${(remaining + 999) / 1000} 秒"
                    if (remaining <= 0L) {
                        val done = recordingEndAction; recordingEndAction = null
                        stopRecording(finishNow = true, onComplete = done)
                    }
                }
                val spec = watermarkSpec(WatermarkTarget.RECORDING)
                if (spec != lastInspectionSpec) { player?.updateWatermark(spec); lastInspectionSpec = spec }
            } else if (player?.isPlaying() == true) {
                val spec = watermarkSpec(WatermarkTarget.PREVIEW)
                if (spec != lastInspectionSpec) { player?.updateWatermark(spec); lastInspectionSpec = spec }
            }
            handler.postDelayed(this, 200L)
        }
    }
    private val onvif by lazy { OnvifDiscovery(applicationContext) }
    private var cameraSearchHandle: CameraSearchHandle? = null
    private val handler = Handler(Looper.getMainLooper())
    // 只在前台轮询状态；SDK 的播放与录像回调仍由播放器管理。
    private val statusTick = object : Runnable {
        override fun run() {
            updateHeaderClock()
            refreshPlayerStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    private lateinit var licenseStatus: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerTime: TextView
    private lateinit var headerLatency: TextView
    private lateinit var urlInput: EditText
    private lateinit var placeholder: TextView
    private lateinit var streamInfo: TextView
    private lateinit var pauseButton: Button
    private lateinit var recordButton: Button
    private lateinit var fitButton: Button
    private lateinit var videoCropButton: Button
    private lateinit var digitalZoomButton: Button
    private lateinit var recordingAudioButton: Button
    private lateinit var videoContainer: FrameLayout
    private lateinit var playerGrid: GridLayout
    private lateinit var fullscreenButton: Button
    private lateinit var closePreviewButton: Button
    private var digitalZoomPopup: PopupWindow? = null
    private lateinit var normalContent: View
    private lateinit var fullscreenHost: FrameLayout
    private var fullscreenPreviewSlot: PreviewSlot? = null
    private var fullscreenPreviewIndex = -1
    private var fullscreenPreviewLayoutParams: ViewGroup.LayoutParams? = null
    private var fullscreenButtonIndex = -1
    private var fullscreenButtonParent: ViewGroup? = null
    private var fullscreenButtonLayoutParams: ViewGroup.LayoutParams? = null
    private var isFullscreen = false
    private var previousSystemUiVisibility = 0
    private lateinit var historyPanel: View
    private lateinit var onvifPanel: View
    private lateinit var capturesPanel: View
    private lateinit var sidebarBase: View
    private lateinit var onvifControlSurface: View
    private lateinit var onvifControlTitle: TextView
    private lateinit var onvifControlStatus: TextView
    private lateinit var onvifControlContent: LinearLayout
    private lateinit var onvifControlRefresh: Button
    private lateinit var historyList: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var capturesList: GridLayout
    private lateinit var deleteCapturesButton: Button
    private lateinit var onvifHint: TextView
    private lateinit var playerWorkspace: View
    private lateinit var deviceResultsPanel: View
    private lateinit var fileOpenButton: Button
    private var fileDetailsPopup: PopupWindow? = null
    private val latencyMonitor = RtspLatencyMonitor()
    private var selectedCapture: File? = null
    private val selectedCapturePaths = linkedSetOf<String>()
    private lateinit var selectAllCapturesButton: Button
    private lateinit var shareCapturesButton: Button
    private lateinit var fileSelectionCount: TextView
    private lateinit var captureFilterAllButton: Button
    private lateinit var captureFilterVideoButton: Button
    private lateinit var captureFilterImageButton: Button
    private lateinit var captureSortButton: Button
    private var captureFilter = CaptureFilter.ALL
    private var capturesNewestFirst = true
    private var lastLatencyProbeAtMs = 0L
    private var pendingAutoPlayUrl: String? = null
    private var onvifControlRequestId = 0
    private var player: RoboYiPlayerFragment? = null
    private val previewSlots = mutableListOf<PreviewSlot>()
    private var selectedPreviewSlot: PreviewSlot? = null
    private var remoteFocusTouchSlot: PreviewSlot? = null
    private var remoteFocusDownX = 0f
    private var remoteFocusDownY = 0f
    private var remoteFocusTouchMoved = false
    private var bitmapWatermark: Bitmap? = null
    private var recordingAudioSource: RecordingAudioSource = RecordingAudioSource.Rtsp
    private var microphoneCapture: MicrophoneRecordingAudioCapture? = null
    private var recordingStopInProgress = false
    private var pendingLegacyStorageAction: (() -> Unit)? = null
    private var renderedState: MainUiState? = null
    private var discoveryRequestId = 0
    private var detailRequestId = 0
    private var deviceResultsCollapsed = false
    private var deviceSearchVisible = false
    private val collapsedOverlays = mutableSetOf<Int>()
    private var playbackOverlaysHidden = false
    private var playbackOverlayAutoCompacted = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val staged = File.createTempFile("watermark-", ".tmp", cacheDir)
            try {
                contentResolver.openInputStream(uri)?.use { source ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            check(count > 0) { getString(R.string.image_read_failed) }
                            total += count
                            check(total <= 10L * 1024 * 1024) { getString(R.string.image_too_large) }
                            output.write(buffer, 0, count)
                        }
                    }
                } ?: error(getString(R.string.image_read_failed))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(staged.absolutePath, bounds)
                check(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
                    bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000L) {
                    getString(R.string.image_invalid_dimensions)
                }
                val target = File(filesDir, "demo-watermark.png")
                staged.inputStream().use { source -> target.outputStream().use { source.copyTo(it) } }
                viewModel.setWatermark(viewModel.state.watermark.copy(imagePath = target.absolutePath))
                updateCurrentWatermark()
            } finally {
                staged.delete()
            }
        }.onSuccess { showMessage(getString(R.string.watermark_image_updated)) }
            .onFailure { showMessage(getString(R.string.image_import_failed, safeError(it))) }
    }

    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecordingIfReady()
        } else {
            showMessage(getString(R.string.recording_audio_microphone_permission_required))
        }
    }

    private val requestPublicStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingLegacyStorageAction
        pendingLegacyStorageAction = null
        if (granted) {
            pending?.invoke()
        } else {
            showMessage(getString(R.string.public_storage_permission_required))
        }
    }

    private val requestInspectionFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pending = pendingFolderAction; pendingFolderAction = null
        if (uri != null) runCatching { InspectionXml.acceptFolder(this, uri) }
            .onSuccess { pending?.invoke() }
            .onFailure { showMessage(it.message ?: "目录授权失败") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPlaybackSystemBars(hideNavigation = false)
        setContentView(R.layout.activity_main)
        bindViews()
        setupCollapsibleOverlays()
        setupFullscreen()
        viewModel = ViewModelProvider(this, MainViewModel.Factory(applicationContext))[MainViewModel::class.java]
        urlInput.setText(viewModel.lastUrl)
        if (savedInstanceState != null) {
            supportFragmentManager.fragments.filterIsInstance<RoboYiPlayerFragment>().forEach {
                supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss()
            }
            viewModel.onHostRecreated()
        }
        viewModel.uiState.observe(this, ::renderState)

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.projects_tab).setOnClickListener { showProjectManager() }
        findViewById<ImageButton>(R.id.about_button).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.connect_button).setOnClickListener { connect() }
        findViewById<Button>(R.id.snapshot_button).setOnClickListener { snapshot() }
        findViewById<Button>(R.id.watermark_button).setOnClickListener { showWatermarkModeDialog() }
        recordingAudioButton.setOnClickListener { showRecordingAudioSettings() }
        findViewById<Button>(R.id.clear_history).setOnClickListener { confirmClearHistory() }
        findViewById<View>(R.id.history_dropdown_button).setOnClickListener { showRtspHistoryDropdown() }
        findViewById<Button>(R.id.search_button).setOnClickListener { searchOnvif() }
        findViewById<Button>(R.id.history_tab).setOnClickListener {
            showPrimarySection(MainSection.PLAYBACK)
        }
        findViewById<View>(R.id.device_search_reveal).setOnClickListener { showDeviceSearchOverlay() }
        findViewById<View>(R.id.connection_reveal).setOnClickListener {
            deviceSearchVisible = false
            collapsedOverlays += R.id.sidebar_panel_container
            collapsedOverlays -= R.id.connection_panel
            applyWorkspaceVisibility(viewModel.state)
        }
        findViewById<Button>(R.id.captures_tab).setOnClickListener {
            showPrimarySection(MainSection.FILES)
        }
        findViewById<Button>(R.id.camera_push_button).setOnClickListener {
            AppNavigation.openPush(this)
        }
        findViewById<Button>(R.id.refresh_captures).setOnClickListener { viewModel.refreshCaptures() }
        selectAllCapturesButton.setOnClickListener { toggleSelectAllCaptures() }
        deleteCapturesButton.setOnClickListener { confirmDeleteSelectedCaptures() }
        fileOpenButton.setOnClickListener { selectedCapture?.let(::openCapture) }
        shareCapturesButton.setOnClickListener { shareSelectedCaptures() }
        captureFilterAllButton.setOnClickListener { setCaptureFilter(CaptureFilter.ALL) }
        captureFilterVideoButton.setOnClickListener { setCaptureFilter(CaptureFilter.VIDEO) }
        captureFilterImageButton.setOnClickListener { setCaptureFilter(CaptureFilter.IMAGE) }
        captureSortButton.setOnClickListener {
            capturesNewestFirst = !capturesNewestFirst
            renderCaptures(viewModel.state)
        }
        pauseButton.setOnClickListener { toggleRecordingPause() }
        recordButton.setOnClickListener { toggleRecording() }
        fitButton.setOnClickListener {
            val fillMode = viewModel.toggleFillMode()
            player?.setFillMode(fillMode)
            selectedPreviewSlot?.fillMode = fillMode
        }
        videoCropButton.setOnClickListener { showVideoCropSelector() }
        digitalZoomButton.setOnClickListener { showDigitalZoomControls() }
        closePreviewButton.setOnClickListener { selectedPreviewSlot?.let(::requestClosePreview) }

        showDataNoticeIfNeeded()
        handleNavigationIntent(intent)
        viewModel.refreshCaptures()
        refreshPlayerStatus()
        renderRecordingAudioSource()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeRemoteFocusTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        applyPlaybackSystemBars(hideNavigation = isFullscreen)
        viewModel.refreshCaptures()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyPlaybackSystemBars(hideNavigation = isFullscreen)
    }

    private fun applyPlaybackSystemBars(hideNavigation: Boolean) {
        // 播放页始终占用状态栏区域；创建/移除多个 Fragment 时也不允许系统恢复顶部栏。
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
            if (hideNavigation) {
                hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
        handler.removeCallbacks(inspectionTick)
        handler.post(inspectionTick)
    }

    override fun onStop() {
        handler.removeCallbacks(statusTick)
        // Recording watermarks continue while a system picker or another Activity covers this one.
        if (inspection.session == null) handler.removeCallbacks(inspectionTick)
        super.onStop()
    }

    override fun onDestroy() {
        discoveryRequestId++
        cameraSearchHandle?.cancel()
        cameraSearchHandle = null
        handler.removeCallbacks(inspectionTick)
        digitalZoomPopup?.dismiss()
        fileDetailsPopup?.dismiss()
        stopMicrophoneCapture()
        latencyMonitor.close()
        previewSlots.forEach { slot ->
            slot.fragment.onFirstFrameRendered = null
            if (isFinishing) runCatching { slot.fragment.stop() }
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavigationIntent(intent)
    }

    /** 统一处理顶部导航和推流测试播放，不让其他 Activity 直接操作本页视图。 */
    private fun handleNavigationIntent(intent: Intent) {
        intent.getStringExtra(AppNavigation.EXTRA_SECTION)
            ?.let { runCatching { MainSection.valueOf(it) }.getOrNull() }
            ?.let(viewModel::showSection)

        intent.getStringExtra(AppNavigation.EXTRA_RTSP_URL)?.let { url ->
            urlInput.setText(url)
            if (intent.getBooleanExtra(AppNavigation.EXTRA_AUTO_PLAY, false)) {
                pendingAutoPlayUrl = url
                consumePendingAutoPlay()
            }
        }
        when (intent.getStringExtra(AppNavigation.EXTRA_DIALOG)) {
            "projects" -> window.decorView.post { showProjectManager() }
            AppNavigation.DIALOG_SETTINGS -> window.decorView.post { showSettings() }
            AppNavigation.DIALOG_ABOUT -> window.decorView.post { showAbout() }
        }
        intent.removeExtra(AppNavigation.EXTRA_RTSP_URL)
        intent.removeExtra(AppNavigation.EXTRA_AUTO_PLAY)
        intent.removeExtra(AppNavigation.EXTRA_DIALOG)
    }

    private fun consumePendingAutoPlay() {
        val url = pendingAutoPlayUrl ?: return
        if (!viewModel.state.licensed) return
        pendingAutoPlayUrl = null
        viewModel.showSection(MainSection.PLAYBACK)
        urlInput.setText(url)
        urlInput.post { connect() }
    }

    private fun updateHeaderClock() {
        headerTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    }

    private fun bindViews() {
        licenseStatus = findViewById(R.id.license_status)
        headerTitle = findViewById(R.id.header_title)
        headerTime = findViewById(R.id.header_time)
        headerLatency = findViewById(R.id.header_latency)
        urlInput = findViewById(R.id.rtsp_url)
        placeholder = findViewById(R.id.preview_placeholder)
        streamInfo = findViewById(R.id.stream_info)
        pauseButton = findViewById(R.id.pause_button)
        recordButton = findViewById(R.id.record_button)
        fitButton = findViewById(R.id.fit_button)
        videoCropButton = findViewById(R.id.video_crop_button)
        digitalZoomButton = findViewById(R.id.digital_zoom_button)
        recordingAudioButton = findViewById(R.id.recording_audio_button)
        videoContainer = findViewById(R.id.video_container)
        playerGrid = findViewById(R.id.player_grid)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        closePreviewButton = findViewById(R.id.close_preview_button)
        historyPanel = findViewById(R.id.history_panel)
        onvifPanel = findViewById(R.id.onvif_panel)
        capturesPanel = findViewById(R.id.captures_panel)
        sidebarBase = findViewById(R.id.sidebar_base)
        onvifControlSurface = findViewById(R.id.onvif_control_surface)
        onvifControlTitle = findViewById(R.id.onvif_control_title)
        onvifControlStatus = findViewById(R.id.onvif_control_status)
        onvifControlContent = findViewById(R.id.onvif_control_content)
        onvifControlRefresh = findViewById(R.id.onvif_control_refresh)
        findViewById<Button>(R.id.onvif_control_close).setOnClickListener { closeOnvifControls() }
        sidebarBase.visibility = View.VISIBLE
        onvifControlSurface.visibility = View.GONE
        historyList = findViewById(R.id.history_list)
        deviceList = findViewById(R.id.device_list)
        capturesList = findViewById(R.id.captures_list)
        deleteCapturesButton = findViewById(R.id.delete_captures)
        selectAllCapturesButton = findViewById(R.id.select_all_captures)
        shareCapturesButton = findViewById(R.id.share_captures)
        fileSelectionCount = findViewById(R.id.file_selection_count)
        captureFilterAllButton = findViewById(R.id.capture_filter_all)
        captureFilterVideoButton = findViewById(R.id.capture_filter_video)
        captureFilterImageButton = findViewById(R.id.capture_filter_image)
        captureSortButton = findViewById(R.id.capture_sort_button)
        onvifHint = findViewById(R.id.onvif_hint)
        playerWorkspace = findViewById(R.id.player_workspace)
        deviceResultsPanel = findViewById(R.id.device_results_panel)
        fileOpenButton = findViewById(R.id.file_open_button)
    }

    private fun setupCollapsibleOverlays() {
        collapsedOverlays += R.id.connection_panel
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            findViewById<View>(R.id.connection_panel).layoutParams =
                findViewById<View>(R.id.connection_panel).layoutParams.apply {
                    width = minOf(dp(560), resources.displayMetrics.widthPixels - dp(32))
                }
        }
        bindCollapsibleOverlay(R.id.connection_panel, R.id.connection_collapse, R.id.connection_reveal)
        findViewById<View>(R.id.controls_collapse).setOnClickListener {
            playbackOverlaysHidden = true
            digitalZoomPopup?.dismiss()
            applyWorkspaceVisibility(viewModel.state)
        }
        findViewById<View>(R.id.controls_reveal).setOnClickListener {
            playbackOverlaysHidden = false
            applyWorkspaceVisibility(viewModel.state)
        }
        bindCollapsibleOverlay(R.id.sidebar_panel_container, R.id.sidebar_collapse, R.id.sidebar_reveal)
        bindCollapsibleOverlay(R.id.app_header, R.id.header_collapse, R.id.header_reveal)

        findViewById<View>(R.id.device_results_collapse).setOnClickListener {
            deviceResultsCollapsed = true
            applyWorkspaceVisibility(viewModel.state)
        }
        findViewById<View>(R.id.device_results_reveal).setOnClickListener {
            deviceResultsCollapsed = false
            applyWorkspaceVisibility(viewModel.state)
        }
    }

    private fun bindCollapsibleOverlay(panelId: Int, collapseId: Int, revealId: Int) {
        findViewById<View>(collapseId).setOnClickListener {
            collapsedOverlays += panelId
            applyWorkspaceVisibility(viewModel.state)
        }
        findViewById<View>(revealId).setOnClickListener {
            collapsedOverlays -= panelId
            applyWorkspaceVisibility(viewModel.state)
        }
    }

    private fun showPrimarySection(section: MainSection) {
        if (section == MainSection.DISCOVERY) {
            viewModel.showSection(MainSection.PLAYBACK)
            showDeviceSearchOverlay()
            return
        }
        when (section) {
            MainSection.PLAYBACK -> {
                deviceSearchVisible = false
                collapsedOverlays += R.id.sidebar_panel_container
                collapsedOverlays -= R.id.controls_panel
            }
            MainSection.DISCOVERY -> {
                Unit
            }
            MainSection.FILES -> {
                Unit
            }
        }
        viewModel.showSection(section)
        if (section == MainSection.FILES) viewModel.refreshCaptures()
        applyWorkspaceVisibility(viewModel.state)
    }

    private fun showDeviceSearchOverlay() {
        if (viewModel.state.section == MainSection.FILES) {
            viewModel.showSection(MainSection.PLAYBACK)
        }
        deviceSearchVisible = true
        deviceResultsCollapsed = false
        collapsedOverlays += R.id.connection_panel
        collapsedOverlays -= R.id.sidebar_panel_container
        sidebarBase.visibility = View.VISIBLE
        onvifControlSurface.visibility = View.GONE
        applyWorkspaceVisibility(viewModel.state)
    }

    private fun renderCollapsibleOverlay(panelId: Int, revealId: Int, relevant: Boolean) {
        val collapsed = panelId in collapsedOverlays
        findViewById<View>(panelId).visibility = if (relevant && !collapsed) View.VISIBLE else View.GONE
        findViewById<View>(revealId).visibility = if (relevant && collapsed) View.VISIBLE else View.GONE
    }

    private fun applyWorkspaceVisibility(state: MainUiState) {
        val filesVisible = state.section == MainSection.FILES
        val playbackVisible = !filesVisible
        val searchPanelVisible = playbackVisible &&
            (deviceSearchVisible || state.section == MainSection.DISCOVERY)
        val resultsVisible = playbackVisible && deviceSearchVisible &&
            (state.onvifSearching || state.onvifSearchCompleted || state.onvifDevices.isNotEmpty())

        playerWorkspace.visibility = if (filesVisible) View.GONE else View.VISIBLE
        capturesPanel.visibility = if (filesVisible) View.VISIBLE else View.GONE
        historyPanel.visibility = View.GONE
        onvifPanel.visibility = if (searchPanelVisible) View.VISIBLE else View.GONE

        headerTitle.text = getString(if (filesVisible) R.string.nav_files else R.string.nav_playback)
        headerTitle.setCompoundDrawablesWithIntrinsicBounds(
            if (filesVisible) R.drawable.ic_nav_files else R.drawable.ic_nav_playback,
            0, 0, 0
        )
        if (filesVisible) {
            streamInfo.text = getString(R.string.captures_summary, state.captures.size)
        }

        renderCollapsibleOverlay(R.id.connection_panel, R.id.connection_reveal, playbackVisible)
        renderCollapsibleOverlay(R.id.controls_panel, R.id.controls_reveal, playbackVisible)
        renderCollapsibleOverlay(
            R.id.sidebar_panel_container,
            R.id.sidebar_reveal,
            searchPanelVisible
        )
        if (playbackVisible) findViewById<View>(R.id.sidebar_reveal).visibility = View.GONE
        renderCollapsibleOverlay(R.id.app_header, R.id.header_reveal, true)
        findViewById<View>(R.id.connection_quick_actions).visibility =
            if (playbackVisible && R.id.connection_panel in collapsedOverlays) View.VISIBLE else View.GONE
        headerLatency.visibility = if (playbackVisible) View.VISIBLE else View.GONE
        fullscreenButton.visibility = if (playbackVisible) View.VISIBLE else View.GONE
        placeholder.visibility = if (!playbackVisible) {
            View.GONE
        } else if (state.playback == PlaybackPhase.STOPPED &&
            previewSlots.none { it.playback != PlaybackPhase.STOPPED }) {
            View.VISIBLE
        } else {
            View.GONE
        }

        deviceResultsPanel.visibility = if (resultsVisible && !deviceResultsCollapsed) {
            View.VISIBLE
        } else {
            View.GONE
        }
        findViewById<View>(R.id.device_results_reveal).visibility =
            if (resultsVisible && deviceResultsCollapsed) View.VISIBLE else View.GONE

        // Keep a single visibility switch below the stream; preserve each panel's state.
        intArrayOf(
            R.id.connection_collapse, R.id.sidebar_collapse,
            R.id.header_collapse, R.id.device_results_collapse
        ).forEach { findViewById<View>(it).visibility = View.GONE }
        if (playbackVisible && playbackOverlaysHidden) {
            intArrayOf(
                R.id.connection_panel, R.id.connection_quick_actions,
                R.id.controls_panel, R.id.sidebar_panel_container,
                R.id.sidebar_reveal, R.id.app_header, R.id.header_reveal,
                R.id.navigation_rail, R.id.device_results_panel, R.id.device_results_reveal
            ).forEach { findViewById<View>(it).visibility = View.GONE }
        } else {
            findViewById<View>(R.id.navigation_rail).visibility = View.VISIBLE
        }
        findViewById<View>(R.id.controls_reveal).visibility =
            if (playbackVisible && playbackOverlaysHidden) View.VISIBLE else View.GONE
    }

    private fun compactPlaybackOverlays() {
        if (playbackOverlayAutoCompacted) return
        playbackOverlayAutoCompacted = true
        collapsedOverlays += R.id.connection_panel
        collapsedOverlays += R.id.sidebar_panel_container
        applyWorkspaceVisibility(viewModel.state)
    }

    private fun setupFullscreen() {
        val content = findViewById<FrameLayout>(android.R.id.content)
        normalContent = content.getChildAt(0)
        fullscreenHost = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        content.addView(fullscreenHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        fullscreenButton.setOnClickListener { if (isFullscreen) exitFullscreen() else enterFullscreen() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    exitFullscreen()
                } else if (onvifControlSurface.visibility == View.VISIBLE) {
                    closeOnvifControls()
                } else if (viewModel.state.recordingFile != null) {
                    stopRecording { finish() }
                } else if (recordingStopInProgress) {
                    showMessage("正在保存录像与 XML，请稍候")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        val slot = selectedPreviewSlot ?: run {
            showMessage(getString(R.string.connect_video_first))
            return
        }
        if (slot.tile.parent !== playerGrid) return
        fullscreenPreviewSlot = slot
        fullscreenPreviewIndex = playerGrid.indexOfChild(slot.tile)
        fullscreenPreviewLayoutParams = slot.tile.layoutParams
        fullscreenButtonParent = fullscreenButton.parent as? ViewGroup ?: return
        fullscreenButtonIndex = fullscreenButtonParent?.indexOfChild(fullscreenButton) ?: -1
        fullscreenButtonLayoutParams = fullscreenButton.layoutParams
        previousSystemUiVisibility = window.decorView.systemUiVisibility
        playerGrid.removeView(slot.tile)
        fullscreenButtonParent?.removeView(fullscreenButton)
        fullscreenHost.addView(slot.tile, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        fullscreenHost.addView(fullscreenButton, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(48),
            Gravity.END or Gravity.BOTTOM
        ).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        normalContent.visibility = View.GONE
        fullscreenHost.visibility = View.VISIBLE
        fullscreenButton.setText(R.string.exit_fullscreen)
        fullscreenButton.contentDescription = getString(R.string.exit_fullscreen_description)
        // 全屏仍保持完整画面；仅放大选中卡片，不改变其他播放画面的 Surface。
        slot.fragment.setFillMode(false)
        isFullscreen = true
        window.decorView.systemUiVisibility = previousSystemUiVisibility or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        applyPlaybackSystemBars(hideNavigation = true)
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        val slot = fullscreenPreviewSlot ?: return
        fullscreenHost.removeView(fullscreenButton)
        fullscreenHost.removeView(slot.tile)
        val tileIndex = fullscreenPreviewIndex.coerceIn(0, playerGrid.childCount)
        playerGrid.addView(slot.tile, tileIndex, fullscreenPreviewLayoutParams)
        val buttonParent = fullscreenButtonParent ?: videoContainer
        val buttonIndex = fullscreenButtonIndex.coerceIn(0, buttonParent.childCount)
        buttonParent.addView(fullscreenButton, buttonIndex, fullscreenButtonLayoutParams)
        fullscreenHost.visibility = View.GONE
        normalContent.visibility = View.VISIBLE
        fullscreenButton.setText(R.string.fullscreen)
        fullscreenButton.contentDescription = getString(R.string.enter_fullscreen_description)
        slot.fragment.setFillMode(slot.fillMode)
        relayoutPreviewSlots()
        window.decorView.systemUiVisibility = previousSystemUiVisibility
        applyPlaybackSystemBars(hideNavigation = false)
        isFullscreen = false
        fullscreenPreviewSlot = null
        fullscreenPreviewIndex = -1
        fullscreenPreviewLayoutParams = null
        fullscreenButtonIndex = -1
        fullscreenButtonParent = null
        fullscreenButtonLayoutParams = null
    }

    private fun bindPlayerCallbacks(slot: PreviewSlot) {
        slot.fragment.onFirstFrameRendered = {
            if (!isDestroyed) {
                runOnUiThread {
                    slot.playback = PlaybackPhase.PLAYING
                    if (selectedPreviewSlot === slot) {
                        viewModel.onVideoReady()
                        compactPlaybackOverlays()
                    }
                    renderPreviewSelection()
                }
            }
        }
    }

    private fun renderState(state: MainUiState) {
        val previous = renderedState
        licenseStatus.text = state.licenseLabel
        licenseStatus.setTextColor(getColor(if (state.licensed) R.color.green else R.color.muted))
        pauseButton.visibility = if (state.recordingFile != null) View.VISIBLE else View.GONE
        pauseButton.setText(if (state.recordingPaused) R.string.resume_recording else R.string.pause_recording)
        recordButton.setText(when {
            recordingStopInProgress -> R.string.recording_finishing
            state.recordingFile != null -> R.string.stop_recording
            else -> R.string.record
        })
        recordButton.setCompoundDrawablesWithIntrinsicBounds(
            0,
            if (state.recordingFile != null) R.drawable.ic_record_active else R.drawable.ic_record,
            0,
            0
        )
        recordButton.isEnabled = state.playback == PlaybackPhase.PLAYING && !recordingStopInProgress
        recordingAudioButton.isEnabled = state.recordingFile == null
        fitButton.setText(if (state.fillMode) R.string.fill_video else R.string.fit_video)
        videoCropButton.setText(R.string.video_crop_select)
        videoCropButton.isEnabled = state.playback == PlaybackPhase.PLAYING &&
            state.recordingFile == null && !recordingStopInProgress
        // 入口始终可响应，避免播放器状态回调稍慢时点击无反馈。
        digitalZoomButton.isEnabled = state.licensed
        when (state.playback) {
            PlaybackPhase.STOPPED -> {
                placeholder.visibility = if (previewSlots.any { it.playback != PlaybackPhase.STOPPED }) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
                placeholder.setText(if (state.licensed) R.string.enter_rtsp_connect else R.string.license_unavailable_contact)
            }
            PlaybackPhase.CONNECTING -> {
                placeholder.visibility = View.GONE
            }
            PlaybackPhase.PLAYING -> {
                placeholder.visibility = View.GONE
            }
        }
        renderPlaybackStatus(state)

        applyWorkspaceVisibility(state)
        if (state.section == MainSection.FILES) {
            deviceSearchVisible = false
            sidebarBase.visibility = View.VISIBLE
            onvifControlSurface.visibility = View.GONE
        }
        listOf(
            R.id.history_tab to MainSection.PLAYBACK,
            R.id.captures_tab to MainSection.FILES
        ).forEach { (id, section) ->
            findViewById<Button>(id).apply {
                val active = state.section == section ||
                    (section == MainSection.PLAYBACK && state.section == MainSection.DISCOVERY)
                setBackgroundResource(if (active) R.drawable.primary_button else android.R.color.transparent)
                setTextColor(if (active) Color.WHITE else getColor(R.color.muted))
            }
        }
        findViewById<Button>(R.id.camera_push_button).apply {
            setBackgroundResource(android.R.color.transparent)
            setTextColor(getColor(R.color.muted))
        }
        findViewById<Button>(R.id.search_button).setText(if (state.onvifSearching) R.string.onvif_cancel_search else R.string.search_devices)
        findViewById<EditText>(R.id.onvif_scan_range).isEnabled = !state.onvifSearching
        findViewById<EditText>(R.id.onvif_user).isEnabled = !state.onvifSearching
        findViewById<EditText>(R.id.onvif_password).isEnabled = !state.onvifSearching
        onvifHint.text = state.onvifHint
        if (previous?.history != state.history) renderHistory(state.history)
        if (previous?.captures != state.captures) {
            renderCaptures(state)
        }
        if (previous?.onvifDevices != state.onvifDevices ||
            previous?.onvifSearching != state.onvifSearching ||
            previous?.onvifSearchCompleted != state.onvifSearchCompleted ||
            previous?.onvifHint != state.onvifHint) renderOnvifDevices(state)
        if (state.playback != PlaybackPhase.PLAYING) {
            headerLatency.setText(R.string.header_latency_unknown)
        }
        renderedState = state
        consumePendingAutoPlay()
    }

    private fun renderPlaybackStatus(state: MainUiState) {
        if (!::streamInfo.isInitialized || state.section == MainSection.FILES) return
        val playback = if (state.recordingFile != null) {
            getString(
                R.string.recording_stream_info,
                state.streamInfo,
                state.recordingDurationMs / 1000,
                if (state.recordingPaused) getString(R.string.paused_suffix) else ""
            )
        } else {
            state.streamInfo
        }
        val crop = if (state.videoCropRegion.isFullFrame) {
            getString(R.string.video_crop_full_status)
        } else {
            getString(
                R.string.video_crop_active,
                cropPercent(state.videoCropRegion.left),
                cropPercent(state.videoCropRegion.top),
                cropPercent(state.videoCropRegion.right),
                cropPercent(state.videoCropRegion.bottom)
            )
        }
        val audio = getString(when (recordingAudioSource) {
            RecordingAudioSource.None -> R.string.recording_audio_status_none
            RecordingAudioSource.Rtsp -> R.string.recording_audio_status_rtsp
            is RecordingAudioSource.FileSource,
            is RecordingAudioSource.UriSource,
            RecordingAudioSource.External -> R.string.recording_audio_status_microphone
        })
        streamInfo.text = listOf(playback, crop, audio).joinToString("  |  ")
    }

    private fun showRtspHistoryDropdown() {
        val entries = viewModel.state.history
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundResource(R.drawable.card)
        }
        content.addView(TextView(this).apply {
            setText(R.string.playback_history_title)
            textSize = 15f
            setTextColor(getColor(R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(10))
        })
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val popupWidth = minOf(
            dp(560),
            resources.displayMetrics.widthPixels - dp(32)
        )
        val popup = PopupWindow(
            ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            },
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(20).toFloat()
        }
        if (entries.isEmpty()) {
            rows.addView(TextView(this).apply {
                setText(R.string.no_history)
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.muted))
                setPadding(dp(12), dp(24), dp(12), dp(24))
            })
        } else {
            entries.forEach { entry ->
                rows.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    setBackgroundResource(R.drawable.outline_button)
                    addView(TextView(this@MainActivity).apply {
                        text = entry.url
                        textSize = 13f
                        setTextColor(getColor(R.color.ink))
                        maxLines = 3
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(entry.connectedAtMs))
                        textSize = 11f
                        setTextColor(getColor(R.color.muted))
                        setPadding(0, dp(4), 0, 0)
                    })
                    setOnClickListener {
                        urlInput.setText(entry.url)
                        urlInput.setSelection(entry.url.length)
                        popup.dismiss()
                        collapsedOverlays -= R.id.connection_panel
                        applyWorkspaceVisibility(viewModel.state)
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) })
            }
        }
        content.addView(rows)
        content.addView(Button(this).apply {
            setText(R.string.clear_history)
            setAllCaps(false)
            setTextColor(getColor(R.color.danger))
            setBackgroundResource(R.drawable.outline_button)
            isEnabled = entries.isNotEmpty()
            setOnClickListener {
                popup.dismiss()
                confirmClearHistory()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(3)
        })
        val anchor = findViewById<View>(R.id.history_dropdown_button)
        popup.showAsDropDown(anchor, anchor.width - popupWidth, dp(8))
    }

    private fun renderOnvifDevices(state: MainUiState) {
        deviceList.removeAllViews()
        if (state.onvifSearchCompleted && state.onvifDevices.isEmpty()) {
            deviceList.addView(rowText(getString(R.string.no_devices), getString(R.string.no_devices_hint)))
        } else if (state.onvifDevices.isEmpty() && !state.onvifSearching) {
            deviceList.addView(rowText(
                getString(R.string.search_devices),
                getString(R.string.onvif_control_intro)
            ).apply {
                layoutParams = LinearLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
        state.onvifDevices.forEach { device ->
            val label = device.name.ifBlank { device.model.ifBlank { getString(R.string.network_camera) } }
            val source = getString(if (device.source == CameraSource.ONVIF)
                R.string.device_source_onvif else R.string.device_source_hikvision)
            val card = rowText(label, getString(R.string.device_row_subtitle,
                device.ipAddress, device.manufacturer, source))
            (card.getChildAt(0) as? TextView)?.apply {
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_nav_devices, 0, 0, 0)
                compoundDrawablePadding = dp(4)
            }
            card.layoutParams = LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(8)
            }
            card.setOnClickListener { inspectDevice(device) }
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.view_device_details)
                    setAllCaps(false)
                    setBackgroundResource(R.drawable.outline_button)
                    setTextColor(getColor(R.color.blue))
                    setOnClickListener { inspectDevice(device) }
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
                if (device.source == CameraSource.ONVIF && device.onvifDevice != null) {
                    addView(Button(this@MainActivity).apply {
                        text = getString(R.string.onvif_control_button)
                        setAllCaps(false)
                        setBackgroundResource(R.drawable.primary_button)
                        setTextColor(Color.WHITE)
                        setOnClickListener { loadOnvifControls(device, onvifCredentials()) }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        marginStart = dp(8)
                    })
                }
            })
            deviceList.addView(card)
        }
    }

    private fun activateLicense() {
        try {
            RoboYiPlayer.initialize(applicationContext, BuildConfig.ROBOYI_LICENSE)
            viewModel.onLicensed(
                getString(R.string.license_status_ok)
            )
            ensurePlayer()?.apply {
                setDecoderMode(viewModel.state.decoderMode)
                setFillMode(false)
                setVideoCrop(viewModel.state.videoCropRegion)
            }
            if (viewModel.state.fillMode) viewModel.toggleFillMode()
            showMessage(getString(R.string.license_active))
        } catch (e: RoboYiLicenseException) {
            viewModel.onLicenseFailed(getString(R.string.license_unavailable_code, e.code))
            showMessage(getString(R.string.license_failed, e.code))
        } catch (e: Exception) {
            viewModel.onLicenseFailed(getString(R.string.license_unavailable_detail, safeError(e)))
            showMessage(getString(R.string.license_failed, safeError(e)))
        }
    }

    private fun ensurePlayer(): RoboYiPlayerFragment? {
        if (!viewModel.state.licensed) return null
        selectedPreviewSlot?.let { return it.fragment }
        return createPreviewSlot()?.fragment
    }

    private fun createPreviewSlot(): PreviewSlot? {
        if (previewSlots.size >= MAX_PREVIEW_SLOTS) {
            showMessage(getString(R.string.preview_limit_reached, MAX_PREVIEW_SLOTS))
            return null
        }
        val host = FrameLayout(this).apply { id = View.generateViewId() }
        val selectionOverlay = View(this).apply {
            // A non-zero alpha prevents vendor SurfaceView composition from optimizing this
            // interaction layer out while remaining visually transparent.
            setBackgroundColor(0x01000000)
            isClickable = false
            isFocusable = false
            contentDescription = getString(R.string.select_preview)
        }
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xAA101C2A.toInt())
        }
        val focusIndicator = View(this).apply {
            // Activity overlay only: never enters the SDK render/encode/screenshot pipeline.
            visibility = View.GONE
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val tile = FrameLayout(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(host, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(selectionOverlay, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(focusIndicator, FrameLayout.LayoutParams(dp(52), dp(52)))
            addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM
            ).apply { setMargins(dp(8), 0, dp(48), dp(8)) })
        }
        playerGrid.addView(tile)
        val fragment = RoboYiPlayerFragment.newInstance()
        supportFragmentManager.beginTransaction().add(host.id, fragment).commitNow()
        val slot = PreviewSlot(
            container = host,
            tile = tile,
            label = label,
            selectionOverlay = selectionOverlay,
            focusIndicator = focusIndicator,
            fragment = fragment,
            streamInfo = getString(R.string.waiting_stream),
            fillMode = false,
            digitalZoomFactor = viewModel.state.digitalZoomFactor,
            cropRegion = viewModel.state.videoCropRegion
        )
        previewSlots += slot
        bindPlayerCallbacks(slot)
        fragment.setDecoderMode(viewModel.state.decoderMode)
        fragment.setFillMode(false)
        fragment.setVideoCrop(slot.cropRegion)
        selectPreview(slot)
        relayoutPreviewSlots()
        window.decorView.post { applyPlaybackSystemBars(hideNavigation = isFullscreen) }
        return slot
    }

    private fun observeRemoteFocusTouch(event: MotionEvent) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                remoteFocusTouchSlot = previewSlots.lastOrNull { slot ->
                    slot.playback == PlaybackPhase.PLAYING &&
                        isScreenPointInside(slot.tile, event.rawX, event.rawY)
                }?.takeUnless { isRemoteFocusOverlayPoint(event.rawX, event.rawY) }
                remoteFocusDownX = event.rawX
                remoteFocusDownY = event.rawY
                remoteFocusTouchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(event.rawX - remoteFocusDownX) > touchSlop ||
                    kotlin.math.abs(event.rawY - remoteFocusDownY) > touchSlop
                ) remoteFocusTouchMoved = true
            }
            MotionEvent.ACTION_UP -> {
                val slot = remoteFocusTouchSlot
                remoteFocusTouchSlot = null
                if (slot == null || remoteFocusTouchMoved ||
                    isRemoteFocusOverlayPoint(event.rawX, event.rawY)
                ) return
                selectPreview(slot)
                if (selectedPreviewSlot !== slot) return
                val location = IntArray(2).also(slot.selectionOverlay::getLocationOnScreen)
                val localX = event.rawX - location[0]
                val localY = event.rawY - location[1]
                val normalized = mapTapToStream(slot, slot.selectionOverlay, localX, localY)
                val target = RemoteFocusTarget.fromRtspUrl(slot.url)
                Log.d(REMOTE_FOCUS_TAG, "Preview tap url=${slot.url} mapped=$normalized")
                if (normalized != null && target != null) {
                    requestRemoteFocus(
                        slot, target, normalized, slot.selectionOverlay, localX, localY
                    )
                }
            }
            MotionEvent.ACTION_CANCEL -> remoteFocusTouchSlot = null
        }
    }

    private fun isRemoteFocusOverlayPoint(rawX: Float, rawY: Float): Boolean {
        val overlayIds = intArrayOf(
            R.id.app_header,
            R.id.navigation_rail,
            R.id.controls_panel,
            R.id.controls_reveal,
            R.id.connection_quick_actions,
            R.id.connection_panel,
            R.id.sidebar_base,
            R.id.sidebar_reveal,
            R.id.device_results_panel,
            R.id.device_results_reveal,
            R.id.onvif_control_surface
        )
        return overlayIds.any { id ->
            findViewById<View>(id)?.let { isScreenPointInside(it, rawX, rawY) } == true
        }
    }

    private fun isScreenPointInside(view: View, rawX: Float, rawY: Float): Boolean {
        if (!view.isShown || view.width <= 0 || view.height <= 0) return false
        val location = IntArray(2).also(view::getLocationOnScreen)
        return rawX >= location[0] && rawX < location[0] + view.width &&
            rawY >= location[1] && rawY < location[1] + view.height
    }

    private fun mapTapToStream(
        slot: PreviewSlot,
        view: View,
        tapX: Float,
        tapY: Float
    ): Pair<Float, Float>? {
        val viewWidth = view.width.toFloat().takeIf { it > 0f } ?: return null
        val viewHeight = view.height.toFloat().takeIf { it > 0f } ?: return null
        val dimensions = Regex("""(\d+)\s*[x×]\s*(\d+)""").find(slot.streamInfo)
        val sourceWidth = dimensions?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: viewWidth
        val sourceHeight = dimensions?.groupValues?.getOrNull(2)?.toFloatOrNull() ?: viewHeight
        val crop = slot.cropRegion
        val croppedWidth = sourceWidth * (crop.right - crop.left).coerceAtLeast(0.001f)
        val croppedHeight = sourceHeight * (crop.bottom - crop.top).coerceAtLeast(0.001f)
        val scale = if (slot.fillMode) {
            maxOf(viewWidth / croppedWidth, viewHeight / croppedHeight)
        } else {
            minOf(viewWidth / croppedWidth, viewHeight / croppedHeight)
        }
        val renderedWidth = croppedWidth * scale
        val renderedHeight = croppedHeight * scale
        val contentX = (viewWidth - renderedWidth) / 2f
        val contentY = (viewHeight - renderedHeight) / 2f
        if (!slot.fillMode &&
            (tapX < contentX || tapX > contentX + renderedWidth ||
                tapY < contentY || tapY > contentY + renderedHeight)
        ) return null
        val croppedX = ((tapX - contentX) / renderedWidth).coerceIn(0f, 1f)
        val croppedY = ((tapY - contentY) / renderedHeight).coerceIn(0f, 1f)
        return (crop.left + croppedX * (crop.right - crop.left)).coerceIn(0f, 1f) to
            (crop.top + croppedY * (crop.bottom - crop.top)).coerceIn(0f, 1f)
    }

    private fun requestRemoteFocus(
        slot: PreviewSlot,
        target: RemoteFocusTarget,
        normalized: Pair<Float, Float>,
        sourceView: View,
        tapX: Float,
        tapY: Float
    ) {
        val sourceLocation = IntArray(2).also(sourceView::getLocationInWindow)
        val tileLocation = IntArray(2).also(slot.tile::getLocationInWindow)
        val indicatorX = tapX + sourceLocation[0] - tileLocation[0]
        val indicatorY = tapY + sourceLocation[1] - tileLocation[1]
        showRemoteFocusIndicator(slot, indicatorX, indicatorY, getColor(R.color.blue), 1_800L)
        lifecycleScope.launch {
            val focused = withContext(Dispatchers.IO) {
                runCatching { RemoteFocusClient.send(target, normalized.first, normalized.second) }
                    .getOrDefault(false)
            }
            showRemoteFocusIndicator(
                slot,
                indicatorX,
                indicatorY,
                getColor(if (focused) R.color.green else R.color.danger),
                if (focused) 650L else 1_100L
            )
        }
    }

    private fun showRemoteFocusIndicator(
        slot: PreviewSlot,
        centerX: Float,
        centerY: Float,
        color: Int,
        hideAfterMs: Long
    ) {
        val indicator = slot.focusIndicator
        val size = dp(52)
        indicator.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), color)
        }
        indicator.x = (centerX - size / 2f).coerceIn(0f, (slot.tile.width - size).coerceAtLeast(0).toFloat())
        indicator.y = (centerY - size / 2f).coerceIn(0f, (slot.tile.height - size).coerceAtLeast(0).toFloat())
        indicator.animate().cancel()
        indicator.visibility = View.VISIBLE
        indicator.alpha = 1f
        indicator.scaleX = 1.25f
        indicator.scaleY = 1.25f
        indicator.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
        val marker = SystemClock.uptimeMillis()
        indicator.tag = marker
        indicator.postDelayed({
            if (indicator.tag == marker) {
                indicator.animate().alpha(0f).setDuration(180L).withEndAction {
                    if (indicator.tag == marker) indicator.visibility = View.GONE
                }.start()
            }
        }, hideAfterMs)
    }

    private fun selectPreview(slot: PreviewSlot) {
        if (selectedPreviewSlot === slot) return
        if (viewModel.state.recordingFile != null) {
            showMessage(getString(R.string.preview_switch_recording_blocked))
            return
        }
        selectedPreviewSlot = slot
        player = slot.fragment
        if (slot.url.isNotBlank()) urlInput.setText(slot.url)
        viewModel.onSelectedPreviewChanged(
            playback = slot.playback,
            streamInfo = slot.streamInfo,
            fillMode = slot.fillMode,
            digitalZoomFactor = slot.digitalZoomFactor,
            videoCropRegion = slot.cropRegion
        )
        renderPreviewSelection()
    }

    private fun requestClosePreview(slot: PreviewSlot) {
        if (selectedPreviewSlot === slot && viewModel.state.recordingFile != null) {
            stopRecording { closePreview(slot) }
        } else {
            closePreview(slot)
        }
    }

    private fun closePreview(slot: PreviewSlot) {
        if (isFullscreen && fullscreenPreviewSlot === slot) exitFullscreen()
        runCatching { slot.fragment.stop() }
        slot.fragment.onFirstFrameRendered = null
        supportFragmentManager.beginTransaction().remove(slot.fragment).commitNowAllowingStateLoss()
        playerGrid.removeView(slot.tile)
        previewSlots.remove(slot)
        if (selectedPreviewSlot === slot) {
            selectedPreviewSlot = null
            player = null
            previewSlots.lastOrNull()?.let(::selectPreview) ?: viewModel.onStopped()
        }
        relayoutPreviewSlots()
        renderPreviewSelection()
    }

    private fun relayoutPreviewSlots() {
        val count = previewSlots.size.coerceAtLeast(1)
        val columns = if (count == 1) 1 else 2
        val rows = (count + columns - 1) / columns
        // 关闭画面后可能从两列缩为一列。先保留足够大的网格并迁移所有子项，
        // 再收缩行列数，避免 GridLayout 校验到仍位于旧列的 LayoutParams 后崩溃。
        playerGrid.columnCount = maxOf(playerGrid.columnCount, columns)
        playerGrid.rowCount = maxOf(playerGrid.rowCount, rows)
        if (count > 1) {
            // 多画面统一使用完整适配，避免窄单元格继续沿用单画面的裁切填充模式。
            previewSlots.forEach { slot ->
                slot.fillMode = false
                runCatching { slot.fragment.setFillMode(false) }
            }
            if (viewModel.state.fillMode) viewModel.toggleFillMode()
        }
        (videoContainer.layoutParams as? LinearLayout.LayoutParams)
            ?.takeIf { it.weight == 0f }
            ?.let { params ->
                // 窄屏固定预览高度，避免多画面把控制菜单挤出屏幕。
                params.height = dp(COMPACT_PREVIEW_HEIGHT_DP)
                videoContainer.layoutParams = params
            }
        previewSlots.forEachIndexed { index, slot ->
            if (slot.tile.parent !== playerGrid) return@forEachIndexed
            slot.tile.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(index / columns, 1, 1f),
                GridLayout.spec(index % columns, 1, 1f)
            ).apply {
                width = 0
                height = 0
                setGravity(Gravity.FILL)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
        }
        playerGrid.columnCount = columns
        playerGrid.rowCount = rows
        playerGrid.requestLayout()
    }

    private fun renderPreviewSelection() {
        val multiPreview = previewSlots.size > 1
        closePreviewButton.isEnabled = selectedPreviewSlot != null
        previewSlots.forEachIndexed { index, slot ->
            val selected = slot === selectedPreviewSlot
            // Always keep the transparent interaction layer above the player Surface. SurfaceView
            // consumes touches itself, so hiding this for the selected tile disabled tap-to-focus.
            slot.selectionOverlay.visibility = View.VISIBLE
            slot.tile.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                if (multiPreview) {
                    setStroke(
                        dp(if (selected) 3 else 1),
                        getColor(if (selected) R.color.blue else R.color.muted)
                    )
                }
            }
            slot.label.visibility = if (multiPreview) View.VISIBLE else View.GONE
            slot.label.text = getString(
                if (selected) R.string.preview_selected_label else R.string.preview_label,
                index + 1,
                slot.url.takeIf(String::isNotBlank)?.let { Uri.parse(it).host }.orEmpty()
            )
        }
    }

    private fun showDigitalZoomControls() {
        digitalZoomPopup?.takeIf { it.isShowing }?.let {
            it.dismiss()
            return
        }
        val value = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(getColor(R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun zoomButton(icon: Int, description: Int) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(description)
            setBackgroundResource(R.drawable.video_overlay_button)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val decrease = zoomButton(R.drawable.ic_zoom_out, R.string.digital_zoom_out_description)
        val increase = zoomButton(R.drawable.ic_zoom_in, R.string.digital_zoom_in_description)
        var liveZoomFactor = viewModel.state.digitalZoomFactor
        fun renderZoomValue(factor: Float = liveZoomFactor) {
            val canAdjust = player != null && viewModel.state.playback != PlaybackPhase.STOPPED
            value.text = getString(R.string.digital_zoom_value, factor)
            decrease.isEnabled = canAdjust && (decrease.isPressed || factor > DigitalZoom.MIN_FACTOR)
            increase.isEnabled = canAdjust && (increase.isPressed || factor < DigitalZoom.MAX_FACTOR)
        }
        val zoomRepeatTasks = mutableListOf<Runnable>()
        fun bindContinuousZoom(button: ImageButton, direction: Float) {
            var pressStartFactor = liveZoomFactor
            var lastFrameTimeMs = 0L
            lateinit var repeat: Runnable
            repeat = Runnable {
                if (!button.isPressed || !button.isEnabled) return@Runnable
                val frameTimeMs = SystemClock.uptimeMillis()
                val elapsedMs = (frameTimeMs - lastFrameTimeMs)
                    .coerceIn(0L, DIGITAL_ZOOM_MAX_FRAME_DELTA_MS)
                lastFrameTimeMs = frameTimeMs
                val next = DigitalZoom.normalize(
                    liveZoomFactor + direction * DIGITAL_ZOOM_RATE_PER_SECOND * elapsedMs / 1000f
                )
                if (next != liveZoomFactor && applyDigitalZoom(next, persistState = false)) {
                    liveZoomFactor = next
                    renderZoomValue(next)
                }
                if (button.isPressed && button.isEnabled) {
                    button.postOnAnimation(repeat)
                }
            }
            zoomRepeatTasks += repeat
            button.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        pressStartFactor = liveZoomFactor
                        lastFrameTimeMs = SystemClock.uptimeMillis()
                        handler.removeCallbacks(repeat)
                        button.postOnAnimation(repeat)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(repeat)
                        view.isPressed = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            if (kotlin.math.abs(liveZoomFactor - pressStartFactor) < DIGITAL_ZOOM_STEP) {
                                val tapTarget = DigitalZoom.normalize(
                                    pressStartFactor + direction * DIGITAL_ZOOM_STEP
                                )
                                if (applyDigitalZoom(tapTarget, persistState = false)) {
                                    liveZoomFactor = tapTarget
                                }
                            }
                            view.performClick()
                        }
                        applyDigitalZoom(liveZoomFactor, persistState = true)
                        renderZoomValue(liveZoomFactor)
                        true
                    }
                    else -> true
                }
            }
        }
        bindContinuousZoom(decrease, -1f)
        bindContinuousZoom(increase, 1f)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(6), dp(7), dp(6))
            setBackgroundResource(R.drawable.card)
            addView(decrease, LinearLayout.LayoutParams(dp(42), dp(42)))
            addView(value, LinearLayout.LayoutParams(dp(72), dp(42)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
            addView(increase, LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        renderZoomValue()
        val popupWidth = dp(178)
        val popupHeight = dp(54)
        val popup = PopupWindow(row, popupWidth, popupHeight, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(18).toFloat()
            setOnDismissListener {
                decrease.isPressed = false
                increase.isPressed = false
                zoomRepeatTasks.forEach(handler::removeCallbacks)
                if (liveZoomFactor != viewModel.state.digitalZoomFactor) {
                    applyDigitalZoom(liveZoomFactor, persistState = true)
                }
                digitalZoomPopup = null
            }
        }
        digitalZoomPopup = popup
        popup.showAsDropDown(
            digitalZoomButton,
            (digitalZoomButton.width - popupWidth) / 2,
            -(digitalZoomButton.height + popupHeight + dp(8))
        )
    }

    private fun applyDigitalZoom(factor: Float, persistState: Boolean = true): Boolean {
        val normalized = DigitalZoom.normalize(factor)
        return runCatching {
            val fragment = player ?: error(getString(R.string.player_not_ready))
            fragment.setDigitalZoom(normalized)
            if (persistState) {
                viewModel.setDigitalZoom(normalized)
                selectedPreviewSlot?.digitalZoomFactor = normalized
                selectedPreviewSlot?.cropRegion = viewModel.state.videoCropRegion
            }
            true
        }.getOrElse {
            showMessage(getString(R.string.digital_zoom_failed, safeError(it)))
            false
        }
    }

    private fun applyVideoCrop(region: VideoCropRegion): Boolean {
        return runCatching {
            val fragment = player ?: error(getString(R.string.player_not_ready))
            fragment.setVideoCrop(region)
            viewModel.setVideoCrop(region)
            selectedPreviewSlot?.cropRegion = region
            selectedPreviewSlot?.digitalZoomFactor = region.equivalentZoomFactor
            true
        }.getOrElse {
            showMessage(getString(R.string.video_crop_failed, safeError(it)))
            false
        }
    }

    private fun showVideoCropSelector() {
        if (viewModel.state.recordingFile != null || recordingStopInProgress) {
            showMessage(getString(R.string.video_crop_recording_blocked))
            return
        }
        val fragment = player
        if (fragment?.isPlaying() != true || viewModel.state.playback != PlaybackPhase.PLAYING) {
            showMessage(getString(R.string.connect_video_first))
            return
        }

        val previousRegion = viewModel.state.videoCropRegion
        val previousFillMode = viewModel.state.fillMode
        val snapshotFile = runCatching {
            File.createTempFile("video-crop-source-", ".jpg", cacheDir)
        }.getOrElse {
            showMessage(getString(R.string.video_crop_failed, safeError(it)))
            return
        }
        showMessage(getString(R.string.video_crop_loading))

        val restorePreview = {
            runCatching { fragment.setFillMode(previousFillMode) }
            runCatching { fragment.setVideoCrop(previousRegion) }
        }
        runCatching {
            fragment.setVideoCrop(VideoCropRegion.FULL_FRAME)
            fragment.setFillMode(false)
        }.onFailure {
            snapshotFile.delete()
            restorePreview()
            showMessage(getString(R.string.video_crop_failed, safeError(it)))
            return
        }

        handler.postDelayed({
            runCatching {
                fragment.captureSurfaceScreenshot(snapshotFile) { ok, error ->
                    runOnUiThread {
                        restorePreview()
                        if (isDestroyed || isFinishing) {
                            snapshotFile.delete()
                            return@runOnUiThread
                        }
                        if (!ok) {
                            snapshotFile.delete()
                            showMessage(getString(R.string.video_crop_failed, safeError(error)))
                            return@runOnUiThread
                        }
                        val bitmap = BitmapFactory.decodeFile(snapshotFile.absolutePath)
                        snapshotFile.delete()
                        if (bitmap == null) {
                            showMessage(getString(R.string.video_crop_failed, "Bitmap"))
                            return@runOnUiThread
                        }
                        showVideoCropDialog(bitmap, previousRegion)
                    }
                }
            }.onFailure {
                restorePreview()
                snapshotFile.delete()
                showMessage(getString(R.string.video_crop_failed, safeError(it)))
            }
        }, VIDEO_CROP_CAPTURE_SETTLE_MS)
    }

    private fun showVideoCropDialog(bitmap: Bitmap, initialRegion: VideoCropRegion) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        content.addView(TextView(this).apply {
            setText(R.string.video_crop_hint)
            setTextColor(getColor(R.color.ink))
            textSize = 14f
        })
        val regionValue = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            setTextColor(getColor(R.color.muted))
            textSize = 13f
        }
        fun updateRegionValue(region: VideoCropRegion) {
            regionValue.text = getString(
                R.string.video_crop_region_value,
                cropPercent(region.left),
                cropPercent(region.top),
                cropPercent(region.right),
                cropPercent(region.bottom)
            )
        }
        updateRegionValue(initialRegion)
        content.addView(regionValue)
        val selector = VideoCropSelectionView(this, bitmap, initialRegion, ::updateRegionValue)
        content.addView(selector, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(340)
        ))

        AlertDialog.Builder(this)
            .setTitle(R.string.video_crop_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.video_crop_full_frame) { _, _ ->
                applyVideoCrop(VideoCropRegion.FULL_FRAME)
            }
            .setPositiveButton(R.string.video_crop_apply) { _, _ ->
                applyVideoCrop(selector.selectedRegion)
            }
            .create()
            .apply {
                setOnDismissListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                show()
            }
    }

    private fun cropPercent(value: Float): Int = (value * 100f).toInt().coerceIn(0, 100)

    private fun connect() {
        if (!viewModel.state.licensed) {
            showMessage(getString(R.string.license_unavailable_contact))
            return
        }
        val url = urlInput.text.toString().trim()
        if (runCatching { Uri.parse(url).fragment }.getOrNull() != null ||
            !RtspAddress.isSupported(url)
        ) {
            urlInput.error = getString(R.string.enter_rtsp_url)
            return
        }
        if (recordingStopInProgress) {
            showMessage(getString(R.string.recording_finishing_hint))
            return
        }
        if (viewModel.state.recordingFile != null) {
            stopRecording { performConnect(url) }
            return
        }
        performConnect(url)
    }

    private fun performConnect(url: String) {
        runCatching {
            playbackOverlayAutoCompacted = false
            collapsedOverlays += R.id.connection_panel
            applyWorkspaceVisibility(viewModel.state)
            bitmapWatermark = null // 新码流可能改变视频分辨率，需重新创建等尺寸画布。
            val slot = selectedPreviewSlot?.takeIf { it.playback == PlaybackPhase.STOPPED }
                ?: createPreviewSlot()
                ?: error(getString(R.string.player_not_ready))
            selectPreview(slot)
            val fragment = slot.fragment
            fragment.setDecoderMode(viewModel.state.decoderMode)
            // 每次开始播放都从完整适配开始，不继承其他画面的填充裁切状态。
            slot.fillMode = false
            fragment.setFillMode(false)
            if (viewModel.state.fillMode) viewModel.toggleFillMode()
            slot.url = url
            slot.playback = PlaybackPhase.CONNECTING
            slot.streamInfo = getString(R.string.connecting_stream)
            slot.digitalZoomFactor = viewModel.state.digitalZoomFactor
            slot.cropRegion = viewModel.state.videoCropRegion
            viewModel.onConnectRequested(url)
            fragment.play(url, watermarkSpec(WatermarkTarget.PREVIEW))
            renderPreviewSelection()
        }.onFailure {
            if (selectedPreviewSlot?.playback == PlaybackPhase.CONNECTING) {
                selectedPreviewSlot?.playback = PlaybackPhase.STOPPED
                viewModel.onStopped()
            }
            showMessage(getString(R.string.connect_failed, safeError(it)))
        }
    }

    private fun toggleRecordingPause() {
        if (viewModel.state.recordingFile == null) return
        if (inspection.stopAtMs != null) { showMessage("片尾倒计时期间不能暂停"); return }
        val wasPaused = viewModel.state.recordingPaused
        runCatching {
            val fragment = player ?: error(getString(R.string.player_not_ready))
            val changed = if (wasPaused) fragment.resumeRecording() else fragment.pauseRecording()
            if (!changed) error(getString(R.string.recording_channel_unavailable))
            if (wasPaused) microphoneCapture?.resume() else microphoneCapture?.pause()
            viewModel.onRecordingPauseChanged(!wasPaused)
        }.onFailure { showMessage(getString(if (wasPaused) R.string.resume_recording_failed
            else R.string.pause_recording_failed, safeError(it))) }
    }

    private fun snapshot() {
        if (recordingStopInProgress) { showMessage("正在保存录像资料，请稍候再截图"); return }
        val fragment = player
        if (fragment?.isPlaying() != true || viewModel.state.playback != PlaybackPhase.PLAYING) {
            showMessage(getString(R.string.connect_video_first)); return
        }
        if (!ensurePublicStorageAccess(::snapshot)) return
        runCatching {
            val session = inspection.session
            val atMs = inspection.elapsedMs
            val capturedAt = System.currentTimeMillis()
            val target = inspection.snapshotFile(session)
            val source = inspection.sourceFile(target)
            val shotSpec = inspection.spec(baseWatermarkSpec(WatermarkTarget.SCREENSHOT), session, atMs, now = capturedAt)
            fragment.captureSurfaceScreenshot(source) { ok, error ->
                runOnUiThread {
                    if (!ok) showMessage(getString(R.string.screenshot_failed, safeError(error)))
                    else launchInspectionJob {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                inspection.paintSnapshot(source, target, shotSpec)
                                inspection.saveSnapshot(session, target, capturedAt, atMs)
                            }
                        }
                        result.onSuccess { published ->
                            viewModel.refreshCaptures()
                            showMessage(getString(R.string.screenshot_saved, published.absolutePath))
                            if (session?.project != null) {
                                inspectionUi.editInfo("defect", null, source) { info, seconds ->
                                    if (inspection.session === session) {
                                        inspection.activateDefect(info, seconds)
                                        updateCurrentWatermark()
                                    }
                                    val annotated = inspection.spec(baseWatermarkSpec(WatermarkTarget.SCREENSHOT),
                                        session, atMs, defectOverride = info, now = capturedAt)
                                    launchInspectionJob {
                                        runCatching { withContext(Dispatchers.IO) { inspection.saveDefectInfo(session, target, info, seconds) } }
                                            .onFailure { showMessage("缺陷 XML 保存失败：${it.message}") }
                                    }
                                    val replace: () -> Unit = {
                                        launchInspectionJob {
                                            val replacement = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    inspection.paintSnapshot(source, target, annotated)
                                                    inspection.saveSnapshot(session, target, capturedAt, atMs, info, seconds)
                                                }
                                            }
                                            replacement.onSuccess {
                                                viewModel.refreshCaptures()
                                                showMessage("缺陷已保存，截图已替换，XML 已更新")
                                            }.onFailure { showMessage("缺陷保存失败：${it.message}；原始截图已保留") }
                                        }
                                    }
                                    if (inspection.session === session && seconds > 0) {
                                        pendingDefectImages += (android.os.SystemClock.elapsedRealtime() + seconds * 1000L) to replace
                                        showMessage("缺陷显示 $seconds 秒后自动替换截图")
                                    } else replace()
                                }
                            }
                        }.onFailure { showMessage("截图保存失败：${it.message}；暂存文件已保留") }
                    }
                }
            }
        }.onFailure { showMessage(getString(R.string.screenshot_failed, safeError(it))) }
    }

    private fun toggleRecording() {
        if (viewModel.state.recordingFile != null) {
            stopRecording()
            return
        }
        startRecordingIfReady()
    }

    private fun startRecordingIfReady() {
        if (recordingStopInProgress) { showMessage("上一段录像正在保存，请稍候"); return }
        val fragment = player
        if (fragment?.isPlaying() != true || viewModel.state.playback != PlaybackPhase.PLAYING) {
            showMessage(getString(R.string.connect_video_first))
            return
        }
        if (!ensurePublicStorageAccess(::startRecordingIfReady)) return
        if (!ensureInspectionFolder(::startRecordingIfReady)) return
        if (recordingAudioSource === RecordingAudioSource.External &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        runCatching {
            val file = inspection.begin()
            val session = checkNotNull(inspection.session)
            viewModel.onRecordingStarted(file)
            val started = fragment.startRecording(
                file = file,
                spec = watermarkSpec(WatermarkTarget.RECORDING),
                audio = RecordingAudioConfig(source = recordingAudioSource),
                onDurationChangedMs = { ms -> runOnUiThread {
                    if (viewModel.state.recordingFile == file) inspection.duration(ms)
                    viewModel.onRecordingDuration(file, ms)
                } },
                onSafetyStopped = { reason ->
                    runOnUiThread {
                        if (viewModel.onRecordingStopped(file) != null) {
                            finishDefectImages(force = true)
                            inspection.clear()
                            stopMicrophoneCapture()
                            lifecycleScope.launch {
                                inspectionJobs.toList().joinAll()
                                val published = withContext(Dispatchers.IO) {
                                    runCatching { inspection.finish(session, file, "safety-stopped") }
                                }
                                published.onSuccess {
                                    viewModel.refreshCaptures()
                                    showMessage(getString(R.string.recording_stopped, reason))
                                }.onFailure { failure ->
                                    showMessage(getString(R.string.stop_recording_failed, safeError(failure)))
                                }
                            }
                        }
                    }
                }
            )
            if (started && recordingAudioSource === RecordingAudioSource.External) {
                startMicrophoneCapture(fragment)
            }
            file to started
        }.onSuccess { (file, started) ->
            if (started && viewModel.state.recordingFile == file) {
                showMessage(getString(R.string.recording_started, file.name))
            } else if (!started) {
                viewModel.onRecordingStopped(file)
                inspection.abort()
                showMessage(getString(R.string.recording_not_started))
            }
        }.onFailure {
            inspection.abort()
            viewModel.onRecordingStopped()
            showMessage(getString(R.string.recording_failed, safeError(it)))
        }
    }

    private fun stopRecording(finishNow: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (recordingStopInProgress) return
        val activeFile = viewModel.state.recordingFile ?: run {
            onComplete?.invoke()
            return
        }
        if (!finishNow) {
            if (inspection.stopAtMs != null) { if (onComplete != null) recordingEndAction = onComplete; return }
            if (viewModel.state.recordingPaused) toggleRecordingPause()
            if (viewModel.state.recordingPaused) return
            val seconds = inspection.startEnding()
            if (seconds > 0) {
                recordingEndAction = onComplete
                updateCurrentWatermark()
                showMessage("正在补录片尾水印，将在 $seconds 秒后结束")
                return
            }
        }
        val session = inspection.session
        finishDefectImages(force = true)
        val fragment = player
        val capture = microphoneCapture
        microphoneCapture = null
        recordingStopInProgress = true

        // 先立即复位按钮和录像标识，让点击获得即时反馈；MP4 封装和音频线程回收放到后台。
        viewModel.onRecordingStopped(activeFile)
        recordButton.isEnabled = false
        recordButton.setText(R.string.recording_finishing)
        lifecycleScope.launch {
            inspectionJobs.toList().joinAll()
            val result = withContext(Dispatchers.IO) {
                var error: Throwable? = null
                var publicFile: File? = null
                runCatching { capture?.close() }.onFailure { error = it }
                runCatching { fragment?.stopAllRecordings() }.onFailure { if (error == null) error = it }
                if (error == null) {
                    runCatching { if (session != null) inspection.finish(session, activeFile) else viewModel.publishCapture(activeFile) }
                        .onSuccess { publicFile = it }
                        .onFailure { error = it }
                }
                error to publicFile
            }
            val (failure, publicFile) = result
            inspection.clear()
            recordingStopInProgress = false
            recordButton.isEnabled = viewModel.state.playback == PlaybackPhase.PLAYING
            recordButton.setText(R.string.record)
            viewModel.refreshCaptures()
            if (failure == null) {
                showMessage(getString(R.string.recording_saved, publicFile?.absolutePath.orEmpty()))
                onComplete?.invoke()
            } else {
                showMessage("录像 / XML 保存失败：${failure.message}；请在项目页重试导出 XML")
            }
        }
    }

    private fun ensurePublicStorageAccess(resume: () -> Unit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        pendingLegacyStorageAction = resume
        requestPublicStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return false
    }

    private fun ensureInspectionFolder(resume: () -> Unit): Boolean {
        if (InspectionXml.hasAccess(this)) return true
        AlertDialog.Builder(this).setTitle("授权录像资料目录")
            .setMessage("录像保存在公共目录 DCIM/RoboYiPlayer。为在同一目录保存 XML，请在系统选择器中选择 DCIM 或其下的 RoboYiPlayer 文件夹，并点击“使用此文件夹”。只需授权一次。")
            .setPositiveButton("选择文件夹") { _, _ -> chooseInspectionFolder(resume) }
            .setNegativeButton("取消", null).show()
        return false
    }

    private fun chooseInspectionFolder(resume: (() -> Unit)? = null) {
        pendingFolderAction = resume
        requestInspectionFolder.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM"))
    }

    private fun showProjectManager() {
        inspectionUi.showManager(viewModel.state.recordingFile != null || recordingStopInProgress,
            authorize = { chooseInspectionFolder() }, retryXml = {
                if (ensureInspectionFolder { retryInspectionXml() }) retryInspectionXml()
            })
    }

    private fun retryInspectionXml() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val sessions = inspection.store.sessions()
                sessions.forEach { InspectionXml.write(applicationContext, it) }
                sessions.size
            } }
            result.onSuccess { showMessage("已导出 $it 个录像 XML") }.onFailure { showMessage("XML 导出失败：${it.message}") }
        }
    }

    private fun showRecordingAudioSettings() {
        val options = arrayOf(
            getString(R.string.recording_audio_none),
            getString(R.string.recording_audio_rtsp),
            getString(R.string.recording_audio_microphone)
        )
        val selected = when (recordingAudioSource) {
            RecordingAudioSource.None -> 0
            RecordingAudioSource.Rtsp -> 1
            is RecordingAudioSource.FileSource,
            is RecordingAudioSource.UriSource,
            RecordingAudioSource.External -> 2
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.recording_audio_title)
            .setSingleChoiceItems(options, selected) { dialog, which ->
                when (which) {
                    0 -> {
                        recordingAudioSource = RecordingAudioSource.None
                        renderRecordingAudioSource()
                        dialog.dismiss()
                    }
                    1 -> {
                        recordingAudioSource = RecordingAudioSource.Rtsp
                        renderRecordingAudioSource()
                        dialog.dismiss()
                    }
                    else -> {
                        recordingAudioSource = RecordingAudioSource.External
                        renderRecordingAudioSource()
                        dialog.dismiss()
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun renderRecordingAudioSource() {
        if (!::recordingAudioButton.isInitialized) return
        recordingAudioButton.setText(R.string.recording_audio_button)
        renderPlaybackStatus(viewModel.state)
    }

    private fun startMicrophoneCapture(fragment: RoboYiPlayerFragment) {
        stopMicrophoneCapture()
        lateinit var capture: MicrophoneRecordingAudioCapture
        capture = MicrophoneRecordingAudioCapture(
            enqueue = fragment::queueExternalRecordingAudio,
            onError = { error ->
                runOnUiThread {
                    if (microphoneCapture === capture) {
                        stopRecording()
                        showMessage(getString(R.string.recording_audio_microphone_failed, safeError(error)))
                    }
                }
            }
        )
        microphoneCapture = capture
        try {
            capture.start()
        } catch (error: Throwable) {
            microphoneCapture = null
            capture.close()
            runCatching { fragment.stopAllRecordings() }
            throw error
        }
    }

    private fun stopMicrophoneCapture() {
        microphoneCapture?.close()
        microphoneCapture = null
    }

    private fun refreshPlayerStatus() {
        val fragment = player ?: return
        if (!fragment.isPlaying()) return
        val info = applyLocalCameraPushFrameRate(fragment.streamInfoText())
        selectedPreviewSlot?.streamInfo = info
        viewModel.onStreamInfo(info)

        // 延迟探测与播放器完全分离，失败只影响状态显示，不打断视频。
        val now = SystemClock.elapsedRealtime()
        if (now - lastLatencyProbeAtMs >= 3_000L) {
            lastLatencyProbeAtMs = now
            val probedUrl = urlInput.text.toString().trim()
            latencyMonitor.probe(probedUrl) { value ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (viewModel.state.playback != PlaybackPhase.PLAYING ||
                        !urlInput.text.toString().trim().equals(probedUrl, ignoreCase = true)
                    ) return@runOnUiThread
                    headerLatency.text = value?.let {
                        getString(R.string.header_latency_value, it)
                    } ?: getString(R.string.header_latency_unknown)
                }
            }
        }
    }

    private fun applyLocalCameraPushFrameRate(streamInfo: String): String {
        val slotUrl = selectedPreviewSlot?.url?.trim()?.trimEnd('/') ?: return streamInfo
        val push = CameraPushRuntime.snapshot
        val pushUrl = push.url?.trim()?.trimEnd('/') ?: return streamInfo
        val configuredFps = push.config?.frameRate?.takeIf { it > 0 } ?: return streamInfo
        if (push.phase != CameraPushPhase.RUNNING || !slotUrl.equals(pushUrl, ignoreCase = true)) {
            return streamInfo
        }

        val parts = streamInfo.split('|').map(String::trim).toMutableList()
        if (parts.size < 2) return streamInfo
        val reportedFps = parts[1]
            .substringBefore("fps", missingDelimiterValue = "")
            .trim()
            .replace(',', '.')
            .toFloatOrNull()
        if (reportedFps != null && reportedFps > 0f) return streamInfo
        parts[1] = "${configuredFps.toFloat()}fps"
        return parts.joinToString(" | ")
    }

    private fun renderCaptures(state: MainUiState) {
        val allFiles = state.captures
        val files = visibleCaptureFiles(allFiles)
        val availablePaths = allFiles.mapTo(hashSetOf(), File::getAbsolutePath)
        selectedCapturePaths.retainAll(availablePaths)
        renderCaptureFilters(allFiles)
        capturesList.removeAllViews()
        val columns = FILE_GRID_COLUMNS
        capturesList.columnCount = columns
        if (files.isEmpty()) {
            val empty = rowText(getString(R.string.no_local_files), getString(R.string.no_local_files_hint))
            capturesList.addView(empty, GridLayout.LayoutParams(
                GridLayout.spec(0, 1), GridLayout.spec(0, columns, 1f)
            ).apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            })
            if (allFiles.isEmpty()) {
                selectedCapture = null
                selectedCapturePaths.clear()
            }
            selectAllCapturesButton.isEnabled = false
            selectAllCapturesButton.setText(R.string.select_all)
            renderFileActions(selectedCapture)
            return
        }
        selectAllCapturesButton.isEnabled = true
        selectedCapture = selectedCapture?.takeIf { it.absolutePath in selectedCapturePaths }
            ?: allFiles.firstOrNull { it.absolutePath in selectedCapturePaths }
        val sessions = inspection.store.sessions()
        val byPath = buildMap<String, InspectionSession> {
            sessions.forEach { session ->
                put(session.video, session)
                session.snapshots.forEach { put(it.optString("path"), session) }
            }
        }
        fun sessionFor(file: File): InspectionSession? =
            runCatching { byPath[inspection.repository.relative(file)] }.getOrNull()
        var gridRow = 0
        fun groupHeader(title: String, members: List<File>) {
            val paths = members.map { it.absolutePath }.toSet()
            val checked = paths.isNotEmpty() && selectedCapturePaths.containsAll(paths)
            val header = Button(this).apply {
                text = "${if (checked) "☑" else "☐"} $title（${members.size}） · ${if (checked) "取消全选" else "全选本组"}"
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isAllCaps = false
                setOnClickListener {
                    if (selectedCapturePaths.containsAll(paths)) selectedCapturePaths.removeAll(paths)
                    else selectedCapturePaths.addAll(paths)
                    selectedCapture = allFiles.firstOrNull { it.absolutePath in selectedCapturePaths }
                    renderCaptures(viewModel.state)
                }
            }
            capturesList.addView(header, GridLayout.LayoutParams(
                GridLayout.spec(gridRow++, 1), GridLayout.spec(0, columns, 1f)
            ).apply { width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT })
        }
        files.groupBy { sessionFor(it)?.project?.id.orEmpty() }.forEach { (_, projectFiles) ->
            val firstSession = sessionFor(projectFiles.first())
            groupHeader(firstSession?.project?.let { "项目：${it.name} · ${it.values["number"].orEmpty()}" } ?: "未选择项目 / 独立截图", projectFiles)
            projectFiles.groupBy { sessionFor(it)?.task?.id.orEmpty() }.forEach { (_, taskFiles) ->
                val task = sessionFor(taskFiles.first())?.task
                groupHeader(task?.let { "    任务：${it.name} · ${it.values["number"].orEmpty()}" } ?: "    未选择任务", taskFiles)
                taskFiles.forEachIndexed { index, file ->
                    val video = file.extension.equals("mp4", true)
                    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified()))
                    val size = getString(R.string.file_size_mb, file.length() / 1048576.0)
                    val item = captureCard(file, video, time, size, file.absolutePath in selectedCapturePaths)
                    item.setOnClickListener {
                        if (!selectedCapturePaths.add(file.absolutePath)) selectedCapturePaths.remove(file.absolutePath)
                        selectedCapture = file.takeIf { it.absolutePath in selectedCapturePaths }
                            ?: files.firstOrNull { it.absolutePath in selectedCapturePaths }
                        renderCaptures(viewModel.state)
                    }
                    capturesList.addView(item, GridLayout.LayoutParams(
                        GridLayout.spec(gridRow + index / columns, 1), GridLayout.spec(index % columns, 1, 1f)
                    ).apply { width = 0; height = ViewGroup.LayoutParams.WRAP_CONTENT; setMargins(dp(4), dp(4), dp(4), dp(4)) })
                }
                gridRow += (taskFiles.size + columns - 1) / columns
            }
        }
        val allSelected = files.isNotEmpty() && files.all { it.absolutePath in selectedCapturePaths }
        selectAllCapturesButton.setText(if (allSelected) R.string.clear_selection else R.string.select_all)
        renderFileActions(selectedCapture)
    }

    private fun visibleCaptureFiles(files: List<File>): List<File> {
        val filtered = when (captureFilter) {
            CaptureFilter.ALL -> files
            CaptureFilter.VIDEO -> files.filter { it.extension.equals("mp4", true) }
            CaptureFilter.IMAGE -> files.filterNot { it.extension.equals("mp4", true) }
        }
        return if (capturesNewestFirst) {
            filtered.sortedByDescending(File::lastModified)
        } else {
            filtered.sortedBy(File::lastModified)
        }
    }

    private fun setCaptureFilter(filter: CaptureFilter) {
        if (captureFilter == filter) return
        captureFilter = filter
        renderCaptures(viewModel.state)
    }

    private fun renderCaptureFilters(files: List<File>) {
        val videoCount = files.count { it.extension.equals("mp4", true) }
        captureFilterAllButton.text = getString(R.string.file_filter_all, files.size)
        captureFilterVideoButton.text = getString(R.string.file_filter_video, videoCount)
        captureFilterImageButton.text = getString(R.string.file_filter_image, files.size - videoCount)
        listOf(
            captureFilterAllButton to CaptureFilter.ALL,
            captureFilterVideoButton to CaptureFilter.VIDEO,
            captureFilterImageButton to CaptureFilter.IMAGE
        ).forEach { (button, filter) ->
            val active = captureFilter == filter
            button.setBackgroundResource(if (active) R.drawable.primary_button else R.drawable.outline_button)
            button.setTextColor(getColor(if (active) android.R.color.white else R.color.ink))
        }
        captureSortButton.setText(
            if (capturesNewestFirst) R.string.file_sort_newest else R.string.file_sort_oldest
        )
    }

    private fun captureCard(
        file: File,
        video: Boolean,
        time: String,
        size: String,
        selected: Boolean
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setBackgroundResource(if (selected) R.drawable.selected_row else R.drawable.outline_button)
        addView(FrameLayout(this@MainActivity).apply {
            setBackgroundResource(R.drawable.file_preview_surface)
            clipToOutline = true
            addView(ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = file.name
                setImageBitmap(captureThumbnail(file))
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            addView(TextView(this@MainActivity).apply {
                text = getString(if (video) R.string.file_type_video else R.string.file_type_image)
                textSize = 10f
                setTextColor(Color.WHITE)
                setPadding(dp(7), dp(4), dp(7), dp(4))
                setBackgroundResource(R.drawable.video_overlay_button)
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM
            ).apply { setMargins(dp(7), dp(7), dp(7), dp(7)) })
            addView(TextView(this@MainActivity).apply {
                text = if (selected) "✓" else ""
                gravity = Gravity.CENTER
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(
                    if (selected) R.drawable.file_select_checked else R.drawable.file_select_unchecked
                )
                contentDescription = getString(R.string.select_file, file.name)
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.END or Gravity.TOP).apply {
                setMargins(0, dp(7), dp(7), 0)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(108)))
        addView(TextView(this@MainActivity).apply {
            text = file.name
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 13f
            setTextColor(getColor(R.color.ink))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(7), dp(2), 0)
        })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(1))
            addView(TextView(this@MainActivity).apply {
                text = time
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 11f
                setTextColor(getColor(R.color.muted))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = size
                maxLines = 1
                textSize = 11f
                setTextColor(getColor(R.color.muted))
                setPadding(0, 0, dp(5), 0)
            })
            addView(Button(this@MainActivity, null, 0, R.style.FileChipButton).apply {
                setText(R.string.file_details_button)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_info, 0, 0, 0)
                compoundDrawablePadding = dp(2)
                minWidth = 0
                minHeight = 0
                setPadding(dp(7), 0, dp(7), 0)
                setOnClickListener { button -> showCaptureDetails(button, file) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            ))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun showCaptureDetails(anchor: View, file: File) {
        if (!file.isFile) {
            viewModel.refreshCaptures()
            showMessage(getString(R.string.file_missing))
            return
        }
        fileDetailsPopup?.dismiss()
        val popupContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundResource(R.drawable.card)
        }
        val popup = PopupWindow(
            popupContent,
            minOf(dp(420), resources.displayMetrics.widthPixels - dp(32)),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dp(22).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { if (fileDetailsPopup === this) fileDetailsPopup = null }
        }
        popupContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                setText(R.string.file_preview_title)
                textSize = 17f
                setTextColor(getColor(R.color.ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageButton(this@MainActivity).apply {
                setImageResource(R.drawable.ic_close)
                setBackgroundColor(Color.TRANSPARENT)
                contentDescription = getString(R.string.close)
                setPadding(dp(7), dp(7), dp(7), dp(7))
                setOnClickListener { popup.dismiss() }
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
        })
        popupContent.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = file.name
            setBackgroundResource(R.drawable.file_preview_surface)
            clipToOutline = true
            setImageBitmap(captureThumbnail(file))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(150)
        ).apply { topMargin = dp(8) })
        popupContent.addView(TextView(this).apply {
            text = file.name
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 15f
            setTextColor(getColor(R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        popupContent.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(TextView(this@MainActivity).apply {
                text = captureDetailsText(file)
                textSize = 12f
                setTextColor(getColor(R.color.muted))
                setLineSpacing(dp(4).toFloat(), 1f)
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(220)
        ).apply { topMargin = dp(10) })
        popupContent.addView(Button(this, null, 0, R.style.OverlayTextButton).apply {
            setText(if (file.extension.equals("mp4", true)) R.string.play else R.string.view)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_nav_playback, 0, 0, 0)
            setOnClickListener {
                popup.dismiss()
                openCapture(file)
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(44)
        ).apply { topMargin = dp(10) })
        fileDetailsPopup = popup
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        popup.showAtLocation(capturesPanel, Gravity.CENTER, 0, 0)
    }

    private fun renderFileActions(file: File?) {
        val available = file?.isFile == true
        fileOpenButton.isEnabled = available
        deleteCapturesButton.isEnabled = selectedCapturePaths.isNotEmpty()
        shareCapturesButton.isEnabled = selectedCapturePaths.isNotEmpty()
        fileSelectionCount.text = if (selectedCapturePaths.isEmpty()) {
            getString(R.string.file_selected_count_zero)
        } else {
            getString(R.string.file_selected_count, selectedCapturePaths.size)
        }
        deleteCapturesButton.setText(R.string.delete)
        fileOpenButton.setText(if (file?.extension.equals("mp4", true) == true)
            R.string.play else R.string.view)
    }

    private fun captureDetailsText(file: File): String {
        val video = file.extension.equals("mp4", true)
        val unknown = getString(R.string.file_detail_unknown)
        val createdAt = captureCreatedAtMs(file)
        val size = getString(
            R.string.file_size_detail,
            getString(R.string.file_size_mb, file.length() / 1048576.0),
            java.text.NumberFormat.getIntegerInstance().format(file.length())
        )
        val lines = mutableListOf(
            getString(R.string.file_detail_type, getString(if (video) R.string.file_type_video else R.string.file_type_image)),
            getString(R.string.file_detail_path, file.absolutePath),
            getString(R.string.file_detail_size, size),
            getString(
                R.string.file_detail_created,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(createdAt))
            )
        )
        if (video) {
            val metadata = readVideoMetadata(file)
            lines += getString(R.string.file_detail_resolution, metadata.resolution ?: unknown)
            lines += getString(R.string.file_detail_frame_rate, metadata.frameRate ?: unknown)
            lines += getString(R.string.file_detail_bitrate, metadata.bitRate ?: unknown)
            lines += getString(R.string.file_detail_duration, metadata.duration ?: unknown)
            lines += getString(R.string.file_detail_mime, metadata.mimeType ?: unknown)
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val resolution = if (options.outWidth > 0 && options.outHeight > 0) {
                getString(R.string.file_resolution_value, options.outWidth, options.outHeight)
            } else {
                unknown
            }
            lines += getString(R.string.file_detail_resolution, resolution)
            lines += getString(R.string.file_detail_mime, options.outMimeType ?: unknown)
        }
        return lines.joinToString("\n")
    }

    private data class VideoMetadata(
        val resolution: String?,
        val frameRate: String?,
        val bitRate: String?,
        val duration: String?,
        val mimeType: String?
    )

    private fun readVideoMetadata(file: File): VideoMetadata = runCatching {
        MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(file.absolutePath)
                var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull()
                var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull()
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                if (rotation % 180 != 0) {
                    val originalWidth = width
                    width = height
                    height = originalWidth
                }
                val resolution = if (width != null && height != null && width > 0 && height > 0) {
                    getString(R.string.file_resolution_value, width, height)
                } else {
                    null
                }
                val frameRate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?.let { getString(R.string.file_frame_rate_value, it) }
                val bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let(::formatBitRate)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }
                    ?.let(::formatDuration)
                VideoMetadata(
                    resolution = resolution,
                    frameRate = frameRate,
                    bitRate = bitRate,
                    duration = duration,
                    mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                )
            } finally {
                retriever.release()
            }
        }
    }.getOrElse { VideoMetadata(null, null, null, null, null) }

    private fun captureCreatedAtMs(file: File): Long = file.nameWithoutExtension
        .substringAfterLast('_')
        .toLongOrNull()
        ?.takeIf { it in 946684800000L..System.currentTimeMillis() }
        ?: file.lastModified().takeIf { it > 0L }
        ?: System.currentTimeMillis()

    private fun formatBitRate(bitsPerSecond: Long): String = if (bitsPerSecond >= 1_000_000L) {
        getString(R.string.file_bitrate_mbps, bitsPerSecond / 1_000_000.0)
    } else {
        getString(R.string.file_bitrate_kbps, bitsPerSecond / 1_000.0)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun captureThumbnail(file: File): Bitmap? = runCatching {
        if (file.extension.equals("mp4", true)) {
            MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(file.absolutePath)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            0L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            480,
                            270
                        )
                    } else {
                        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                } finally {
                    retriever.release()
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 640) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
            })
        }
    }.getOrNull()

    private fun openCapture(file: File) {
        if (!file.isFile) {
            viewModel.refreshCaptures()
            showMessage(getString(R.string.file_missing))
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = if (file.extension.equals("mp4", true)) "video/mp4" else "image/jpeg"
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { showMessage(getString(R.string.file_open_failed, safeError(it))) }
    }

    private fun shareSelectedCaptures() {
        val files = viewModel.state.captures.filter { it.absolutePath in selectedCapturePaths && it.isFile }
        if (files.isEmpty()) return
        runCatching {
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            })
            val shareIntent = Intent(
                if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            ).apply {
                type = when {
                    files.all { it.extension.equals("mp4", true) } -> "video/mp4"
                    files.none { it.extension.equals("mp4", true) } -> "image/jpeg"
                    else -> "*/*"
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (uris.size == 1) {
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                } else {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                }
                clipData = ClipData.newUri(contentResolver, files.first().name, uris.first()).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_files_title)))
        }.onFailure { showMessage(getString(R.string.file_open_failed, safeError(it))) }
    }

    private fun toggleSelectAllCaptures() {
        val files = visibleCaptureFiles(viewModel.state.captures)
        val visiblePaths = files.mapTo(linkedSetOf(), File::getAbsolutePath)
        if (visiblePaths.isNotEmpty() && selectedCapturePaths.containsAll(visiblePaths)) {
            selectedCapturePaths.removeAll(visiblePaths)
            selectedCapture = viewModel.state.captures.firstOrNull {
                it.absolutePath in selectedCapturePaths
            }
        } else {
            selectedCapturePaths += visiblePaths
            selectedCapture = selectedCapture ?: files.firstOrNull()
        }
        renderCaptures(viewModel.state)
    }

    private fun confirmDeleteSelectedCaptures() {
        val files = viewModel.state.captures.filter { it.absolutePath in selectedCapturePaths }
        if (files.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_selected_prompt, files.size))
            .setMessage(R.string.cannot_undo)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = viewModel.deleteCaptures(files)
                if (deleted == files.size) {
                    selectedCapture = null
                    selectedCapturePaths.clear()
                    showMessage(getString(R.string.file_deleted))
                } else {
                    selectedCapturePaths.removeAll(files.map(File::getAbsolutePath).toSet())
                    showMessage(getString(R.string.files_deleted_count, deleted, files.size))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderHistory(entries: List<ConnectionEntry>) {
        historyList.removeAllViews()
        if (entries.isEmpty()) {
            historyList.addView(rowText(getString(R.string.no_history), getString(R.string.history_hint)))
            return
        }
        entries.forEach { entry ->
            val url = entry.url
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: getString(R.string.rtsp_source)
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(entry.connectedAtMs))
            val view = rowText(host, getString(R.string.history_row_subtitle, RtspAddress.maskCredentials(url), time))
            (view.getChildAt(0) as? TextView)?.apply {
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_nav_playback, 0, 0, 0)
                compoundDrawablePadding = dp(4)
            }
            view.setOnClickListener {
                urlInput.setText(url)
                connect()
            }
            historyList.addView(view)
        }
    }

    private fun rowText(title: String, subtitle: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundResource(R.drawable.outline_button)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(getColor(R.color.muted))
        })
        return row
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history_prompt)
            .setPositiveButton(R.string.clear) { _, _ ->
                viewModel.clearHistory()
                urlInput.setText("")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun searchOnvif() {
        if (viewModel.state.onvifSearching) {
            cameraSearchHandle?.cancel()
            return
        }
        val credentials = onvifCredentials()
        if (credentials.username.isBlank() && credentials.password.isBlank()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.default_credentials_title)
                .setMessage(R.string.default_credentials_notice)
                .setPositiveButton(R.string.search_devices) { _, _ -> performOnvifSearch(credentials) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        performOnvifSearch(credentials)
    }

    private fun performOnvifSearch(credentials: OnvifCredentials) {
        val options = runCatching {
            CameraSearchOptions(
                manualSubnetCidr = findViewById<EditText>(R.id.onvif_scan_range).text.toString().trim().ifEmpty { null },
                knownCameraIps = viewModel.state.history.mapNotNull { runCatching { java.net.URI(it.url).host }.getOrNull() }.toSet(),
                preferredServiceUrls = viewModel.state.onvifDevices.map { it.serviceUrl }
            )
        }.getOrElse {
            viewModel.setOnvifHint(getString(R.string.onvif_scan_range_invalid))
            return
        }
        val requestId = ++discoveryRequestId
        cameraSearchHandle?.cancel()
        viewModel.onOnvifSearchStarted()
        runCatching {
            cameraSearchHandle = onvif.search(credentials, options) { update ->
                runOnUiThread {
                    if (isDestroyed || requestId != discoveryRequestId) return@runOnUiThread
                    if (update.phase == CameraSearchPhase.FINISHED) cameraSearchHandle = null
                    viewModel.onOnvifSearchUpdate(update)
                }
            }
        }.onFailure {
            viewModel.onOnvifSearchUpdate(CameraSearchUpdate(CameraSearchPhase.FINISHED, emptyList(), end = CameraSearchEnd.FAILED))
        }
    }

    private fun onvifCredentials() = OnvifCredentials(
            username = findViewById<EditText>(R.id.onvif_user).text.toString().trim(),
            password = findViewById<EditText>(R.id.onvif_password).text.toString()
        )

    private fun inspectDevice(device: CameraDevice) {
        val credentials = onvifCredentials()
        val requestId = ++detailRequestId
        viewModel.setOnvifHint(getString(R.string.device_inspecting, device.ipAddress))
        runCatching {
            onvif.inspect(device, credentials) { details ->
                runOnUiThread {
                    if (isDestroyed || requestId != detailRequestId) return@runOnUiThread
                    viewModel.setOnvifHint(getString(R.string.device_loaded, device.ipAddress))
                    showCameraDetails(details, credentials)
                }
            }
        }.onFailure { viewModel.setOnvifHint(getString(R.string.inspection_failed, safeError(it))) }
    }

    private fun showCameraDetails(details: CameraDetails, credentials: OnvifCredentials) {
        val device = details.device
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            setBackgroundResource(R.drawable.card)
        }
        fun section(title: Int) {
            content.addView(TextView(this).apply {
                setText(title)
                textSize = 16f
                setTextColor(getColor(R.color.ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(16), 0, dp(6))
            })
        }
        fun infoRow(label: Int, value: String) {
            if (value.isBlank()) return
            content.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundResource(R.drawable.outline_button)
                addView(TextView(this@MainActivity).apply {
                    setText(label)
                    textSize = 11f
                    setTextColor(getColor(R.color.muted))
                })
                addView(TextView(this@MainActivity).apply {
                    text = value
                    textSize = 14f
                    setTextColor(getColor(R.color.ink))
                    setTextIsSelectable(true)
                    setPadding(0, dp(3), 0, 0)
                })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) })
        }

        val displayName = device.name.ifBlank {
            device.model.ifBlank { getString(R.string.network_camera) }
        }
        content.addView(TextView(this).apply {
            text = displayName
            textSize = 22f
            setTextColor(getColor(R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(TextView(this).apply {
            text = device.ipAddress
            textSize = 13f
            setTextColor(getColor(R.color.blue))
            setPadding(0, dp(3), 0, 0)
        })

        section(R.string.device_basic_info)
        infoRow(R.string.detail_source, getString(if (device.source == CameraSource.ONVIF)
            R.string.device_source_onvif else R.string.device_source_hikvision_probe))
        listOf(
            R.string.detail_name to device.name,
            R.string.detail_manufacturer to device.manufacturer,
            R.string.detail_model to device.model,
            R.string.detail_firmware to device.firmwareVersion,
            R.string.detail_serial to device.serialNumber,
            R.string.detail_hardware to device.hardwareId
        ).forEach { (label, value) -> infoRow(label, value) }

        section(R.string.device_network_info)
        infoRow(R.string.detail_ip, device.ipAddress)
        infoRow(R.string.detail_service_address, device.serviceUrl)
        infoRow(R.string.detail_endpoint, device.endpointReference)

        // Scopes/XAddrs/原始 XML 属于协议诊断信息，不进入面向操作员的设备详情。
        section(R.string.device_stream_info)
        var dialog: AlertDialog? = null
        if (details.streams.isEmpty()) {
            content.addView(TextView(this).apply {
                setText(R.string.no_device_streams)
                textSize = 13f
                setTextColor(getColor(R.color.muted))
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
        }
        details.streams.forEachIndexed { index, stream ->
            content.addView(Button(this).apply {
                val streamName = when {
                    stream.returnedByDevice -> stream.name
                    device.source == CameraSource.HIKVISION_HTTP && index == 0 -> getString(R.string.hikvision_main_stream)
                    device.source == CameraSource.HIKVISION_HTTP -> getString(R.string.hikvision_sub_stream)
                    else -> getString(R.string.common_stream_path)
                }
                text = getString(
                    R.string.stream_play_now,
                    streamName,
                    getString(if (stream.returnedByDevice) R.string.stream_returned else R.string.stream_candidate),
                    RtspAddress.maskCredentials(stream.url)
                )
                setAllCaps(false)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(78)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.primary_button)
                contentDescription = RtspAddress.maskCredentials(stream.url)
                setOnClickListener {
                    val url = OnvifSearcher.normalizeRtspUrl(
                        stream.url, device.ipAddress, credentials,
                        appendCredentials = credentials.username.isNotBlank()
                    )
                    urlInput.setText(url)
                    dialog?.dismiss()
                    viewModel.setOnvifHint(getString(R.string.stream_playing_and_saved))
                    connect()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        if (details.warnings.isNotEmpty()) {
            section(R.string.device_warning_info)
            details.warnings.forEach { warning -> infoRow(R.string.detail_warning, warning) }
        }
        dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    private fun loadOnvifControls(device: CameraDevice, credentials: OnvifCredentials) {
        val onvifDevice = device.onvifDevice ?: run {
            showMessage(getString(R.string.onvif_controls_unavailable))
            return
        }
        val requestId = ++onvifControlRequestId
        sidebarBase.visibility = View.INVISIBLE
        onvifControlSurface.visibility = View.VISIBLE
        onvifControlTitle.text = getString(R.string.onvif_controls_title, device.ipAddress)
        onvifControlStatus.apply {
            setText(R.string.onvif_controls_loading)
            visibility = View.VISIBLE
        }
        onvifControlContent.removeAllViews()
        onvifControlRefresh.apply {
            isEnabled = false
            setOnClickListener { loadOnvifControls(device, onvifCredentials()) }
        }
        val controller = OnvifController(onvifDevice, credentials)
        controller.load { snapshot ->
            runOnUiThread {
                if (isDestroyed || requestId != onvifControlRequestId ||
                    onvifControlSurface.visibility != View.VISIBLE
                ) return@runOnUiThread
                onvifControlStatus.visibility = View.GONE
                onvifControlRefresh.isEnabled = true
                showOnvifControlPanel(device, controller, snapshot)
            }
        }
    }

    private fun closeOnvifControls() {
        onvifControlRequestId++
        onvifControlContent.removeAllViews()
        onvifControlSurface.visibility = View.GONE
        sidebarBase.visibility = View.VISIBLE
    }

    private fun showOnvifControlPanel(
        device: CameraDevice,
        controller: OnvifController,
        initialSnapshot: OnvifControlSnapshot
    ) {
        onvifControlTitle.text = getString(R.string.onvif_controls_title, device.ipAddress)
        val profiles = initialSnapshot.profiles.toMutableList()
        val imaging = initialSnapshot.imaging.toMutableMap()
        val profileAwareUpdates = mutableListOf<() -> Unit>()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        content.addView(TextView(this).apply {
            text = getString(
                R.string.onvif_support_summary,
                yesNo(initialSnapshot.mediaSupported),
                yesNo(initialSnapshot.imagingSupported),
                yesNo(initialSnapshot.ptzSupported)
            )
            textSize = 13f
            setTextColor(getColor(R.color.ink))
        })
        if (initialSnapshot.warnings.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = initialSnapshot.warnings.joinToString("\n") { getString(R.string.onvif_warning, it) }
                textSize = 12f
                setTextColor(getColor(R.color.muted))
                setPadding(0, dp(6), 0, dp(6))
            })
        }

        val profileSpinner = Spinner(this)
        if (profiles.isNotEmpty()) {
            val labels = profiles.map { profile ->
                val encoder = profile.videoEncoder
                if (encoder == null) profile.name else getString(
                    R.string.onvif_profile_summary,
                    profile.name,
                    encoder.encoding,
                    encoder.resolution.width,
                    encoder.resolution.height,
                    encoder.frameRateLimit,
                    encoder.bitrateLimit
                )
            }
            profileSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            content.addView(TextView(this).apply {
                text = getString(R.string.onvif_profile)
                setTextColor(getColor(R.color.ink))
            })
            content.addView(profileSpinner)
        } else {
            content.addView(TextView(this).apply {
                text = getString(R.string.onvif_no_profiles)
                setTextColor(getColor(R.color.muted))
            })
        }
        fun selectedProfile(): OnvifMediaProfile? =
            profiles.getOrNull(profileSpinner.selectedItemPosition.coerceAtLeast(0))
        fun selectedImagingConfiguration(): OnvifImagingConfiguration? =
            selectedProfile()?.videoSourceToken?.let(imaging::get)

        content.addView(Button(this).apply {
            text = getString(R.string.onvif_stream_settings)
            setAllCaps(false)
            isEnabled = profiles.any { it.videoEncoder != null }
            setOnClickListener {
                val index = profileSpinner.selectedItemPosition
                val profile = profiles.getOrNull(index) ?: return@setOnClickListener
                showVideoEncoderSettings(
                    controller,
                    profile,
                    onSaved = { updated ->
                        profiles[index] = profile.copy(videoEncoder = updated)
                        showOnvifControlPanel(
                            device, controller,
                            initialSnapshot.copy(profiles = profiles, imaging = imaging)
                        )
                    },
                    onBack = {
                        showOnvifControlPanel(
                            device, controller,
                            initialSnapshot.copy(profiles = profiles, imaging = imaging)
                        )
                    }
                )
            }
        })
        content.addView(Button(this).apply {
            text = getString(R.string.onvif_image_settings)
            setAllCaps(false)
            isEnabled = imaging.isNotEmpty()
            setOnClickListener {
                val token = selectedProfile()?.videoSourceToken.orEmpty()
                val configuration = imaging[token] ?: imaging.values.firstOrNull()
                if (configuration == null) {
                    showMessage(getString(R.string.onvif_image_unavailable))
                    return@setOnClickListener
                }
                showImagingSettings(
                    controller,
                    configuration,
                    onSaved = { updated ->
                        imaging[configuration.videoSourceToken] = configuration.copy(settings = updated)
                    },
                    onBack = {
                        showOnvifControlPanel(
                            device, controller,
                            initialSnapshot.copy(profiles = profiles, imaging = imaging)
                        )
                    }
                )
            }
        })

        if (initialSnapshot.ptzSupported && profiles.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.onvif_ptz_hold_hint)
                textSize = 13f
                setTextColor(getColor(R.color.ink))
                setPadding(0, dp(12), 0, dp(4))
            })
            fun ptzRow(vararg buttons: Button) = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { button -> addView(button, LinearLayout.LayoutParams(0, dp(48), 1f)) }
            }
            fun spacer() = Button(this).apply { visibility = View.INVISIBLE; isEnabled = false }
            val up = ptzHoldButton(getString(R.string.onvif_ptz_up), controller, ::selectedProfile, 0f, 0.65f, 0f)
            val left = ptzHoldButton(getString(R.string.onvif_ptz_left), controller, ::selectedProfile, -0.65f, 0f, 0f)
            val right = ptzHoldButton(getString(R.string.onvif_ptz_right), controller, ::selectedProfile, 0.65f, 0f, 0f)
            val down = ptzHoldButton(getString(R.string.onvif_ptz_down), controller, ::selectedProfile, 0f, -0.65f, 0f)
            val zoomIn = ptzHoldButton(getString(R.string.onvif_zoom_in), controller, ::selectedProfile, 0f, 0f, 0.65f)
            val zoomOut = ptzHoldButton(getString(R.string.onvif_zoom_out), controller, ::selectedProfile, 0f, 0f, -0.65f)
            val stop = Button(this).apply {
                text = getString(R.string.onvif_ptz_stop)
                setAllCaps(false)
                setOnClickListener {
                    selectedProfile()?.let { profile ->
                        controller.stop(profile.token, callback = ::showOnvifCommandFailure)
                    }
                }
            }
            content.addView(ptzRow(spacer(), up, spacer()))
            content.addView(ptzRow(left, stop, right))
            content.addView(ptzRow(zoomOut, down, zoomIn))
            val homeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.onvif_go_home)
                    setAllCaps(false)
                    setOnClickListener {
                        selectedProfile()?.let { profile ->
                            controller.goToHomePosition(profile.token) { result ->
                                showOnvifCommandResult(result, R.string.onvif_home_sent)
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.onvif_set_home)
                    setAllCaps(false)
                    setOnClickListener {
                        selectedProfile()?.let { profile ->
                            controller.setHomePosition(profile.token) { result ->
                                showOnvifCommandResult(result, R.string.onvif_home_saved)
                            }
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(homeRow)
            profileAwareUpdates += {
                val profile = selectedProfile()
                val panTiltSupported = profile?.ptzCapabilities?.continuousPanTilt == true
                val zoomSupported = profile?.ptzCapabilities?.continuousZoom == true
                listOf(up, down, left, right).forEach { button ->
                    button.isEnabled = panTiltSupported
                    button.visibility = if (panTiltSupported) View.VISIBLE else View.INVISIBLE
                }
                listOf(zoomIn, zoomOut).forEach { button ->
                    button.isEnabled = zoomSupported
                    button.visibility = if (zoomSupported) View.VISIBLE else View.INVISIBLE
                }
                stop.isEnabled = panTiltSupported || zoomSupported
                homeRow.isEnabled = profile?.ptzConfigurationToken?.isNotBlank() == true
                for (index in 0 until homeRow.childCount) {
                    homeRow.getChildAt(index).isEnabled = homeRow.isEnabled
                }
            }
        }

        if (imaging.values.any { it.focusMoveOptions.nearFarSupported }) {
            val focusHint = TextView(this).apply {
                text = getString(R.string.onvif_focus_hold_hint)
                textSize = 13f
                setTextColor(getColor(R.color.ink))
                setPadding(0, dp(12), 0, dp(4))
            }
            val focusNear = focusHoldButton(
                getString(R.string.onvif_focus_near), controller,
                ::selectedImagingConfiguration, -1f
            )
            val focusFar = focusHoldButton(
                getString(R.string.onvif_focus_far), controller,
                ::selectedImagingConfiguration, 1f
            )
            val focusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(focusNear, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(focusFar, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            content.addView(focusHint)
            content.addView(focusRow)
            profileAwareUpdates += {
                val options = selectedImagingConfiguration()?.focusMoveOptions
                val nearSupported = focusDirectionValue(options?.continuousSpeed, -1f, 0.65f) != null ||
                    focusDirectionValue(options?.relativeDistance, -1f, 0.2f) != null ||
                    options?.absolutePosition != null
                val farSupported = focusDirectionValue(options?.continuousSpeed, 1f, 0.65f) != null ||
                    focusDirectionValue(options?.relativeDistance, 1f, 0.2f) != null ||
                    options?.absolutePosition != null
                focusNear.isEnabled = nearSupported
                focusFar.isEnabled = farSupported
                val visible = nearSupported || farSupported
                focusHint.visibility = if (visible) View.VISIBLE else View.GONE
                focusRow.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }

        if (profiles.isNotEmpty()) {
            profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    profileAwareUpdates.forEach { it() }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {
                    profileAwareUpdates.forEach { it() }
                }
            }
            profileAwareUpdates.forEach { it() }
        }

        onvifControlContent.removeAllViews()
        onvifControlContent.addView(content)
    }

    private fun showVideoEncoderSettings(
        controller: OnvifController,
        profile: OnvifMediaProfile,
        onSaved: (OnvifVideoEncoderConfiguration) -> Unit,
        onBack: () -> Unit
    ) {
        val current = profile.videoEncoder ?: run {
            showMessage(getString(R.string.onvif_stream_unavailable))
            return
        }
        val options = profile.videoEncoderOptions
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(8), 0, 0)
        }
        fun numberField(title: String, value: Number?, range: String = "", decimal: Boolean = false): EditText {
            content.addView(label(if (range.isBlank()) title else "$title ($range)"))
            return EditText(this).apply {
                setText(value?.toString().orEmpty())
                inputType = InputType.TYPE_CLASS_NUMBER or
                    if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
                isSingleLine = true
                content.addView(this)
            }
        }

        content.addView(label(getString(R.string.onvif_encoding)))
        content.addView(TextView(this).apply {
            text = current.encoding
            setTextColor(getColor(R.color.muted))
            setPadding(0, dp(4), 0, dp(4))
        })
        val resolutions = (listOf(current.resolution) + options?.resolutions.orEmpty()).distinct()
        content.addView(label(getString(R.string.onvif_resolution)))
        val resolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                resolutions.map { "${it.width} × ${it.height}" }
            )
            setSelection(resolutions.indexOf(current.resolution).coerceAtLeast(0))
        }
        content.addView(resolutionSpinner)
        val quality = numberField(getString(R.string.onvif_quality), current.quality, rangeText(options?.quality), true)
        val frameRate = numberField(getString(R.string.onvif_frame_rate), current.frameRateLimit, rangeText(options?.frameRate))
        val bitrate = numberField(getString(R.string.onvif_bitrate), current.bitrateLimit, rangeText(options?.bitrate))
        val interval = numberField(getString(R.string.onvif_encoding_interval), current.encodingInterval, rangeText(options?.encodingInterval))
        val gov = numberField(getString(R.string.onvif_gov_length), current.govLength, rangeText(options?.govLength))

        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(Button(this@MainActivity).apply {
                setText(R.string.cancel)
                setAllCaps(false)
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(Button(this@MainActivity).apply {
                setText(R.string.save)
                setAllCaps(false)
                setOnClickListener {
                runCatching {
                    val updated = current.copy(
                        resolution = resolutions[resolutionSpinner.selectedItemPosition],
                        quality = quality.text.toString().toFloat(),
                        frameRateLimit = frameRate.text.toString().toInt(),
                        bitrateLimit = bitrate.text.toString().toInt(),
                        encodingInterval = interval.text.toString().toInt(),
                        govLength = gov.text.toString().toIntOrNull()
                    )
                    require(options?.quality?.contains(updated.quality) != false)
                    require(options?.frameRate?.contains(updated.frameRateLimit) != false)
                    require(options?.bitrate?.contains(updated.bitrateLimit) != false)
                    require(options?.encodingInterval?.contains(updated.encodingInterval) != false)
                    updated.govLength?.let { require(options?.govLength?.contains(it) != false) }
                    updated
                }.onFailure { showMessage(getString(R.string.onvif_invalid_settings)) }
                    .onSuccess { updated ->
                        controller.setVideoEncoderConfiguration(updated) { result ->
                            if (result.success) runOnUiThread { onSaved(updated) }
                            showOnvifCommandResult(result, R.string.onvif_stream_saved)
                        }
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        })
        onvifControlTitle.text = getString(R.string.onvif_stream_settings_for, profile.name)
        onvifControlContent.removeAllViews()
        onvifControlContent.addView(content)
    }

    private data class ImagingSliderControl(
        val value: () -> Float,
        val setValue: (Float) -> Unit
    )

    private fun showImagingSettings(
        controller: OnvifController,
        configuration: OnvifImagingConfiguration,
        onSaved: (OnvifImagingSettings) -> Unit,
        onBack: () -> Unit
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        var workingSettings = configuration.settings
        fun commitSetting(
            settings: OnvifImagingSettings,
            merge: (OnvifImagingSettings) -> OnvifImagingSettings
        ) {
            controller.setImagingSettings(configuration.videoSourceToken, settings) { result ->
                if (result.success) runOnUiThread {
                    workingSettings = merge(workingSettings)
                    onSaved(workingSettings)
                }
                showOnvifCommandResult(result, R.string.onvif_image_saved)
            }
        }
        fun slider(
            title: String,
            value: Float?,
            range: OnvifFloatRange?,
            onCommit: (Float) -> Unit
        ): ImagingSliderControl? {
            if (value == null && range == null) return null
            val lower = range?.minimum ?: minOf(0f, value ?: 0f)
            val reportedUpper = range?.maximum ?: maxOf(100f, value ?: 100f)
            val movable = reportedUpper > lower
            val span = if (movable) reportedUpper - lower else 1f
            val initial = (value ?: lower).coerceIn(lower, maxOf(lower, reportedUpper))
            fun sliderValue(progress: Int): Float = if (movable) {
                lower + span * progress / 1000f
            } else lower
            fun display(number: Float): String = if (number % 1f == 0f) {
                number.toInt().toString()
            } else String.format(java.util.Locale.getDefault(), "%.1f", number)

            val valueLabel = TextView(this).apply {
                textSize = 14f
                setTextColor(getColor(R.color.ink))
                setPadding(0, dp(10), 0, 0)
            }
            val seekBar = SeekBar(this).apply {
                max = 1000
                progress = (((initial - lower) / span) * max).toInt().coerceIn(0, max)
                isEnabled = movable
            }
            fun updateLabel() {
                valueLabel.text = getString(
                    R.string.onvif_slider_value,
                    title,
                    display(sliderValue(seekBar.progress)),
                    display(lower),
                    display(reportedUpper)
                )
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateLabel()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seekBar?.let { onCommit(sliderValue(it.progress)) }
                }
            })
            updateLabel()
            content.addView(valueLabel)
            content.addView(seekBar)
            return ImagingSliderControl(
                value = { sliderValue(seekBar.progress) },
                setValue = { next ->
                    seekBar.progress = (((next.coerceIn(lower, reportedUpper) - lower) / span) * seekBar.max)
                        .toInt().coerceIn(0, seekBar.max)
                    updateLabel()
                }
            )
        }
        val current = configuration.settings
        val options = configuration.options
        val brightness = slider(
            getString(R.string.onvif_brightness), current.brightness, options.brightness
        ) { value ->
            commitSetting(OnvifImagingSettings(brightness = value)) { it.copy(brightness = value) }
        }
        val saturation = slider(
            getString(R.string.onvif_saturation), current.colorSaturation, options.colorSaturation
        ) { value ->
            commitSetting(OnvifImagingSettings(colorSaturation = value)) {
                it.copy(colorSaturation = value)
            }
        }
        val contrast = slider(
            getString(R.string.onvif_contrast), current.contrast, options.contrast
        ) { value ->
            commitSetting(OnvifImagingSettings(contrast = value)) { it.copy(contrast = value) }
        }
        val sharpness = slider(
            getString(R.string.onvif_sharpness), current.sharpness, options.sharpness
        ) { value ->
            commitSetting(OnvifImagingSettings(sharpness = value)) { it.copy(sharpness = value) }
        }
        fun defaultValue(value: Float?, range: OnvifFloatRange?): Float? =
            range?.let { (it.minimum + it.maximum) / 2f } ?: value
        val defaults = OnvifImagingSettings(
            brightness = defaultValue(current.brightness, options.brightness),
            colorSaturation = defaultValue(current.colorSaturation, options.colorSaturation),
            contrast = defaultValue(current.contrast, options.contrast),
            sharpness = defaultValue(current.sharpness, options.sharpness)
        )
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(Button(this@MainActivity).apply {
                setText(R.string.back_to_controls)
                setAllCaps(false)
                setOnClickListener { onBack() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(Button(this@MainActivity).apply {
                setText(R.string.restore_defaults)
                setAllCaps(false)
                isEnabled = listOf(
                    defaults.brightness, defaults.colorSaturation,
                    defaults.contrast, defaults.sharpness
                ).any { it != null }
                setOnClickListener {
                    controller.setImagingSettings(configuration.videoSourceToken, defaults) { result ->
                        if (result.success) runOnUiThread {
                            defaults.brightness?.let { brightness?.setValue?.invoke(it) }
                            defaults.colorSaturation?.let { saturation?.setValue?.invoke(it) }
                            defaults.contrast?.let { contrast?.setValue?.invoke(it) }
                            defaults.sharpness?.let { sharpness?.setValue?.invoke(it) }
                            workingSettings = defaults
                            onSaved(workingSettings)
                        }
                        showOnvifCommandResult(result, R.string.onvif_defaults_restored)
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        })
        onvifControlTitle.setText(R.string.onvif_image_settings)
        onvifControlContent.removeAllViews()
        onvifControlContent.addView(content)
    }

    private fun ptzHoldButton(
        label: String,
        controller: OnvifController,
        profile: () -> OnvifMediaProfile?,
        pan: Float,
        tilt: Float,
        zoom: Float
    ) = Button(this).apply {
        text = label
        setAllCaps(false)
        var activeToken: String? = null
        setOnClickListener { }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeToken = profile()?.token
                    activeToken?.let { token ->
                        controller.continuousMove(token, pan, tilt, zoom, ::showOnvifCommandFailure)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeToken?.let { token -> controller.stop(token, callback = ::showOnvifCommandFailure) }
                    activeToken = null
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun focusHoldButton(
        label: String,
        controller: OnvifController,
        configuration: () -> OnvifImagingConfiguration?,
        direction: Float
    ) = Button(this).apply {
        text = label
        setAllCaps(false)
        var activeContinuousToken: String? = null
        setOnClickListener { }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val selected = configuration()
                    val token = selected?.videoSourceToken.orEmpty()
                    val options = selected?.focusMoveOptions
                    val speed = focusDirectionValue(options?.continuousSpeed, direction, 0.65f)
                    val distance = focusDirectionValue(options?.relativeDistance, direction, 0.2f)
                    val position = options?.absolutePosition?.let { range ->
                        if (direction < 0f) range.minimum else range.maximum
                    }
                    when {
                        token.isBlank() -> Unit
                        speed != null -> {
                            activeContinuousToken = token
                            controller.continuousFocusMove(token, speed, ::showOnvifCommandFailure)
                        }
                        distance != null -> controller.relativeFocusMove(
                            token, distance, ::showOnvifCommandFailure
                        )
                        position != null -> controller.absoluteFocusMove(
                            token, position, ::showOnvifCommandFailure
                        )
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeContinuousToken?.let { token ->
                        controller.stopFocus(token, ::showOnvifCommandFailure)
                    }
                    activeContinuousToken = null
                    if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun focusDirectionValue(
        range: OnvifFloatRange?,
        direction: Float,
        fraction: Float
    ): Float? {
        range ?: return null
        return if (direction < 0f && range.minimum < 0f) {
            (range.minimum * fraction).coerceIn(range.minimum, minOf(0f, range.maximum))
        } else if (direction > 0f && range.maximum > 0f) {
            (range.maximum * fraction).coerceIn(maxOf(0f, range.minimum), range.maximum)
        } else null
    }

    private fun showOnvifCommandResult(result: OnvifCommandResult, successMessage: Int) {
        runOnUiThread {
            showMessage(if (result.success) getString(successMessage) else
                getString(R.string.onvif_command_failed, result.message))
        }
    }

    private fun showOnvifCommandFailure(result: OnvifCommandResult) {
        if (!result.success) showOnvifCommandResult(result, R.string.onvif_command_sent)
    }

    private fun yesNo(value: Boolean) = getString(if (value) R.string.yes else R.string.no)

    private fun rangeText(range: OnvifFloatRange?): String =
        range?.let { "${it.minimum}–${it.maximum}" }.orEmpty()

    private fun rangeText(range: OnvifIntRange?): String =
        range?.let { "${it.minimum}–${it.maximum}" }.orEmpty()

    private fun showSettings() {
        val content = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this).setView(content).create()
        val auto = content.findViewById<Button>(R.id.settings_decoder_auto)
        val hardware = content.findViewById<Button>(R.id.settings_decoder_hardware)
        if (viewModel.state.decoderMode == DecoderMode.AUTO) {
            auto.setBackgroundResource(R.drawable.primary_button)
        } else {
            hardware.setBackgroundResource(R.drawable.primary_button)
        }
        fun applyDecoder(mode: DecoderMode, label: String) {
            viewModel.setDecoderMode(mode)
            previewSlots.forEach { it.fragment.setDecoderMode(mode) }
            showMessage(getString(R.string.decoder_mode, label))
            dialog.dismiss()
        }
        auto.setOnClickListener { applyDecoder(DecoderMode.AUTO, auto.text.toString()) }
        hardware.setOnClickListener {
            applyDecoder(DecoderMode.HARDWARE, hardware.text.toString())
        }
        content.findViewById<View>(R.id.settings_privacy_action).setOnClickListener {
            dialog.dismiss()
            showPrivacyNotice()
        }
        content.findViewById<View>(R.id.settings_delete_action).setOnClickListener {
            dialog.dismiss()
            confirmPrivacyReset()
        }
        content.findViewById<View>(R.id.settings_licenses_action).setOnClickListener {
            dialog.dismiss()
            showOpenSourceNotices()
        }
        content.findViewById<View>(R.id.settings_about_action).setOnClickListener {
            dialog.dismiss()
            showAbout()
        }
        content.findViewById<View>(R.id.settings_close_action).setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt().coerceAtMost(dp(680)),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun showDataNoticeIfNeeded() {
        val prefs = getSharedPreferences("demo", MODE_PRIVATE)
        if (prefs.getString(PRIVACY_ACCEPTED_VERSION_KEY, null) == BuildConfig.PRIVACY_POLICY_VERSION) {
            activateLicense()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_notice_title)
            .setMessage(privacyNoticeText())
            .setPositiveButton(R.string.privacy_continue) { _, _ ->
                prefs.edit().putString(PRIVACY_ACCEPTED_VERSION_KEY, BuildConfig.PRIVACY_POLICY_VERSION)
                    .remove("data_notice_accepted").apply()
                activateLicense()
            }
            .setNeutralButton(R.string.privacy_full_policy, null)
            .setNegativeButton(R.string.privacy_exit) { _, _ -> finish() }
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { openPrivacyPolicy() }
                }
                dialog.show()
            }
    }

    private fun showPrivacyNotice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_notice_title)
            .setMessage(privacyNoticeText())
            .setNeutralButton(R.string.privacy_full_policy) { _, _ -> openPrivacyPolicy() }
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun privacyNoticeText(): String = buildString {
        append(getString(R.string.privacy_notice_body))
        if (BuildConfig.PRIVACY_OPERATOR.isNotBlank()) {
            append("\n\n")
            append(getString(R.string.privacy_operator_label, BuildConfig.PRIVACY_OPERATOR))
        }
        val email = privacyContactEmail()
        if (email.isNotBlank()) {
            append("\n")
            append(getString(R.string.privacy_contact_label, email))
        }
    }

    private fun privacyContactEmail(): String = BuildConfig.PRIVACY_CONTACT_EMAIL
        .ifBlank { getString(R.string.contact_email_address) }

    private fun confirmPrivacyReset() {
        if (viewModel.state.onvifSearching) {
            showMessage(getString(R.string.privacy_wait_for_search))
            return
        }
        if (viewModel.state.recordingFile != null || recordingStopInProgress || inspectionJobs.isNotEmpty()) {
            showMessage(getString(R.string.privacy_wait_for_recording))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_delete_local_data)
            .setMessage(R.string.privacy_delete_local_data_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val consentCleared = getSharedPreferences("demo", MODE_PRIVATE).edit()
                    .remove(PRIVACY_ACCEPTED_VERSION_KEY).remove("data_notice_accepted").commit()
                if (!consentCleared) {
                    showMessage(getString(R.string.privacy_delete_failed))
                    return@setPositiveButton
                }
                val result = runCatching {
                    stopService(Intent(this, CameraPushService::class.java))
                    pendingDefectImages.clear()
                    handler.removeCallbacks(inspectionTick)
                    inspection.clear()
                    if (viewModel.state.recordingFile != null) {
                        player?.stopAllRecordings()
                        viewModel.onRecordingStopped()
                    }
                    player?.stop()
                    viewModel.onStopped()
                    discoveryRequestId++
                    cameraSearchHandle?.cancel()
                    onvif.clearHistory()
                    detailRequestId++
                    viewModel.deleteLocalData()
                }.getOrElse {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.privacy_delete_partial)
                        .setPositiveButton(R.string.close) { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                    return@setPositiveButton
                }
                urlInput.setText("")
                findViewById<EditText>(R.id.onvif_user).setText("")
                findViewById<EditText>(R.id.onvif_password).setText("")
                bitmapWatermark = null
                if (result.complete) {
                    showMessage(getString(R.string.privacy_data_deleted))
                    finish()
                } else {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.privacy_delete_partial)
                        .setPositiveButton(R.string.close) { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openPrivacyPolicy() {
        val url = BuildConfig.PRIVACY_POLICY_URL
        if (url.startsWith("https://") && runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.isSuccess) return
        runCatching {
            val html = assets.open("legal/privacy-policy.html").bufferedReader().use { it.readText() }
            val webView = android.webkit.WebView(this).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        if (request?.url?.scheme == "mailto") runCatching {
                            startActivity(Intent(Intent.ACTION_SENDTO, request.url))
                        }
                        return true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.privacy_full_policy)
                .setView(webView)
                .setPositiveButton(R.string.close, null)
                .create().apply {
                    setOnDismissListener { webView.destroy() }
                    show()
                    window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(),
                        (resources.displayMetrics.heightPixels * 0.85f).toInt())
                }
        }.onFailure { showMessage(getString(R.string.privacy_open_failed)) }
    }

    private fun showOpenSourceNotices() {
        val paths = listOf(
            "legal/APP_OPEN_SOURCE_NOTICES.txt",
            "roboyiplayer/legal/GSTREAMER_NOTICE.txt",
            "roboyiplayer/legal/GSTREAMER-LGPL-2.1-or-later.txt",
            "legal/Apache-2.0.txt",
            "legal/LLVM-exception.txt",
            "legal/libffi-LICENSE.txt",
            "legal/PCRE2-LICENCE.txt",
            "legal/ORC-COPYING.txt",
            "legal/zlib-README.txt"
        )
        val notices = runCatching {
            paths.joinToString("\n\n====================\n\n") { path ->
                assets.open(path).bufferedReader().use { it.readText() }
            }
        }.getOrElse {
            showMessage(getString(R.string.open_source_unavailable))
            return
        }
        val text = TextView(this).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textSize = 12f
            setTextIsSelectable(true)
            this.text = notices
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_source_notices)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showAbout() {
        val email = privacyContactEmail()
        AlertDialog.Builder(this)
            .setTitle(R.string.about_app)
            .setMessage(getString(R.string.about_details,
                getString(R.string.app_name), BuildConfig.VERSION_NAME, email))
            .setPositiveButton(R.string.send_email) { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null))
                runCatching { startActivity(intent) }
                    .onFailure { showMessage(getString(R.string.no_email_app)) }
            }
            .setNeutralButton(R.string.copy_email) { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.contact_label), email))
                showMessage(getString(R.string.email_copied))
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun watermarkSpec(target: WatermarkTarget): WatermarkSpec = inspection.spec(baseWatermarkSpec(target))

    private fun baseWatermarkSpec(target: WatermarkTarget): WatermarkSpec =
        RoboYiWatermarkMapper.spec(
            this, viewModel.state.watermark, target, bitmapWatermark
        )

    private fun updateCurrentWatermark() {
        val target = if (viewModel.state.recordingFile != null) {
            WatermarkTarget.RECORDING
        } else {
            WatermarkTarget.PREVIEW
        }
        val spec = watermarkSpec(target)
        player?.updateWatermark(spec)
        lastInspectionSpec = spec
    }

    private fun showWatermarkModeDialog() {
        val modes = arrayOf(
            getString(R.string.watermark_mode_osd),
            getString(R.string.watermark_mode_bitmap),
            "项目 / 任务 / 缺陷 / 时间规则"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.watermark_mode_title)
            .setItems(modes) { dialog, index ->
                dialog.dismiss()
                when (index) {
                    0 -> showWatermarkSettings()
                    1 -> showBitmapWatermarkEditor()
                    else -> if (inspection.session != null || recordingStopInProgress) showMessage("请结束当前录像后修改规则") else inspectionUi.showRules()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBitmapWatermarkEditor() {
        val fragment = player
        if (fragment?.isPlaying() != true) {
            showMessage(getString(R.string.bitmap_connect_first))
            return
        }
        val size = Regex("""^(\d+)x(\d+)""").find(fragment.streamInfoText())
        val videoWidth = size?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val videoHeight = size?.groupValues?.get(2)?.toIntOrNull() ?: 0
        if (videoWidth <= 0 || videoHeight <= 0) {
            showMessage(getString(R.string.bitmap_resolution_wait))
            return
        }
        if (videoWidth.toLong() * videoHeight > 16_000_000L) {
            showMessage(getString(R.string.bitmap_resolution_too_high))
            return
        }
        val draft = runCatching {
            bitmapWatermark?.takeIf { it.width == videoWidth && it.height == videoHeight }
                ?.copy(Bitmap.Config.ARGB_8888, true)
                ?: Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
        }.getOrElse {
            showMessage(getString(R.string.bitmap_create_failed, safeError(it)))
            return
        }
        val editor = BitmapWatermarkEditorView(this, draft)
        val textInput = EditText(this).apply {
            hint = getString(R.string.bitmap_text_hint)
            setSingleLine(true)
        }
        val placeText = Button(this).apply {
            text = getString(R.string.place_text)
            setOnClickListener {
                val value = textInput.text.toString().trim()
                if (value.isEmpty()) textInput.error = getString(R.string.enter_text_first)
                else {
                    editor.placeTextOnNextTap(value)
                    showMessage(getString(R.string.tap_canvas_for_text))
                }
            }
        }
        val addGraphic = Button(this).apply {
            text = getString(R.string.sample_shape)
            setOnClickListener { editor.addSampleGraphic() }
        }
        val clear = Button(this).apply {
            text = getString(R.string.clear_canvas)
            setOnClickListener { editor.clearDrawing() }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.bitmap_description, videoWidth, videoHeight)
                textSize = 13f
            })
            addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
            addView(textInput)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(placeText, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(addGraphic, LinearLayout.LayoutParams(0, dp(48), 1f))
                addView(clear, LinearLayout.LayoutParams(0, dp(48), 1f))
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bitmap_editor_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.apply_layer) { _, _ ->
                bitmapWatermark = draft
                viewModel.setWatermark(viewModel.state.watermark.copy(
                    applyPreview = true,
                    applyRecording = true
                ))
                updateCurrentWatermark()
                showMessage(getString(R.string.bitmap_applied))
            }
            .setNeutralButton(R.string.remove_layer) { _, _ ->
                bitmapWatermark = null
                updateCurrentWatermark()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showWatermarkSettings() {
        val watermark = viewModel.state.watermark
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        fun label(value: String) = TextView(this).apply {
            text = value
            textSize = 14f
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(10), 0, dp(4))
        }
        val textInput = EditText(this).apply {
            setText(watermark.text)
            hint = getString(R.string.watermark_text_hint)
            setSingleLine(true)
        }
        content.addView(label(getString(R.string.osd_text)))
        content.addView(textInput)
        val sizeLabel = label(getString(R.string.text_size, watermark.textSizeSp.toInt()))
        content.addView(sizeLabel)
        val sizeSeek = SeekBar(this).apply {
            max = 42
            progress = (watermark.textSizeSp - 12).toInt().coerceIn(0, 42)
        }
        val sizeDecrease = Button(this).apply {
            text = "−"; contentDescription = "文字大小 减少 1"
            minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            setOnClickListener { sizeSeek.progress = (sizeSeek.progress - 1).coerceAtLeast(0) }
        }
        val sizeIncrease = Button(this).apply {
            text = "+"; contentDescription = "文字大小 增加 1"
            minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            setOnClickListener { sizeSeek.progress = (sizeSeek.progress + 1).coerceAtMost(sizeSeek.max) }
        }
        fun updateSize() {
            sizeLabel.text = getString(R.string.text_size, sizeSeek.progress + 12)
            sizeDecrease.isEnabled = sizeSeek.progress > 0
            sizeIncrease.isEnabled = sizeSeek.progress < sizeSeek.max
        }
        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = updateSize()
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        updateSize()
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(sizeDecrease, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(sizeSeek, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(sizeIncrease, LinearLayout.LayoutParams(dp(48), dp(48)))
        })
        content.addView(label(getString(R.string.text_color)))
        val colors = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(R.string.color_white, R.string.color_black, R.string.color_blue).forEachIndexed { index, name ->
            colors.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(name)
                tag = index
                isChecked = when (index) {
                    0 -> watermark.textColorArgb == Color.WHITE
                    1 -> watermark.textColorArgb == Color.BLACK
                    else -> watermark.textColorArgb != Color.WHITE && watermark.textColorArgb != Color.BLACK
                }
            })
        }
        content.addView(colors)
        content.addView(label(getString(R.string.text_position)))
        val positions = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(R.string.position_top_left, R.string.position_top_right, R.string.position_bottom_left, R.string.position_bottom_right).forEachIndexed { index, name ->
            positions.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(name)
                tag = index
                isChecked = watermark.position.ordinal == index
            })
        }
        content.addView(positions)
        val imageLabel = label(getString(R.string.image_watermark,
            watermark.imagePath?.let { File(it).name } ?: getString(R.string.none_selected)))
        content.addView(imageLabel)
        val imageButton = Button(this).apply {
            text = getString(R.string.choose_image)
            setOnClickListener { pickImage.launch("image/*") }
        }
        content.addView(imageButton)
        if (watermark.imagePath != null) {
            content.addView(Button(this).apply {
                text = getString(R.string.delete_watermark_image)
                setOnClickListener {
                    if (viewModel.deleteWatermarkImage()) {
                        imageLabel.text = getString(R.string.image_watermark, getString(R.string.none_selected))
                        visibility = View.GONE
                        updateCurrentWatermark()
                        showMessage(getString(R.string.watermark_image_deleted))
                    } else {
                        showMessage(getString(R.string.watermark_image_delete_failed))
                    }
                }
            })
        }
        val previewCheck = CheckBox(this).apply { text = getString(R.string.apply_preview); isChecked = watermark.applyPreview }
        val screenshotCheck = CheckBox(this).apply { text = getString(R.string.apply_screenshot); isChecked = watermark.applyScreenshot }
        val recordingCheck = CheckBox(this).apply { text = getString(R.string.apply_recording); isChecked = watermark.applyRecording }
        content.addView(previewCheck)
        content.addView(screenshotCheck)
        content.addView(recordingCheck)
        AlertDialog.Builder(this)
            .setTitle(R.string.osd_settings_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.apply) { _, _ ->
                viewModel.setWatermark(viewModel.state.watermark.copy(
                    text = textInput.text.toString().trim(),
                    textSizeSp = (sizeSeek.progress + 12).toFloat(),
                    textColorArgb = when (colors.findViewById<RadioButton>(colors.checkedRadioButtonId)?.tag) {
                        1 -> Color.BLACK
                        2 -> getColor(R.color.blue)
                        else -> Color.WHITE
                    },
                    position = when (positions.findViewById<RadioButton>(positions.checkedRadioButtonId)?.tag) {
                        1 -> WatermarkPosition.RIGHT_TOP
                        2 -> WatermarkPosition.LEFT_BOTTOM
                        3 -> WatermarkPosition.RIGHT_BOTTOM
                        else -> WatermarkPosition.LEFT_TOP
                    },
                    applyPreview = previewCheck.isChecked,
                    applyScreenshot = screenshotCheck.isChecked,
                    applyRecording = recordingCheck.isChecked
                ))
                updateCurrentWatermark()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** SDK/network exception messages may contain complete RTSP URLs and camera credentials. */
    private fun safeError(error: Throwable?): String = error?.javaClass?.simpleName
        ?.takeIf(String::isNotBlank) ?: "Error"

    private companion object {
        const val MAX_PREVIEW_SLOTS = 4
        const val FILE_GRID_COLUMNS = 4
        const val COMPACT_PREVIEW_HEIGHT_DP = 250
        const val PRIVACY_ACCEPTED_VERSION_KEY = "privacy_notice_accepted_version"
        const val DIGITAL_ZOOM_STEP = 0.1f
        const val DIGITAL_ZOOM_RATE_PER_SECOND = 1.25f
        const val DIGITAL_ZOOM_MAX_FRAME_DELTA_MS = 34L
        const val VIDEO_CROP_CAPTURE_SETTLE_MS = 200L
        const val REMOTE_FOCUS_TAG = "RoboYiRemoteFocus"
    }
}
