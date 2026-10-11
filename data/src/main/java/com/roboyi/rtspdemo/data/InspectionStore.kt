package com.roboyi.rtspdemo.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class InfoField(val key: String, val label: String)
data class InspectionInfo(
    val id: String = UUID.randomUUID().toString(),
    val values: Map<String, String> = emptyMap(),
    val tasks: List<InspectionInfo> = emptyList()
) {
    val name: String get() = values["name"].orEmpty()
    fun json(): JSONObject = JSONObject().put("id", id).put("values", JSONObject(values))
        .put("tasks", JSONArray(tasks.map { it.json() }))
    companion object {
        fun from(j: JSONObject): InspectionInfo = InspectionInfo(j.getString("id"),
            j.getJSONObject("values").stringMap(), j.optJSONArray("tasks").objects().map(::from))
    }
}

/** Positions are percentages of the video; timings use the recording timeline, excluding pauses. */
data class InspectionRule(
    val enabled: Boolean = true, val x: Float = 3f, val y: Float = 4f,
    val size: Float = 20f, val color: Int = -1, val background: Int = -16777216,
    val mode: String = "all", val startSeconds: Int = 5, val endSeconds: Int = 5,
    val timeFormat: String = "yyyy-MM-dd HH:mm:ss",
    val textOpacity: Float = 1f, val backgroundOpacity: Float = 1f
) {
    fun json() = JSONObject().put("enabled", enabled).put("x", x.toDouble()).put("y", y.toDouble())
        .put("size", size.toDouble()).put("color", color).put("background", background)
        .put("mode", mode).put("n", startSeconds).put("m", endSeconds).put("timeFormat", timeFormat)
        .put("textOpacity", textOpacity.toDouble()).put("backgroundOpacity", backgroundOpacity.toDouble())
    fun visible(elapsedMs: Long, remainingMs: Long?): Boolean = enabled && when (mode) {
        "start" -> elapsedMs < startSeconds * 1000L
        "middle" -> elapsedMs >= startSeconds * 1000L && (remainingMs == null || remainingMs > endSeconds * 1000L)
        "end" -> remainingMs != null && remainingMs <= endSeconds * 1000L
        else -> true
    }
    companion object {
        private fun opacity(j: JSONObject, key: String): Float {
            val legacy = j.optDouble("opacity", 1.0).takeIf { it.isFinite() } ?: 1.0
            return (j.optDouble(key, legacy).takeIf { it.isFinite() } ?: legacy).toFloat().coerceIn(0f, 1f)
        }
        fun from(j: JSONObject) = InspectionRule(j.optBoolean("enabled", true),
            j.optDouble("x", 3.0).toFloat().coerceIn(0f, 100f), j.optDouble("y", 4.0).toFloat().coerceIn(0f, 100f),
            j.optDouble("size", 20.0).toFloat().coerceIn(8f, 96f), j.optInt("color", -1), j.optInt("background", -16777216),
            j.optString("mode", "all"), j.optInt("n", 5).coerceIn(0, 3600), j.optInt("m", 5).coerceIn(0, 3600),
            j.optString("timeFormat", "yyyy-MM-dd HH:mm:ss"),
            opacity(j, "textOpacity"), opacity(j, "backgroundOpacity"))
    }
}

/** A frozen recording manifest; later project/template edits cannot rewrite its history. */
data class InspectionSession(
    val id: String, val video: String, val startedAt: Long,
    val project: InspectionInfo?, val task: InspectionInfo?,
    val fields: Map<String, List<InfoField>>, val rules: Map<String, InspectionRule>,
    var durationMs: Long = 0, var state: String = "recording", val snapshots: MutableList<JSONObject> = mutableListOf()
) {
    fun json() = JSONObject().put("id", id).put("video", video).put("startedAt", startedAt)
        .put("project", project?.json()).put("task", task?.json()).put("durationMs", durationMs).put("state", state)
        .put("fields", JSONObject().apply { fields.forEach { (scope, fs) -> put(scope, JSONArray(fs.map { JSONObject().put("key", it.key).put("label", it.label) })) } })
        .put("rules", JSONObject().apply { rules.forEach { (key, rule) -> put(key, rule.json()) } })
        .put("snapshots", JSONArray(snapshots))
    companion object {
        fun from(j: JSONObject): InspectionSession {
            val fs = j.getJSONObject("fields")
            val rs = j.getJSONObject("rules")
            return InspectionSession(j.getString("id"), j.getString("video"), j.getLong("startedAt"),
                j.optJSONObject("project")?.let(InspectionInfo::from), j.optJSONObject("task")?.let(InspectionInfo::from),
                fs.keys().asSequence().associateWith { key -> fs.getJSONArray(key).objects().map { InfoField(it.getString("key"), it.getString("label")) } },
                rs.keys().asSequence().associateWith { InspectionRule.from(rs.getJSONObject(it)) },
                j.optLong("durationMs"), j.optString("state"), j.optJSONArray("snapshots").objects().toMutableList())
        }
    }
}

class InspectionStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "inspection-projects.json"))
    private var data: JSONObject = if (file.baseFile.exists()) JSONObject(file.openRead().bufferedReader().use { it.readText() }) else JSONObject()
    @Synchronized fun projects(): List<InspectionInfo> = data.optJSONArray("projects").objects().map(InspectionInfo::from)
    @Synchronized fun selectedProject(): InspectionInfo? = projects().firstOrNull { it.id == data.optString("projectId") }
    @Synchronized fun selectedTask(): InspectionInfo? = selectedProject()?.tasks?.firstOrNull { it.id == data.optString("taskId") }
    @Synchronized fun select(project: InspectionInfo?, task: InspectionInfo? = null) {
        data.put("projectId", project?.id.orEmpty()).put("taskId", task?.id.orEmpty()); save()
    }
    @Synchronized fun saveProject(project: InspectionInfo) {
        val list = projects().toMutableList(); val index = list.indexOfFirst { it.id == project.id }
        if (index >= 0) list[index] = project else list += project
        data.put("projects", JSONArray(list.map { it.json() })); save()
    }
    @Synchronized fun fields(scope: String): List<InfoField> = BASE_FIELDS.getValue(scope) +
        data.optJSONObject("customFields")?.optJSONArray(scope).objects().map { InfoField(it.getString("key"), it.getString("label")) }
    @Synchronized fun addField(scope: String, label: String): InfoField {
        val existing = fields(scope)
        require(label.isNotBlank() && label.length <= 40 && existing.none { it.label == label }) { "字段名称不能为空、重复或超过40字" }
        require(existing.size - BASE_FIELDS.getValue(scope).size < LIMITS.getValue(scope)) { "自定义字段数量已达上限" }
        val field = InfoField("custom_${UUID.randomUUID()}", label.trim())
        val custom = data.optJSONObject("customFields") ?: JSONObject().also { data.put("customFields", it) }
        val array = custom.optJSONArray(scope) ?: JSONArray().also { custom.put(scope, it) }
        array.put(JSONObject().put("key", field.key).put("label", field.label)); save(); return field
    }
    /** Rename existing fields without changing their keys or any saved values. */
    @Synchronized fun saveFieldTemplates(scope: String, labels: List<String>) {
        require(labels.size <= LIMITS.getValue(scope)) { "自定义字段数量已达上限" }
        val previous = fields(scope).drop(BASE_FIELDS.getValue(scope).size)
        val names = labels.map(String::trim)
        require(previous.indices.all { !names.getOrNull(it).isNullOrBlank() }) { "已有字段不能清空，可修改字段名称" }
        val used = BASE_FIELDS.getValue(scope).map { it.label } + names.filter(String::isNotBlank)
        require(used.distinct().size == used.size && names.all { it.length <= 40 }) { "字段名称不能重复或超过40字" }
        val custom = data.optJSONObject("customFields") ?: JSONObject().also { data.put("customFields", it) }
        custom.put(scope, JSONArray(names.mapIndexedNotNull { index, name ->
            if (name.isBlank()) null else JSONObject().put("key", previous.getOrNull(index)?.key ?: "custom_${UUID.randomUUID()}").put("label", name)
        })); save()
    }
    @Synchronized fun rule(key: String): InspectionRule {
        val json = data.optJSONObject("rules")?.optJSONObject(key)
        if (json != null) return InspectionRule.from(json).let { if (key.startsWith("defect.")) it.copy(mode = "all") else it }
        val index = allRuleKeys().indexOf(key).coerceAtLeast(0)
        return InspectionRule(x = if (index < 14) 3f else 52f, y = (4 + (index % 14) * 6).toFloat())
    }
    @Synchronized fun allRuleKeys(): List<String> = BASE_FIELDS.keys.flatMap { scope -> fields(scope).map { "$scope.${it.key}" } } + "time"
    @Synchronized fun saveRule(key: String, rule: InspectionRule, all: Boolean = false) {
        val keys = if (all) allRuleKeys().filter { it.startsWith("defect.") == key.startsWith("defect.") } else listOf(key)
        saveRules(keys.associateWith { rule })
    }
    @Synchronized fun saveRules(values: Map<String, InspectionRule>, defectSeconds: Int? = null) {
        if (defectSeconds != null) require(defectSeconds in 0..3600)
        val rules = data.optJSONObject("rules") ?: JSONObject().also { data.put("rules", it) }
        values.forEach { (key, rule) -> rules.put(key, (if (key.startsWith("defect.")) rule.copy(mode = "all") else rule).json()) }
        if (defectSeconds != null) data.put("defectDisplaySeconds", defectSeconds)
        save()
    }
    @Synchronized fun defectDisplaySeconds(): Int = data.optInt("defectDisplaySeconds", 5).coerceIn(0, 3600)
    @Synchronized fun newSession(): InspectionSession {
        val now = System.currentTimeMillis()
        val p = selectedProject()?.copy(tasks = emptyList()); val t = selectedTask()
        val stamp = timestamp(now)
        val folder = p?.name?.let(::safeName) ?: stamp
        val base = if (p == null) stamp else "${safeName(p.name)}_${safeName(p.values["number"].orEmpty())}_$stamp"
        val used = sessions().map { it.video }.toSet()
        var relative = "$folder/$base.mp4"; var suffix = 2
        while (relative in used) { relative = "$folder/${base}_${suffix++}.mp4" }
        return InspectionSession(UUID.randomUUID().toString(), relative, now, p, t,
            BASE_FIELDS.keys.associateWith(::fields), allRuleKeys().associateWith(::rule)).also(::saveSession)
    }
    @Synchronized fun sessions(): List<InspectionSession> = data.optJSONArray("sessions").objects().map(InspectionSession::from)
    @Synchronized fun saveSession(session: InspectionSession) {
        val list = sessions().toMutableList(); val i = list.indexOfFirst { it.id == session.id }
        if (i < 0) list += session else list[i] = session
        data.put("sessions", JSONArray(list.map { it.json() })); save()
    }
    @Synchronized fun folderUri(): String = data.optString("folderUri")
    @Synchronized fun setFolderUri(uri: String) { data.put("folderUri", uri); save() }
    @Synchronized fun clearLocalData(context: Context): Boolean {
        var complete = true
        val uri = folderUri().takeIf(String::isNotBlank)
        data = JSONObject()
        runCatching {
            file.delete()
            check(!file.baseFile.exists())
        }.onFailure { complete = false }
        listOf("inspection-xml", "inspection-sources").forEach { name ->
            val directory = File(context.filesDir, name)
            if (directory.exists() && !directory.deleteRecursively()) complete = false
        }
        if (uri != null) context.contentResolver.persistedUriPermissions
            .filter { it.uri.toString() == uri }.forEach { permission ->
                var flags = 0
                if (permission.isReadPermission) flags = flags or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.releasePersistableUriPermission(permission.uri, flags) }
                    .onFailure { complete = false }
            }
        return complete
    }
    @Synchronized private fun save() {
        val output = file.startWrite()
        try { output.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (e: Throwable) { file.failWrite(output); throw e }
    }
    companion object {
        private var instance: InspectionStore? = null
        @Synchronized fun get(context: Context): InspectionStore = instance ?: InspectionStore(context.applicationContext).also { instance = it }
        val LIMITS = mapOf("project" to 10, "task" to 20, "defect" to 10)
        val BASE_FIELDS = linkedMapOf(
            "project" to listOf(InfoField("name", "项目名称"), InfoField("number", "项目编号"), InfoField("client", "委托公司"), InfoField("executor", "执行单位")),
            "task" to listOf(InfoField("name", "任务名称"), InfoField("number", "任务编号"), InfoField("location", "任务地点"), InfoField("operator", "执行人")),
            "defect" to listOf(InfoField("name", "缺陷名称"), InfoField("location", "缺陷位置"), InfoField("length", "缺陷长度"), InfoField("details", "缺陷详情")))
        fun timestamp(time: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(time))
        fun safeName(value: String): String = value.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').take(80).ifBlank { "未编号" }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.stringMap(): Map<String, String> = keys().asSequence().associateWith { optString(it) }
