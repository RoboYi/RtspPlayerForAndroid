package com.roboyi.rtspdemo.data

import android.content.Context
import com.roboyi.rtspdemo.domain.CaptureRepository
import java.io.File

/** 所有删除操作都重新核对文件身份，防止误删目录内的其他内容。 */
class AppCaptureRepository(private val context: Context) : CaptureRepository {
    override val directory: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "captures")

    override fun newFile(prefix: String, extension: String): File {
        check(directory.isDirectory || directory.mkdirs()) { context.getString(R.string.capture_directory_error) }
        return File(directory, "${prefix}_${System.currentTimeMillis()}.$extension")
    }

    override fun files(): List<File> = directory.listFiles()
        ?.filter(::isCapture)
        ?.sortedByDescending(File::lastModified)
        .orEmpty()

    override fun delete(files: Collection<File>): Int = files.count { isCapture(it) && it.delete() }

    private fun isCapture(file: File): Boolean = file.parentFile == directory &&
        file.isFile && file.length() > 0 &&
        ((file.name.startsWith("recording_") && file.extension.equals("mp4", true)) ||
            (file.name.startsWith("snapshot_") && file.extension.equals("jpg", true)))
}
