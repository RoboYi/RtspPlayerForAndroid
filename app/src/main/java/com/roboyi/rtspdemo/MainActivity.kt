package com.roboyi.rtspdemo

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.roboyi.rtsp.CameraDetails
import com.roboyi.rtsp.CameraDevice
import com.roboyi.rtsp.CameraSource
import com.roboyi.rtsp.DecoderMode
import com.roboyi.rtsp.OnvifCredentials
import com.roboyi.rtsp.OnvifSearcher
import com.roboyi.rtsp.RoboYiPlayer
import com.roboyi.rtsp.RoboYiPlayerFragment
import com.roboyi.rtsp.WatermarkSpec
import com.roboyi.rtsp.license.RoboYiLicenseException
import com.roboyi.rtspdemo.domain.ConnectionEntry
import com.roboyi.rtspdemo.domain.RtspAddress
import com.roboyi.rtspdemo.domain.WatermarkPosition
import com.roboyi.rtspdemo.domain.WatermarkTarget
import java.io.File
import java.text.DateFormat
import java.util.Date

/** 页面负责交互和播放器生命周期；持久化、文件规则和设备发现分别交给独立组件。 */
class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainViewModel
    private val onvif by lazy { OnvifDiscovery(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    // 只在前台轮询状态；SDK 的播放与录像回调仍由播放器管理。
    private val statusTick = object : Runnable {
        override fun run() {
            refreshPlayerStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    private lateinit var licenseStatus: TextView
    private lateinit var urlInput: EditText
    private lateinit var placeholder: TextView
    private lateinit var previewBadge: TextView
    private lateinit var streamInfo: TextView
    private lateinit var pauseButton: Button
    private lateinit var recordButton: Button
    private lateinit var fitButton: Button
    private lateinit var bitmapWatermarkButton: Button
    private lateinit var videoContainer: FrameLayout
    private lateinit var fullscreenButton: Button
    private lateinit var normalContent: View
    private lateinit var fullscreenHost: FrameLayout
    private var videoParent: ViewGroup? = null
    private var videoIndex = -1
    private var videoLayoutParams: ViewGroup.LayoutParams? = null
    private var isFullscreen = false
    private var previousSystemUiVisibility = 0
    private var restoreOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private lateinit var historyPanel: View
    private lateinit var onvifPanel: View
    private lateinit var capturesPanel: View
    private lateinit var historyList: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var capturesList: LinearLayout
    private lateinit var capturesSummary: TextView
    private lateinit var deleteCapturesButton: Button
    private lateinit var onvifHint: TextView
    private var player: RoboYiPlayerFragment? = null
    private var bitmapWatermark: Bitmap? = null
    private var renderedState: MainUiState? = null
    private var discoveryRequestId = 0
    private var detailRequestId = 0

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupFullscreen()
        viewModel = ViewModelProvider(this, MainViewModel.Factory(applicationContext))[MainViewModel::class.java]
        urlInput.setText(viewModel.lastUrl)
        player = supportFragmentManager.findFragmentById(R.id.player_host) as? RoboYiPlayerFragment
        player?.let(::bindPlayerCallbacks)
        if (savedInstanceState != null) viewModel.onHostRecreated()
        viewModel.uiState.observe(this, ::renderState)

        findViewById<ImageButton>(R.id.settings_button).setOnClickListener { showSettings() }
        findViewById<ImageButton>(R.id.about_button).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.connect_button).setOnClickListener { connect() }
        findViewById<Button>(R.id.stop_button).setOnClickListener { stop() }
        findViewById<Button>(R.id.snapshot_button).setOnClickListener { snapshot() }
        findViewById<Button>(R.id.watermark_button).setOnClickListener { showWatermarkSettings() }
        bitmapWatermarkButton.setOnClickListener { showBitmapWatermarkEditor() }
        findViewById<Button>(R.id.clear_history).setOnClickListener { confirmClearHistory() }
        findViewById<Button>(R.id.search_button).setOnClickListener { searchOnvif() }
        findViewById<Button>(R.id.history_tab).setOnClickListener { viewModel.showTab(SidebarTab.HISTORY) }
        findViewById<Button>(R.id.onvif_tab).setOnClickListener { viewModel.showTab(SidebarTab.ONVIF) }
        findViewById<Button>(R.id.captures_tab).setOnClickListener { viewModel.showTab(SidebarTab.CAPTURES) }
        findViewById<Button>(R.id.refresh_captures).setOnClickListener { viewModel.refreshCaptures() }
        deleteCapturesButton.setOnClickListener { confirmDeleteCaptures() }
        pauseButton.setOnClickListener { toggleRecordingPause() }
        recordButton.setOnClickListener { toggleRecording() }
        fitButton.setOnClickListener {
            player?.setFillMode(viewModel.toggleFillMode())
        }

        showDataNoticeIfNeeded()
        viewModel.refreshCaptures()
        refreshPlayerStatus()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshCaptures()
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(statusTick)
        handler.post(statusTick)
    }

    override fun onStop() {
        handler.removeCallbacks(statusTick)
        super.onStop()
    }

    override fun onDestroy() {
        player?.onFirstFrameRendered = null
        if (isFinishing) runCatching { player?.stop() }
        super.onDestroy()
    }

    private fun bindViews() {
        licenseStatus = findViewById(R.id.license_status)
        urlInput = findViewById(R.id.rtsp_url)
        placeholder = findViewById(R.id.preview_placeholder)
        previewBadge = findViewById(R.id.preview_badge)
        streamInfo = findViewById(R.id.stream_info)
        pauseButton = findViewById(R.id.pause_button)
        recordButton = findViewById(R.id.record_button)
        fitButton = findViewById(R.id.fit_button)
        bitmapWatermarkButton = findViewById(R.id.bitmap_watermark_button)
        videoContainer = findViewById(R.id.video_container)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        historyPanel = findViewById(R.id.history_panel)
        onvifPanel = findViewById(R.id.onvif_panel)
        capturesPanel = findViewById(R.id.captures_panel)
        historyList = findViewById(R.id.history_list)
        deviceList = findViewById(R.id.device_list)
        capturesList = findViewById(R.id.captures_list)
        capturesSummary = findViewById(R.id.captures_summary)
        deleteCapturesButton = findViewById(R.id.delete_captures)
        onvifHint = findViewById(R.id.onvif_hint)
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
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun enterFullscreen() {
        if (isFullscreen) return
        val parent = videoContainer.parent as? ViewGroup ?: return
        videoParent = parent
        videoIndex = parent.indexOfChild(videoContainer)
        videoLayoutParams = videoContainer.layoutParams
        previousSystemUiVisibility = window.decorView.systemUiVisibility
        restoreOrientation = if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation
        } else if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        parent.removeView(videoContainer)
        fullscreenHost.addView(videoContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        normalContent.visibility = View.GONE
        fullscreenHost.visibility = View.VISIBLE
        fullscreenButton.setText(R.string.exit_fullscreen)
        fullscreenButton.contentDescription = getString(R.string.exit_fullscreen_description)
        player?.setFillMode(true)
        isFullscreen = true
        window.decorView.systemUiVisibility = previousSystemUiVisibility or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun exitFullscreen() {
        if (!isFullscreen) return
        val parent = videoParent ?: return
        fullscreenHost.removeView(videoContainer)
        parent.addView(videoContainer, videoIndex, checkNotNull(videoLayoutParams))
        fullscreenHost.visibility = View.GONE
        normalContent.visibility = View.VISIBLE
        fullscreenButton.setText(R.string.fullscreen)
        fullscreenButton.contentDescription = getString(R.string.enter_fullscreen_description)
        player?.setFillMode(viewModel.state.fillMode)
        window.decorView.systemUiVisibility = previousSystemUiVisibility
        isFullscreen = false
        requestedOrientation = restoreOrientation
        videoParent = null
        videoLayoutParams = null
    }

    private fun bindPlayerCallbacks(fragment: RoboYiPlayerFragment) {
        fragment.onFirstFrameRendered = {
            if (!isDestroyed) viewModel.onVideoReady()
        }
    }

    private fun renderState(state: MainUiState) {
        val previous = renderedState
        licenseStatus.text = state.licenseLabel
        licenseStatus.setTextColor(getColor(if (state.licensed) R.color.green else R.color.muted))
        pauseButton.visibility = if (state.recordingFile != null) View.VISIBLE else View.GONE
        pauseButton.setText(if (state.recordingPaused) R.string.resume_recording else R.string.pause_recording)
        recordButton.setText(if (state.recordingFile != null) R.string.stop_recording else R.string.record)
        fitButton.setText(if (state.fillMode) R.string.fill_video else R.string.fit_video)
        when (state.playback) {
            PlaybackPhase.STOPPED -> {
                placeholder.visibility = View.VISIBLE
                placeholder.setText(if (state.licensed) R.string.enter_rtsp_connect else R.string.license_unavailable_contact)
                previewBadge.setText(R.string.disconnected_badge)
            }
            PlaybackPhase.CONNECTING -> {
                placeholder.visibility = View.GONE
                previewBadge.setText(R.string.connecting_badge)
            }
            PlaybackPhase.PLAYING -> {
                placeholder.visibility = View.GONE
                previewBadge.text = when {
                    state.recordingPaused -> getString(R.string.recording_paused_badge)
                    state.recordingFile != null -> getString(R.string.recording_badge)
                    else -> getString(R.string.live_preview_badge)
                }
            }
        }
        streamInfo.text = if (state.recordingFile != null) {
            getString(R.string.recording_stream_info, state.streamInfo, state.recordingDurationMs / 1000,
                if (state.recordingPaused) getString(R.string.paused_suffix) else "")
        } else state.streamInfo

        historyPanel.visibility = if (state.tab == SidebarTab.HISTORY) View.VISIBLE else View.GONE
        onvifPanel.visibility = if (state.tab == SidebarTab.ONVIF) View.VISIBLE else View.GONE
        capturesPanel.visibility = if (state.tab == SidebarTab.CAPTURES) View.VISIBLE else View.GONE
        listOf(
            R.id.history_tab to SidebarTab.HISTORY,
            R.id.onvif_tab to SidebarTab.ONVIF,
            R.id.captures_tab to SidebarTab.CAPTURES
        ).forEach { (id, tab) ->
            findViewById<Button>(id).apply {
                val active = state.tab == tab
                setBackgroundResource(if (active) R.drawable.primary_button else R.drawable.outline_button)
                setTextColor(if (active) Color.WHITE else getColor(R.color.blue))
            }
        }
        findViewById<Button>(R.id.search_button).isEnabled = !state.onvifSearching
        onvifHint.text = state.onvifHint
        if (previous?.history != state.history) renderHistory(state.history)
        if (previous?.captures != state.captures || previous?.selectedCaptures != state.selectedCaptures) {
            renderCaptures(state)
        }
        if (previous?.onvifDevices != state.onvifDevices ||
            previous?.onvifSearching != state.onvifSearching ||
            previous?.onvifSearchCompleted != state.onvifSearchCompleted ||
            previous?.onvifHint != state.onvifHint) renderOnvifDevices(state)
        renderedState = state
    }

    private fun renderOnvifDevices(state: MainUiState) {
        deviceList.removeAllViews()
        if (state.onvifSearchCompleted && state.onvifDevices.isEmpty()) {
            deviceList.addView(rowText(getString(R.string.no_devices), getString(R.string.no_devices_hint)))
        }
        state.onvifDevices.forEach { device ->
            val label = device.name.ifBlank { device.model.ifBlank { getString(R.string.network_camera) } }
            val source = getString(if (device.source == CameraSource.ONVIF)
                R.string.device_source_onvif else R.string.device_source_hikvision)
            deviceList.addView(rowText(label, getString(R.string.device_row_subtitle,
                device.ipAddress, device.manufacturer, source)).apply {
                setOnClickListener { inspectDevice(device) }
            })
        }
    }

    private fun activateLicense() {
        try {
            val info = RoboYiPlayer.initialize(applicationContext, BuildConfig.ROBOYI_LICENSE)
            viewModel.onLicensed(
                getString(R.string.built_in_license_label,
                    getString(if (info.isPerpetual) R.string.perpetual_license else R.string.licensed),
                    info.codecScope, info.features.joinToString())
            )
            ensurePlayer()?.apply {
                setDecoderMode(viewModel.state.decoderMode)
                setFillMode(viewModel.state.fillMode)
            }
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
        player?.let { bindPlayerCallbacks(it); return it }
        // 内置授权验证通过后再创建 Fragment，避免未授权时触发播放器初始化。
        return RoboYiPlayerFragment.newInstance().also {
            player = it
            supportFragmentManager.beginTransaction()
                .replace(R.id.player_host, it)
                .commitNow()
            bindPlayerCallbacks(it)
            it.setDecoderMode(viewModel.state.decoderMode)
            it.setFillMode(viewModel.state.fillMode)
        }
    }

    private fun connect() {
        if (!viewModel.state.licensed) {
            showMessage(getString(R.string.license_unavailable_contact))
            return
        }
        val url = urlInput.text.toString().trim()
        if (!RtspAddress.isSupported(url)) {
            urlInput.error = getString(R.string.enter_rtsp_url)
            return
        }
        runCatching {
            if (viewModel.state.recordingFile != null) stopRecording()
            bitmapWatermark = null // 新码流可能改变视频分辨率，需重新创建等尺寸画布。
            bitmapWatermarkButton.setText(R.string.bitmap_draw)
            val fragment = ensurePlayer() ?: error(getString(R.string.player_not_ready))
            fragment.setDecoderMode(viewModel.state.decoderMode)
            fragment.setFillMode(viewModel.state.fillMode)
            viewModel.onConnectRequested(url)
            fragment.play(url, watermarkSpec(WatermarkTarget.PREVIEW))
        }.onFailure {
            viewModel.onStopped()
            showMessage(getString(R.string.connect_failed, safeError(it)))
        }
    }

    private fun stop() {
        runCatching {
            if (viewModel.state.recordingFile != null) stopRecording()
            player?.stop()
            viewModel.onStopped()
        }.onFailure { showMessage(getString(R.string.stop_failed, safeError(it))) }
    }

    private fun toggleRecordingPause() {
        if (viewModel.state.recordingFile == null) return
        val wasPaused = viewModel.state.recordingPaused
        runCatching {
            val fragment = player ?: error(getString(R.string.player_not_ready))
            val changed = if (wasPaused) fragment.resumeRecording() else fragment.pauseRecording()
            if (!changed) error(getString(R.string.recording_channel_unavailable))
            viewModel.onRecordingPauseChanged(!wasPaused)
        }.onFailure { showMessage(getString(if (wasPaused) R.string.resume_recording_failed
            else R.string.pause_recording_failed, safeError(it))) }
    }

    private fun snapshot() {
        val fragment = player
        if (fragment?.isPlaying() != true || viewModel.state.playback != PlaybackPhase.PLAYING) {
            showMessage(getString(R.string.connect_video_first))
            return
        }
        runCatching {
            val target = viewModel.newSnapshotFile()
            val callback: (Boolean, Throwable?) -> Unit = { ok, error ->
                runOnUiThread {
                    showMessage(if (ok) getString(R.string.screenshot_saved, target.absolutePath)
                        else getString(R.string.screenshot_failed, safeError(error)))
                    if (ok) viewModel.refreshCaptures()
                }
            }
            if (viewModel.state.watermark.appliesTo(WatermarkTarget.SCREENSHOT)) {
                fragment.captureSurfaceScreenshotWithWatermark(
                    target, watermarkSpec(WatermarkTarget.SCREENSHOT), callback = callback
                )
            } else {
                fragment.captureSurfaceScreenshot(target, callback = callback)
            }
        }.onFailure { showMessage(getString(R.string.screenshot_failed, safeError(it))) }
    }

    private fun toggleRecording() {
        if (viewModel.state.recordingFile != null) {
            stopRecording()
            return
        }
        val fragment = player
        if (fragment?.isPlaying() != true || viewModel.state.playback != PlaybackPhase.PLAYING) {
            showMessage(getString(R.string.connect_video_first))
            return
        }
        runCatching {
            val file = viewModel.newRecordingFile()
            viewModel.onRecordingStarted(file)
            val started = fragment.startRecording(
                file = file,
                spec = watermarkSpec(WatermarkTarget.RECORDING),
                onDurationChangedMs = { ms -> runOnUiThread { viewModel.onRecordingDuration(file, ms) } },
                onSafetyStopped = { reason ->
                    runOnUiThread {
                        if (viewModel.onRecordingStopped(file) != null) showMessage(getString(R.string.recording_stopped, reason))
                    }
                }
            )
            file to started
        }.onSuccess { (file, started) ->
            if (started && viewModel.state.recordingFile == file) {
                showMessage(getString(R.string.recording_started, file.name))
            } else if (!started) {
                viewModel.onRecordingStopped(file)
                showMessage(getString(R.string.recording_not_started))
            }
        }.onFailure {
            viewModel.onRecordingStopped()
            showMessage(getString(R.string.recording_failed, safeError(it)))
        }
    }

    private fun stopRecording() {
        // SDK 可能从自己的安全停止回调通知结束；手动停止在此统一复位页面状态。
        runCatching { player?.stopAllRecordings() }
            .onFailure { showMessage(getString(R.string.stop_recording_failed, safeError(it))) }
        viewModel.onRecordingStopped()?.let { showMessage(getString(R.string.recording_saved, it.absolutePath)) }
    }

    private fun refreshPlayerStatus() {
        val fragment = player ?: return
        if (fragment.isPlaying()) viewModel.onStreamInfo(fragment.streamInfoText())
    }

    private fun renderCaptures(state: MainUiState) {
        val files = state.captures
        capturesSummary.text = getString(R.string.captures_summary, files.size)
        capturesList.removeAllViews()
        if (files.isEmpty()) {
            capturesList.addView(rowText(getString(R.string.no_local_files), getString(R.string.no_local_files_hint)))
        }
        files.forEach { file ->
            val video = file.extension.equals("mp4", true)
            val checked = CheckBox(this).apply {
                isChecked = file.absolutePath in state.selectedCaptures
                contentDescription = getString(R.string.select_file, file.name)
                setOnCheckedChangeListener { _, selected ->
                    viewModel.selectCapture(file, selected)
                }
            }
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(file.lastModified()))
            val size = getString(R.string.file_size_mb, file.length() / 1048576.0)
            val item = rowText(getString(if (video) R.string.video_file_name else R.string.image_file_name, file.name),
                getString(R.string.file_detail, time, size, getString(if (video) R.string.play else R.string.view)))
            item.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            item.setOnClickListener { openCapture(file) }
            capturesList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(checked)
                addView(item)
            })
        }
        deleteCapturesButton.isEnabled = state.selectedCaptures.isNotEmpty()
        deleteCapturesButton.text = if (state.selectedCaptures.isEmpty()) getString(R.string.delete_selected)
            else getString(R.string.delete_selected_count, state.selectedCaptures.size)
    }

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

    private fun confirmDeleteCaptures() {
        val files = viewModel.selectedCaptureFiles()
        if (files.isEmpty()) {
            viewModel.refreshCaptures()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_selected_prompt, files.size))
            .setMessage(R.string.cannot_undo)
            .setPositiveButton(R.string.delete) { _, _ ->
                val deleted = viewModel.deleteSelectedCaptures()
                showMessage(getString(R.string.deleted_count, deleted))
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
            view.setOnClickListener {
                urlInput.setText(url)
                showMessage(getString(R.string.url_filled))
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
        val requestId = ++discoveryRequestId
        viewModel.onOnvifSearchStarted()
        runCatching {
            onvif.search(credentials) { devices ->
                runOnUiThread {
                    if (isDestroyed || requestId != discoveryRequestId) return@runOnUiThread
                    viewModel.onOnvifSearchFinished(devices)
                }
            }
        }.onFailure {
            viewModel.setOnvifHint(getString(R.string.search_failed, safeError(it)))
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
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val summary = buildString {
            fun line(label: Int, value: String) {
                append(getString(R.string.detail_line, getString(label), value))
            }
            line(R.string.detail_source, getString(if (device.source == CameraSource.ONVIF)
                R.string.device_source_onvif else R.string.device_source_hikvision_probe))
            line(R.string.detail_ip, device.ipAddress)
            line(R.string.detail_service_address, device.serviceUrl)
            listOf(
                R.string.detail_name to device.name, R.string.detail_manufacturer to device.manufacturer,
                R.string.detail_model to device.model, R.string.detail_firmware to device.firmwareVersion,
                R.string.detail_serial to device.serialNumber, R.string.detail_hardware to device.hardwareId,
                R.string.detail_endpoint to device.endpointReference
            ).forEach { (label, value) -> if (value.isNotBlank()) line(label, value) }
            if (device.xAddrs.isNotEmpty()) line(R.string.detail_xaddrs, device.xAddrs.joinToString())
            if (device.scopes.isNotEmpty()) append(getString(R.string.detail_multiline,
                getString(R.string.detail_scopes), device.scopes.joinToString("\n")))
            device.properties.forEach { (name, value) -> append(getString(R.string.detail_line, name, value)) }
            details.warnings.forEach { line(R.string.detail_warning, it) }
        }
        content.addView(TextView(this).apply {
            text = summary.trim()
            textSize = 13f
            setTextColor(getColor(R.color.ink))
            setTextIsSelectable(true)
        })
        var dialog: AlertDialog? = null
        details.streams.forEachIndexed { index, stream ->
            content.addView(Button(this).apply {
                val streamName = when {
                    stream.returnedByDevice -> stream.name
                    device.source == CameraSource.HIKVISION_HTTP && index == 0 -> getString(R.string.hikvision_main_stream)
                    device.source == CameraSource.HIKVISION_HTTP -> getString(R.string.hikvision_sub_stream)
                    else -> getString(R.string.common_stream_path)
                }
                text = getString(R.string.use_stream, streamName,
                    getString(if (stream.returnedByDevice) R.string.stream_returned else R.string.stream_candidate))
                setAllCaps(false)
                setOnClickListener {
                    val url = OnvifSearcher.normalizeRtspUrl(
                        stream.url, device.ipAddress, credentials,
                        appendCredentials = credentials.username.isNotBlank()
                    )
                    urlInput.setText(url)
                    viewModel.setOnvifHint(getString(if (stream.returnedByDevice)
                        R.string.stream_filled_returned else R.string.stream_filled_candidate))
                    dialog?.dismiss()
                }
            })
        }
        if (BuildConfig.DEBUG && device.rawResponses.isNotEmpty()) {
            content.addView(TextView(this).apply {
                text = getString(R.string.raw_responses_header) + device.rawResponses.entries.joinToString("\n\n") {
                    getString(R.string.raw_response_entry, it.key, it.value)
                }
                textSize = 11f
                setTextColor(getColor(R.color.muted))
                setTextIsSelectable(true)
            })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_details_title, device.ipAddress))
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    private fun showSettings() {
        val options = arrayOf(getString(R.string.decoder_auto), getString(R.string.decoder_hardware),
            getString(R.string.privacy_notice_title), getString(R.string.privacy_delete_local_data),
            getString(R.string.open_source_notices))
        AlertDialog.Builder(this).setTitle(R.string.player_settings_title)
            .setItems(options) { _, which ->
                if (which == 2) {
                    showPrivacyNotice()
                    return@setItems
                }
                if (which == 3) {
                    confirmPrivacyReset()
                    return@setItems
                }
                if (which == 4) {
                    showOpenSourceNotices()
                    return@setItems
                }
                val mode = if (which == 0) DecoderMode.AUTO else DecoderMode.HARDWARE
                viewModel.setDecoderMode(mode)
                player?.setDecoderMode(mode)
                showMessage(getString(R.string.decoder_mode, options[which]))
            }.show()
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
            .apply { if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank())
                setNeutralButton(R.string.privacy_full_policy, null) }
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
            .apply { if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank())
                setNeutralButton(R.string.privacy_full_policy) { _, _ -> openPrivacyPolicy() } }
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
                    if (viewModel.state.recordingFile != null) {
                        player?.stopAllRecordings()
                        viewModel.onRecordingStopped()
                    }
                    player?.stop()
                    viewModel.onStopped()
                    discoveryRequestId++
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
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }
            .onFailure { showMessage(getString(R.string.privacy_open_failed)) }
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

    private fun watermarkSpec(target: WatermarkTarget): WatermarkSpec =
        RoboYiWatermarkMapper.spec(
            this, viewModel.state.watermark, target, getString(R.string.app_name), bitmapWatermark
        )

    private fun updateCurrentWatermark() {
        val target = if (viewModel.state.recordingFile != null) {
            WatermarkTarget.RECORDING
        } else {
            WatermarkTarget.PREVIEW
        }
        player?.updateWatermark(watermarkSpec(target))
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
                bitmapWatermarkButton.text = getString(R.string.edit_bitmap)
                viewModel.setWatermark(viewModel.state.watermark.copy(
                    applyPreview = true,
                    applyRecording = true
                ))
                updateCurrentWatermark()
                showMessage(getString(R.string.bitmap_applied))
            }
            .setNeutralButton(R.string.remove_layer) { _, _ ->
                bitmapWatermark = null
                bitmapWatermarkButton.text = getString(R.string.bitmap_draw)
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
        content.addView(label(getString(R.string.text_size, watermark.textSizeSp.toInt())))
        val sizeSeek = SeekBar(this).apply {
            max = 42
            progress = (watermark.textSizeSp - 12).toInt().coerceIn(0, 42)
        }
        content.addView(sizeSeek)
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
        const val PRIVACY_ACCEPTED_VERSION_KEY = "privacy_notice_accepted_version"
    }
}
