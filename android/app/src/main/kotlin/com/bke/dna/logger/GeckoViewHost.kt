package com.bke.dna.logger

import android.app.Activity
import android.util.Log
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * Owns the Android browser surface only. GeckoView stays native to Kotlin;
 * DNA persistence/normalization remains a separate layer for the next stack.
 */
class GeckoViewHost(
    activity: Activity,
    private val view: GeckoView,
) {
    companion object {
        private const val TAG = "BkeDnaGeckoView"
        private const val CHATGPT_URL = "https://chatgpt.com/"
        private const val EXTENSION_URI = "resource://android/assets/dna-extension/"
        private const val EXTENSION_ID = "bke-dna-logger@jl-bke.com"
        private const val NATIVE_APP = "bke.dna.logger"
    }

    private val runtime = GeckoRuntimeProvider.get(activity.applicationContext)
    private val session = GeckoSession()
    private var started = false

    private val messageDelegate = object : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            if (nativeApp != NATIVE_APP) {
                Log.w(TAG, "Ignoring unexpected native app: $nativeApp")
                return null
            }

            // #17 proves the GeckoView/WebExtension channel is registered.
            // #18 will validate and persist the DNA wire payload through
            // AndroidWireIngress -> SQLite -> verified .dna.
            Log.d(TAG, "DNA message reached native endpoint: ${message.javaClass.simpleName}")
            return null
        }
    }

    fun start() {
        check(!started) { "GeckoViewHost is already started" }
        started = true

        // Mozilla's embedding guidance recommends a ContentDelegate even when
        // no content callbacks are needed yet.
        session.setContentDelegate(object : GeckoSession.ContentDelegate {})
        session.open(runtime)
        view.setSession(session)

        // Install/refresh the privileged built-in extension before ChatGPT is
        // loaded so document_start capture is present on the first navigation.
        runtime.getWebExtensionController()
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                { extension ->
                    if (extension == null) {
                        Log.e(TAG, "Built-in DNA extension resolved without an extension")
                        return@accept
                    }

                    session.getWebExtensionController().setMessageDelegate(
                        extension,
                        messageDelegate,
                        NATIVE_APP,
                    )
                    session.loadUri(CHATGPT_URL)
                },
                { error ->
                    Log.e(TAG, "Unable to install built-in DNA extension", error)
                },
            )
    }

    fun stop() {
        if (!started) {
            return
        }

        view.releaseSession()
        if (session.isOpen) {
            session.close()
        }
        started = false
    }
}
