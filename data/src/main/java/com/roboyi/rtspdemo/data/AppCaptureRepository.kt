package com.roboyi.rtspdemo.data

import android.content.Context
import com.roboyi.rtspdemo.domain.CaptureRepository
import java.io.File

/** 所有删除操作都重新核对文件身份，防止误删目录内的其他内容。 */
class AppCaptureRepository(private val context: Context) : CaptureRepository {
    // New camera footage belongs in credential-protected app storage even on Android 7–9.
    override val directory: File = File(context.filesDir, "captures")
    private val legacyDirectory: File? = context.getExternalFilesDir(null)?.let { File(it, "captures") }

    override fun newFile(prefix: String, extension: String): File {
        check(directory.isDirectory || directory.mkdirs()) { context.getString(R.string.capture_directory_error) }
        return File(directory, "${prefix}_${System.currentTimeMillis()}.$extension")
    }

    override fun files(): List<File> = (directory.listFiles()?.toList().orEmpty() +
        legacyDirectory?.listFiles()?.toList().orEmpty())
        .filter { isCapture(it) && it.length() > 0 }
        .sortedByDescending(File::lastModified)

    override fun delete(files: Collection<File>): Int = files.count { isCapture(it) && it.delete() }

    override fun deleteAll(): Pair<Int, Int> {
        val candidates = (directory.listFiles()?.toList().orEmpty() +
            legacyDirectory?.listFiles()?.toList().orEmpty()).filter(::isCapture)
        return candidates.size to delete(candidates)
    }

    private fun isCapture(file: File): Boolean =
        (file.parentFile == directory || file.parentFile == legacyDirectory) &&
        runCatching { file.canonicalFile.parentFile == file.parentFile?.canonicalFile }
            .getOrDefault(false) &&
        file.isFile &&
        ((file.name.startsWith("recording_") && file.extension.equals("mp4", true)) ||
            (file.name.startsWith("snapshot_") && file.extension.equals("jpg", true)))
}
