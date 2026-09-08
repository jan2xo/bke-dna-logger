package com.bke.dna.logger

import android.content.Context

/** GeckoView native-message ingress into durable Android raw-evidence storage. */
class AndroidWireIngress(context: Context) : AutoCloseable {
    private val store = AndroidCaptureStore(context.applicationContext)

    fun accept(rawMessage: ByteArray): String {
        val json = DnaWireContract.parse(rawMessage)
        return store.accept(json)
    }

    override fun close() = store.close()
}
