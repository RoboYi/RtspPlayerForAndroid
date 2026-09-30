package com.roboyi.rtspdemo.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.roboyi.rtspdemo.domain.CaptureRepository
import java.io.File

class AppCaptureRepository(private val context: Context) : CaptureRepository {
    override val directory = File(context.filesDir, "captures")
    private val legacyDirectory = context.getExternalFilesDir(null)?.let { File(it, "captures") }
    val publicRoot: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "RoboYiPlayer")
    private val legacyInspectionRoot get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "RtspPlayer")
    private val legacyRoots get() = listOf(legacyInspectionRoot) + listOf("Pictures", "Movies").map {
        File(Environment.getExternalStorageDirectory(), "$it/RoboYiPlayer")
    }
    fun stagingFile(relative: String): File {
        val file = File(directory, relative)
        require(isWithin(file, directory)) { "Invalid capture path" }
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "无法创建暂存目录" }
        return file
    }
    fun relative(file: File): String = when {
        isWithin(file, directory) -> file.relativeTo(directory).invariantSeparatorsPath
        isWithin(file, publicRoot) -> file.relativeTo(publicRoot).invariantSeparatorsPath
        isWithin(file, legacyInspectionRoot) -> file.relativeTo(legacyInspectionRoot).invariantSeparatorsPath
        else -> error("Not a current capture")
    }
    override fun newFile(prefix: String, extension: String): File {
        val stamp = InspectionStore.timestamp()
        return stagingFile("pictures/$stamp/${prefix}_${System.currentTimeMillis()}.$extension")
    }
    override fun publish(file: File): File {
        require(isWithin(file, directory) && file.isFile && file.length() > 0) { "Capture is empty or outside staging" }
        val relative = relative(file)
        val destination = File(publicRoot, relative)
        val video = file.extension.equals("mp4", true)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = collection(video)
            val path = "DCIM/RoboYiPlayer/" + relative.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val existing = mediaId(video, path, file.name)?.let { ContentUris.withAppendedId(collection, it) }
            val uri = existing ?: checkNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })) { "无法创建媒体文件" }
            try {
                checkNotNull(resolver.openOutputStream(uri, "wt")).use { output -> file.inputStream().use { it.copyTo(output) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Throwable) {
                if (existing == null) runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
            file.copyTo(destination, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(destination.absolutePath), null, null)
        }
        check(file.delete() || !file.exists()) { "无法清理暂存文件" }
        return destination
    }
    override fun files(): List<File> = (publicFiles() + stagedFiles() + legacyFiles()).distinctBy { it.absolutePath }
        .filter { it.isFile && it.extension.lowercase() in setOf("mp4", "jpg", "jpeg") && it.length() > 0 }
        .sortedByDescending(File::lastModified)
    private fun stagedFiles() = if (directory.exists()) directory.walkTopDown().filter(File::isFile).toList() else emptyList()
    private fun legacyFiles() = (legacyRoots + listOfNotNull(legacyDirectory)).flatMap { root ->
        if (root.exists()) root.walkTopDown().filter(File::isFile).toList() else emptyList()
    }
    private fun publicFiles(): List<File> {
        if (Build.VERSION.SDK_INT < 29) return if (publicRoot.exists()) publicRoot.walkTopDown().filter(File::isFile).toList() else emptyList()
        return listOf(false, true).flatMap { video ->
            val files = mutableListOf<File>()
            context.contentResolver.query(collection(video), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING}=0", arrayOf("DCIM/RoboYiPlayer/%"), null)?.use { c ->
                while (c.moveToNext()) files += File(Environment.getExternalStorageDirectory(), c.getString(1) + c.getString(0))
            }
            files
        }
    }
    override fun delete(files: Collection<File>): Int = files.count { file ->
        if (!allowed(file) || file.extension.lowercase() !in setOf("mp4", "jpg", "jpeg")) false
        else runCatching {
            if (Build.VERSION.SDK_INT >= 29 && (listOf(publicRoot) + legacyRoots).any { isWithin(file, it) }) {
                val relative = file.relativeTo(Environment.getExternalStorageDirectory()).invariantSeparatorsPath
                val video = file.extension.equals("mp4", true)
                val id = mediaId(video, relative.substringBeforeLast('/') + "/", file.name)
                id != null && context.contentResolver.delete(ContentUris.withAppendedId(collection(video), id), null, null) > 0
            } else file.delete()
        }.getOrDefault(false)
    }
    override fun deleteAll(): Pair<Int, Int> { val candidates = files(); return candidates.size to delete(candidates) }
    private fun allowed(file: File) = (listOf(directory, publicRoot) + legacyRoots + listOfNotNull(legacyDirectory)).any { isWithin(file, it) }
    private fun collection(video: Boolean) = if (Build.VERSION.SDK_INT >= 29) {
        if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private fun mediaId(video: Boolean, path: String, name: String): Long? = context.contentResolver.query(collection(video),
        arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
        arrayOf(path, name), null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
    private fun isWithin(file: File, root: File): Boolean = file.canonicalPath.startsWith(root.canonicalPath + File.separator)
}
