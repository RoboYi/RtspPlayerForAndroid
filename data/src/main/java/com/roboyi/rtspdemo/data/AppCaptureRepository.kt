package com.roboyi.rtspdemo.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.roboyi.rtspdemo.domain.CaptureRepository
import java.io.File

/** 所有删除操作都重新核对文件身份，防止误删目录内的其他内容。 */
class AppCaptureRepository(private val context: Context) : CaptureRepository {
    // SDK 写入期间使用私有暂存文件，封装完成后再原子式发布到系统公共媒体库。
    override val directory: File = File(context.filesDir, "captures")
    private val legacyDirectory: File? = context.getExternalFilesDir(null)?.let { File(it, "captures") }

    override fun newFile(prefix: String, extension: String): File {
        check(directory.isDirectory || directory.mkdirs()) { context.getString(R.string.capture_directory_error) }
        return File(directory, "${prefix}_${System.currentTimeMillis()}.$extension")
    }

    override fun publish(file: File): File {
        require(isCapture(file)) { "Not a RoboYiPlayer capture: ${file.name}" }
        require(file.length() > 0L) { "Capture is empty: ${file.name}" }
        val video = file.extension.equals("mp4", true)
        val publicFile = publicFile(file.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishWithMediaStore(file, publicFile, video)
        } else {
            check(publicFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                context.getString(R.string.capture_directory_error)
            }
            file.copyTo(publicFile, overwrite = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(publicFile.absolutePath),
                arrayOf(if (video) VIDEO_MIME else IMAGE_MIME),
                null
            )
        }
        check(file.delete() || !file.exists()) { "Could not remove capture staging file" }
        return publicFile
    }

    override fun files(): List<File> = (publicFiles() +
        directory.listFiles()?.toList().orEmpty() +
        legacyDirectory?.listFiles()?.toList().orEmpty())
        .distinctBy { it.absolutePath }
        .filter { isCapture(it) && it.length() > 0 }
        .sortedByDescending(File::lastModified)

    override fun delete(files: Collection<File>): Int = files.count { file ->
        isCapture(file) && deleteCapture(file)
    }

    override fun deleteAll(): Pair<Int, Int> {
        val candidates = (publicFiles() + directory.listFiles()?.toList().orEmpty() +
            legacyDirectory?.listFiles()?.toList().orEmpty())
            .distinctBy { it.absolutePath }
            .filter(::isCapture)
        return candidates.size to delete(candidates)
    }

    private fun isCapture(file: File): Boolean =
        (file.parentFile == directory || file.parentFile == legacyDirectory ||
            file.parentFile == publicDcimDirectory() ||
            file.parentFile == publicPicturesDirectory() || file.parentFile == publicMoviesDirectory()) &&
        runCatching { file.canonicalFile.parentFile == file.parentFile?.canonicalFile }
            .getOrDefault(false) &&
        file.isFile &&
        ((file.name.startsWith("recording_") && file.extension.equals("mp4", true)) ||
            (file.name.startsWith("snapshot_") && file.extension.equals("jpg", true)))

    private fun publishWithMediaStore(source: File, publicFile: File, video: Boolean) {
        val resolver = context.contentResolver
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = DCIM_RELATIVE_PATH
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) VIDEO_MIME else IMAGE_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) {
            context.getString(R.string.capture_directory_error)
        }
        try {
            checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        // MediaStore 已决定真实位置；publicFile 路径用于 Demo 内预览和 FileProvider 分享。
    }

    private fun publicFiles(): List<File> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore(video = false) + queryMediaStore(video = true) + legacyPublicFiles()
        } else {
            publicDcimDirectory().listFiles()?.toList().orEmpty() + legacyPublicFiles()
        }

    private fun legacyPublicFiles(): List<File> =
        publicPicturesDirectory().listFiles()?.toList().orEmpty() +
            publicMoviesDirectory().listFiles()?.toList().orEmpty()

    private fun queryMediaStore(video: Boolean): List<File> {
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = DCIM_RELATIVE_PATH
        val result = mutableListOf<File>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(relativePath),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                result += publicFile(cursor.getString(nameColumn))
            }
        }
        return result
    }

    private fun deleteCapture(file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            file.parentFile != publicDcimDirectory()
        ) return file.delete()

        val video = file.extension.equals("mp4", true)
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = DCIM_RELATIVE_PATH
        return context.contentResolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(file.name, relativePath)
        ) > 0
    }

    private fun publicFile(name: String): File = File(publicDcimDirectory(), name)

    private fun publicDcimDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        PUBLIC_DIRECTORY
    )

    private fun publicPicturesDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        PUBLIC_DIRECTORY
    )

    private fun publicMoviesDirectory(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        PUBLIC_DIRECTORY
    )

    private companion object {
        const val PUBLIC_DIRECTORY = "RoboYiPlayer"
        const val DCIM_RELATIVE_PATH = "DCIM/$PUBLIC_DIRECTORY/"
        const val IMAGE_MIME = "image/jpeg"
        const val VIDEO_MIME = "video/mp4"
    }
}
