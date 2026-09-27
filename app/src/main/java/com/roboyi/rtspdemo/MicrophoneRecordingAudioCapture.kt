package com.roboyi.rtspdemo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import com.roboyi.rtsp.RecordingAudioFrame
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Captures microphone PCM and feeds the SDK's external recording-audio input. */
internal class MicrophoneRecordingAudioCapture(
    private val enqueue: (RecordingAudioFrame) -> Boolean,
    private val onError: (Throwable) -> Unit
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        check(worker == null) { "Microphone capture is already started" }
        val setup = createRecorder()
        audioRecord = setup.audioRecord
        try {
            setup.audioRecord.startRecording()
            check(setup.audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Microphone did not enter the recording state"
            }
            worker = Thread(
                { captureLoop(setup) },
                "roboyi-demo-microphone"
            ).also { it.start() }
        } catch (error: Throwable) {
            stopped.set(true)
            release(setup.audioRecord)
            throw error
        }
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    override fun close() {
        if (stopped.compareAndSet(false, true)) {
            runCatching { audioRecord?.stop() }
        }
        val activeWorker = worker
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(STOP_JOIN_TIMEOUT_MS) }
        }
        audioRecord?.let(::release)
        worker = null
        audioRecord = null
    }

    private fun captureLoop(setup: RecorderSetup) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val buffer = ByteArray(setup.frameBytes)
        var submittedSampleFrames = 0L
        try {
            while (!stopped.get()) {
                val read = setup.audioRecord.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING
                )
                if (read < 0) error("Microphone read failed: $read")
                val completeBytes = read - (read % PCM_BYTES_PER_SAMPLE)
                if (completeBytes == 0 || paused.get()) continue

                val sampleFrames = completeBytes / PCM_BYTES_PER_SAMPLE
                val accepted = enqueue(
                    RecordingAudioFrame(
                        data = buffer.copyOf(completeBytes),
                        presentationTimeUs = submittedSampleFrames * MICROS_PER_SECOND /
                            setup.sampleRate,
                        mimeType = MediaFormat.MIMETYPE_AUDIO_RAW,
                        sampleRate = setup.sampleRate,
                        channelCount = CHANNEL_COUNT,
                        pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                    )
                )
                if (accepted) submittedSampleFrames += sampleFrames
            }
        } catch (error: Throwable) {
            if (!stopped.get() && errorReported.compareAndSet(false, true)) onError(error)
        } finally {
            release(setup.audioRecord)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): RecorderSetup {
        var lastError: Throwable? = null
        for (sampleRate in SAMPLE_RATES) {
            val minBufferBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferBytes <= 0) continue
            val frameBytes = ((sampleRate * PACKET_DURATION_MS / 1_000) *
                CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE).coerceAtLeast(PCM_BYTES_PER_SAMPLE)
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBufferBytes, frameBytes * 4)
                )
            } catch (error: Throwable) {
                lastError = error
                continue
            }
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                return RecorderSetup(recorder, sampleRate, frameBytes)
            }
            recorder.release()
        }
        throw IllegalStateException("No supported microphone PCM configuration", lastError)
    }

    private fun release(recorder: AudioRecord) {
        if (released.compareAndSet(false, true)) runCatching { recorder.release() }
    }

    private data class RecorderSetup(
        val audioRecord: AudioRecord,
        val sampleRate: Int,
        val frameBytes: Int
    )

    private companion object {
        val SAMPLE_RATES = intArrayOf(48_000, 44_100, 16_000)
        const val CHANNEL_COUNT = 1
        const val PCM_BYTES_PER_SAMPLE = 2
        const val PACKET_DURATION_MS = 20
        const val MICROS_PER_SECOND = 1_000_000L
        const val STOP_JOIN_TIMEOUT_MS = 1_000L
    }
}
