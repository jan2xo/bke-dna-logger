package com.bke.dna.logger

import android.content.Context

/**
 * Process-local capture ingress controller.
 *
 * GeckoView/GeckoSession owns browsing state and may remain alive while the
 * capture writer is paused for a short storage mutation. Pausing closes SQLite
 * and incomplete capture sessions, drains queued derivative work, and prevents
 * new native messages from touching Working Data until resumed.
 */
object AndroidCaptureRuntime {
    private val lock = Any()
    private var ingress: AndroidWireIngress? = null
    private var pauseDepth = 0

    fun start(context: Context) {
        val appContext = context.applicationContext
        AndroidDerivationScheduler.start(appContext)
        synchronized(lock) {
            if (pauseDepth == 0 && ingress == null) {
                ingress = AndroidWireIngress(appContext)
            }
        }
    }

    fun accept(context: Context, rawMessage: ByteArray): String? {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (pauseDepth > 0) return null
            val active = ingress ?: AndroidWireIngress(appContext).also { ingress = it }
            return active.accept(rawMessage)
        }
    }

    fun pauseForStorageMutation(context: Context) {
        val appContext = context.applicationContext
        var firstPause = false
        synchronized(lock) {
            pauseDepth += 1
            if (pauseDepth == 1) {
                firstPause = true
                ingress?.close()
                ingress = null
            }
        }
        if (firstPause) {
            AndroidCaptureStore.awaitBackgroundDerivationIdle(appContext)
        }
    }

    fun resumeAfterStorageMutation(context: Context) {
        val appContext = context.applicationContext
        synchronized(lock) {
            check(pauseDepth > 0) { "Capture runtime resume without a matching pause" }
            pauseDepth -= 1
            if (pauseDepth == 0 && ingress == null) {
                AndroidDerivationScheduler.start(appContext)
                ingress = AndroidWireIngress(appContext)
            }
        }
    }

    fun <T> withStorageMutationPause(context: Context, block: () -> T): T {
        pauseForStorageMutation(context)
        return try {
            block()
        } finally {
            resumeAfterStorageMutation(context)
        }
    }

    fun stop() {
        synchronized(lock) {
            ingress?.close()
            ingress = null
            pauseDepth = 0
        }
    }

    fun isPaused(): Boolean = synchronized(lock) { pauseDepth > 0 }
}
