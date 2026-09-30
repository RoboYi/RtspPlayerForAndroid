package com.roboyi.rtspdemo

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.roboyi.camerapush.CameraDeviceInfo
import com.roboyi.camerapush.CameraFacing
import com.roboyi.camerapush.CameraPreviewController
import com.roboyi.camerapush.CameraRtspPushConfig
import com.roboyi.camerapush.CameraRtspPushController
import com.roboyi.camerapush.CameraVideoSize
import com.roboyi.camerapush.VideoCodec
import com.roboyi.rtsp.RoboYiPlayer
import com.roboyi.rtsp.UvcCameraEnumerator
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

/**
 * 推流工作台只负责参数编辑和预览；正式推流由 [CameraPushService] 独占。
 * 因此切换到播放、设备或文件页时，Activity 生命周期不会误停摄像头推流。
 */
class CameraPushActivity : AppCompatActivity() {
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var sourceSpinner: Spinner
    private lateinit var sizeSpinner: Spinner
    private lateinit var fpsSpinner: Spinner
    private lateinit var codecSpinner: Spinner
    private lateinit var bitrateInput: EditText
    private lateinit var pathInput: EditText
    private lateinit var osdButton: Button
    private lateinit var osdSummary: TextView
    private lateinit var toggleButton: Button
    private lateinit var copyButton: Button
    private lateinit var testButton: Button
    private lateinit var torchButton: Button
    private lateinit var urlText: TextView
    private lateinit var uvcHint: TextView
    private lateinit var licenseStatus: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerTime: TextView
    private lateinit var headerStreamInfo: TextView

    // 探测控制器仅查询设备能力，不负责正在运行的推流。
    private val cameraProbeController by lazy { CameraRtspPushController(this, RTSP_PORT) }
    private val previewController by lazy { CameraPreviewController(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var previewSurface: Surface? = null
    private var cameras: List<CameraDeviceInfo> = emptyList()
    private var activeUrl: String? = null
    private var suppressSelectionCallbacks = false
    private var lastPhase = CameraPushPhase.IDLE
    private var pushConfigAutoCollapsed = false
    private var torchEnabled = false
    private val osdItems = mutableListOf<PushOsdItem>()

    private val runtimeTick = object : Runnable {
        override fun run() {
            syncRuntime()
            headerTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            handler.postDelayed(this, 500L)
        }
    }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadCameras() else {
            status.setText(R.string.camera_push_permission_denied)
            setControlsEnabled(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_push)
        bindViews()
        setupCollapsibleOverlays()
        setupTopNavigation()
        osdItems += PushOsdConfig.decode(savedInstanceState?.getString(STATE_OSD_ITEMS))
        updateOsdSummary()

        toggleButton.setOnClickListener {
            if (isPushBusy()) stopPush() else startPush()
        }
        copyButton.setOnClickListener { copyActiveUrl() }
        testButton.setOnClickListener {
            activeUrl?.let { AppNavigation.openMain(this, MainSection.PLAYBACK, it, autoPlay = true) }
        }
        osdButton.setOnClickListener { showOsdSettings() }
        torchButton.setOnClickListener { toggleTorch() }

        codecSpinner.adapter = textAdapter(listOf("H.264", "H.265"))
        sourceSpinner.onItemSelectedListener = selectionListener { refreshCameraOptions() }
        sizeSpinner.onItemSelectedListener = selectionListener { restartPreview() }
        fpsSpinner.onItemSelectedListener = selectionListener { restartPreview() }
        codecSpinner.onItemSelectedListener = selectionListener { loadCameras() }

        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                previewSurface?.release()
                previewSurface = Surface(texture)
                applyPreviewBufferSize()
                restartPreview()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform()
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                previewController.stop()
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }

        if (!activateSharedLicense()) return
        if (hasCameraPermission()) {
            if (!isPushBusy()) loadCameras() else syncRuntime()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(runtimeTick)
        handler.post(runtimeTick)
    }

    override fun onStop() {
        handler.removeCallbacks(runtimeTick)
        if (!isPushBusy()) previewController.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission() && !isPushBusy()) {
            if (cameras.isEmpty()) loadCameras() else restartPreview()
        }
    }

    override fun onDestroy() {
        // 不停止 CameraPushService；旋转或页面切换不应影响后台推流。
        previewController.stop()
        previewSurface?.release()
        previewSurface = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_OSD_ITEMS, PushOsdConfig.encode(osdItems))
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        preview = findViewById(R.id.camera_push_preview)
        status = findViewById(R.id.camera_push_status)
        sourceSpinner = findViewById(R.id.camera_push_source_spinner)
        sizeSpinner = findViewById(R.id.camera_push_size_spinner)
        fpsSpinner = findViewById(R.id.camera_push_fps_spinner)
        codecSpinner = findViewById(R.id.camera_push_codec_spinner)
        bitrateInput = findViewById(R.id.camera_push_bitrate_input)
        pathInput = findViewById(R.id.camera_push_path_input)
        osdButton = findViewById(R.id.camera_push_osd_settings)
        osdSummary = findViewById(R.id.camera_push_osd_summary)
        toggleButton = findViewById(R.id.camera_push_toggle)
        copyButton = findViewById(R.id.camera_push_copy)
        testButton = findViewById(R.id.camera_push_test)
        torchButton = findViewById(R.id.camera_push_torch)
        urlText = findViewById(R.id.camera_push_url)
        uvcHint = findViewById(R.id.camera_push_uvc_hint)
        licenseStatus = findViewById(R.id.license_status)
        headerTitle = findViewById(R.id.header_title)
        headerTime = findViewById(R.id.header_time)
        headerStreamInfo = findViewById(R.id.stream_info)
        headerTitle.setText(R.string.nav_push)
        headerTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_nav_push, 0, 0, 0)
        headerStreamInfo.setText(R.string.camera_push_title)
    }

    private fun setupCollapsibleOverlays() {
        bindCollapsibleOverlay(
            panelId = R.id.push_config_scroll,
            collapseId = R.id.push_config_collapse,
            revealId = R.id.push_config_reveal
        )
        bindCollapsibleOverlay(
            panelId = R.id.app_header,
            collapseId = R.id.header_collapse,
            revealId = R.id.header_reveal
        )
    }

    private fun bindCollapsibleOverlay(panelId: Int, collapseId: Int, revealId: Int) {
        val panel = findViewById<View>(panelId)
        val reveal = findViewById<View>(revealId)
        findViewById<View>(collapseId).setOnClickListener {
            panel.visibility = View.GONE
            reveal.visibility = View.VISIBLE
        }
        reveal.setOnClickListener {
            panel.visibility = View.VISIBLE
            reveal.visibility = View.GONE
        }
    }

    private fun setPushConfigCollapsed(collapsed: Boolean) {
        findViewById<View>(R.id.push_config_scroll).visibility = if (collapsed) View.GONE else View.VISIBLE
        findViewById<View>(R.id.push_config_reveal).visibility = if (collapsed) View.VISIBLE else View.GONE
    }

    private fun setupTopNavigation() {
        findViewById<Button>(R.id.projects_tab).setOnClickListener {
            AppNavigation.openMain(this, MainSection.PLAYBACK, dialog = "projects")
        }
        findViewById<Button>(R.id.history_tab).setOnClickListener {
            AppNavigation.openMain(this, MainSection.PLAYBACK)
        }
        findViewById<Button>(R.id.captures_tab).setOnClickListener {
            AppNavigation.openMain(this, MainSection.FILES)
        }
        findViewById<Button>(R.id.camera_push_button).apply {
            setBackgroundResource(R.drawable.primary_button)
            setTextColor(Color.WHITE)
        }
        findViewById<ImageButton>(R.id.settings_button).setOnClickListener {
            AppNavigation.openMain(this, MainSection.PLAYBACK, dialog = AppNavigation.DIALOG_SETTINGS)
        }
        findViewById<ImageButton>(R.id.about_button).setOnClickListener {
            AppNavigation.openMain(this, MainSection.PLAYBACK, dialog = AppNavigation.DIALOG_ABOUT)
        }
    }

    private fun activateSharedLicense(): Boolean = runCatching {
        RoboYiPlayer.initialize(applicationContext, BuildConfig.ROBOYI_LICENSE)
        licenseStatus.setText(R.string.license_status_ok)
        licenseStatus.setTextColor(getColor(R.color.green))
    }.onFailure { error ->
        status.text = getString(R.string.camera_push_failed, readableError(error))
        setControlsEnabled(false)
    }.isSuccess

    private fun loadCameras() {
        if (!hasCameraPermission() || isPushBusy()) return
        runCatching {
            cameraProbeController.listCameras(codec = selectedCodec()).distinctBy { it.facing }
        }.onSuccess { available ->
            cameras = available
            suppressSelectionCallbacks = true
            sourceSpinner.adapter = textAdapter(available.map { facingLabel(it.facing) })
            sourceSpinner.setSelection(available.indexOfFirst { it.facing == CameraFacing.BACK }.coerceAtLeast(0))
            suppressSelectionCallbacks = false
            if (available.isEmpty()) {
                status.setText(R.string.camera_push_no_camera)
                setControlsEnabled(false)
            } else {
                setControlsEnabled(true)
                refreshCameraOptions()
            }
            updateUvcHintAsync(available.any { it.facing == CameraFacing.EXTERNAL })
        }.onFailure { error ->
            status.text = getString(R.string.camera_push_failed, readableError(error))
            setControlsEnabled(false)
        }
    }

    private fun refreshCameraOptions() {
        if (suppressSelectionCallbacks || isPushBusy()) return
        val camera = selectedCamera() ?: return
        if (!camera.torchAvailable) torchEnabled = false
        suppressSelectionCallbacks = true
        sizeSpinner.adapter = textAdapter(camera.supportedSizes.map(CameraVideoSize::toString))
        val defaultSize = camera.supportedSizes.indexOfFirst { it.width == 1280 && it.height == 720 }
            .takeIf { it >= 0 } ?: 0
        sizeSpinner.setSelection(defaultSize)
        val rates = camera.supportedFrameRates.ifEmpty { listOf(15, 25, 30) }
        fpsSpinner.adapter = textAdapter(rates.map { "$it fps" })
        fpsSpinner.setSelection(rates.indexOf(25).takeIf { it >= 0 }
            ?: rates.indexOf(30).takeIf { it >= 0 } ?: 0)
        suppressSelectionCallbacks = false
        applyPreviewBufferSize()
        restartPreview()
        renderTorchControl()
    }

    private fun restartPreview() {
        if (suppressSelectionCallbacks || isPushBusy() || !hasCameraPermission()) return
        val surface = previewSurface?.takeIf { it.isValid } ?: return
        val config = buildConfig() ?: return
        runCatching { previewController.start(config, surface) }
            .onSuccess {
                applyPreviewTransform()
                status.text = getString(
                    R.string.camera_push_previewing,
                    facingLabel(config.facing),
                    "${config.width}x${config.height}",
                    config.frameRate
                )
            }
            .onFailure { status.text = getString(R.string.camera_push_failed, readableError(it)) }
    }

    private fun startPush() {
        val config = buildConfig()
        if (config == null) {
            Toast.makeText(this, R.string.camera_push_invalid_value, Toast.LENGTH_SHORT).show()
            return
        }
        previewController.stop()
        setControlsEnabled(false)
        status.setText(R.string.push_starting)
        runCatching { CameraPushService.start(this, config, osdItems.toList()) }
            .onFailure {
                status.text = getString(R.string.camera_push_failed, readableError(it))
                setControlsEnabled(cameras.isNotEmpty())
                restartPreview()
            }
    }

    private fun stopPush() {
        status.setText(R.string.camera_push_stopped)
        toggleButton.isEnabled = false
        CameraPushService.stop(this)
    }

    /** 每 500ms 同步轻量快照；不绑定服务，页面与推流生命周期保持松耦合。 */
    private fun syncRuntime() {
        val snapshot = CameraPushRuntime.snapshot
        if (snapshot.phase == CameraPushPhase.RUNNING) {
            torchEnabled = snapshot.config?.torchEnabled ?: torchEnabled
        }
        if (snapshot.phase != lastPhase && snapshot.config != null) {
            torchEnabled = snapshot.config.torchEnabled
            if (cameras.isEmpty()) {
                cameras = runCatching {
                    cameraProbeController.listCameras(snapshot.config.codec).distinctBy { it.facing }
                }.getOrDefault(emptyList())
            }
        }
        activeUrl = snapshot.url
        headerStreamInfo.text = snapshot.config?.let {
            "${it.width}×${it.height}  |  ${it.frameRate} FPS  |  ${it.bitRate / 1000} kbps"
        } ?: getString(R.string.camera_push_title)
        when (snapshot.phase) {
            CameraPushPhase.IDLE -> {
                if (pushConfigAutoCollapsed && lastPhase != CameraPushPhase.IDLE) {
                    setPushConfigCollapsed(false)
                    pushConfigAutoCollapsed = false
                }
                urlText.setText(R.string.runtime_unknown)
                toggleButton.setText(R.string.camera_push_start)
                copyButton.isEnabled = false
                testButton.isEnabled = false
                setControlsEnabled(cameras.isNotEmpty())
                if (lastPhase != CameraPushPhase.IDLE) {
                    if (cameras.isEmpty() && hasCameraPermission()) loadCameras() else restartPreview()
                }
            }
            CameraPushPhase.STARTING -> {
                status.setText(R.string.push_starting)
                toggleButton.isEnabled = true
                toggleButton.setText(R.string.camera_push_stop)
                setControlsEnabled(false)
            }
            CameraPushPhase.RUNNING -> {
                if (lastPhase != CameraPushPhase.RUNNING) {
                    setPushConfigCollapsed(true)
                    pushConfigAutoCollapsed = true
                }
                status.text = getString(R.string.camera_push_active, snapshot.url)
                urlText.text = snapshot.url
                toggleButton.isEnabled = true
                toggleButton.setText(R.string.camera_push_stop)
                copyButton.isEnabled = true
                testButton.isEnabled = true
                setControlsEnabled(false)
            }
            CameraPushPhase.STOPPING -> {
                status.setText(R.string.camera_push_stopped)
                toggleButton.isEnabled = false
                setControlsEnabled(false)
            }
            CameraPushPhase.FAILED -> {
                if (pushConfigAutoCollapsed && lastPhase != CameraPushPhase.FAILED) {
                    setPushConfigCollapsed(false)
                    pushConfigAutoCollapsed = false
                }
                urlText.setText(R.string.runtime_unknown)
                status.text = getString(R.string.push_service_failed, snapshot.error.orEmpty())
                toggleButton.setText(R.string.camera_push_start)
                setControlsEnabled(cameras.isNotEmpty())
                if (lastPhase != CameraPushPhase.FAILED) restartPreview()
            }
        }
        renderTorchControl()
        lastPhase = snapshot.phase
    }

    private fun buildConfig(): CameraRtspPushConfig? {
        val camera = selectedCamera() ?: return null
        val size = camera.supportedSizes.getOrNull(sizeSpinner.selectedItemPosition) ?: return null
        val fps = camera.supportedFrameRates.ifEmpty { listOf(15, 25, 30) }
            .getOrNull(fpsSpinner.selectedItemPosition) ?: return null
        val bitRateKbps = bitrateInput.text.toString().toIntOrNull()?.takeIf { it in 300..20_000 }
            ?: return null
        val path = pathInput.text.toString().trim().trim('/').takeIf { it.isNotBlank() } ?: return null
        return CameraRtspPushConfig(
            facing = camera.facing,
            width = size.width,
            height = size.height,
            cameraWidth = size.width,
            cameraHeight = size.height,
            frameRate = fps,
            bitRate = bitRateKbps * 1_000,
            path = path,
            codec = selectedCodec(),
            watermarkEnabled = osdItems.isNotEmpty(),
            watermarkSpec = PushOsdConfig.toWatermarkSpec(osdItems),
            torchEnabled = torchEnabled
        )
    }

    /**
     * 推流 OSD 与播放器水印一样使用图层思路：每一项都能独立添加、编辑、定位和删除。
     * 配置变化后只重启本地预览，正在运行的后台推流保持不可编辑，避免中途切换编码管线。
     */
    private fun showOsdSettings() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        content.addView(TextView(this).apply {
            setText(R.string.push_osd_description)
            textSize = 13f
            setTextColor(getColor(R.color.muted))
            setPadding(0, 0, 0, dp(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        lateinit var renderList: () -> Unit
        renderList = {
            list.removeAllViews()
            if (osdItems.isEmpty()) {
                list.addView(TextView(this).apply {
                    setText(R.string.push_osd_empty)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.muted))
                    setPadding(dp(8), dp(24), dp(8), dp(24))
                })
            }
            osdItems.forEachIndexed { index, item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    setBackgroundResource(R.drawable.outline_button)
                }
                row.addView(TextView(this).apply {
                    text = getString(R.string.push_osd_item_title, index + 1, item.text)
                    textSize = 15f
                    setTextColor(getColor(R.color.ink))
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                row.addView(TextView(this).apply {
                    text = getString(
                        R.string.push_osd_item_meta,
                        (item.x * 100).toInt(),
                        (item.y * 100).toInt(),
                        item.textSizeSp.toInt(),
                        getString(if (item.includeTimestamp) R.string.push_osd_with_time else R.string.push_osd_without_time)
                    )
                    textSize = 12f
                    setTextColor(getColor(R.color.muted))
                    setPadding(0, dp(4), 0, dp(8))
                })
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@CameraPushActivity).apply {
                        setText(R.string.edit)
                        isAllCaps = false
                        setTextColor(getColor(R.color.blue))
                        setBackgroundResource(R.drawable.outline_button)
                        setOnClickListener {
                            showOsdItemEditor(item) { updated ->
                                osdItems[index] = updated
                                onOsdItemsChanged()
                                renderList()
                            }
                        }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(Button(this@CameraPushActivity).apply {
                        setText(R.string.delete)
                        isAllCaps = false
                        setTextColor(getColor(R.color.danger))
                        setBackgroundResource(R.drawable.outline_button)
                        setOnClickListener {
                            osdItems.removeAt(index)
                            onOsdItemsChanged()
                            renderList()
                        }
                    }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
                })
                list.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) })
            }
        }
        renderList()

        content.addView(Button(this).apply {
            setText(R.string.push_osd_add)
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.primary_button)
            setOnClickListener {
                showOsdItemEditor(null) { item ->
                    osdItems += item
                    onOsdItemsChanged()
                    renderList()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        if (osdItems.isNotEmpty()) {
            content.addView(Button(this).apply {
                setText(R.string.push_osd_clear)
                isAllCaps = false
                setTextColor(getColor(R.color.danger))
                setBackgroundResource(R.drawable.outline_button)
                setOnClickListener {
                    osdItems.clear()
                    onOsdItemsChanged()
                    renderList()
                    visibility = View.GONE
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            })
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.push_osd_title)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showOsdItemEditor(existing: PushOsdItem?, onSave: (PushOsdItem) -> Unit) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.ink))
            setPadding(0, dp(10), 0, dp(4))
        }
        val textInput = EditText(this).apply {
            setText(existing?.text.orEmpty())
            setHint(R.string.watermark_text_hint)
            setSingleLine(true)
            setTextColor(getColor(R.color.ink))
            setHintTextColor(getColor(R.color.muted))
            setBackgroundResource(R.drawable.outline_button)
            setPadding(dp(12), 0, dp(12), 0)
        }
        content.addView(label(getString(R.string.osd_text)))
        content.addView(textInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val xSeek = SeekBar(this).apply { max = 95; progress = ((existing?.x ?: 0.04f) * 100).toInt() }
        val ySeek = SeekBar(this).apply { max = 95; progress = ((existing?.y ?: 0.05f) * 100).toInt() }
        val sizeSeek = SeekBar(this).apply { max = 42; progress = ((existing?.textSizeSp ?: 20f) - 12).toInt() }
        val xLabel = label("")
        val yLabel = label("")
        val sizeLabel = label("")
        fun refreshLabels() {
            xLabel.text = getString(R.string.push_osd_x, xSeek.progress)
            yLabel.text = getString(R.string.push_osd_y, ySeek.progress)
            sizeLabel.text = getString(R.string.text_size, sizeSeek.progress + 12)
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = refreshLabels()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        xSeek.setOnSeekBarChangeListener(listener)
        ySeek.setOnSeekBarChangeListener(listener)
        sizeSeek.setOnSeekBarChangeListener(listener)
        refreshLabels()
        content.addView(xLabel)
        content.addView(xSeek)
        content.addView(yLabel)
        content.addView(ySeek)
        content.addView(sizeLabel)
        content.addView(sizeSeek)

        content.addView(label(getString(R.string.text_color)))
        val colors = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf(
            R.string.color_white to Color.WHITE,
            R.string.color_black to Color.BLACK,
            R.string.color_blue to getColor(R.color.blue)
        ).forEach { (name, color) ->
            colors.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(name)
                tag = color
                isChecked = color == (existing?.textColor ?: Color.WHITE)
            })
        }
        content.addView(colors)
        val timestamp = CheckBox(this).apply {
            setText(R.string.push_osd_timestamp)
            isChecked = existing?.includeTimestamp ?: false
            setTextColor(getColor(R.color.ink))
        }
        content.addView(timestamp)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.push_osd_add else R.string.push_osd_edit)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.apply) { _, _ ->
                val color = colors.findViewById<RadioButton>(colors.checkedRadioButtonId)?.tag as? Int
                    ?: Color.WHITE
                onSave(PushOsdItem(
                    id = existing?.id ?: "osd_${System.currentTimeMillis()}",
                    text = textInput.text.toString().trim().ifBlank { getString(R.string.push_osd_default_text) },
                    x = xSeek.progress / 100f,
                    y = ySeek.progress / 100f,
                    textSizeSp = (sizeSeek.progress + 12).toFloat(),
                    textColor = color,
                    includeTimestamp = timestamp.isChecked
                ))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onOsdItemsChanged() {
        updateOsdSummary()
        restartPreview()
    }

    private fun updateOsdSummary() {
        osdSummary.text = if (osdItems.isEmpty()) {
            getString(R.string.push_osd_none)
        } else {
            getString(R.string.push_osd_count, osdItems.size)
        }
    }

    private fun selectedCamera(): CameraDeviceInfo? = cameras.getOrNull(sourceSpinner.selectedItemPosition)

    private fun toggleTorch() {
        val snapshot = CameraPushRuntime.snapshot
        val facing = snapshot.config?.facing ?: selectedCamera()?.facing
        val available = cameras.firstOrNull { it.facing == facing }?.torchAvailable == true
        if (!available) {
            Toast.makeText(this, R.string.camera_push_torch_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val target = !torchEnabled
        if (snapshot.phase == CameraPushPhase.RUNNING) {
            torchEnabled = target
            renderTorchControl()
            CameraPushService.setTorchEnabled(this, target)
            return
        }
        runCatching {
            !previewController.isRunning() || previewController.setTorchEnabled(target)
        }.onSuccess { applied ->
            if (applied) {
                torchEnabled = target
                renderTorchControl()
            }
        }.onFailure { error ->
            Toast.makeText(
                this,
                getString(R.string.camera_push_torch_failed, readableError(error)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderTorchControl() {
        if (!::torchButton.isInitialized) return
        val snapshot = CameraPushRuntime.snapshot
        val facing = snapshot.config?.facing ?: selectedCamera()?.facing
        val available = cameras.firstOrNull { it.facing == facing }?.torchAvailable == true
        torchButton.isEnabled = available && snapshot.phase !in setOf(
            CameraPushPhase.STARTING,
            CameraPushPhase.STOPPING
        )
        torchButton.setText(if (torchEnabled) R.string.camera_push_torch_on else R.string.camera_push_torch)
        torchButton.setBackgroundResource(
            if (torchEnabled) R.drawable.primary_button else android.R.color.transparent
        )
        torchButton.setTextColor(getColor(if (torchEnabled) android.R.color.white else R.color.ink))
    }

    private fun selectedCodec(): VideoCodec =
        if (codecSpinner.selectedItemPosition == 1) VideoCodec.H265 else VideoCodec.H264

    private fun applyPreviewBufferSize() {
        val size = selectedCamera()?.supportedSizes?.getOrNull(sizeSpinner.selectedItemPosition) ?: return
        preview.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
        if (preview.width > 0 && preview.height > 0) {
            applyPreviewTransform()
        } else {
            preview.post { applyPreviewTransform() }
        }
    }

    /**
     * SurfaceTexture receives the camera sensor's native orientation. Rotate it into the current
     * display orientation and use a center-crop transform so the preview fills the screen without
     * stretching. This affects only the local TextureView; encoded RTSP frames remain unchanged.
     */
    private fun applyPreviewTransform() {
        val camera = selectedCamera() ?: return
        val size = camera.supportedSizes.getOrNull(sizeSpinner.selectedItemPosition) ?: return
        val viewWidth = preview.width.toFloat().takeIf { it > 0f } ?: return
        val viewHeight = preview.height.toFloat().takeIf { it > 0f } ?: return
        val displayDegrees = when (preview.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val rawRotation = if (camera.facing == CameraFacing.FRONT) {
            camera.sensorOrientation - displayDegrees
        } else {
            camera.sensorOrientation + displayDegrees
        }
        val rotation = ((rawRotation % 360) + 360) % 360
        val quarterTurn = rotation == 90 || rotation == 270
        val orientedWidth = if (quarterTurn) size.height.toFloat() else size.width.toFloat()
        val orientedHeight = if (quarterTurn) size.width.toFloat() else size.height.toFloat()
        val scale = max(viewWidth / orientedWidth, viewHeight / orientedHeight)
        val sourceScaleX = scale * size.width / viewWidth
        val sourceScaleY = scale * size.height / viewHeight
        val (cos, sin) = when (rotation) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        val scaleX = cos * sourceScaleX
        val skewX = -sin * sourceScaleY
        val skewY = sin * sourceScaleX
        val scaleY = cos * sourceScaleY
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        preview.setTransform(Matrix().apply {
            setValues(floatArrayOf(
                scaleX,
                skewX,
                centerX - scaleX * centerX - skewX * centerY,
                skewY,
                scaleY,
                centerY - skewY * centerX - scaleY * centerY,
                0f,
                0f,
                1f
            ))
        })
    }

    private fun setControlsEnabled(enabled: Boolean) {
        val editable = enabled && !isPushBusy()
        sourceSpinner.isEnabled = editable
        sizeSpinner.isEnabled = editable
        fpsSpinner.isEnabled = editable
        codecSpinner.isEnabled = editable
        bitrateInput.isEnabled = editable
        pathInput.isEnabled = editable
        osdButton.isEnabled = editable
        if (!isPushBusy()) toggleButton.isEnabled = enabled
    }

    private fun isPushBusy(): Boolean = CameraPushRuntime.snapshot.phase in setOf(
        CameraPushPhase.STARTING,
        CameraPushPhase.RUNNING,
        CameraPushPhase.STOPPING
    )

    private fun updateUvcHintAsync(camera2ExternalAvailable: Boolean) {
        if (camera2ExternalAvailable) {
            uvcHint.setText(R.string.camera_push_uvc_ready)
            return
        }
        Thread({
            val directUvcCount = runCatching {
                UvcCameraEnumerator(applicationContext).listDevices().size
            }.getOrDefault(0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                uvcHint.text = if (directUvcCount > 0) {
                    getString(R.string.camera_push_uvc_adapter_required, directUvcCount)
                } else {
                    getString(R.string.camera_push_uvc_none)
                }
            }
        }, "CameraPush-UvcProbe").start()
    }

    private fun copyActiveUrl() {
        val url = activeUrl ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RTSP URL", url))
        Toast.makeText(this, R.string.camera_push_url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun facingLabel(facing: CameraFacing): String = getString(
        when (facing) {
            CameraFacing.BACK -> R.string.camera_push_back
            CameraFacing.FRONT -> R.string.camera_push_front
            CameraFacing.EXTERNAL -> R.string.camera_push_external
        }
    )

    private fun textAdapter(values: List<String>) = ArrayAdapter(
        this,
        android.R.layout.simple_spinner_dropdown_item,
        values
    )

    private fun selectionListener(onSelected: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun readableError(error: Throwable): String = sequenceOf(error, error.cause)
        .mapNotNull { it?.message }
        .firstOrNull { it.isNotBlank() }
        ?: error::class.java.simpleName

    companion object {
        private const val RTSP_PORT = 8554
        private const val STATE_OSD_ITEMS = "camera_push_osd_items"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
