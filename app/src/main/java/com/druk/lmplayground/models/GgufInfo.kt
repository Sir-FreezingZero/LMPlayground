package com.druk.lmplayground.models

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Architecture details read from a GGUF file's header, to estimate how much
 * memory a model needs before loading it. Ported from PrivateLM
 * (gguf_metadata.dart).
 */
data class GgufInfo(
    val architecture: String,
    val layers: Int,
    val trainedContext: Int,
    val embeddingLength: Int,
    val headCount: Int,
    val headCountKv: Int,
    val keyLength: Int?,
    val valueLength: Int?,
    val fileBytes: Long,
) {
    private val headDimK get() = keyLength ?: if (headCount > 0) embeddingLength / headCount else 0
    private val headDimV get() = valueLength ?: if (headCount > 0) embeddingLength / headCount else 0

    /** Bytes of KV cache for [contextTokens] (f16 K and V, llama.cpp's default). */
    fun kvCacheBytes(contextTokens: Int): Long =
        layers.toLong() * contextTokens * headCountKv * (headDimK + headDimV) * 2

    /**
     * Working memory beyond weights + KV cache: compute buffers, GPU driver
     * allocations, the runtime and allocator slack. PrivateLM calibrated it
     * on a Pixel 8 (Vulkan): Qwen2.5-1.5B Q4_K_M at 4096 context grew the app
     * by ~1813 MB = 1066 MB weights + 112 MB KV + ~635 MB overhead. Errs
     * high, since under-estimating is what gets the app killed.
     */
    fun overheadBytes(contextTokens: Int): Long = 400L * MB + (fileBytes * 0.2).toLong() + contextTokens * 4L * KB

    /** Estimated resident memory to load the model and use [contextTokens]. */
    fun estimateBytes(contextTokens: Int): Long = fileBytes + kvCacheBytes(contextTokens) + overheadBytes(contextTokens)

    /** Largest context (a multiple of 512, at least 512) whose estimate fits [availableBytes]; null if none. */
    fun maxContextFitting(availableBytes: Long): Int? {
        if (estimateBytes(512) > availableBytes) return null
        var lo = 512
        var hi = maxOf(trainedContext, 512)
        while (lo < hi) {
            val mid = ((lo + hi + 512) / 1024) * 512
            if (mid <= lo) break
            if (estimateBytes(mid) <= availableBytes) lo = mid else hi = mid - 512
        }
        return lo
    }

    companion object {
        private const val KB = 1024L
        private const val MB = 1024L * 1024
        // Metadata lives at the start; don't wander through a corrupt file.
        private const val MAX_METADATA_BYTES = 64L * 1024 * 1024
        private const val MAX_STRING = 1 shl 20

        /**
         * Reads only the metadata at the start of a GGUF [stream] (never the
         * tensors). Null if it isn't a GGUF this understands or lacks the
         * architecture fields. Closes nothing; the caller owns the stream.
         */
        fun read(stream: InputStream, fileBytes: Long): GgufInfo? = try {
            Reader(DataInputStream(BufferedInputStream(stream, 1 shl 16))).readInfo(fileBytes)
        } catch (_: Exception) {
            null
        }
    }

    /** Little-endian GGUF reader. */
    private class Reader(private val input: DataInputStream) {
        private var read = 0L

        private fun bytes(n: Int): ByteArray {
            read += n
            if (read > MAX_METADATA_BYTES) throw EOFException("GGUF metadata too long")
            return ByteArray(n).also { input.readFully(it) }
        }

        private fun le(n: Int): Long {
            val b = bytes(n)
            var v = 0L
            for (i in n - 1 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
            return v
        }

        private fun string(): String {
            val len = le(8)
            if (len < 0 || len > MAX_STRING) throw EOFException("String too long")
            return String(bytes(len.toInt()), Charsets.UTF_8)
        }

        private fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val chunk = minOf(left, 1L shl 16).toInt()
                bytes(chunk)
                left -= chunk
            }
        }

        private fun size(type: Int): Int? = when (type) {
            0, 1, 7 -> 1
            2, 3 -> 2
            4, 5, 6 -> 4
            10, 11, 12 -> 8
            else -> null
        }

        /** A value as a number when it is one, a string, or null (arrays: their largest number). */
        private fun value(type: Int): Any? = when (type) {
            0 -> le(1) and 0xFF
            1 -> le(1).toByte().toLong()
            2 -> le(2) and 0xFFFF
            3 -> le(2).toShort().toLong()
            4 -> le(4) and 0xFFFFFFFFL
            5 -> le(4).toInt().toLong()
            6 -> java.lang.Float.intBitsToFloat(le(4).toInt()).toDouble()
            7 -> le(1)
            8 -> string()
            9 -> {
                val elementType = le(4).toInt()
                val count = le(8)
                val elementSize = size(elementType)
                when {
                    elementType == 8 -> { repeat(count.toInt()) { string() }; null }
                    elementSize == null -> throw EOFException("Unknown GGUF array type $elementType")
                    // Per-layer head counts: keep the largest.
                    elementType in listOf(4, 5) && count <= 4096 ->
                        (0 until count).maxOfOrNull { (value(elementType) as Long) }
                    else -> { skip(count * elementSize); null }
                }
            }
            10, 11 -> le(8)
            12 -> java.lang.Double.longBitsToDouble(le(8))
            else -> throw EOFException("Unknown GGUF type $type")
        }

        fun readInfo(fileBytes: Long): GgufInfo? {
            if (String(bytes(4), Charsets.US_ASCII) != "GGUF") return null
            le(4) // version
            le(8) // tensor count
            val kvCount = le(8)
            val values = HashMap<String, Any?>()
            for (i in 0 until kvCount) {
                val key = string()
                values[key] = value(le(4).toInt())
            }
            val arch = values["general.architecture"] as? String ?: return null
            fun intOf(key: String) = (values["$arch.$key"] as? Number)?.toInt()
            val layers = intOf("block_count") ?: return null
            val embedding = intOf("embedding_length") ?: return null
            val heads = intOf("attention.head_count") ?: return null
            return GgufInfo(
                architecture = arch,
                layers = layers,
                trainedContext = intOf("context_length") ?: 4096,
                embeddingLength = embedding,
                headCount = heads,
                headCountKv = intOf("attention.head_count_kv") ?: heads,
                keyLength = intOf("attention.key_length"),
                valueLength = intOf("attention.value_length"),
                fileBytes = fileBytes,
            )
        }
    }
}
