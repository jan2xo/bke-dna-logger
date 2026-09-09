package com.bke.dna.logger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/** Owner-facing Working Data generation management plus one unified conversation library. */
class AndroidExportsBackupsActivity : Activity() {
    private var inspectedWorkingDataId: String = AndroidWorkingDataManager.LATEST_ID
    private var searchQuery: String = ""
    private var libraryLimit: Int = AndroidUnifiedConversationLibrary.DEFAULT_PAGE_SIZE
    private var pendingWorkingDataId: String = AndroidWorkingDataManager.LATEST_ID
    private var pendingConversationKey: String? = null
    @Volatile private var operationInProgress = false
    private var queueStatusView: TextView? = null
    private val queueMonitorHandler = Handler(Looper.getMainLooper())
    private val queueMonitorTick = object : Runnable {
        override fun run() {
            refreshQueueStatus()
            if (!isFinishing && !isDestroyed) {
                queueMonitorHandler.postDelayed(this, QUEUE_REFRESH_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        AndroidDerivationScheduler.start(this)
        queueMonitorHandler.removeCallbacks(queueMonitorTick)
        queueMonitorHandler.post(queueMonitorTick)
    }

    override fun onPause() {
        queueMonitorHandler.removeCallbacks(queueMonitorTick)
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (operationInProgress) {
            showOperationInProgress()
            return
        }
        super.onBackPressed()
    }

    private fun refreshUi() {
        val manager = AndroidWorkingDataManager(this)
        val generations = manager.listWorkingData()
        if (generations.none { it.id == inspectedWorkingDataId }) {
            inspectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
        }
        val inspected = generations.first { it.id == inspectedWorkingDataId }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)

        content.addView(TextView(this).apply {
            text = "Working Data & Conversations"
            textSize = 24f
        })
        content.addView(compactRow(compactButton("← ChatGPT") { finish() }))

        content.addView(sectionTitle("Processing"))
        queueStatusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, 0, 0, 6)
        }
        content.addView(queueStatusView)
        val currentProfile = AndroidDerivationScheduler.getProfile(this)
        content.addView(
            compactRow(
                *AndroidProcessingProfile.entries.map { profile ->
                    compactButton(
                        if (profile == currentProfile) "✓ ${profileLabel(profile)}" else profileLabel(profile),
                    ) {
                        AndroidDerivationScheduler.setProfile(this, profile)
                        refreshUi()
                    }.apply {
                        isEnabled = profile != currentProfile
                    }
                }.toTypedArray(),
            ),
        )
        content.addView(TextView(this).apply {
            text = "Slow gives the browser the most breathing room; Balanced is the default; Fast minimizes queue rest time. Capture always keeps priority."
            textSize = 12f
            setPadding(0, 4, 0, 8)
        })
        refreshQueueStatus()

        content.addView(sectionTitle("Working Data"))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            generations.map { it.label },
        )
        spinner.setSelection(generations.indexOfFirst { it.id == inspectedWorkingDataId }, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (operationInProgress) return
                val chosen = generations[position].id
                if (chosen != inspectedWorkingDataId) {
                    inspectedWorkingDataId = chosen
                    refreshUi()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        content.addView(spinner)

        content.addView(TextView(this).apply {
            text = buildString {
                if (inspected.isLatest) {
                    append("LATEST — ACTIVE / WRITABLE")
                    append("\nAll live ChatGPT capture writes to this SQLite generation.")
                } else {
                    append("READ-ONLY WORKING DATA")
                    append("\n${inspected.label}")
                    if (!inspected.rawEvidenceIncluded) {
                        append("\nLegacy generation: RAW may still depend on pre-SQLite fallback evidence.")
                    }
                }
                append("\nThe selector only inspects Working Data; the conversation library remains unified below.")
            }
            textSize = 14f
            setPadding(0, 10, 0, 8)
        })

        if (inspected.isLatest) {
            content.addView(
                compactRow(
                    compactButton("SAVE NEW") {
                        runWork(
                            successMessage = "Working Data saved; fresh Latest is now active",
                            storageMutation = true,
                        ) {
                            AndroidWorkingDataManager(this).rotateLatest()
                            inspectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                        }
                    },
                    compactButton("BACKUP") { prepareWorkingDataBackup(inspected) },
                    compactButton("IMPORT") {
                        startActivityForResult(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            },
                            REQUEST_IMPORT,
                        )
                    },
                ),
            )
            content.addView(TextView(this).apply {
                text = "SAVE NEW checkpoints verified SQLite/state and activates a fresh Latest. BACKUP writes the self-contained SQLite generation. IMPORT restores a Working Data backup as read-only; conversation .dna import remains supported separately."
                textSize = 12f
                setPadding(0, 4, 0, 8)
            })
        } else {
            val backupButton = compactButton("BACKUP") { prepareWorkingDataBackup(inspected) }.apply {
                isEnabled = inspected.rawEvidenceIncluded
            }
            content.addView(
                compactRow(
                    compactButton("LATEST") {
                        inspectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                        refreshUi()
                    },
                    backupButton,
                    dangerButton("DELETE") { confirmDeleteGeneration(inspected) },
                ),
            )
            content.addView(TextView(this).apply {
                text = if (inspected.rawEvidenceIncluded) {
                    "Saved Working Data is a self-contained read-only SQLite recovery generation. DELETE removes this whole generation after exact jan2x confirmation; Latest is never eligible."
                } else {
                    "This pre-SQLite-RAW generation cannot produce a self-contained SQLite backup. It remains readable through legacy fallback and may still be deleted explicitly with jan2x."
                }
                textSize = 12f
                setPadding(0, 4, 0, 8)
            })
        }

        val totalWorkingBytes = AndroidWorkingStorage.workingBytes(this) + manager.savedWorkingDataBytes()
        val warning = DnaReconciliationContract.shouldNotifyStorage(totalWorkingBytes)
        content.addView(TextView(this).apply {
            text = buildString {
                append("Total local Working Data: ${formatBytes(totalWorkingBytes)}")
                append("\nInspected generation SQLite/state: ${formatBytes(inspected.snapshotBytes)}")
                append("\nWorking Data generations: ${generations.size}")
                append("\nNotify threshold: 1 GiB — capture continues; Jan decides when saved generations are deleted.")
                if (warning) append("\n⚠ Storage is above the warning threshold.")
            }
            textSize = 13f
            setPadding(0, 10, 0, 12)
        })

        content.addView(sectionTitle("All Conversations"))
        content.addView(TextView(this).apply {
            text = "One deduplicated library across Latest + every saved SQLite. Tap a conversation row to read it. CLEAN and RAW stay immediate; portable .dna export lives under MORE when Latest owns the conversation."
            textSize = 12f
            setPadding(0, 0, 0, 8)
        })

        val searchInput = EditText(this).apply {
            hint = "Search conversation titles"
            setSingleLine(true)
            setText(searchQuery)
        }
        content.addView(searchInput)
        content.addView(
            compactRow(
                compactButton("SEARCH") {
                    searchQuery = searchInput.text.toString().trim()
                    libraryLimit = AndroidUnifiedConversationLibrary.DEFAULT_PAGE_SIZE
                    refreshUi()
                },
                compactButton("CLEAR") {
                    searchQuery = ""
                    libraryLimit = AndroidUnifiedConversationLibrary.DEFAULT_PAGE_SIZE
                    refreshUi()
                },
            ),
        )

        val library = AndroidUnifiedConversationLibrary(this)
        val conversations = runCatching { library.search(searchQuery, libraryLimit) }
            .getOrElse {
                content.addView(TextView(this).apply { text = "Unable to read unified library: ${it.message}" })
                emptyList()
            }

        if (conversations.isEmpty()) {
            content.addView(TextView(this).apply {
                text = if (searchQuery.isBlank()) "No normalized conversations yet." else "No conversations matched ‘$searchQuery’."
                setPadding(0, 12, 0, 12)
            })
        }

        conversations.forEach { summary ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 16)
                isClickable = true
                isFocusable = true
                contentDescription = "Read ${summary.displayTitle}"
                setOnClickListener { openConversation(summary) }
            }
            panel.addView(TextView(this).apply {
                text = summary.displayTitle
                textSize = 17f
            })
            panel.addView(TextView(this).apply {
                text = buildString {
                    append(summary.stateObservedThrough)
                    append(" · ${summary.nodeCount} nodes · ${summary.sourceCount} sources")
                    append(" · ${summary.generationCount} Working Data")
                    append(" · ${summary.coverageStatus}")
                    if (summary.dnaArchived) append(" · .dna verified")
                }
                textSize = 12f
                setPadding(0, 2, 0, 4)
            })

            val actions = mutableListOf<Button>()
            actions += compactButton("CLEAN") { prepareHumanExport(summary, clean = true) }
            actions += compactButton("RAW") { prepareHumanExport(summary, clean = false) }
            if (summary.hasLatest) {
                val moreButton = compactButton("MORE")
                moreButton.setOnClickListener {
                    if (operationInProgress) showOperationInProgress() else showConversationMenu(moreButton, summary)
                }
                actions += moreButton
            }
            panel.addView(compactRow(*actions.toTypedArray()))
            content.addView(panel)
        }

        if (conversations.size >= libraryLimit && libraryLimit < AndroidUnifiedConversationLibrary.MAX_PAGE_SIZE) {
            content.addView(compactRow(compactButton("LOAD MORE") {
                libraryLimit = (libraryLimit + AndroidUnifiedConversationLibrary.DEFAULT_PAGE_SIZE)
                    .coerceAtMost(AndroidUnifiedConversationLibrary.MAX_PAGE_SIZE)
                refreshUi()
            }))
        }
    }

    private fun prepareWorkingDataBackup(generation: AndroidWorkingDataGeneration) {
        pendingConversationKey = null
        pendingWorkingDataId = generation.id
        val fileLabel = if (generation.isLatest) "latest" else generation.id
        createDocument(
            REQUEST_BACKUP,
            "BKE-DNA-working-$fileLabel.dna-backup.zip",
            "application/zip",
        )
    }

    private fun confirmDeleteGeneration(generation: AndroidWorkingDataGeneration) {
        require(!generation.isLatest)
        showJan2xConfirmation(
            title = "DELETE WORKING DATA?",
            message = buildString {
                append(generation.label)
                append("\n\nDelete this complete read-only Working Data generation and reclaim approximately ${formatBytes(generation.snapshotBytes)}.")
                append("\n\nThis does not touch Latest and does not require a .dna export.")
            },
        ) {
            runResultWork(storageMutation = true) {
                val result = AndroidWorkingDataManager(this).deleteSavedGeneration(
                    generation.id,
                    AndroidWorkingDataManager.DELETE_CONFIRMATION_TEXT,
                )
                inspectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                "Deleted ${result.generationId}; reclaimed ${formatBytes(result.bytesReclaimed)}"
            }
        }
    }

    private fun showJan2xConfirmation(title: String, message: String, confirmed: () -> Unit) {
        val input = EditText(this).apply {
            hint = "Type jan2x"
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$message\n\nType exact confirmation: jan2x")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("DELETE", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(Color.RED)
                setOnClickListener {
                    if (input.text.toString() != AndroidWorkingDataManager.DELETE_CONFIRMATION_TEXT) {
                        input.error = "Type exactly jan2x"
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    confirmed()
                }
            }
        }
        dialog.show()
    }

    private fun refreshQueueStatus() {
        val target = queueStatusView ?: return
        if (operationInProgress) {
            target.text = "Queue monitor paused · Working Data operation in progress"
            return
        }
        val snapshot = runCatching { AndroidDerivationScheduler.snapshot(this) }
        target.text = snapshot.fold(
            onSuccess = { queue ->
                buildString {
                    when {
                        queue.processing > 0 -> {
                            append("PROCESSING")
                            queue.currentStage?.let { append(" · $it") }
                        }
                        queue.waiting > 0 -> append("QUEUED")
                        queue.failed > 0 -> append("IDLE · ATTENTION")
                        else -> append("IDLE")
                    }
                    append("\n${queue.waiting} waiting · ${queue.processing} processing · ${queue.failed} failed · ${queue.done} done")
                }
            },
            onFailure = { error -> "Queue unavailable · ${error.message ?: "unknown error"}" },
        )
    }

    private fun showConversationMenu(anchor: Button, summary: AndroidUnifiedConversationSummary) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_EXPORT_DNA, 0, "Export .dna")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT_DNA -> {
                    pendingConversationKey = summary.conversationKey
                    pendingWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                    createDocument(
                        REQUEST_EXPORT_DNA,
                        "${safeFileBase(summary.displayTitle)}.dna",
                        "application/zip",
                    )
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun openConversation(summary: AndroidUnifiedConversationSummary) {
        startActivity(
            Intent(this, AndroidConversationReaderActivity::class.java)
                .putExtra(
                    AndroidConversationReaderActivity.EXTRA_CONVERSATION_NATIVE_ID,
                    summary.conversationNativeId,
                ),
        )
    }

    private fun prepareHumanExport(summary: AndroidUnifiedConversationSummary, clean: Boolean) {
        val location = AndroidUnifiedConversationLibrary(this).resolve(summary.conversationNativeId)
        val verifiedGeneration = AndroidWorkingDataManager(this).generation(location.generation.id)
        val human = AndroidHumanExportService(this, verifiedGeneration.conversationStateDirectory)
        val descriptor = human.describe(location.conversationKey)
        pendingConversationKey = location.conversationKey
        pendingWorkingDataId = verifiedGeneration.id
        createDocument(
            if (clean) REQUEST_EXPORT_CLEAN_MD else REQUEST_EXPORT_RAW_MD,
            "${descriptor.fileBase} - ${if (clean) "CLEAN" else "RAW"}.md",
            "text/markdown",
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_EXPORT_DNA -> {
                val conversationKey = pendingConversationKey ?: return
                require(pendingWorkingDataId == AndroidWorkingDataManager.LATEST_ID) {
                    "Historical Working Data cannot mutate Latest .dna durability state"
                }
                runWork(
                    successMessage = "Conversation .dna exported and verified",
                    storageMutation = true,
                ) {
                    AndroidConversationDnaArchiveService(this).use { service ->
                        service.exportToUri(conversationKey, contentResolver, uri)
                    }
                }
            }
            REQUEST_EXPORT_CLEAN_MD -> exportHuman(uri, clean = true)
            REQUEST_EXPORT_RAW_MD -> exportHuman(uri, clean = false)
            REQUEST_BACKUP -> runWork(
                successMessage = "Working Data SQLite backup exported and verified",
                storageMutation = true,
            ) {
                AndroidWorkingBackupService(this).backupToUri(
                    contentResolver,
                    uri,
                    pendingWorkingDataId,
                )
            }
            REQUEST_IMPORT -> runResultWork(storageMutation = true) {
                importSelected(uri)
            }
        }
    }

    private fun exportHuman(uri: Uri, clean: Boolean) {
        val conversationKey = pendingConversationKey ?: return
        val generation = AndroidWorkingDataManager(this).generation(pendingWorkingDataId)
        runWork(if (clean) "CLEAN Markdown exported" else "RAW Markdown exported") {
            val human = AndroidHumanExportService(this, generation.conversationStateDirectory)
            if (clean) {
                human.exportCleanMarkdownToUri(conversationKey, contentResolver, uri)
            } else {
                human.exportRawMarkdownToUri(conversationKey, contentResolver, uri)
            }
        }
    }

    private fun importSelected(uri: Uri): String {
        val name = displayName(uri).lowercase(Locale.ROOT)
        return if (name.endsWith(".dna")) {
            val temp = File(cacheDir, "conversation-import-${UUID.randomUUID()}.dna")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp, false).use { output ->
                        input.copyTo(output, 128 * 1024)
                        output.flush()
                        output.fd.sync()
                    }
                } ?: error("Unable to open selected conversation .dna")
                AndroidConversationDnaImportService(this).importVerified(listOf(temp))
                "Conversation .dna reconciled into Latest Working Data"
            } finally {
                temp.delete()
            }
        } else {
            val restored = AndroidWorkingBackupService(this).importFromUri(contentResolver, uri)
            inspectedWorkingDataId = restored.generationId
            "Working Data restored as read-only generation ${restored.generationId}"
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: ""
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun createDocument(requestCode: Int, fileName: String, mimeType: String) {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, fileName)
            },
            requestCode,
        )
    }

    private fun runWork(
        successMessage: String,
        storageMutation: Boolean = false,
        work: () -> Unit,
    ) {
        runResultWork(storageMutation) {
            work()
            successMessage
        }
    }

    private fun runResultWork(storageMutation: Boolean, work: () -> String) {
        if (operationInProgress) {
            showOperationInProgress()
            return
        }
        operationInProgress = true
        refreshQueueStatus()
        Thread {
            val result = runCatching {
                if (storageMutation) {
                    AndroidCaptureRuntime.withStorageMutationPause(this) { work() }
                } else {
                    work()
                }
            }
            runOnUiThread {
                operationInProgress = false
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { it },
                        onFailure = { "BKE DNA operation failed: ${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                pendingConversationKey = null
                pendingWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                refreshUi()
            }
        }.start()
    }

    private fun sectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 19f
        setPadding(0, 20, 0, 6)
    }

    private fun compactRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = 8 },
                )
            }
        }

    private fun compactButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 12f
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(18, 8, 18, 8)
    }

    private fun compactButton(label: String, action: () -> Unit): Button =
        compactButton(label).apply {
            setOnClickListener {
                if (operationInProgress) showOperationInProgress() else action()
            }
        }

    private fun dangerButton(label: String, action: () -> Unit): Button =
        compactButton(label, action).apply {
            setBackgroundColor(Color.rgb(183, 28, 28))
            setTextColor(Color.WHITE)
        }

    private fun profileLabel(profile: AndroidProcessingProfile): String = when (profile) {
        AndroidProcessingProfile.SLOW -> "Slow"
        AndroidProcessingProfile.BALANCED -> "Balanced"
        AndroidProcessingProfile.FAST -> "Fast"
    }

    private fun showOperationInProgress() {
        Toast.makeText(this, "Working Data operation in progress", Toast.LENGTH_SHORT).show()
    }

    private fun safeFileBase(value: String): String = value
        .replace(Regex("[\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(100)
        .ifBlank { "conversation" }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) return String.format(Locale.US, "%.1f KiB", kib)
        val mib = kib / 1024.0
        if (mib < 1024.0) return String.format(Locale.US, "%.1f MiB", mib)
        return String.format(Locale.US, "%.2f GiB", mib / 1024.0)
    }

    companion object {
        private const val REQUEST_EXPORT_DNA = 1101
        private const val REQUEST_EXPORT_CLEAN_MD = 1102
        private const val REQUEST_BACKUP = 1103
        private const val REQUEST_IMPORT = 1104
        private const val REQUEST_EXPORT_RAW_MD = 1105
        private const val MENU_EXPORT_DNA = 2101
        private const val QUEUE_REFRESH_MS = 1_500L
    }
}
