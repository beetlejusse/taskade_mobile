package com.app.taskade_mobile.voice.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.app.taskade_mobile.core.ApiConfig
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD (v5) running on ONNX Runtime Mobile — the same model and tuned
 * thresholds the web client uses (PRD §5.2). The model file is bundled as the
 * asset `silero_vad_v5.onnx`.
 *
 * v5 signature (index-based access avoids name coupling):
 *  - inputs:  `input` [1, N] float, `state` [2, 1, 128] float, `sr` [1] int64
 *  - outputs: probability [1, 1] float, next `state` [2, 1, 128] float
 *
 * The recurrent state is carried across frames and zeroed by [reset] per turn.
 * All ORT handles are released in [close].
 */
class SileroVadModel(context: Context) : VadModel {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val state = FloatArray(STATE_SIZE) // [2 * 1 * 128]

    init {
        val modelBytes = context.assets.open(VadModel.Factory.SILERO_ASSET).use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
    }

    override fun probability(frame: ShortArray): Float {
        val audio = FloatArray(frame.size) { frame[it] / 32768f }

        val inputTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())
        )
        val srTensor = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(ApiConfig.MIC_SAMPLE_RATE.toLong())), longArrayOf(1)
        )
        val stateTensor = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(state), longArrayOf(2, 1, STATE_HIDDEN.toLong())
        )

        return try {
            val result = session.run(
                mapOf("input" to inputTensor, "sr" to srTensor, "state" to stateTensor)
            )
            result.use {
                @Suppress("UNCHECKED_CAST")
                val prob = (it[0].value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val next = it[1].value as Array<Array<FloatArray>> // [2][1][128]
                var k = 0
                for (i in 0 until 2) for (j in 0 until STATE_HIDDEN) state[k++] = next[i][0][j]
                prob
            }
        } finally {
            inputTensor.close()
            srTensor.close()
            stateTensor.close()
        }
    }

    override fun reset() {
        state.fill(0f)
    }

    override fun close() {
        runCatching { session.close() }
    }

    private companion object {
        const val STATE_HIDDEN = 128
        const val STATE_SIZE = 2 * 1 * STATE_HIDDEN
    }
}
