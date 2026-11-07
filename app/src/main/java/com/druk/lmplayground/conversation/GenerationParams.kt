package com.druk.lmplayground.conversation

data class GenerationParams(
    val contextSize: Int = 4096,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.0f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val seed: Int = -1,
    /** Tokens of thinking before the model is made to answer; -1 = no limit. */
    val thinkingBudget: Int = -1,
    /** Penalty for reusing any word already said; curbs loops. 0 = off. */
    val presencePenalty: Float = 0f,
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
        "presencePenalty" to presencePenalty,
    )

    companion object {
        /**
         * The model makers' recommended sampling for [filename]'s family, for a
         * model with no saved settings; the general defaults for unknown ones.
         * Families match by name, most specific first ("qwen3.5" before "qwen3").
         * Values per family: temperature, top-p, top-k (0 = off), min-p, repetition
         * penalty and, where given, presence penalty. Models of 2B or less get an
         * 8,192-token context, which their small memory footprint allows even on
         * low-end phones; bigger ones 4,096.
         */
        fun recommendedFor(filename: String?): GenerationParams {
            // Server models are "remote:<url>|<id>"; match on the model id.
            val f = filename.orEmpty().substringAfterLast('|').substringAfterLast('/').lowercase()
            val base = GenerationParams(contextSize = if ((sizeInBillions(f) ?: 99.0) <= 2.0) 8192 else 4096)
            val p = FAMILIES.firstOrNull { (pattern, _) -> pattern in f }?.second ?: return base
            return base.copy(
                temperature = p[0], topP = p[1], topK = p[2].toInt(), minP = p[3], repetitionPenalty = p[4],
                presencePenalty = p.getOrElse(5) { 0f },
            )
        }

        /** The parameter count in a model's name ("Qwen3.5-0.8B" 0.8, "LFM2.5-350M" 0.35), or null. */
        fun sizeInBillions(name: String): Double? =
            Regex("""(?i)(?<![\w.])[a-z]?(\d+(?:\.\d+)?)\s*([bm])(?![a-z])""").findAll(name).lastOrNull()?.let {
                it.groupValues[1].toDouble() / if (it.groupValues[2].equals("m", true)) 1000 else 1
            }

        private val FAMILIES: List<Pair<String, FloatArray>> = listOf(
            "qwen3.6" to floatArrayOf(0.7f, 0.8f, 20f, 0f, 1f, 1.5f),
            "qwen3.5" to floatArrayOf(0.7f, 0.8f, 20f, 0f, 1f, 1.5f),
            "qwen3-vl" to floatArrayOf(0.7f, 0.8f, 20f, 0f, 1f),
            "qwen3" to floatArrayOf(0.6f, 0.95f, 20f, 0f, 1f),
            "qwen2.5" to floatArrayOf(0.7f, 0.8f, 20f, 0f, 1f),
            "qwen2" to floatArrayOf(0.7f, 0.8f, 20f, 0f, 1f),
            "gemma-4" to floatArrayOf(1f, 0.95f, 64f, 0f, 1f),
            "gemma-3n" to floatArrayOf(1f, 0.95f, 64f, 0f, 1f),
            "gemma-3" to floatArrayOf(1f, 0.95f, 64f, 0f, 1f),
            "gemma-2" to floatArrayOf(1f, 0.95f, 64f, 0f, 1f),
            "llama-3" to floatArrayOf(1.5f, 0.95f, 0f, 0.1f, 1f),
            "phi-4" to floatArrayOf(0.8f, 0.95f, 0f, 0f, 1f),
            "phi-3" to floatArrayOf(0.7f, 0.9f, 0f, 0.01f, 1f),
            "ministral" to floatArrayOf(0.15f, 0.95f, 0f, 0.01f, 1f),
            "mistral-small" to floatArrayOf(0.15f, 0.95f, 0f, 0.01f, 1f),
            "mistral-nemo" to floatArrayOf(0.7f, 0.95f, 0f, 0.01f, 1f),
            "granite-4" to floatArrayOf(0f, 1f, 0f, 0.01f, 1f),
            "deepseek-r1" to floatArrayOf(0.6f, 0.95f, 0f, 0.01f, 1f),
            "gpt-oss" to floatArrayOf(1f, 1f, 0f, 0.01f, 1f),
            "nemotron3.5" to floatArrayOf(1f, 0.95f, 0f, 0.01f, 1f),
            "nemotron-3.5" to floatArrayOf(1f, 0.95f, 0f, 0.01f, 1f),
            "nemotron" to floatArrayOf(1f, 1f, 0f, 0.01f, 1f),
            "lfm3" to floatArrayOf(0.1f, 1f, 50f, 0f, 1.1f),
            "lfm2" to floatArrayOf(0.1f, 0.1f, 50f, 0.15f, 1.05f),
            "smollm" to floatArrayOf(0.7f, 0.95f, 0f, 0.01f, 1f),
            "olmo" to floatArrayOf(0.7f, 0.95f, 0f, 0.01f, 1f),
        )

        fun fromMap(map: Map<String, Float>): GenerationParams {
            val contextSize = map["contextSize"]?.toInt() ?: 4096
            return GenerationParams(
                contextSize = contextSize,
                temperature = map["temperature"] ?: 0.8f,
                topP = map["topP"] ?: 0.95f,
                repetitionPenalty = map["repetitionPenalty"] ?: 1.0f,
                topK = map["topK"]?.toInt() ?: 40,
                minP = map["minP"] ?: 0.05f,
                seed = map["seed"]?.toInt() ?: -1,
                thinkingBudget = map["thinkingBudget"]?.toInt() ?: -1,
                presencePenalty = map["presencePenalty"] ?: 0f,
            )
        }
    }
}
