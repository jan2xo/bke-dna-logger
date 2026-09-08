package com.bke.dna.logger

import android.app.Activity
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.nio.charset.StandardCharsets

/** Owns GeckoView and routes DNA WebExtension messages into native Android ingress. */
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
    private val ingress = AndroidWireIngress(activity.applicationContext)
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

            if (message !is JSONObject) {
                Log.w(TAG, "Ignoring non-object DNA message: ${message.javaClass.simpleName}")
                return null
            }

            try {
                val type = ingress.accept(message.toString().toByteArray(StandardCharsets.UTF_8))
                Log.d(TAG, "Persisted DNA wire message: $type")
            } catch (error: Exception) {
                Log.e(TAG, "Rejected DNA wire message", error)
            }
            return null
        }
    }

    fun start() {
        check(!started) { "GeckoViewHost is already started" }
        started = true

        session.setContentDelegate(object : GeckoSession.ContentDelegate {})
        session.open(runtime)
        view.setSession(session)

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
        if (!started) return

        view.releaseSession()
        if (session.isOpen) session.close()
        ingress.close()
        started = false
    }
}
