package com.aquascope.smriti.llm

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.io.RandomAccessFile

/**
 * On-device Whisper Tiny (litert-community encode/decode signatures).
 *
 * encode: float32[1,80,3000] → encoder states [1,1500,D]
 * decode: states + int32[1,128] tokens + float32[1,1,128,128] mask → logits [1,128,51865]
 */
class WhisperSttEngine(
    modelFile: File,
    private val tokenizer: WhisperTokenizer
) : AutoCloseable {

    private val interpreter: Interpreter
    private val encodeIn: String
    private val encodeOut: String
    private val decodeIns: List<String>
    private val decodeOuts: List<String>
    private val encHidden: Int
    private val maxTokens = 128
    private val vocabSize = 51_865

    init {
        val opts = Interpreter.Options().apply { setNumThreads(4) }
        interpreter = Interpreter(loadModel(modelFile), opts)
        val sigs = interpreter.signatureKeys
        require(sigs.contains("encode") && sigs.contains("decode")) {
            "Whisper model missing encode/decode signatures: ${sigs.toList()}"
        }
        encodeIn = interpreter.getSignatureInputs("encode").first()
        encodeOut = interpreter.getSignatureOutputs("encode").first()
        decodeIns = interpreter.getSignatureInputs("decode").toList()
        decodeOuts = interpreter.getSignatureOutputs("decode").toList()

        val encOutTensor = interpreter.getOutputTensorFromSignature(encodeOut, "encode")
        encHidden = encOutTensor.shape()[2]
        Log.i(TAG, "Whisper ready encodeIn=$encodeIn encodeOut=$encodeOut hidden=$encHidden")
    }

    fun transcribe(pcm16: ShortArray, languageTag: String?): String {
        val mel = WhisperMelFrontend.fromPcm16(pcm16)
        val encInShape = interpreter.getInputTensorFromSignature(encodeIn, "encode").shape()
        val melBuf = float3ToBuffer(mel, encInShape)
        val statesBuf = ByteBuffer.allocateDirect(1 * 1_500 * encHidden * 4).order(ByteOrder.nativeOrder())

        val encInputs = hashMapOf<String, Any>(encodeIn to melBuf)
        val encOutputs = hashMapOf<String, Any>(encodeOut to statesBuf)
        interpreter.runSignature(encInputs, encOutputs, "encode")
        statesBuf.rewind()

        val lang = WhisperTokenizer.languageId(languageTag)
        val tokens = IntArray(maxTokens)
        tokens[0] = WhisperTokenizer.SOT
        tokens[1] = lang
        tokens[2] = WhisperTokenizer.TRANSCRIBE
        tokens[3] = WhisperTokenizer.NO_TIMESTAMPS
        var len = 4

        val tokenDtype = decodeTokenDtype()
        val tokenBuf = ByteBuffer.allocateDirect(maxTokens * tokenDtype).order(ByteOrder.nativeOrder())
        val maskBuf = ByteBuffer.allocateDirect(1 * 1 * maxTokens * maxTokens * 4).order(ByteOrder.nativeOrder())
        val logitsElems = decodeLogitsElems()
        val logitsBuf = ByteBuffer.allocateDirect(logitsElems * 4).order(ByteOrder.nativeOrder())
        val logitsPosStride = if (logitsElems >= maxTokens * vocabSize) vocabSize else 0

        val generated = ArrayList<Int>()
        while (len < maxTokens - 1) {
            fillTokens(tokenBuf, tokens, tokenDtype)
            fillCausalMask(maskBuf, len)
            logitsBuf.clear()

            val decInputs = bindDecodeInputs(statesBuf, tokenBuf, maskBuf)
            val decOutputs = bindDecodeOutputs(logitsBuf)
            statesBuf.rewind()
            tokenBuf.rewind()
            maskBuf.rewind()
            interpreter.runSignature(decInputs, decOutputs, "decode")
            logitsBuf.rewind()

            val next = argmaxAt(logitsBuf, pos = len - 1, posStride = logitsPosStride)
            if (next == WhisperTokenizer.EOT || next >= WhisperTokenizer.TIMESTAMP_BEGIN) break
            tokens[len] = next
            generated.add(next)
            len++
            if (generated.size >= 96) break
        }
        return tokenizer.decode(generated)
    }

    private fun decodeLogitsElems(): Int {
        val name = decodeOuts.first()
        val shape = interpreter.getOutputTensorFromSignature(name, "decode").shape()
        return shape.fold(1) { a, b -> a * b }.coerceAtLeast(vocabSize)
    }

    private fun decodeTokenDtype(): Int {
        for (name in decodeIns) {
            val t = interpreter.getInputTensorFromSignature(name, "decode")
            val shape = t.shape()
            if (shape.size == 2 && shape[1] == maxTokens) {
                return if (t.dataType().name().contains("INT64")) 8 else 4
            }
        }
        return 4
    }

    private fun bindDecodeInputs(
        states: ByteBuffer,
        tokens: ByteBuffer,
        mask: ByteBuffer
    ): Map<String, Any> {
        val map = HashMap<String, Any>(3)
        for (name in decodeIns) {
            val shape = interpreter.getInputTensorFromSignature(name, "decode").shape()
            when {
                shape.size == 3 && shape[1] == 1_500 -> map[name] = states
                shape.size == 2 && shape[1] == maxTokens -> map[name] = tokens
                shape.size == 4 -> map[name] = mask
                shape.contentEquals(intArrayOf(1, maxTokens)) -> map[name] = tokens
                else -> {
                    // Fallback by element count
                    val elems = shape.fold(1) { a, b -> a * b }
                    when (elems) {
                        1_500 * encHidden -> map[name] = states
                        maxTokens -> map[name] = tokens
                        maxTokens * maxTokens -> map[name] = mask
                    }
                }
            }
        }
        require(map.size >= 2) { "Could not bind decode inputs: $decodeIns bound=${map.keys}" }
        return map
    }

    private fun bindDecodeOutputs(logits: ByteBuffer): Map<String, Any> {
        val name = decodeOuts.first()
        return mapOf(name to logits)
    }

    private fun fillTokens(buf: ByteBuffer, tokens: IntArray, bytesPer: Int) {
        buf.clear()
        if (bytesPer == 8) {
            val asLong = buf.asLongBuffer()
            for (i in 0 until maxTokens) asLong.put(tokens[i].toLong())
        } else {
            val asInt = buf.asIntBuffer()
            for (i in 0 until maxTokens) asInt.put(tokens[i])
        }
        buf.rewind()
    }

    private fun fillCausalMask(buf: ByteBuffer, len: Int) {
        buf.clear()
        val f = buf.asFloatBuffer()
        val neg = -1e9f
        for (i in 0 until maxTokens) {
            for (j in 0 until maxTokens) {
                val allow = i < len && j <= i
                f.put(if (allow) 0f else neg)
            }
        }
        buf.rewind()
    }

    private fun argmaxAt(logits: ByteBuffer, pos: Int, posStride: Int): Int {
        val f = logits.asFloatBuffer()
        val total = f.capacity()
        val stride = if (posStride > 0) pos * posStride else 0
        var best = 0
        var bestV = Float.NEGATIVE_INFINITY
        val limit = minOf(vocabSize, (total - stride).coerceAtLeast(0))
        for (i in 0 until limit) {
            val v = f.get(stride + i)
            if (v > bestV) {
                bestV = v
                best = i
            }
        }
        return best
    }

    private fun float3ToBuffer(mel: Array<Array<FloatArray>>, shape: IntArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(1 * WhisperMelFrontend.N_MELS * WhisperMelFrontend.N_FRAMES * 4)
            .order(ByteOrder.nativeOrder())
        val f = buf.asFloatBuffer()
        val timeMajor = shape.size >= 3 && shape[1] == WhisperMelFrontend.N_FRAMES
        if (timeMajor) {
            for (t in 0 until WhisperMelFrontend.N_FRAMES) {
                for (m in 0 until WhisperMelFrontend.N_MELS) f.put(mel[0][m][t])
            }
        } else {
            for (m in 0 until WhisperMelFrontend.N_MELS) {
                for (t in 0 until WhisperMelFrontend.N_FRAMES) f.put(mel[0][m][t])
            }
        }
        buf.rewind()
        return buf
    }

    override fun close() {
        runCatching { interpreter.close() }
    }

    companion object {
        private const val TAG = "WhisperStt"

        private fun loadModel(file: File): MappedByteBuffer {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        }
    }
}
