package com.druk.lmplayground.conversation

/**
 * Generation settings. Like PrivateLM, one set applies to every model
 * (Settings → Model, or the chat's settings sheet): temperature, longest
 * reply and context size are adjustable; top-p, top-k, min-p and the
 * repetition penalty are fixed at PrivateLM's values.
 */
data class GenerationParams(
    val contextSize: Int = DEFAULT_CONTEXT_SIZE,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val repetitionPenalty: Float = 1.1f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val seed: Int = -1,
    val thinkingBudget: Int = contextSize / 4,
    /** Longest visible answer, in tokens; the reply stops there (thinking not counted). */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {
    fun toMap(): Map<String, Float> = mapOf(
        "contextSize" to contextSize.toFloat(),
        "temperature" to temperature,
        "topP" to topP,
        "repetitionPenalty" to repetitionPenalty,
        "topK" to topK.toFloat(),
        "minP" to minP,
        "seed" to seed.toFloat(),
        "thinkingBudget" to thinkingBudget.toFloat(),
        "maxTokens" to maxTokens.toFloat(),
    )

    companion object {
        const val DEFAULT_CONTEXT_SIZE = 2048
        const val DEFAULT_MAX_TOKENS = 1024
        const val MIN_CONTEXT_SIZE = 512
        const val MAX_CONTEXT_SIZE = 8192
        const val MIN_MAX_TOKENS = 64
        const val MAX_MAX_TOKENS = 4096

        fun fromMap(map: Map<String, Float>): GenerationParams {
            val contextSize = map["contextSize"]?.toInt() ?: DEFAULT_CONTEXT_SIZE
            return GenerationParams(
                contextSize = contextSize,
                temperature = map["temperature"] ?: 0.7f,
                topP = map["topP"] ?: 0.9f,
                repetitionPenalty = map["repetitionPenalty"] ?: 1.1f,
                topK = map["topK"]?.toInt() ?: 40,
                minP = map["minP"] ?: 0.05f,
                seed = map["seed"]?.toInt() ?: -1,
                thinkingBudget = map["thinkingBudget"]?.toInt()?.takeIf { it >= 0 } ?: (contextSize / 4),
                maxTokens = map["maxTokens"]?.toInt() ?: DEFAULT_MAX_TOKENS,
            )
        }
    }
}
