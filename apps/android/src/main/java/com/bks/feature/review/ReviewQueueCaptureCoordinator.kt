package com.bks.feature.review

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ReviewQueueCaptureCoordinator {
    private val mutex = Mutex()

    suspend fun <T> serialize(block: suspend () -> T): T = mutex.withLock {
        block()
    }

    companion object {
        val Shared = ReviewQueueCaptureCoordinator()
    }
}
