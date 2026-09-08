package com.bke.dna.logger

import android.app.Activity
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import java.net.URI
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

        private const val ROUTE_CONVERSATION = "capture_route_conversation"
        private const val ROUTE_CONVERSATIONS_LIST = "capture_route_conversations_list"
        private const val ROUTE_BACKEND_API = "capture_route_backend_api"
        private const val ROUTE_PUBLIC_API = "capture_route_public_api"
        private const val ROUTE_OTHER = "capture_route_other"

        private val DIAGNOSTIC_EVENTS = setOf(
            "interceptor_ready",
            "fetch_seen",
            "capture_candidate",
            "body_read_started",
            "body_read_complete",
            "body_read_failed",
            "capture_posted",
            "capture_received",
            "capture_metadata_rejected",
            "capture_body_accepted",
            "capture_body_rejected",
            "capture_start_sent",
            "chunk_encode_started",
            "chunk_encode_complete",
            "chunk_send_started",
            "chunk_send_complete",
            "capture_end_sent",
            "capture_forward_failed",
            "interceptor_load_error",
        )
        private val DIAGNOSTIC_KEYS = setOf("type", "event")

        private fun classifyCaptureRoute(requestUrl: String?): String {
            if (requestUrl.isNullOrBlank()) return ROUTE_OTHER
            val uri = runCatching { URI(requestUrl) }.getOrNull() ?: return ROUTE_OTHER
            val host = uri.host?.lowercase() ?: return ROUTE_OTHER
            val chatGptHost = host == "chatgpt.com" ||
                host.endsWith(".chatgpt.com") ||
                host == "chat.openai.com"
            if (!chatGptHost) return ROUTE_OTHER

            val path = uri.path.orEmpty()
            return when {
                path == "/backend-api/conversations" ||
                    path.startsWith("/backend-api/conversations/") -> ROUTE_CONVERSATIONS_LIST
                path == "/backend-api/conversation" ||
                    path.startsWith("/backend-api/conversation/") -> ROUTE_CONVERSATION
                path.startsWith("/backend-api/") -> ROUTE_BACKEND_API
                path.startsWith("/public-api/") -> ROUTE_PUBLIC_API
                else -> ROUTE_OTHER
            }
        }
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

            if (message.optString("type") == "diagnostic") {
                handleDiagnostic(message)
                return null
            }

            try {
                val type = ingress.accept(message.toString().toByteArray(StandardCharsets.UTF_8))
                if (type == "capture_start") {
                    val requestUrl = if (message.has("requestUrl") && !message.isNull("requestUrl")) {
                        message.optString("requestUrl")
                    } else {
                        null
                    }
                    Log.d(TAG, "DNA capture route: ${classifyCaptureRoute(requestUrl)}")
                }
                Log.d(TAG, "Persisted DNA wire message: $type")
            } catch (error: Exception) {
                Log.e(TAG, "Rejected DNA wire message", error)
            }
            return null
        }
    }

    private fun handleDiagnostic(message: JSONObject) {
        val keys = buildSet {
            val iterator = message.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (keys != DIAGNOSTIC_KEYS) {
            Log.w(TAG, "Rejected malformed DNA diagnostic")
            return
        }

        val event = message.optString("event")
        if (event !in DIAGNOSTIC_EVENTS) {
            Log.w(TAG, "Rejected unknown DNA diagnostic")
            return
        }

        Log.d(TAG, "DNA diagnostic: $event")
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
