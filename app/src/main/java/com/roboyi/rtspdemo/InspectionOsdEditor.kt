package com.roboyi.rtspdemo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.*
import com.roboyi.rtspdemo.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

internal class InspectionOsdEditor(
    private val activity: Activity, private val store: InspectionStore, private val ui: InspectionUiKit,
    private val project: InspectionInfo?, private val task: InspectionInfo?, private var defectMode: Boolean,
    private val allowNormal: Boolean, private val defect: InspectionInfo?, private val frame: Bitmap?, private val changed: () -> Unit
) {
    private val drafts = store.allRuleKeys().associateWith(store::rule).toMutableMap()
    private var selected = if (defectMode) "defect.name" else "project.name"
    private var duration = store.defectDisplaySeconds()
    private var dirty = false
    private var timeTab = false
    private var simulation = false
    private var simulationSeconds = 0
    private lateinit var screen: InspectionUiKit.Screen
    private lateinit var list: LinearLayout
    private lateinit var properties: LinearLayout
    private lateinit var simulationControls: LinearLayout
    private lateinit var preview: InspectionOsdPreview
    private var xControl: InspectionUiKit.Slider? = null
    private var yControl: InspectionUiKit.Slider? = null
    private fun keys() = drafts.keys.filter { it.startsWith("defect.") == defectMode }
    private fun rule() = drafts.getValue(selected)
    private fun fieldName(key: String) = if (key == "time") "日期与时间" else store.fields(key.substringBefore('.')).first { it.key == key.substringAfter('.') }.label
    private fun update(rule: InspectionRule) { drafts[selected] = rule; dirty = true; redraw() }
    private fun close() {
        if (!dirty) screen.dialog.dismiss()
        else AlertDialog.Builder(activity).setTitle("放弃未保存的规则？").setMessage("已保存的规则保持不变。")
            .setPositiveButton("放弃修改") { _, _ -> screen.dialog.dismiss() }.setNegativeButton("继续编辑", null).show()
    }
    fun show() {
        screen = ui.screen("OSD 规则与画面预览")
        screen.dialog.setCanceledOnTouchOutside(false)
        screen.dialog.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) close(); true } else false
        }
        build()
    }
    private fun build() {
        screen.body.removeAllViews(); screen.footer.removeAllViews()
        screen.body.addView(ui.label(if (defectMode) "缺陷 OSD · 录像中截图保存后触发，倒计时结束自动消失；不参与录像时段。" else
            "项目 / 任务 / 时间 · 显示整套启用项；未填写字段以占位文字预览，实际录像不显示空值。", 12f, ui.muted))
        val columns = ui.row(); screen.body.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        list = ui.column(8)
        columns.addView(ui.scroll(list).apply { background = ui.shape() }, LinearLayout.LayoutParams(ui.dp(166), -1).apply { marginEnd = ui.dp(12) })
        val middle = ui.column(); columns.addView(middle, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = ui.dp(12) })
        middle.addView(ui.label(if (frame == null) "画面预览 · 示意画面（非实时视频）" else "画面预览 · 本次原始截图", 12f, ui.muted))
        preview = InspectionOsdPreview(activity, frame)
        middle.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        middle.addView(ui.label("拖动任意 OSD 移动位置；红框为当前选中项。", 12f, Color.rgb(255, 97, 121)))
        simulationControls = ui.column(); middle.addView(simulationControls)
        properties = ui.column(12)
        columns.addView(ui.scroll(properties).apply { background = ui.shape() }, LinearLayout.LayoutParams(ui.dp(260), -1))
        preview.onSelected = { key -> selected = key; renderList(); renderProperties(); preview.selected = key }
        preview.onMoved = { key, x, y ->
            drafts[key] = drafts.getValue(key).copy(x = x, y = y); dirty = true
            xControl?.set(x.roundToInt()); yControl?.set(y.roundToInt()); redraw()
        }
        val bottom = ui.row(); screen.footer.addView(bottom)
        ui.addButton(bottom, ui.button("返回") { close() })
        if (allowNormal) ui.addButton(bottom, ui.button(if (defectMode) "项目 / 任务 / 时间" else "缺陷 OSD") {
            defectMode = !defectMode; selected = if (defectMode) "defect.name" else "project.name"; timeTab = false; build()
        })
        ui.spacer(bottom)
        ui.addButton(bottom, ui.button("应用到全部…") { applyAll() })
        ui.addButton(bottom, ui.button("保存规则", true) {
            runCatching {
                val pattern = drafts.getValue("time").timeFormat
                require(pattern.isNotBlank()) { "时间格式不能为空" }; SimpleDateFormat(pattern, Locale.getDefault())
                store.saveRules(if (allowNormal) drafts else drafts.filterKeys { it.startsWith("defect.") }, duration)
                dirty = false; changed(); screen.dialog.dismiss()
            }.onFailure { Toast.makeText(activity, it.message ?: "规则保存失败", Toast.LENGTH_LONG).show() }
        })
        renderList(); renderProperties(); renderSimulation(); redraw()
    }
    private fun renderList() {
        list.removeAllViews(); list.addView(ui.title("OSD 内容"))
        var previousScope = ""
        keys().forEach { key ->
            val scope = key.substringBefore('.')
            if (scope != previousScope) {
                list.addView(ui.label(if (scope == "time") "时间" else InspectionUi.scopeName(scope) + "信息", 11f, ui.muted)); previousScope = scope
            }
            list.addView(ui.button((if (drafts.getValue(key).enabled) "● " else "○ ") + fieldName(key), key == selected) {
                selected = key; renderList(); renderProperties(); redraw()
            }.apply { textSize = 12f }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(5) })
        }
    }
    private fun renderProperties() {
        properties.removeAllViews(); xControl = null; yControl = null
        properties.addView(ui.title(fieldName(selected)))
        if (timeTab && !defectMode) {
            properties.addView(ui.label("显示范围", 12f, ui.muted))
            val modes = listOf("all", "start", "middle", "end")
            val spinner = Spinner(activity).apply {
                adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item,
                    listOf("全程显示", "录像开始后的前 N 秒", "第 N 秒至结束前 M 秒", "录像结束前 M 秒"))
                setSelection(modes.indexOf(rule().mode).coerceAtLeast(0))
            }
            properties.addView(spinner)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (modes[position] != rule().mode) { update(rule().copy(mode = modes[position])); renderProperties() }
                }
            }
            if (rule().mode in listOf("start", "middle")) properties.addView(ui.Slider("N · 开始秒数", rule().startSeconds, 0, 3600, " 秒") { update(rule().copy(startSeconds = it)) }.view)
            if (rule().mode in listOf("middle", "end")) properties.addView(ui.Slider("M · 结束前秒数", rule().endSeconds, 0, 3600, " 秒") { update(rule().copy(endSeconds = it)) }.view)
            properties.addView(ui.label("常用滑条 0–60 秒，点击数值精确输入。\n仅项目、任务、时间使用此规则。\n片尾模式：停止后补录 M 秒。", 12f, ui.muted))
        } else {
            properties.addView(ui.check("显示此项", rule().enabled).apply {
                setOnCheckedChangeListener { _, value -> update(rule().copy(enabled = value)); renderList() }
            })
            xControl = ui.Slider("水平位置 X", rule().x.roundToInt(), 0, 100, "%") { update(rule().copy(x = it.toFloat())) }
            yControl = ui.Slider("垂直位置 Y", rule().y.roundToInt(), 0, 100, "%") { update(rule().copy(y = it.toFloat())) }
            properties.addView(xControl!!.view); properties.addView(yControl!!.view)
            properties.addView(ui.Slider("文字大小", rule().size.roundToInt(), 8, 96, "") { update(rule().copy(size = it.toFloat())) }.view)
            val colors = ui.row(); properties.addView(colors)
            ui.addButton(colors, ui.button("文字颜色") { pickColor(false) }, 1f)
            ui.addButton(colors, ui.button("背景颜色") { pickColor(true) }, 1f)
            val swatches = ui.row(); properties.addView(swatches)
            listOf(rule().color, rule().background).forEach { color -> swatches.addView(ui.label(if (Color.alpha(color) == 0) "透明" else String.format("#%06X", color and 0xFFFFFF), 11f).apply {
                setBackgroundColor(color); setTextColor(if (Color.red(color) + Color.green(color) + Color.blue(color) > 430) Color.BLACK else Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f)) }
            properties.addView(ui.Slider("文字透明度", ((1f - rule().textOpacity) * 100f).roundToInt(), 0, 100, "%") {
                update(rule().copy(textOpacity = 1f - it / 100f))
            }.view)
            properties.addView(ui.Slider("背景透明度", ((1f - rule().backgroundOpacity) * 100f).roundToInt(), 0, 100, "%") {
                update(rule().copy(backgroundOpacity = 1f - it / 100f))
            }.view)
            properties.addView(ui.label("0% 不透明，100% 完全透明；文字与背景分别生效。选择透明背景后不显示底色。", 11f, ui.muted))
            if (selected == "time") {
                properties.addView(ui.label("时间格式（可自定义）", 12f, ui.muted))
                properties.addView(ui.input(rule().timeFormat).apply { addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(rule().copy(timeFormat = s.toString())) }
                    override fun afterTextChanged(s: Editable?) = Unit
                }) })
                properties.addView(ui.label("yyyy-MM-dd HH:mm:ss\nyyyy年MM月dd日 HH:mm:ss\nHH:mm:ss", 11f, ui.muted))
            }
            properties.addView(ui.label("X/Y：画面百分比 0–100；文字靠近边界时自动收进画面。", 11f, ui.muted))
        }
        if (!defectMode) properties.addView(ui.row().apply {
            ui.addButton(this, ui.button("位置样式", !timeTab) { timeTab = false; renderProperties() }, 1f)
            ui.addButton(this, ui.button("录像时段", timeTab) { timeTab = true; renderProperties() }, 1f)
        })
    }
    private fun renderSimulation() {
        simulationControls.removeAllViews()
        if (defectMode) {
            simulationControls.addView(ui.Slider("截图保存后显示时长", duration, 0, 3600, " 秒") { duration = it; dirty = true }.view)
            simulationControls.addView(ui.label("到时自动消失；0 秒只保存截图与 XML。\n此时长为缺陷全部字段共用，不受录像时段、暂停影响。", 11f, ui.muted))
        } else {
            simulationControls.addView(ui.row().apply {
                ui.addButton(this, ui.button("全量布局", !simulation) { simulation = false; renderSimulation(); redraw() })
                ui.addButton(this, ui.button("时段模拟", simulation) { simulation = true; renderSimulation(); redraw() })
            })
            if (simulation) simulationControls.addView(ui.Slider("模拟录像进度（总长 60 秒）", simulationSeconds, 0, 60, " 秒") { simulationSeconds = it; redraw() }.view)
        }
    }
    private fun redraw() {
        preview.selected = selected
        preview.items = keys().mapNotNull { key ->
            val r = drafts.getValue(key)
            if (!r.enabled || (!defectMode && simulation && !r.visible(simulationSeconds * 1000L, (60 - simulationSeconds) * 1000L))) return@mapNotNull null
            val text = if (key == "time") runCatching { SimpleDateFormat(r.timeFormat, Locale.getDefault()).format(Date()) }.getOrDefault("时间格式无效") else {
                val info = when (key.substringBefore('.')) { "project" -> project; "task" -> task; else -> defect }
                val value = info?.values?.get(key.substringAfter('.')).orEmpty()
                val sample = when (key) { "defect.name" -> "纵向裂缝"; "defect.location" -> "距起点 12.6 m"; "defect.length" -> "0.35 m"; "defect.details" -> "管壁局部开裂"; else -> "未填写" }
                "${fieldName(key)}：${value.ifBlank { if (defect == null && defectMode) sample else "[未填写]" }}"
            }
            InspectionPreviewItem(key, text, r)
        }
    }
    private fun pickColor(background: Boolean) {
        val content = ui.column(8).apply { setBackgroundColor(ui.surface) }
        val selectedColor = if (background) rule().background else rule().color
        val dialog = AlertDialog.Builder(activity).setTitle(if (background) "背景颜色 · 27 色 + 透明" else "文字颜色 · 27 色")
            .setView(ui.scroll(content)).setNegativeButton("取消", null).create()
        fun choose(color: Int) {
            update(if (background) rule().copy(background = color) else rule().copy(color = color))
            renderProperties(); dialog.dismiss()
        }
        content.addView(ui.label("每行同一色系，从左到右：浅色 → 中色 → 深色", 12f, ui.muted))
        if (background) content.addView(ui.button("透明背景（不显示底色）", Color.alpha(selectedColor) == 0) {
            choose(Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        InspectionUi.COLOR_GROUPS.forEach { (name, colors) ->
            val row = ui.row(); content.addView(row)
            row.addView(ui.label(name, 15f), LinearLayout.LayoutParams(ui.dp(38), -2))
            colors.forEachIndexed { index, color ->
                val shade = listOf("浅", "中", "深")[index]
                val button = ui.button((if (selectedColor == color) "✓ " else "") + shade) { choose(color) }.apply {
                    this.background = ui.shape(color, if (selectedColor == color) ui.blue else ui.line)
                    setTextColor(if (Color.red(color) + Color.green(color) + Color.blue(color) > 430) Color.BLACK else Color.WHITE)
                    contentDescription = "$name · $shade · ${String.format("#%06X", color and 0xFFFFFF)}"
                }
                ui.addButton(row, button, 1f)
            }
        }
        dialog.show()
    }
    private fun applyAll() {
        val labels = mutableListOf("文字大小", "文字颜色", "背景颜色", "是否显示", "X / Y 位置", "文字透明度", "背景透明度")
        if (!defectMode) labels += "录像时段"
        val checked = BooleanArray(labels.size) { it < 3 || it == 5 || it == 6 }
        AlertDialog.Builder(activity).setTitle("应用到${if (defectMode) "全部缺陷字段" else "项目 / 任务 / 时间"}")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, index, value -> checked[index] = value }
            .setNegativeButton("取消", null).setPositiveButton("应用") { _, _ ->
                fun apply() {
                    val source = rule()
                    keys().forEach { key ->
                        var target = drafts.getValue(key)
                        if (checked[0]) target = target.copy(size = source.size)
                        if (checked[1]) target = target.copy(color = source.color)
                        if (checked[2]) target = target.copy(background = source.background)
                        if (checked[3]) target = target.copy(enabled = source.enabled)
                        if (checked[4]) target = target.copy(x = source.x, y = source.y)
                        if (checked[5]) target = target.copy(textOpacity = source.textOpacity)
                        if (checked[6]) target = target.copy(backgroundOpacity = source.backgroundOpacity)
                        if (!defectMode && checked[7]) target = target.copy(mode = source.mode, startSeconds = source.startSeconds, endSeconds = source.endSeconds)
                        drafts[key] = target
                    }
                    dirty = true; renderList(); renderProperties(); redraw()
                }
                if (checked[4]) AlertDialog.Builder(activity).setTitle("全部使用相同位置？").setMessage("所有 OSD 将重叠，可在预览中分别拖动。")
                    .setPositiveButton("确认") { _, _ -> apply() }.setNegativeButton("取消", null).show()
                else apply()
            }.show()
    }
}
