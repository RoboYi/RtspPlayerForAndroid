package com.roboyi.rtspdemo

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.roboyi.rtsp.DecoderMode
import com.roboyi.rtsp.CameraDevice
import com.roboyi.rtsp.CameraSearchPhase
import com.roboyi.rtsp.CameraSearchEnd
import com.roboyi.rtsp.CameraSearchUpdate
import com.roboyi.rtsp.DigitalZoom
import com.roboyi.rtsp.VideoCropRegion
import com.roboyi.rtspdemo.data.AppCaptureRepository
import com.roboyi.rtspdemo.data.InspectionStore
import com.roboyi.rtspdemo.data.SharedPreferencesConnectionHistory
import com.roboyi.rtspdemo.data.SecureConnectionStore
import com.roboyi.rtspdemo.domain.CaptureLibrary
import com.roboyi.rtspdemo.domain.ConnectionEntry
import com.roboyi.rtspdemo.domain.ConnectionHistoryRepository
import com.roboyi.rtspdemo.domain.PlaybackWorkflow
import com.roboyi.rtspdemo.domain.WatermarkSettings
import java.io.File

internal enum class PlaybackPhase { STOPPED, CONNECTING, PLAYING }

internal data class LocalDataDeletionResult(
    val capturesRequested: Int,
    val capturesDeleted: Int,
    val watermarkDeleted: Boolean,
    val inspectionDataDeleted: Boolean
) {
    val complete: Boolean get() = capturesRequested == capturesDeleted && watermarkDeleted && inspectionDataDeleted
}

internal data class MainUiState(
    val licensed: Boolean = false,
    val licenseLabel: String = "",
    val playback: PlaybackPhase = PlaybackPhase.STOPPED,
    val streamInfo: String = "",
    val recordingFile: File? = null,
    val recordingPaused: Boolean = false,
    val recordingDurationMs: Long = 0L,
    val fillMode: Boolean = false,
    val digitalZoomFactor: Float = DigitalZoom.MIN_FACTOR,
    val videoCropRegion: VideoCropRegion = VideoCropRegion.FULL_FRAME,
    val decoderMode: DecoderMode = DecoderMode.AUTO,
    val watermark: WatermarkSettings = WatermarkSettings(),
    val history: List<ConnectionEntry> = emptyList(),
    val captures: List<File> = emptyList(),
    val section: MainSection = MainSection.PLAYBACK,
    val onvifHint: String = "",
    val onvifSearching: Boolean = false,
    val onvifSearchCompleted: Boolean = false,
    val onvifDevices: List<CameraDevice> = emptyList<CameraDevice>()
)

/** 保存页面状态和业务操作；播放器 Fragment 与 Android 视图由 Activity 持有。 */
internal class MainViewModel(
    private val appContext: Context,
    private val prefs: SharedPreferences,
    private val connectionStore: SecureConnectionStore,
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
    val lastUrl: String get() = connectionStore.get(SecureConnectionStore.LAST_URL).orEmpty()

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
        connectionStore.put(SecureConnectionStore.LAST_URL, url)
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

    fun onSelectedPreviewChanged(
        playback: PlaybackPhase,
        streamInfo: String,
        fillMode: Boolean,
        digitalZoomFactor: Float,
        videoCropRegion: VideoCropRegion
    ) = update {
        it.copy(
            playback = playback,
            streamInfo = streamInfo,
            fillMode = fillMode,
            digitalZoomFactor = digitalZoomFactor,
            videoCropRegion = videoCropRegion
        )
    }

    fun toggleFillMode(): Boolean {
        val next = !state.fillMode
        update { it.copy(fillMode = next) }
        return next
    }

    fun setDigitalZoom(factor: Float): Float {
        val normalized = DigitalZoom.normalize(factor)
        update {
            it.copy(
                digitalZoomFactor = normalized,
                videoCropRegion = VideoCropRegion.centeredForZoom(normalized)
            )
        }
        return normalized
    }

    fun setVideoCrop(region: VideoCropRegion): VideoCropRegion {
        update {
            it.copy(
                videoCropRegion = region,
                digitalZoomFactor = region.equivalentZoomFactor
            )
        }
        return region
    }

    fun setDecoderMode(mode: DecoderMode) = update { it.copy(decoderMode = mode) }

    fun setWatermark(settings: WatermarkSettings) {
        prefs.edit().putString("watermark_text", settings.text).apply()
        update { it.copy(watermark = settings) }
    }

    fun deleteWatermarkImage(): Boolean {
        val image = File(appContext.filesDir, "demo-watermark.png")
        if (image.exists() && !image.delete()) return false
        update { it.copy(watermark = it.watermark.copy(imagePath = null)) }
        return true
    }

    fun newSnapshotFile(): File = captureLibrary.newSnapshotFile()
    fun newRecordingFile(): File = captureLibrary.newRecordingFile()
    fun publishCapture(file: File): File = captureLibrary.publish(file)

    fun refreshCaptures() {
        val files = captureLibrary.visibleFiles(state.recordingFile)
        update { it.copy(captures = files) }
    }

    fun clearHistory() {
        historyRepository.clear()
        connectionStore.remove(SecureConnectionStore.LAST_URL)
        update { it.copy(history = historyRepository.entries()) }
    }

    fun deleteLocalData(): LocalDataDeletionResult {
        val (captureCount, deleted) = captureLibrary.deleteAll()
        val imageDeleted = deleteWatermarkImage()
        val inspectionDeleted = InspectionStore.get(appContext).clearLocalData(appContext)
        clearHistory()
        check(prefs.edit().remove("watermark_text").commit()) {
            "Could not clear local watermark text"
        }
        update { it.copy(
            watermark = WatermarkSettings(),
            onvifSearching = false,
            onvifSearchCompleted = false,
            onvifDevices = emptyList(),
            onvifHint = appContext.getString(R.string.onvif_initial_hint)
        ) }
        refreshCaptures()
        return LocalDataDeletionResult(captureCount, deleted, imageDeleted, inspectionDeleted)
    }

    fun showSection(section: MainSection) = update { it.copy(section = section) }

    /** 文件页只删除当前预览项，避免把单文件操作与多选状态耦合。 */
    fun deleteCapture(file: File): Boolean {
        val deleted = captureLibrary.delete(listOf(file)) == 1
        refreshCaptures()
        return deleted
    }

    fun deleteCaptures(files: Collection<File>): Int {
        val deleted = captureLibrary.delete(files)
        refreshCaptures()
        return deleted
    }

    fun onOnvifSearchStarted() = update { it.copy(
        onvifSearching = true,
        onvifSearchCompleted = false,
        onvifHint = appContext.getString(R.string.searching_devices),
        onvifDevices = emptyList<CameraDevice>()
    ) }

    fun onOnvifSearchUpdate(result: CameraSearchUpdate) {
        val ranges = result.reportedSubnets.joinToString(", ")
        val hint = when (result.phase) {
            CameraSearchPhase.MULTICAST -> appContext.getString(R.string.onvif_multicast_searching)
            CameraSearchPhase.SUBNET -> appContext.getString(R.string.onvif_subnet_searching, ranges, result.devices.size)
            CameraSearchPhase.FINISHED -> when (result.end) {
                CameraSearchEnd.CANCELLED -> appContext.getString(R.string.onvif_search_cancelled, result.devices.size)
                CameraSearchEnd.TIMED_OUT -> appContext.getString(R.string.onvif_search_timeout, result.devices.size, ranges)
                CameraSearchEnd.RANGE_UNAVAILABLE -> appContext.getString(R.string.onvif_range_unavailable)
                CameraSearchEnd.RANGE_MISMATCH -> appContext.getString(R.string.onvif_range_mismatch, ranges)
                CameraSearchEnd.RANGE_TOO_LARGE -> appContext.getString(R.string.onvif_range_too_large, ranges)
                CameraSearchEnd.FAILED -> appContext.getString(R.string.onvif_search_error)
                else -> if (result.devices.isEmpty()) appContext.getString(R.string.onvif_search_empty, ranges)
                    else appContext.getString(R.string.devices_found, result.devices.size)
            }
        }
        update { it.copy(
            onvifSearching = result.phase != CameraSearchPhase.FINISHED,
            onvifSearchCompleted = result.phase == CameraSearchPhase.FINISHED,
            onvifHint = hint + if (result.phase == CameraSearchPhase.FINISHED && result.devices.isEmpty() && result.authenticationCandidates > 0) "\n" + appContext.getString(R.string.onvif_auth_candidates, result.authenticationCandidates) else "",
            onvifDevices = result.devices
        ) }
    }

    fun setOnvifHint(hint: String) = update { it.copy(
        onvifHint = hint
    ) }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MainViewModel::class.java))
            val prefs = appContext.getSharedPreferences("demo", Context.MODE_PRIVATE)
            val connectionStore = SecureConnectionStore(appContext, prefs)
            val watermark = WatermarkSettings(
                text = prefs.getString("watermark_text", "").orEmpty(),
                imagePath = File(appContext.filesDir, "demo-watermark.png")
                    .takeIf { it.isFile }?.absolutePath
            )
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(
                appContext,
                prefs,
                connectionStore,
                SharedPreferencesConnectionHistory(connectionStore),
                CaptureLibrary(AppCaptureRepository(appContext)),
                watermark
            ) as T
        }
    }

}
