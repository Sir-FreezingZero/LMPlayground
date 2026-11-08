package com.druk.lmplayground.conversation

data class GenerationParams(
    val contextSize: Int = 4096,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val repetitionPenalty: Float = 1.0f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val seed: Int = -1,
    val thinkingBudget: Int = contextSize / 4
) {
    fun toMap(): Map<String, Float> = mapOf(
        "contextSize" to contextSize.toFloat(),
        "temperature" to temperature,
        "topP" to topP,
        "repetitionPenalty" to repetitionPenalty,
        "topK" to topK.toFloat(),
        "minP" to minP,
        "seed" to seed.toFloat(),
        "thinkingBudget" to thinkingBudget.toFloat()
    )

    companion object {
        /**
         * The maker's recommended sampling for [filename]'s model, where we know it
         * (Liquid's model cards); the general defaults otherwise. Small models get
         * rambling and repetitive at the general 0.8 temperature.
         */
        fun recommendedFor(filename: String?): GenerationParams {
            // Server models are "remote:<url>|<id>"; match on the model id.
            val f = filename.orEmpty().substringAfterLast('|').substringAfterLast('/').lowercase()
            return when {
                f.startsWith("lfm3") -> GenerationParams(temperature = 0.1f, topK = 50, topP = 1f, minP = 0f, repetitionPenalty = 1.1f)
                f.startsWith("lfm2.5-vl") || f.startsWith("lfm3-vl") ->
                    GenerationParams(temperature = 0.1f, topK = 50, topP = 1f, minP = 0.15f, repetitionPenalty = 1.05f)
                f.startsWith("lfm2.5") -> GenerationParams(temperature = 0.1f, topK = 50, topP = 1f, minP = 0f, repetitionPenalty = 1.05f)
                else -> GenerationParams()
            }
        }

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
                thinkingBudget = map["thinkingBudget"]?.toInt()?.takeIf { it >= 0 } ?: (contextSize / 4)
            )
        }
    }
}
