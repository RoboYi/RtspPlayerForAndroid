package com.roboyi.rtspdemo

import android.content.Context
import android.content.Intent

/** 一级工作区。与具体 View、Fragment 解耦，Activity 之间只传递稳定的页面语义。 */
internal enum class MainSection { PLAYBACK, DISCOVERY, FILES }

internal object AppNavigation {
    const val EXTRA_SECTION = "roboyi.main.section"
    const val EXTRA_RTSP_URL = "roboyi.main.rtsp_url"
    const val EXTRA_AUTO_PLAY = "roboyi.main.auto_play"
    const val EXTRA_DIALOG = "roboyi.main.dialog"
    const val DIALOG_SETTINGS = "settings"
    const val DIALOG_ABOUT = "about"

    fun mainIntent(
        context: Context,
        section: MainSection,
        rtspUrl: String? = null,
        autoPlay: Boolean = false,
        dialog: String? = null
    ): Intent = Intent(context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        putExtra(EXTRA_SECTION, section.name)
        putExtra(EXTRA_AUTO_PLAY, autoPlay)
        rtspUrl?.let { putExtra(EXTRA_RTSP_URL, it) }
        dialog?.let { putExtra(EXTRA_DIALOG, it) }
    }

    fun openMain(
        context: Context,
        section: MainSection,
        rtspUrl: String? = null,
        autoPlay: Boolean = false,
        dialog: String? = null
    ) {
        context.startActivity(mainIntent(context, section, rtspUrl, autoPlay, dialog))
    }

    fun openPush(context: Context) {
        context.startActivity(Intent(context, CameraPushActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        })
    }
}
