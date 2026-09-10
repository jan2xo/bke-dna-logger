package com.bke.dna.logger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import org.mozilla.geckoview.GeckoSessionSettings
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
        private const val CHROMIUM_ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/153.0.0.0 Mobile Safari/537.36"
        private const val EXTENSION_URI = "resource://android/assets/dna-extension/"
        private const val EXTENSION_ID = "bke-dna-logger@jl-bke.com"
        private const val NATIVE_APP = "bke.dna.logger"
        private const val FILE_PROMPT_REQUEST = 4701
        private const val GECKO_ANDROID_PERMISSION_REQUEST = 4702
        private const val FILE_CAMERA_PERMISSION_REQUEST = 4703

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
            "page_window_open",
            "page_history_push_state",
            "page_history_replace_state",
            "page_navigation_api",
            "page_popstate",
            "page_hashchange",
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
    private val session = GeckoSession(
        GeckoSessionSettings.Builder()
            .userAgentOverride(CHROMIUM_ANDROID_USER_AGENT)
            .build(),
    )
    private var started = false
    private var pendingFilePrompt: PendingFilePrompt? = null
    private var pendingGeckoPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var currentWebUri: URI? = parseWebUri(CHATGPT_URL)

    private fun browserDiagnostic(event: String) {
        Log.d(BROWSER_TAG, "BKE Browser: $event")
    }

    private fun shouldRedirectNewWindowToCurrentSession(
        request: GeckoSession.NavigationDelegate.LoadRequest,
    ): Boolean {
        if (request.target != GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) return false

        val destination = parseWebUri(request.uri) ?: return false
        val source = parseWebUri(request.triggerUri) ?: currentWebUri ?: return false
        return sameWebOrigin(source, destination)
    }

    private fun parseWebUri(value: String?): URI? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun sameWebOrigin(first: URI, second: URI): Boolean {
        if (!first.scheme.equals(second.scheme, ignoreCase = true)) return false
        if (!first.host.equals(second.host, ignoreCase = true)) return false
        return effectivePort(first) == effectivePort(second)
    }

    private fun effectivePort(uri: URI): Int {
        if (uri.port >= 0) return uri.port
        return when (uri.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
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
            val opensNewWindow = request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW
            browserDiagnostic(
                if (opensNewWindow) {
                    "navigation_request_new_window"
                } else {
                    "navigation_request"
                },
            )

            if (opensNewWindow && shouldRedirectNewWindowToCurrentSession(request)) {
                browserDiagnostic("navigation_new_window_redirect_current")
                session.loadUri(request.uri)
                return GeckoResult.deny()
            }

            return super.onLoadRequest(session, request)
        }

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            parseWebUri(url)?.let { currentWebUri = it }
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
            val requested = permissions?.filter { it.isNotBlank() }?.distinct().orEmpty()
            if (Manifest.permission.CAMERA in requested) {
                browserDiagnostic("permission_android_camera")
            }
            if (Manifest.permission.RECORD_AUDIO in requested) {
                browserDiagnostic("permission_android_microphone")
            }

            if (requested.isEmpty() || requested.all(::isPermissionGranted)) {
                browserDiagnostic("permission_android_granted")
                callback.grant()
                return
            }

            if (pendingGeckoPermissionCallback != null) {
                browserDiagnostic("permission_android_busy_rejected")
                callback.reject()
                return
            }

            pendingGeckoPermissionCallback = callback
            runCatching {
                activity.requestPermissions(requested.toTypedArray(), GECKO_ANDROID_PERMISSION_REQUEST)
            }.onFailure { error ->
                Log.e(TAG, "Unable to request Android browser permission", error)
                pendingGeckoPermissionCallback = null
                browserDiagnostic("permission_android_request_failed")
                callback.reject()
            }
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
            val wantsVideo = !video.isNullOrEmpty()
            val wantsAudio = !audio.isNullOrEmpty()
            if (wantsVideo) browserDiagnostic("permission_media_camera")
            if (wantsAudio) browserDiagnostic("permission_media_microphone")

            if ((wantsVideo && !isPermissionGranted(Manifest.permission.CAMERA)) ||
                (wantsAudio && !isPermissionGranted(Manifest.permission.RECORD_AUDIO))
            ) {
                browserDiagnostic("permission_media_missing_android_permission")
                callback.reject()
                return
            }

            val message = when {
                wantsVideo && wantsAudio -> "Allow this site to use your camera and microphone?"
                wantsVideo -> "Allow this site to use your camera?"
                wantsAudio -> "Allow this site to use your microphone?"
                else -> "Allow this site to use media devices?"
            }

            AlertDialog.Builder(activity)
                .setMessage(message)
                .setNegativeButton("Deny") { _, _ ->
                    browserDiagnostic("permission_media_rejected")
                    callback.reject()
                }
                .setPositiveButton("Allow") { _, _ ->
                    browserDiagnostic("permission_media_granted")
                    callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                }
                .setOnCancelListener {
                    browserDiagnostic("permission_media_rejected")
                    callback.reject()
                }
                .show()
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
            val pending = PendingFilePrompt(prompt, result, launch.intent, launch.cameraUri)
            pendingFilePrompt = pending

            if (launch.requiresCameraPermission && !isPermissionGranted(Manifest.permission.CAMERA)) {
                browserDiagnostic("file_prompt_camera_permission_requested")
                return try {
                    activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), FILE_CAMERA_PERMISSION_REQUEST)
                    result
                } catch (error: Exception) {
                    browserDiagnostic("file_prompt_camera_permission_failed")
                    Log.e(TAG, "Unable to request camera permission for file prompt", error)
                    pendingFilePrompt = null
                    cleanupCameraGrant(launch.cameraUri, deleteFile = true)
                    result.complete(prompt.dismiss())
                    result
                }
            }

            launchPendingFilePrompt(pending)
            return result
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

        if (event.startsWith("page_")) {
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

        val geckoUris = prepareGeckoFileUris(finalUris, cameraUri)
        val response = runCatching {
            when {
                geckoUris.isEmpty() -> {
                    browserDiagnostic("file_prompt_dismissed")
                    pending.prompt.dismiss()
                }
                pending.prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE -> {
                    browserDiagnostic("file_prompt_selection_received")
                    pending.prompt.confirm(appContext, geckoUris.toTypedArray())
                }
                else -> {
                    browserDiagnostic("file_prompt_selection_received")
                    pending.prompt.confirm(appContext, geckoUris.first())
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

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode == GECKO_ANDROID_PERMISSION_REQUEST) {
            val callback = pendingGeckoPermissionCallback ?: return true
            pendingGeckoPermissionCallback = null
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                browserDiagnostic("permission_android_granted")
                callback.grant()
            } else {
                browserDiagnostic("permission_android_rejected")
                callback.reject()
            }
            return true
        }

        if (requestCode == FILE_CAMERA_PERMISSION_REQUEST) {
            val pending = pendingFilePrompt ?: return true
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                browserDiagnostic("file_prompt_camera_permission_granted")
                launchPendingFilePrompt(pending)
            } else {
                browserDiagnostic("file_prompt_camera_permission_rejected")
                pendingFilePrompt = null
                cleanupCameraGrant(pending.cameraUri, deleteFile = true)
                pending.result.complete(pending.prompt.dismiss())
            }
            return true
        }

        return false
    }

    fun stop() {
        if (!started) return

        dismissPendingFilePrompt()
        pendingGeckoPermissionCallback?.reject()
        pendingGeckoPermissionCallback = null
        view.releaseSession()
        if (session.isOpen) session.close()
        AndroidCaptureRuntime.stop()
        started = false
    }

    private fun launchPendingFilePrompt(pending: PendingFilePrompt) {
        try {
            activity.startActivityForResult(pending.launchIntent, FILE_PROMPT_REQUEST)
            browserDiagnostic("file_prompt_picker_launched")
        } catch (error: Exception) {
            browserDiagnostic("file_prompt_picker_launch_failed")
            Log.e(TAG, "Unable to launch Gecko file prompt", error)
            if (pendingFilePrompt === pending) pendingFilePrompt = null
            cleanupCameraGrant(pending.cameraUri, deleteFile = true)
            pending.result.complete(pending.prompt.dismiss())
        }
    }

    private fun buildFilePromptLaunch(
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): FilePromptLaunch {
        if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.FOLDER) {
            return FilePromptLaunch(
                intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                cameraUri = null,
                requiresCameraPermission = false,
            )
        }

        val mimeTypes = prompt.mimeTypes
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = browserPickerMimeType(mimeTypes)
            if (prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val cameraMime = supportedCameraMimeType(mimeTypes)
        val directCapture = prompt.capture != GeckoSession.PromptDelegate.FilePrompt.Capture.NONE
        if (directCapture && cameraMime != null) {
            val cameraLaunch = buildCameraLaunch(cameraMime)
            if (cameraLaunch != null) {
                return FilePromptLaunch(
                    intent = cameraLaunch.intent,
                    cameraUri = cameraLaunch.cameraUri,
                    requiresCameraPermission = true,
                )
            }
        }

        // Normal Files/Photos prompts stay pure pickers. Camera is only invoked
        // for a real HTML capture request so it cannot interfere with document upload.
        return FilePromptLaunch(contentIntent, null, false)
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
        return FilePromptLaunch(intent, cameraUri, false)
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

    private fun prepareGeckoFileUris(selected: List<Uri>, cameraUri: Uri?): List<Uri> =
        selected.mapNotNull { uri ->
            if (uri == cameraUri) {
                val staged = AndroidDocumentUploadStager.stage(appContext, uri)
                if (staged != null) {
                    browserDiagnostic("file_prompt_camera_staged")
                    AndroidUserSelectedFileProvider.deleteIfOwned(appContext, uri)
                    staged
                } else {
                    browserDiagnostic("file_prompt_camera_stage_failed")
                    null
                }
            } else if (!AndroidDocumentUploadStager.shouldStage(appContext, uri)) {
                uri
            } else {
                AndroidDocumentUploadStager.stage(appContext, uri)?.also {
                    browserDiagnostic("file_prompt_document_staged")
                } ?: uri.also {
                    browserDiagnostic("file_prompt_document_stage_failed")
                }
            }
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
        val acceptsImage = mimeTypes.isEmpty() || mimeTypes.any { it == "image/*" || it.startsWith("image/") }
        val acceptsVideo = mimeTypes.any { it == "video/*" || it.startsWith("video/") }
        return when {
            acceptsImage -> "image/jpeg"
            acceptsVideo -> "video/mp4"
            else -> null
        }
    }

    private fun browserPickerMimeType(mimeTypes: List<String>): String {
        if (mimeTypes.isEmpty()) return "*/*"
        return when {
            mimeTypes.all { it.startsWith("image/") } -> "image/*"
            mimeTypes.all { it.startsWith("video/") } -> "video/*"
            mimeTypes.all { it.startsWith("audio/") } -> "audio/*"
            else -> "*/*"
        }
    }

    private fun isPermissionGranted(permission: String): Boolean =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private data class FilePromptLaunch(
        val intent: Intent,
        val cameraUri: Uri?,
        val requiresCameraPermission: Boolean,
    )

    private data class PendingFilePrompt(
        val prompt: GeckoSession.PromptDelegate.FilePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
        val launchIntent: Intent,
        val cameraUri: Uri?,
    )
}
