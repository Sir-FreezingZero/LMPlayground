package com.druk.lmplayground

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.lmplayground.conversation.GenerationParams
import com.druk.lmplayground.storage.StoragePreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoragePreferencesTest {

    private lateinit var prefs: StoragePreferences

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = StoragePreferences(context)
    }

    @After
    fun cleanup() {
        prefs.clear()
    }

    @Test
    fun noSavedParamsUsesThePhonesRecommendation() {
        prefs.recommendedContextSize = 3072
        prefs.recommendedMaxTokens = 768
        val params = prefs.modelParams
        assertEquals(3072, params.contextSize)
        assertEquals(768, params.maxTokens)
        assertEquals(GenerationParams().temperature, params.temperature)
    }

    @Test
    fun saveAndLoadRoundtrip() {
        val params = GenerationParams(contextSize = 4096, temperature = 0.5f, thinkingBudget = 256, maxTokens = 2048)
        prefs.modelParams = params
        assertEquals(params, prefs.modelParams)
    }

    @Test
    fun samplingOtherThanTemperatureIsFixed() {
        prefs.modelParams = GenerationParams(topP = 0.5f, topK = 7, minP = 0.3f, repetitionPenalty = 1.9f, seed = 5)
        val loaded = prefs.modelParams
        val defaults = GenerationParams()
        assertEquals(defaults.topP, loaded.topP)
        assertEquals(defaults.topK, loaded.topK)
        assertEquals(defaults.minP, loaded.minP)
        assertEquals(defaults.repetitionPenalty, loaded.repetitionPenalty)
        assertEquals(defaults.seed, loaded.seed)
    }

    @Test
    fun overwrite() {
        prefs.modelParams = GenerationParams(temperature = 0.5f)
        prefs.modelParams = GenerationParams(temperature = 1.0f)
        assertEquals(1.0f, prefs.modelParams.temperature)
    }
}
