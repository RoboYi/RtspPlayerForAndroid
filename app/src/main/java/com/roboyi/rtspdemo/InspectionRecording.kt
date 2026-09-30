package com.roboyi.rtspdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import com.roboyi.rtsp.OverlayItem
import com.roboyi.rtsp.WatermarkSpec
import com.roboyi.rtsp.WatermarkBitmapFactory
import com.roboyi.rtspdemo.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class InspectionRecording(private val context: Context) {
    val store = InspectionStore.get(context)
    val repository = AppCaptureRepository(context)
    var session: InspectionSession? = null
        private set
    var elapsedMs = 0L
        private set
    var stopAtMs: Long? = null
        private set
    private var defect: InspectionInfo? = null
    private var defectUntilMs = 0L
    private var defectFields = emptyList<InfoField>()
    private var defectRules = emptyMap<String, InspectionRule>()
    fun begin(): File {
        val current = store.newSession()
        InspectionXml.write(context, current)
        session = current; elapsedMs = 0L; stopAtMs = null; defect = null
        return repository.stagingFile(current.video)
    }
    fun duration(ms: Long) { elapsedMs = ms; session?.durationMs = ms }
    fun startEnding(): Int {
        val current = session ?: return 0
        val seconds = current.rules.filterKeys { key ->
            key == "time" || when (key.substringBefore('.')) {
                "project" -> !current.project?.values?.get(key.substringAfter('.')).isNullOrBlank()
                "task" -> !current.task?.values?.get(key.substringAfter('.')).isNullOrBlank()
                "defect" -> false
                else -> false
            }
        }
            .values.filter { it.enabled && it.mode in listOf("middle", "end") }.maxOfOrNull { it.endSeconds } ?: 0
        if (seconds > 0) stopAtMs = elapsedMs + seconds * 1000L
        return seconds
    }
    fun remainingMs(): Long? = stopAtMs?.let { (it - elapsedMs).coerceAtLeast(0L) }
    fun abort() { session?.let { it.state = "failed"; store.saveSession(it); runCatching { InspectionXml.write(context, it) } }; clear() }
    fun clear() { session = null; stopAtMs = null; defect = null }
    fun finish(current: InspectionSession, file: File, reason: String = "completed"): File {
        val published = repository.publish(file)
        synchronized(current) { current.state = reason; store.saveSession(current); InspectionXml.write(context, current) }
        return published
    }
    fun snapshotFile(current: InspectionSession?): File {
        if (current == null) return repository.newFile("snapshot", "jpg")
        return repository.stagingFile("${current.video.substringBeforeLast('.')}/snapshot_${System.currentTimeMillis()}.jpg")
    }
    fun sourceFile(target: File): File = File(context.filesDir, "inspection-sources/${repository.relative(target)}.source").apply { parentFile!!.mkdirs() }
    fun spec(base: WatermarkSpec, current: InspectionSession? = session, atMs: Long = elapsedMs,
             defectOverride: InspectionInfo? = null, now: Long = System.currentTimeMillis()): WatermarkSpec {
        val project = current?.project ?: if (current == null && session == null) store.selectedProject() else null
        val task = current?.task ?: if (current == null && session == null) store.selectedTask() else null
        val fields = current?.fields ?: InspectionStore.BASE_FIELDS.keys.associateWith(store::fields)
        val rules = current?.rules ?: store.allRuleKeys().associateWith(store::rule)
        val items = mutableListOf<OverlayItem>()
        val remaining = if (current === session) remainingMs() else null
        fun addInfo(scope: String, info: InspectionInfo?, definitions: List<InfoField>, sourceRules: Map<String, InspectionRule>, elapsed: Long, remaining: Long?) {
            if (info == null) return
            definitions.forEach { field ->
                val value = info.values[field.key].orEmpty()
                val key = "$scope.${field.key}"
                val rule = sourceRules[key] ?: store.rule(key)
                if (value.isNotBlank() && rule.visible(elapsed, remaining)) items += textItem(key, "${field.label}：$value", rule)
            }
        }
        addInfo("project", project, fields["project"].orEmpty(), rules, atMs, remaining)
        addInfo("task", task, fields["task"].orEmpty(), rules, atMs, remaining)
        val shownDefect = defectOverride ?: defect?.takeIf { current === session && SystemClock.elapsedRealtime() < defectUntilMs }
        if (shownDefect != null) {
            val defs = if (defectOverride != null) store.fields("defect") else defectFields
            val rs = if (defectOverride != null) store.allRuleKeys().associateWith { store.rule(it).copy(mode = "all") } else defectRules
            addInfo("defect", shownDefect, defs, rs, atMs, remaining)
        }
        val time = rules["time"] ?: InspectionRule()
        if (time.visible(atMs, remaining)) items += textItem("time", SimpleDateFormat(time.timeFormat, Locale.getDefault()).format(Date(now)), time)
        // Keep the SDK's watermark recorder active even when all time-gated fields are initially hidden.
        val legacy = WatermarkBitmapFactory.run { base.toOverlayModel(context, 1920, 1080, false).items }
        items.addAll(0, legacy)
        if (items.isEmpty()) items += OverlayItem.Text(id = "inspection_clock", x = 0f, y = 0f, text = ".", textSizeSp = 8f, textColor = Color.TRANSPARENT,
            backgroundColor = Color.TRANSPARENT, shadowColor = Color.TRANSPARENT, includeTimestamp = false, alpha = 0f)
        return WatermarkSpec(text = "", showText = false, showOverlay = false, overlayItems = items)
    }
    private fun textItem(id: String, text: String, rule: InspectionRule) = OverlayItem.Text(
        id = "inspection_$id", x = rule.x / 100f, y = rule.y / 100f, text = text, textSizeSp = rule.size,
        textColor = rule.color, backgroundColor = rule.background, shadowColor = Color.TRANSPARENT,
        includeTimestamp = false, alpha = rule.opacity, zIndex = 20)
    fun activateDefect(info: InspectionInfo, seconds: Int) {
        defect = info; defectUntilMs = SystemClock.elapsedRealtime() + seconds * 1000L
        defectFields = store.fields("defect"); defectRules = store.allRuleKeys().filter { it.startsWith("defect.") }
            .associateWith { store.rule(it).copy(mode = "all") }
    }
    fun saveDefectInfo(current: InspectionSession, target: File, info: InspectionInfo, seconds: Int) = synchronized(current) {
        val shot = current.snapshots.first { it.optString("path") == repository.relative(target) }
        shot.put("defect", info.json()).put("displaySeconds", seconds)
            .put("defectFields", JSONArray(store.fields("defect").map { JSONObject().put("key", it.key).put("label", it.label) }))
        store.saveSession(current); InspectionXml.write(context, current)
    }
    fun paintSnapshot(source: File, target: File, spec: WatermarkSpec) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(source.absolutePath)) { "截图读取失败" }
        val output = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle()
        try {
            WatermarkBitmapFactory.drawFullFrame(context, Canvas(output), output.width, output.height, spec, false)
            target.outputStream().use { check(output.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally { output.recycle() }
    }
    fun saveSnapshot(current: InspectionSession?, target: File, capturedAt: Long, recordingTimeMs: Long,
                     info: InspectionInfo? = null, seconds: Int = 0): File {
        val relative = repository.relative(target)
        val published = repository.publish(target)
        if (current != null) synchronized(current) {
            val previous = current.snapshots.indexOfFirst { it.optString("path") == relative }
            val shot = JSONObject().put("path", relative).put("capturedAt", capturedAt).put("recordingTimeMs", recordingTimeMs)
                .put("defect", info?.json()).put("displaySeconds", seconds)
                .put("defectFields", JSONArray(store.fields("defect").map { JSONObject().put("key", it.key).put("label", it.label) }))
            if (previous < 0) current.snapshots += shot else current.snapshots[previous] = shot
            store.saveSession(current); InspectionXml.write(context, current)
        }
        return published
    }
}
