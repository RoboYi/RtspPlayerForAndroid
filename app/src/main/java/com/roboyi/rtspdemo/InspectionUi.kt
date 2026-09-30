package com.roboyi.rtspdemo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import com.roboyi.rtspdemo.data.*
import java.io.File

/** Fullscreen, bottom-action landscape screens; opening them does not recreate the player. */
internal class InspectionUi(private val activity: Activity, private val changed: () -> Unit) {
    private val store = InspectionStore.get(activity)
    private val ui = InspectionUiKit(activity)
    private fun error(e: Throwable) = Toast.makeText(activity, e.message ?: "保存失败", Toast.LENGTH_LONG).show()
    fun showManager(recording: Boolean, authorize: () -> Unit, retryXml: () -> Unit) {
        val screen = ui.screen("项目与任务")
        var projectId = store.selectedProject()?.id ?: store.projects().firstOrNull()?.id
        var taskId = store.selectedTask()?.id
        var editingTask = taskId != null
        fun project() = store.projects().firstOrNull { it.id == projectId }
        fun task() = project()?.tasks?.firstOrNull { it.id == taskId }
        lateinit var render: () -> Unit
        fun action(text: String, primary: Boolean = false, block: () -> Unit) = ui.button(text, primary, block).apply { isEnabled = !recording; alpha = if (recording) .45f else 1f }
        fun editProject(new: Boolean) {
            editInfo("project", if (new) null else project()) { info, _ ->
                store.saveProject(info); projectId = info.id; editingTask = false
                if (new) taskId = null; changed(); render()
            }
        }
        fun editTask(new: Boolean) {
            val p = project() ?: return
            editInfo("task", if (new) null else task()) { info, _ ->
                val latest = store.projects().first { it.id == p.id }
                store.saveProject(latest.copy(tasks = if (new) latest.tasks + info else latest.tasks.map { if (it.id == info.id) info else it }))
                taskId = info.id; editingTask = true; changed(); render()
            }
        }
        fun card(info: InspectionInfo, selected: Boolean, scope: String, click: () -> Unit): View {
            return ui.column(12).apply {
                background = ui.shape(if (selected) Color.rgb(32, 61, 98) else ui.raised, if (selected) ui.blue else ui.line)
                addView(ui.label((if (selected) "●  " else "") + info.name, 15f))
                addView(ui.label(info.values["number"].orEmpty() + if (scope == "project") " · ${info.tasks.size} 个任务" else " · ${info.values["operator"].orEmpty()}", 12f, ui.muted))
                if (selected) {
                    val fields = store.fields(scope).filter { it.key !in listOf("name", "number") }
                    fields.take(5).forEach { field -> addView(ui.label("${field.label}  ${info.values[field.key].orEmpty().ifBlank { "未填写" }}", 13f, ui.muted)) }
                    if (fields.size > 5) addView(ui.label("另有 ${fields.size - 5} 项信息，点击底部“修改”查看全部", 11f, ui.muted))
                }
                isClickable = true; isFocusable = true; contentDescription = "选择${if (scope == "project") "项目" else "任务"} ${info.name}"
                setOnClickListener { click() }
            }
        }
        render = {
            screen.body.removeAllViews(); screen.footer.removeAllViews()
            val p = project(); val t = task()
            if (t == null) editingTask = false
            val used = store.selectedProject()
            screen.body.addView(ui.label(if (recording) "录像进行中：可浏览资料，项目、任务和规则已锁定。" else
                "当前使用：${used?.name ?: "未选择项目"} / ${store.selectedTask()?.name ?: "未选择任务"}    ·    浏览后点击底部确认使用", 12f, ui.muted))
            val columns = ui.row(); screen.body.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
            val left = ui.panel(); val right = ui.panel()
            columns.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = ui.dp(12) })
            columns.addView(right, LinearLayout.LayoutParams(0, -1, 2f))
            left.addView(ui.title("项目 · ${store.projects().size}").apply { setTextColor(if (!editingTask) ui.blue else Color.WHITE) })
            right.addView(ui.title("项目下的任务 · ${p?.tasks?.size ?: 0}").apply { setTextColor(if (editingTask) ui.blue else Color.WHITE) })
            val projectList = ui.column(); val taskList = ui.column()
            left.addView(ui.scroll(projectList), LinearLayout.LayoutParams(-1, 0, 1f)); right.addView(ui.scroll(taskList), LinearLayout.LayoutParams(-1, 0, 1f))
            store.projects().forEach { info -> projectList.addView(card(info, info.id == p?.id, "project") {
                if (projectId != info.id) taskId = if (recording && info.id == store.selectedProject()?.id) store.selectedTask()?.id else null
                projectId = info.id; editingTask = false; render()
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }) }
            if (store.projects().isEmpty()) projectList.addView(ui.label("暂无项目，请点击底部“新建”添加项目。", color = ui.muted))
            p?.tasks?.forEach { info -> taskList.addView(card(info, info.id == t?.id, "task") { taskId = info.id; editingTask = true; render() },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) }) }
            if (p == null || p.tasks.isEmpty()) taskList.addView(ui.label(if (p == null) "先选择左侧项目。" else "暂无任务，请点击底部“新建”添加任务。", color = ui.muted))
            val bottom = ui.row(); screen.footer.addView(bottom)
            ui.addButton(bottom, ui.button("返回") { screen.dialog.dismiss() })
            val create = action("新建 ▾") {}
            create.setOnClickListener {
                PopupMenu(activity, create).apply {
                    menu.add(0, 1, 0, "新建项目")
                    menu.add(0, 2, 1, "新建任务").isEnabled = p != null
                    setOnMenuItemClickListener { item ->
                        if (item.itemId == 1) editProject(true) else editTask(true)
                        true
                    }
                }.show()
            }
            ui.addButton(bottom, create)
            ui.addButton(bottom, action(if (editingTask) "修改任务" else "修改项目") {
                if (editingTask) editTask(false) else editProject(false)
            }.apply { isEnabled = !recording && if (editingTask) t != null else p != null })
            ui.spacer(bottom)
            ui.addButton(bottom, action("OSD 规则") { showRules(p, t) })
            val more = ui.button("更多 ▾") {}
            more.setOnClickListener {
                PopupMenu(activity, more).apply {
                    menu.add(0, 1, 0, "不使用项目").isEnabled = !recording
                    menu.add(0, 2, 1, "取消任务选择").isEnabled = !recording && t != null
                    menu.add(1, 3, 2, if (InspectionXml.hasAccess(activity)) "资料目录 · 重新授权" else "授权资料目录")
                    menu.add(1, 4, 3, "重新导出 XML")
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            1 -> { store.select(null); projectId = null; taskId = null; editingTask = false; changed(); render() }
                            2 -> { taskId = null; editingTask = false; render() }
                            3 -> authorize()
                            4 -> retryXml()
                        }
                        true
                    }
                }.show()
            }
            ui.addButton(bottom, more)
            ui.addButton(bottom, action("使用所选", true) { store.select(p, t); changed(); screen.dialog.dismiss() }.apply { isEnabled = !recording && p != null })
        }
        render()
    }

    fun editInfo(scope: String, existing: InspectionInfo?, snapshot: File? = null, saved: (InspectionInfo, Int) -> Unit) {
        val name = scopeName(scope); val screen = ui.screen("${if (existing == null) "新建" else "修改"}$name")
        val values = existing?.values?.toMutableMap() ?: mutableMapOf()
        var seconds = store.defectDisplaySeconds()
        val inputs = linkedMapOf<String, EditText>()
        val bitmap = snapshot?.let { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.absolutePath, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1600).coerceAtLeast(1) }
            BitmapFactory.decodeFile(file.absolutePath, options)
        }
        screen.dialog.setOnDismissListener { bitmap?.recycle() }
        var preview: InspectionOsdPreview? = null
        fun collect() { inputs.forEach { (key, edit) -> values[key] = edit.text.toString().trim() } }
        fun updatePreview() {
            collect()
            preview?.items = store.fields("defect").mapNotNull { field ->
                val v = values[field.key].orEmpty(); if (v.isBlank()) null else InspectionPreviewItem("defect.${field.key}", "${field.label}：$v", store.rule("defect.${field.key}"))
            }
        }
        lateinit var render: () -> Unit
        render = {
            screen.body.removeAllViews(); screen.footer.removeAllViews(); inputs.clear()
            val row = ui.row(); screen.body.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
            if (scope == "defect") {
                val left = ui.panel(); row.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = ui.dp(12) })
                left.addView(ui.label("本次截图 · 缺陷 OSD 预览", 14f))
                preview = InspectionOsdPreview(activity, bitmap)
                left.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
                left.addView(ui.label("填写后保存，缺陷按右侧设置的时长显示，到时自动消失。\n显示结束后，用带缺陷文字的图片替换本次截图，XML 同步保留。", 12f, ui.muted))
            }
            val form = ui.panel(); row.addView(ui.scroll(form), LinearLayout.LayoutParams(0, -1, if (scope == "defect") 1f else 2f))
            val fields = store.fields(scope); val baseCount = InspectionStore.BASE_FIELDS.getValue(scope).size
            form.addView(ui.title("基本信息"))
            fun addFields(definitions: List<InfoField>) {
                definitions.chunked(2).forEach { pair ->
                    val fieldsRow = ui.row(); form.addView(fieldsRow)
                    pair.forEach { field ->
                        val cell = ui.column(5); fieldsRow.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                        cell.addView(ui.label(field.label + if (field.key == "name") " *" else "", 12f, ui.muted))
                        val edit = ui.input(values[field.key].orEmpty()); cell.addView(edit, LinearLayout.LayoutParams(-1, -2)); inputs[field.key] = edit
                        if (scope == "defect") edit.addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updatePreview() }
                            override fun afterTextChanged(s: Editable?) = Unit
                        })
                    }
                    if (pair.size == 1) fieldsRow.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            addFields(fields.take(baseCount))
            form.addView(ui.title("自定义信息 · ${fields.size - baseCount} / ${InspectionStore.LIMITS.getValue(scope)}"))
            addFields(fields.drop(baseCount))
            if (fields.size == baseCount) form.addView(ui.label("点击底部“自定义字段”添加，名称在以后新建时保留。", 12f, ui.muted))
            if (scope == "defect") {
                form.addView(ui.Slider("本次缺陷显示时长", seconds, 0, 3600, " 秒") { seconds = it }.view)
                form.addView(ui.label("常用滑条 0–60 秒；点击数值可输入更长时长。\n0 秒只写入截图与 XML，不叠加到录像。", 12f, ui.muted))
            }
            val bottom = ui.row(); screen.footer.addView(bottom)
            ui.addButton(bottom, ui.button(if (scope == "defect") "跳过缺陷" else "取消") { screen.dialog.dismiss() })
            ui.addButton(bottom, ui.button("自定义字段") { collect(); showTemplates(scope) { render() } })
            if (scope == "defect") ui.addButton(bottom, ui.button("缺陷 OSD 样式") {
                collect()
                val previousDefault = store.defectDisplaySeconds()
                InspectionOsdEditor(activity, store, ui, store.selectedProject(), store.selectedTask(), true, false,
                    InspectionInfo(values = values.toMap()), bitmap) {
                    changed()
                    if (store.defectDisplaySeconds() != previousDefault) { seconds = store.defectDisplaySeconds(); render() }
                    else updatePreview()
                }.show()
            })
            ui.spacer(bottom)
            ui.addButton(bottom, ui.button(if (scope == "defect") "保存并显示" else "保存$name", true) {
                runCatching {
                    collect(); require(!values["name"].isNullOrBlank()) { "请填写${name}名称" }
                    saved((existing ?: InspectionInfo()).copy(values = values.toMap()), if (scope == "defect") seconds else 0)
                    screen.dialog.dismiss()
                }.onFailure(::error)
            })
            if (scope == "defect") updatePreview()
        }
        render()
    }

    private fun showTemplates(scope: String, saved: () -> Unit) {
        val screen = ui.screen("${scopeName(scope)}自定义字段模板")
        val existing = store.fields(scope).drop(InspectionStore.BASE_FIELDS.getValue(scope).size)
        val form = ui.panel(); screen.body.addView(ui.scroll(form), LinearLayout.LayoutParams(-1, -1))
        form.addView(ui.label("字段名称在以后新建时保留；改名不会丢失已有资料。空白新槽位不显示。", 13f, ui.muted))
        val edits = mutableListOf<EditText>()
        (0 until InspectionStore.LIMITS.getValue(scope)).chunked(3).forEach { indices ->
            val row = ui.row(); form.addView(row)
            indices.forEach { i ->
                val cell = ui.column(6); row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
                cell.addView(ui.label("自定义项 ${i + 1}", 12f, ui.muted))
                val edit = ui.input(existing.getOrNull(i)?.label.orEmpty()).apply { hint = "输入字段名称" }; edits += edit; cell.addView(edit)
            }
            repeat(3 - indices.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
        }
        val bottom = ui.row(); screen.footer.addView(bottom)
        ui.addButton(bottom, ui.button("取消") { screen.dialog.dismiss() }); ui.spacer(bottom)
        ui.addButton(bottom, ui.button("保存模板", true) {
            runCatching { store.saveFieldTemplates(scope, edits.map { it.text.toString() }); saved(); screen.dialog.dismiss() }.onFailure(::error)
        })
    }

    fun showRules(project: InspectionInfo? = store.selectedProject(), task: InspectionInfo? = store.selectedTask()) {
        InspectionOsdEditor(activity, store, ui, project, task, false, true, null, null, changed).show()
    }
    companion object {
        fun scopeName(scope: String) = when (scope) { "project" -> "项目"; "task" -> "任务"; else -> "缺陷" }
        val COLOR_GROUPS = listOf(
            "黑" to listOf("666666", "333333", "000000"),
            "白" to listOf("FFFFFF", "F5F5F5", "E0E0E0"),
            "红" to listOf("FFAAAA", "FF3333", "990000"),
            "橙" to listOf("FFD2A6", "FF8800", "B34700"),
            "黄" to listOf("FFF7A3", "FFD600", "B38A00"),
            "绿" to listOf("B6E6B6", "35B34A", "14652A"),
            "蓝" to listOf("B3D6FF", "2E7DE9", "123F91"),
            "靛" to listOf("C5CAE9", "5C6BC0", "283593"),
            "紫" to listOf("E1BEE7", "9C27B0", "4A148C")
        ).map { (name, colors) -> name to colors.map { Color.parseColor("#$it") } }
        val COLORS = COLOR_GROUPS.flatMap { it.second }
    }
}
