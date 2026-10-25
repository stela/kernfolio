package com.kernfolio

import java.time.Duration

/**
 * Retries [assertion] every [poll] until it passes or [timeout] runs out, then
 * throws its last failure. For things that happen in the background, like a
 * server picking up a renewed cert.
 */
fun <T> eventually(timeout: Duration, poll: Duration = Duration.ofSeconds(1), assertion: () -> T): T {
    val deadline = System.nanoTime() + timeout.toNanos()
    while (true) {
        try {
            return assertion()
        } catch (e: Throwable) {
            if (System.nanoTime() - deadline > 0) throw e
            Thread.sleep(poll)
        }
    }
}
