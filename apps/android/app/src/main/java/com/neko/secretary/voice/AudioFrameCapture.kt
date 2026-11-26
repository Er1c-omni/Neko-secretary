package com.neko.secretary.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

data class AudioFrame(
    val pcm16Le: ByteArray,
    val rms: Float,
    val sequence: Int
)

/**
 * Abstraction over microphone PCM capture so streaming ASR engines can be unit-tested
 * with synthetic audio frames without Android hardware.
 */
interface AudioFrameSource {
    val sampleRateHz: Int
    fun captureFrames(): Flow<AudioFrame>
}

/**
 * 16 kHz / 16-bit mono PCM AudioRecord streamer producing 200 ms frames (6400 bytes).
 * Caller must verify RECORD_AUDIO permission before collecting.
 */
class AndroidAudioRecordSource(
    override val sampleRateHz: Int = 16_000,
    private val frameDurationMs: Int = 200
) : AudioFrameSource {

    private val bytesPerFrame: Int = (sampleRateHz * 2 * frameDurationMs) / 1000

    @SuppressLint("MissingPermission")
    override fun captureFrames(): Flow<AudioFrame> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(bytesPerFrame * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize (check RECORD_AUDIO permission)"
        }

        val buffer = ByteArray(bytesPerFrame)
        var seq = 0
        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val copy = buffer.copyOf(read)
                    emit(AudioFrame(copy, PcmAudioMath.normalizedRms(copy, read), seq++))
                } else if (read < 0) {
                    break
                }
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }.flowOn(Dispatchers.IO)
}
