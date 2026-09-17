package com.roboyi.rtspdemo

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.roboyi.rtsp.DecoderMode
import com.roboyi.rtsp.CameraDevice
import com.roboyi.rtspdemo.data.AppCaptureRepository
import com.roboyi.rtspdemo.data.SharedPreferencesConnectionHistory
import com.roboyi.rtspdemo.domain.CaptureLibrary
import com.roboyi.rtspdemo.domain.ConnectionEntry
import com.roboyi.rtspdemo.domain.ConnectionHistoryRepository
import com.roboyi.rtspdemo.domain.PlaybackWorkflow
import com.roboyi.rtspdemo.domain.WatermarkSettings
import java.io.File

internal enum class PlaybackPhase { STOPPED, CONNECTING, PLAYING }
internal enum class SidebarTab { HISTORY, ONVIF, CAPTURES }

internal data class MainUiState(
    val licensed: Boolean = false,
    val licenseLabel: String = "",
    val playback: PlaybackPhase = PlaybackPhase.STOPPED,
    val streamInfo: String = "",
    val recordingFile: File? = null,
    val recordingPaused: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val fillMode: Boolean = false,
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val watermark: WatermarkSettings = WatermarkSettings(),
    val history: List<ConnectionEntry> = emptyList(),
    val captures: List<File> = emptyList(),
    val selectedCaptures: Set<String> = emptySet(),
    val tab: SidebarTab = SidebarTab.HISTORY,
    val onvifHint: String = "",
    val onvifSearching: Boolean = false,
    val onvifSearchCompleted: Boolean = false,
    val onvifDevices: List<CameraDevice> = emptyList<CameraDevice>()
)

/** 保存页面状态和业务操作；播放器 Fragment 与 Android 视图由 Activity 持有。 */
internal class MainViewModel(
    private val appContext: Context,
    private val prefs: SharedPreferences,
    private val historyRepository: ConnectionHistoryRepository,
    private val captureLibrary: CaptureLibrary,
    initialWatermark: WatermarkSettings
) : ViewModel() {
    private val workflow = PlaybackWorkflow(historyRepository)
    private val mutableState = MutableLiveData(
        MainUiState(
            licenseLabel = appContext.getString(R.string.checking_license),
            streamInfo = appContext.getString(R.string.waiting_stream),
            onvifHint = appContext.getString(R.string.onvif_initial_hint),
            watermark = initialWatermark,
            history = historyRepository.entries(),
            captures = captureLibrary.visibleFiles(null)
        )
    )
    val uiState: LiveData<MainUiState> = mutableState
    val state: MainUiState get() = checkNotNull(mutableState.value)
    val lastUrl: String get() = prefs.getString("last_url", DEFAULT_RTSP_URL).orEmpty()

    private fun update(transform: (MainUiState) -> MainUiState) {
        mutableState.value = transform(state)
    }

    fun onLicensed(label: String) {
        update { it.copy(licensed = true, licenseLabel = label) }
    }

    fun onLicenseFailed(label: String) {
        update { it.copy(licensed = false, licenseLabel = label) }
    }

    fun onConnectRequested(url: String) {
        workflow.onConnectRequested(url)
        prefs.edit().putString("last_url", url).apply()
        update { it.copy(playback = PlaybackPhase.CONNECTING, streamInfo = appContext.getString(R.string.connecting_stream)) }
    }

    fun onVideoReady() {
        if (state.playback != PlaybackPhase.CONNECTING) return
        val historyChanged = workflow.onVideoReady()
        update { it.copy(
            playback = PlaybackPhase.PLAYING,
            history = if (historyChanged) historyRepository.entries() else it.history
        ) }
    }

    fun onStreamInfo(info: String) {
        if (state.playback == PlaybackPhase.CONNECTING || state.playback == PlaybackPhase.PLAYING) {
            update { it.copy(streamInfo = info) }
        }
    }

    fun onStopped() {
        workflow.onStopped()
        update { it.copy(
            playback = PlaybackPhase.STOPPED,
            streamInfo = appContext.getString(R.string.waiting_stream),
            recordingFile = null,
            recordingPaused = false,
            recordingDurationMs = 0L
        ) }
        refreshCaptures()
    }

    /** SDK Fragment 销毁视图时会停止通道，Activity 重建后须清除保留的播放状态。 */
    fun onHostRecreated() {
        if (state.playback != PlaybackPhase.STOPPED || state.recordingFile != null) onStopped()
        update { it.copy(
            licenseLabel = appContext.getString(R.string.checking_license),
            streamInfo = appContext.getString(R.string.waiting_stream),
            onvifSearching = false,
            onvifSearchCompleted = false,
            onvifDevices = emptyList(),
            onvifHint = appContext.getString(R.string.onvif_initial_hint)
        ) }
    }

    fun onRecordingStarted(file: File) {
        update { it.copy(recordingFile = file, recordingPaused = false, recordingDurationMs = 0L) }
        refreshCaptures()
    }

    fun onRecordingPauseChanged(paused: Boolean) {
        if (state.recordingFile != null) update { it.copy(recordingPaused = paused) }
    }

    fun onRecordingDuration(file: File, durationMs: Long) {
        if (state.recordingFile == file) {
            update { it.copy(recordingDurationMs = durationMs) }
        }
    }

    fun onRecordingStopped(file: File? = null): File? {
        val active = state.recordingFile ?: return null
        if (file != null && file != active) return null
        update { it.copy(recordingFile = null, recordingPaused = false, recordingDurationMs = 0L) }
        refreshCaptures()
        return active
    }

    fun toggleFillMode(): Boolean {
        val next = !state.fillMode
        update { it.copy(fillMode = next) }
        return next
    }

    fun setDecoderMode(mode: DecoderMode) = update { it.copy(decoderMode = mode) }

    fun setWatermark(settings: WatermarkSettings) {
        prefs.edit().putString("watermark_text", settings.text).apply()
        update { it.copy(watermark = settings) }
    }

    fun newSnapshotFile(): File = captureLibrary.newSnapshotFile()
    fun newRecordingFile(): File = captureLibrary.newRecordingFile()

    fun refreshCaptures() {
        val files = captureLibrary.visibleFiles(state.recordingFile)
        val paths = files.mapTo(mutableSetOf()) { it.absolutePath }
        update { it.copy(captures = files, selectedCaptures = it.selectedCaptures.intersect(paths)) }
    }

    fun selectCapture(file: File, selected: Boolean) {
        update { current ->
            current.copy(selectedCaptures = if (selected) current.selectedCaptures + file.absolutePath
                else current.selectedCaptures - file.absolutePath)
        }
    }

    fun selectedCaptureFiles(): List<File> = state.captures.filter { it.absolutePath in state.selectedCaptures }

    fun deleteSelectedCaptures(): Int {
        val deleted = captureLibrary.delete(selectedCaptureFiles())
        update { it.copy(selectedCaptures = emptySet()) }
        refreshCaptures()
        return deleted
    }

    fun clearHistory() {
        historyRepository.clear()
        update { it.copy(history = historyRepository.entries()) }
    }

    fun showTab(tab: SidebarTab) = update { it.copy(tab = tab) }

    fun onOnvifSearchStarted() = update { it.copy(
        onvifSearching = true,
        onvifSearchCompleted = false,
        onvifHint = appContext.getString(R.string.searching_devices),
        onvifDevices = emptyList<CameraDevice>()
    ) }

    fun onOnvifSearchFinished(devices: List<CameraDevice>) = update { it.copy(
        onvifSearching = false,
        onvifSearchCompleted = true,
        onvifHint = appContext.getString(R.string.devices_found, devices.size),
        onvifDevices = devices
    ) }

    fun setOnvifHint(hint: String) = update { it.copy(
        onvifHint = hint,
        onvifSearching = false
    ) }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            val prefs = appContext.getSharedPreferences("demo", Context.MODE_PRIVATE)
            val watermark = WatermarkSettings(
                text = prefs.getString("watermark_text", "").orEmpty(),
                imagePath = File(appContext.filesDir, "demo-watermark.png")
                    .takeIf { it.isFile }?.absolutePath
            )
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(
                appContext,
                prefs,
                SharedPreferencesConnectionHistory(prefs),
                CaptureLibrary(AppCaptureRepository(appContext)),
                watermark
            ) as T
        }
    }

    companion object {
        private const val DEFAULT_RTSP_URL =
            "rtsp://172.169.10.65:554/H264?W=1920&H=1080&FPS=30&BR=8000000"
    }
}
