package com.druk.llamacpp.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "said it would use a tool but didn't" check must leave ordinary chat alone. */
class ToolIntentTest {

    private fun fires(text: String) = ToolCallLoop.isIntentWithoutAction(text)

    @Test
    fun announcedToolActionsFire() {
        listOf(
            "I'll search for that.",
            "Let me check the latest news.",
            "Let me look that up for you.",
            "I'm going to quickly search the web.",
            "Let's look it up.",
            "Sure. First, I'll find out what time it is there.",
            "I will calculate the total.",
            "Okay, I'll set an alarm for 7.",
            "Let me get the current weather.",
            "Step 1: search for flights.",
            "I'll go ahead and fetch the page.",
        ).forEach { assertTrue(it, fires(it)) }
    }

    @Test
    fun ordinaryChatDoesNotFire() {
        listOf(
            "Let me explain how that works.",
            "I'll write you a poem about the sea.",
            "First, a little background on the topic.",
            "Here's my plan for your trip: day one, the museum.",
            "Let me know if you want more!",
            "I'll be happy to help with that.",
            "I will not search for that.",
            "Let's run through the story again.",
            "Now I see what you mean.",
            "Let me think about it... the answer is 42.",
            "I'll tell you a joke.",
        ).forEach { assertFalse(it, fires(it)) }
    }
}
