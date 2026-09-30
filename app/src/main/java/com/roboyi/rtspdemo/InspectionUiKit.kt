package com.roboyi.rtspdemo

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

/** Shared native controls for the landscape inspection workspace. */
internal class InspectionUiKit(val activity: Activity) {
    val background = Color.rgb(16, 22, 31)
    val surface = Color.rgb(24, 33, 45)
    val raised = Color.rgb(32, 44, 59)
    val line = Color.rgb(53, 66, 85)
    val blue = Color.rgb(103, 171, 255)
    val muted = Color.rgb(168, 183, 203)
    fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    fun shape(color: Int = surface, stroke: Int = line) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(8).toFloat(); setStroke(dp(1), stroke)
    }
    fun column(padding: Int = 0) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun label(text: String, size: Float = 14f, color: Int = Color.WHITE) = TextView(activity).apply {
        this.text = text; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
    }
    fun title(text: String) = label(text, 17f).apply { setTypeface(typeface, Typeface.BOLD) }
    fun panel() = column(12).apply { background = shape() }
    fun button(text: String, primary: Boolean = false, action: () -> Unit) = Button(activity).apply {
        this.text = text; isAllCaps = false; textSize = 13f; minWidth = 0; minimumWidth = 0
        minHeight = dp(42); minimumHeight = dp(42); setPadding(dp(12), dp(6), dp(12), dp(6))
        background = shape(if (primary) blue else raised, if (primary) blue else line)
        setTextColor(if (primary) Color.rgb(16, 29, 45) else Color.WHITE)
        setOnClickListener { action() }
    }
    fun addButton(row: LinearLayout, button: View, weight: Float = 0f) {
        row.addView(button, LinearLayout.LayoutParams(if (weight > 0f) 0 else -2, -2, weight).apply {
            marginEnd = dp(8); topMargin = dp(3); bottomMargin = dp(3)
        })
    }
    fun spacer(row: LinearLayout) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
    fun scroll(view: View) = ScrollView(activity).apply { isFillViewport = false; addView(view) }
    fun input(value: String = "", numeric: Boolean = false) = EditText(activity).apply {
        setText(value); setTextColor(Color.WHITE); setHintTextColor(muted); textSize = 14f
        background = shape(raised); setPadding(dp(10), dp(8), dp(10), dp(8)); minimumHeight = dp(42)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (!numeric) { maxLines = 3; setSelectAllOnFocus(true) }
    }
    fun check(text: String, selected: Boolean) = CheckBox(activity).apply {
        this.text = text; isChecked = selected; textSize = 13f; setTextColor(Color.WHITE)
        buttonTintList = android.content.res.ColorStateList.valueOf(blue)
    }
    data class Screen(val dialog: Dialog, val body: LinearLayout, val footer: LinearLayout)
    fun screen(name: String): Screen {
        val dialog = Dialog(activity)
        val root = column().apply { setBackgroundColor(this@InspectionUiKit.background) }
        root.addView(row().apply {
            setBackgroundColor(surface); setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(title("RoboYiPlayer  /  $name"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("DCIM / RoboYiPlayer", 11f, muted))
        })
        val body = column(12); root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = column(10).apply { setBackgroundColor(surface) }; root.addView(footer)
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        return Screen(dialog, body, footer)
    }
    /** SeekBar handles the common range, with an exact-value dialog for the full range. */
    inner class Slider(title: String, initial: Int, private val low: Int, private val high: Int,
                       private val unit: String = "", private val changed: (Int) -> Unit) {
        val view = column()
        private val seek = SeekBar(activity)
        private val valueButton = button("$initial$unit") {}
        private val decrease = button("−") { adjust(-1) }
        private val increase = button("+") { adjust(1) }
        var value = initial.coerceIn(low, high); private set
        private var syncing = false
        init {
            view.addView(row().apply {
                addView(label(title, 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueButton)
            })
            valueButton.textSize = 12f; valueButton.minHeight = dp(32); valueButton.minimumHeight = dp(32)
            view.addView(row().apply {
                addView(decrease, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(seek, LinearLayout.LayoutParams(0, dp(30), 1f))
                addView(increase, LinearLayout.LayoutParams(dp(42), dp(42)))
            })
            decrease.contentDescription = "$title 减少 1$unit"
            increase.contentDescription = "$title 增加 1$unit"
            decrease.textSize = 20f; increase.textSize = 20f
            seek.progressTintList = android.content.res.ColorStateList.valueOf(blue)
            seek.thumbTintList = android.content.res.ColorStateList.valueOf(blue)
            fun update(v: Int) { valueButton.text = "$v$unit" }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!syncing && fromUser) {
                        value = progress + low; update(value)
                        updateBounds()
                        changed(value)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
            valueButton.setOnClickListener {
                val edit = input(value.toString(), true)
                val prompt = android.app.AlertDialog.Builder(activity).setTitle("$title（$low–$high）").setView(edit)
                    .setNegativeButton("取消", null).setPositiveButton("应用", null).create()
                prompt.setOnShowListener { prompt.getButton(-1).setOnClickListener {
                    val v = edit.text.toString().toIntOrNull()
                    if (v == null || v !in low..high) edit.error = "请输入 $low–$high 的整数"
                    else { set(v); changed(v); prompt.dismiss() }
                } }; prompt.show()
            }
            set(value)
        }
        private fun adjust(delta: Int) {
            val next = (value + delta).coerceIn(low, high)
            if (next != value) { set(next); changed(next) }
        }
        private fun updateBounds() {
            decrease.isEnabled = value > low; increase.isEnabled = value < high
            decrease.alpha = if (decrease.isEnabled) 1f else .35f
            increase.alpha = if (increase.isEnabled) 1f else .35f
        }
        fun set(v: Int) {
            syncing = true; value = v.coerceIn(low, high)
            seek.max = (if (high > 300) when { value <= 60 -> 60; value <= 300 -> 300; else -> high } else high) - low
            seek.progress = value - low; valueButton.text = "$value$unit"
            updateBounds()
            syncing = false
        }
    }
}
