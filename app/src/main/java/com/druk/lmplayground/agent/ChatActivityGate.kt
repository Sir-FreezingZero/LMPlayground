package com.druk.lmplayground.agent

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lets the background agent yield the CPU to the user's chat.
 *
 * Chat and agent sessions generate on separate engine threads; on a phone
 * they would fight over the same cores and both crawl. The chat marks its
 * turns here, and [DeviceRunPolicy] throttles the agent at its next step
 * boundary while a chat turn is running or happened within [GRACE_MS].
 * (An agent step already in flight finishes; it is not aborted.)
 */
object ChatActivityGate {
    private const val GRACE_MS = 30_000L
    private val active = AtomicInteger(0)
    @Volatile private var lastEnd = 0L

    fun begin() { active.incrementAndGet() }

    fun end() {
        active.updateAndGet { (it - 1).coerceAtLeast(0) }
        lastEnd = SystemClock.elapsedRealtime()
    }

    fun chatBusy(): Boolean =
        active.get() > 0 || (lastEnd != 0L && SystemClock.elapsedRealtime() - lastEnd < GRACE_MS)
}
