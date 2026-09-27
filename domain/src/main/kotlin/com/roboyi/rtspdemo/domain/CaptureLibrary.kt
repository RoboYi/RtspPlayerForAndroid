package com.roboyi.rtspdemo.domain

import java.io.File

/** File 是 SDK 写入截图/录像所需的边界类型，领域层不依赖 Android。 */
interface CaptureRepository {
    val directory: File
    fun newFile(prefix: String, extension: String): File
    /** Publishes a completed staging file to the user-visible DCIM/RoboYiPlayer collection. */
    fun publish(file: File): File
    fun files(): List<File>
    fun delete(files: Collection<File>): Int
    fun deleteAll(): Pair<Int, Int>
}

class CaptureLibrary(private val repository: CaptureRepository) {
    val directory: File get() = repository.directory

    fun newSnapshotFile(): File = repository.newFile("snapshot", "jpg")
    fun newRecordingFile(): File = repository.newFile("recording", "mp4")
    fun publish(file: File): File = repository.publish(file)

    /** 录制中的文件可能尚未封装完成，不应展示或删除。 */
    fun visibleFiles(activeRecording: File?): List<File> =
        repository.files().filterNot { it == activeRecording }

    fun delete(files: Collection<File>): Int = repository.delete(files)

    /** Includes incomplete and zero-byte captures that are hidden from the library. */
    fun deleteAll(): Pair<Int, Int> = repository.deleteAll()
}
