package com.bke.dna.logger

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.net.URI
import java.nio.charset.StandardCharsets

/** Owns GeckoView and routes DNA WebExtension messages into native Android ingress. */
class GeckoViewHost(
    private val activity: Activity,
    private val view: GeckoView,
) {
    companion object {
        private const val TAG = "BkeDnaGeckoView"
        private const val BROWSER_TAG = "BkeDnaBrowser"
        private const val CHATGPT_URL = "https://chatgpt.com/"
        private const val EXTENSION_URI = "resource://android/assets/dna-extension/"
        private const val EXTENSION_ID = "bke-dna-logger@jl-bke.com"
        private const val NATIVE_APP = "bke.dna.logger"
        private const val FILE_PROMPT_REQUEST = 4701

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
            "page_runtime_error",
            "page_unhandled_rejection",
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

    private val appContext = activity.applicationContext
    private val runtime = GeckoRuntimeProvider.get(appContext)
    private val session = GeckoSession()
    private var started = false
    private var pendingFilePrompt: PendingFilePrompt? = null

    private fun browserDiagnostic(event: String) {
        Log.d(BROWSER_TAG, "BKE Browser: $event")
    }

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
                val type = AndroidCaptureRuntime.accept(
                    appContext,
                    message.toString().toByteArray(StandardCharsets.UTF_8),
                ) ?: return null
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

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onFocusRequest(session: GeckoSession) {
            browserDiagnostic("content_focus_request")
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            browserDiagnostic(if (fullScreen) "content_fullscreen_enter" else "content_fullscreen_exit")
        }

        override fun onCloseRequest(session: GeckoSession) {
            browserDiagnostic("content_close_request")
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            browserDiagnostic("external_response")
        }

        override fun onCrash(session: GeckoSession) {
            browserDiagnostic("content_crash")
        }

        override fun onKill(session: GeckoSession) {
            browserDiagnostic("content_kill")
        }
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            session: GeckoSession,
            request: GeckoSession.NavigationDelegate.LoadRequest,
        ): GeckoResult<AllowOrDeny>? {
            browserDiagnostic(
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    "navigation_request_new_window"
                } else {
                    "navigation_request"
                },
            )
            return super.onLoadRequest(session, request)
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            browserDiagnostic("navigation_location_change")
        }

        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            browserDiagnostic("new_window_request")
            return super.onNewSession(session, uri)
        }

        override fun onLoadError(
            session: GeckoSession,
            uri: String?,
            error: WebRequestError,
        ): GeckoResult<String>? {
            browserDiagnostic("navigation_load_error")
            return super.onLoadError(session, uri, error)
        }
    }

    private val permissionDelegate = object : GeckoSession.PermissionDelegate {
        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: GeckoSession.PermissionDelegate.Callback,
        ) {
            browserDiagnostic("permission_android_requested")
            if (permissions?.contains(Manifest.permission.CAMERA) == true) {
                browserDiagnostic("permission_android_camera")
            }
            if (permissions?.contains(Manifest.permission.RECORD_AUDIO) == true) {
                browserDiagnostic("permission_android_microphone")
            }
            super.onAndroidPermissionsRequest(session, permissions, callback)
        }

        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: GeckoSession.PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? {
            browserDiagnostic("permission_content_requested")
            return super.onContentPermissionRequest(session, perm)
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
            callback: GeckoSession.PermissionDelegate.MediaCallback,
        ) {
            browserDiagnostic("permission_media_requested")
            if (!video.isNullOrEmpty()) browserDiagnostic("permission_media_camera")
            if (!audio.isNullOrEmpty()) browserDiagnostic("permission_media_microphone")
            super.onMediaPermissionRequest(session, uri, video, audio, callback)
        }
    }

    private val promptDelegate = object : GeckoSession.PromptDelegate {
        override fun onFilePrompt(
            session: GeckoSession,
            prompt: GeckoSession.PromptDelegate.FilePrompt,
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
            browserDiagnostic("file_prompt_requested")
            dismissPendingFilePrompt()
            AndroidUserSelectedFileProvider.cleanupStaleFiles(appContext)

            val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            val launch = runCatching { buildFilePromptLaunch(prompt) }.getOrElse { error ->
                browserDiagnostic("file_prompt_prepare_failed")
                Log.e(TAG, "Unable to prepare Gecko file prompt", error)
                result.complete(prompt.dismiss())
                return result
            }
            pendingFilePrompt = PendingFilePrompt(prompt, result, launch.cameraUri)

            return try {
                activity.startActivityForResult(launch.intent, FILE_PROMPT_REQUEST)
                browserDiagnostic("file_prompt_picker_launched")
                result
            } catch (error: Exception) {
                browserDiagnostic("file_prompt_picker_launch_failed")
                Log.e(TAG, "Unable to launch Gecko file prompt", error)
                cleanupCameraGrant(launch.cameraUri, deleteFile = true)
                pendingFilePrompt = null
                result.complete(prompt.dismiss())
                result
            }
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

        if (event == "page_runtime_error" || event == "page_unhandled_rejection") {
            browserDiagnostic(event)
        } else {
            Log.d(TAG, "DNA diagnostic: $event")
        }
    }

    fun start() {
        check(!started) { "GeckoViewHost is already started" }
        started = true
        AndroidCaptureRuntime.start(appContext)

        session.setContentDelegate(contentDelegate)
        session.setNavigationDelegate(navigationDelegate)
        session.setPermissionDelegate(permissionDelegate)
        session.setPromptDelegate(promptDelegate)
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

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != FILE_PROMPT_REQUEST) return false
        browserDiagnostic("file_prompt_result_received")
        val pending = pendingFilePrompt
        if (pending == null) {
            browserDiagnostic("file_prompt_result_without_pending")
            return true
        }
        pendingFilePrompt = null

        val selected = if (resultCode == Activity.RESULT_OK) collectSelectedUris(data) else emptyList()
        val cameraUri = pending.cameraUri
        val finalUris = if (selected.isEmpty() && resultCode == Activity.RESULT_OK && cameraUri != null) {
            listOf(cameraUri)
        } else {
            selected
        }

        cleanupCameraGrant(
            cameraUri,
            deleteFile = cameraUri != null && cameraUri !in finalUris,
        )

        val response = runCatching {
            when {
                finalUris.isEmpty() -> {
                    browserDiagnostic("file_prompt_dismissed")
                    pending.prompt.dismiss()
                }
                pending.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE -> {
                    browserDiagnostic("file_prompt_selection_received")
                    pending.prompt.confirm(appContext, finalUris.toTypedArray())
                }
                else -> {
                    browserDiagnostic("file_prompt_selection_received")
                    pending.prompt.confirm(appContext, finalUris.first())
                }
            }
        }.getOrElse { error ->
            browserDiagnostic("file_prompt_resolve_failed")
            Log.e(TAG, "Unable to resolve Gecko file prompt", error)
            if (!pending.prompt.isComplete) pending.prompt.dismiss() else throw error
        }
        pending.result.complete(response)
        browserDiagnostic("file_prompt_resolved")
        return true
    }

    fun stop() {
        if (!started) return

        dismissPendingFilePrompt()
        view.releaseSession()
        if (session.isOpen) session.close()
        AndroidCaptureRuntime.stop()
        started = false
    }

    private fun buildFilePromptLaunch(
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): FilePromptLaunch {
        if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
            return FilePromptLaunch(
                intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                cameraUri = null,
            )
        }

        val mimeTypes = prompt.mimeTypes
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = primaryMimeType(mimeTypes)
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            putExtra(
                Intent.EXTRA_ALLOW_MULTIPLE,
                prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val cameraMime = supportedCameraMimeType(mimeTypes)
        val cameraLaunch = cameraMime?.let(::buildCameraLaunch)
        if (prompt.capture != GeckoSession.PromptDelegate.FilePrompt.Capture.NONE && cameraLaunch != null) {
            return cameraLaunch
        }
        if (cameraLaunch == null) {
            return FilePromptLaunch(
                Intent.createChooser(documentIntent, prompt.title ?: "Choose file"),
                null,
            )
        }

        val chooser = Intent.createChooser(documentIntent, prompt.title ?: "Choose file").apply {
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraLaunch.intent))
        }
        return FilePromptLaunch(chooser, cameraLaunch.cameraUri)
    }

    private fun buildCameraLaunch(mimeType: String): FilePromptLaunch? {
        val cameraUri = AndroidUserSelectedFileProvider.createCameraUri(appContext, mimeType)
        val action = if (mimeType.startsWith("video/")) {
            MediaStore.ACTION_VIDEO_CAPTURE
        } else {
            MediaStore.ACTION_IMAGE_CAPTURE
        }
        val intent = Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
            clipData = ClipData.newRawUri("BKE user-selected upload", cameraUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val handlers = activity.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (handlers.isEmpty()) {
            AndroidUserSelectedFileProvider.deleteIfOwned(appContext, cameraUri)
            return null
        }
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        handlers.forEach { resolveInfo ->
            activity.grantUriPermission(resolveInfo.activityInfo.packageName, cameraUri, grantFlags)
        }
        return FilePromptLaunch(intent, cameraUri)
    }

    private fun collectSelectedUris(data: Intent?): List<Uri> {
        val uris = linkedSetOf<Uri>()
        data?.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let(uris::add)
            }
        }
        data?.data?.let(uris::add)
        return uris.toList()
    }

    private fun dismissPendingFilePrompt() {
        val pending = pendingFilePrompt ?: return
        pendingFilePrompt = null
        cleanupCameraGrant(pending.cameraUri, deleteFile = true)
        if (!pending.prompt.isComplete) {
            runCatching { pending.result.complete(pending.prompt.dismiss()) }
                .onFailure { Log.w(TAG, "Unable to dismiss stale Gecko file prompt", it) }
        }
    }

    private fun cleanupCameraGrant(cameraUri: Uri?, deleteFile: Boolean) {
        if (cameraUri == null) return
        runCatching {
            activity.revokeUriPermission(
                cameraUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (deleteFile) {
            AndroidUserSelectedFileProvider.deleteIfOwned(appContext, cameraUri)
        }
    }

    private fun supportedCameraMimeType(mimeTypes: List<String>): String? {
        val acceptsImage = mimeTypes.any { it == "image/*" || it == "image/jpeg" || it == "image/jpg" }
        val acceptsVideo = mimeTypes.any { it == "video/*" || it == "video/mp4" }
        return when {
            acceptsImage -> "image/jpeg"
            acceptsVideo -> "video/mp4"
            else -> null
        }
    }

    private fun primaryMimeType(mimeTypes: List<String>): String {
        if (mimeTypes.isEmpty()) return "*/*"
        if (mimeTypes.size == 1) return mimeTypes.single()
        val majorTypes = mimeTypes.mapNotNull { type ->
            type.substringBefore('/', missingDelimiterValue = "").takeIf { it.isNotBlank() }
        }.distinct()
        return if (majorTypes.size == 1) "${majorTypes.single()}/*" else "*/*"
    }

    private data class FilePromptLaunch(
        val intent: Intent,
        val cameraUri: Uri?,
    )

    private data class PendingFilePrompt(
        val prompt: GeckoSession.PromptDelegate.FilePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
        val cameraUri: Uri?,
    )
}
