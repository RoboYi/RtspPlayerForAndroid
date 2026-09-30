package com.roboyi.rtspdemo

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import com.roboyi.rtspdemo.data.*
import java.io.File
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject

/** Focused device smoke checks with isolated storage, no camera or production project writes. */
class InspectionSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = runCatching {
            val root = File(targetContext.cacheDir, "inspection-smoke-${UUID.randomUUID()}").apply { mkdirs() }
            val isolated = object : ContextWrapper(targetContext) { override fun getFilesDir() = root }
            fun store(): InspectionStore {
                val constructor = InspectionStore::class.java.getDeclaredConstructor(Context::class.java)
                constructor.isAccessible = true
                return constructor.newInstance(isolated)
            }
            val first = store()
            InspectionStore.LIMITS.forEach { (scope, count) ->
                repeat(count) { first.addField(scope, "扩展字段${it + 1}") }
                check(runCatching { first.addField(scope, "超出上限") }.isFailure)
            }
            val task = InspectionInfo(values = mapOf("name" to "任务A", "number" to "T01", "location" to "东段", "operator" to "张三"))
            val project = InspectionInfo(values = mapOf("name" to "工程<&>", "number" to "P01", "client" to "A&B公司", "executor" to "执行单位"), tasks = listOf(task))
            first.saveProject(project); first.select(project, task)
            first.saveRule("project.name", InspectionRule(mode = "end", endSeconds = 3))
            val reopened = store()
            val originalCustom = reopened.fields("project").drop(4)
            reopened.saveFieldTemplates("project", originalCustom.mapIndexed { index, field -> if (index == 0) "改名后字段" else field.label })
            check(reopened.fields("project")[4].key == originalCustom[0].key)
            check(store().fields("project")[4].label == "改名后字段")
            reopened.saveRule("defect.name", InspectionRule(mode = "end", endSeconds = 99))
            check(reopened.rule("defect.name").mode == "all")
            reopened.saveRule("project.name", InspectionRule(mode = "start", startSeconds = 7), all = true)
            check(reopened.rule("defect.name").mode == "all" && reopened.rule("defect.name").endSeconds == 99)
            reopened.saveRules(emptyMap(), defectSeconds = 12)
            check(store().defectDisplaySeconds() == 12)
            check(reopened.selectedProject()?.id == project.id && reopened.selectedTask()?.id == task.id)
            check(reopened.fields("task").size == 24 && reopened.fields("project").size == 14 && reopened.fields("defect").size == 14)
            check(InspectionRule.from(JSONObject()).opacity == 1f)
            check(InspectionRule.from(JSONObject().put("opacity", 2)).opacity == 1f)
            check(InspectionRule.from(JSONObject().put("opacity", -1)).opacity == 0f)
            val transparentRule = reopened.rule("project.name").copy(background = 0, opacity = .4f)
            check(InspectionRule.from(transparentRule.json()) == transparentRule)
            reopened.saveRule("project.name", transparentRule)
            check(store().rule("project.name") == transparentRule)
            val session = reopened.newSession()
            reopened.saveProject(project.copy(values = project.values + ("name" to "已修改项目")))
            check(session.project?.name == "工程<&>" && session.video.startsWith("工程_&_"))
            val shotPath = session.video.substringBeforeLast('.') + "/snapshot.jpg"
            val defect = InspectionInfo(values = mapOf("name" to "裂缝<&>", "length" to "12cm"))
            session.snapshots += JSONObject().put("path", shotPath).put("defect", defect.json())
            reopened.saveSession(session)
            val restored = store().sessions().single()
            val xml = InspectionXml.serialize(restored)
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
            check(doc.documentElement.getAttribute("pathBase") == "DCIM/RoboYiPlayer")
            check(AppCaptureRepository(isolated).publicRoot.name == "RoboYiPlayer")
            check(doc.getElementsByTagName("video").item(0).attributes.getNamedItem("relativePath").nodeValue == session.video)
            check(doc.getElementsByTagName("screenshot").item(0).attributes.getNamedItem("relativePath").nodeValue == shotPath)
            check(doc.getElementsByTagName("defect").length == 1)
            check(doc.documentElement.textContent.contains("A&B公司"))
            check(restored.rules.getValue("project.name") == transparentRule)
            check(xml.contains("opacity=\"${transparentRule.json().get("opacity")}\""))
            reopened.select(null)
            check(reopened.newSession().video.matches(Regex("\\d{14}/\\d{14}(?:_\\d+)?\\.mp4")))
            val startRule = InspectionRule(mode = "start", startSeconds = 5)
            check(startRule.visible(4999, null) && !startRule.visible(5000, null))
            val middle = InspectionRule(mode = "middle", startSeconds = 5, endSeconds = 3)
            check(!middle.visible(4999, null) && middle.visible(5000, 3001) && !middle.visible(5000, 3000))
            val end = InspectionRule(mode = "end", endSeconds = 3)
            check(!end.visible(10000, null) && !end.visible(10000, 3001) && end.visible(10000, 3000))
            check(!end.copy(enabled = false).visible(10000, 0))
            check(InspectionUi.COLOR_GROUPS.map { it.first } == listOf("黑", "白", "红", "橙", "黄", "绿", "蓝", "靛", "紫"))
            check(InspectionUi.COLOR_GROUPS.all { it.second.size == 3 })
            check(InspectionUi.COLORS.size == 27 && InspectionUi.COLORS.distinct().size == 27)
            "PASS: stable template keys and limits; independent defect rules/duration; project/task selection; frozen metadata; XML hierarchy; paths; time boundaries; 27 colors; transparent background and opacity persistence."
        }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", result.getOrElse { "FAIL: ${it.stackTraceToString()}" }) })
    }
}
