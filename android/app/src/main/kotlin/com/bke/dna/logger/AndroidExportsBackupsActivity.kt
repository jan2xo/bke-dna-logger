package com.bke.dna.logger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/** Owner-facing Working Data, recovery, archive and export surface. */
class AndroidExportsBackupsActivity : Activity() {
    private var selectedWorkingDataId: String = AndroidWorkingDataManager.LATEST_ID
    private var pendingWorkingDataId: String = AndroidWorkingDataManager.LATEST_ID
    private var pendingConversationKey: String? = null
    @Volatile private var operationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshUi()
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
        if (generations.none { it.id == selectedWorkingDataId }) {
            selectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
        }
        val selected = generations.first { it.id == selectedWorkingDataId }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
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
            text = "Working Data & Exports"
            textSize = 24f
        })
        content.addView(actionButton("Back to ChatGPT") { finish() })

        content.addView(TextView(this).apply {
            text = "Working Data"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            generations.map { it.label },
        )
        spinner.setSelection(generations.indexOfFirst { it.id == selectedWorkingDataId }, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (operationInProgress) return
                val chosen = generations[position].id
                if (chosen != selectedWorkingDataId) {
                    selectedWorkingDataId = chosen
                    refreshUi()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        content.addView(spinner)

        content.addView(TextView(this).apply {
            text = if (selected.isLatest) {
                "LATEST — ACTIVE / WRITABLE\nAll live ChatGPT capture writes only to this SQLite generation."
            } else {
                "READ-ONLY RECOVERY\n${selected.label}\nHistorical Working Data can be read and exported as CLEAN/RAW, but is never made writable by selecting it."
            }
            textSize = 15f
            setPadding(0, 10, 0, 12)
        })

        if (selected.isLatest) {
            content.addView(actionButton("Save & Start New Working Data") {
                runWork("Working Data saved; fresh Latest is now active") {
                    AndroidWorkingDataManager(this).rotateLatest()
                    selectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                }
            })
            content.addView(TextView(this).apply {
                text = "Use this when the live SQLite becomes heavy or laggy. The current generation is timestamped and verified, then a fresh Latest SQLite is created. Raw evidence is not duplicated."
                textSize = 13f
                setPadding(0, 4, 0, 12)
            })

            content.addView(actionButton("Backup Working Data") {
                pendingConversationKey = null
                pendingWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                createDocument(
                    REQUEST_BACKUP,
                    "BKE-DNA-working-${System.currentTimeMillis()}.dna-backup.zip",
                    "application/zip",
                )
            })
            content.addView(TextView(this).apply {
                text = "External Working Data backup keeps rebuildable shared evidence and still forbids cross-device SQLite merging. Timestamped SQLite generations remain app-private recovery data."
                textSize = 13f
                setPadding(0, 4, 0, 10)
            })

            content.addView(actionButton("Import .dna / backup") {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    REQUEST_IMPORT,
                )
            })
        } else {
            content.addView(actionButton("Back to Latest Working Data") {
                selectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                refreshUi()
            })
            content.addView(TextView(this).apply {
                text = "Historical `.dna` export is intentionally not allowed to mutate or impersonate Latest. A future explicit restore-as-new operation can promote historical recovery into a new writable generation without changing this snapshot."
                textSize = 13f
                setPadding(0, 6, 0, 12)
            })
        }

        val totalWorkingBytes = AndroidWorkingStorage.workingBytes(this) + manager.savedWorkingDataBytes()
        val warning = DnaReconciliationContract.shouldNotifyStorage(totalWorkingBytes)
        content.addView(TextView(this).apply {
            text = buildString {
                append("Total local working data: ${formatBytes(totalWorkingBytes)}")
                append("\nSelected generation SQLite/state: ${formatBytes(selected.snapshotBytes)}")
                append("\nNotify threshold: 1 GiB — no hard limit; capture continues.")
                if (warning) {
                    append("\n⚠ Local Working Data reached 1 GiB. Consider verified .dna archives and owner-directed cleanup.")
                }
            }
            textSize = 15f
            setPadding(0, 16, 0, 16)
        })
        if (warning) {
            Toast.makeText(
                this,
                "BKE DNA Working Data reached 1 GiB. Capture continues.",
                Toast.LENGTH_LONG,
            ).show()
        }

        content.addView(TextView(this).apply {
            text = "Conversations"
            textSize = 20f
            setPadding(0, 28, 0, 12)
        })

        val conversations = runCatching { manager.listConversations(selected) }.getOrDefault(emptyList())
        val human = AndroidHumanExportService(this, selected.conversationStateDirectory)

        if (conversations.isEmpty()) {
            content.addView(TextView(this).apply {
                text = if (selected.isLatest) {
                    "No normalized logical conversations in Latest yet. Capture remains active when you return to ChatGPT."
                } else {
                    "No logical conversations were indexed in this saved Working Data generation."
                }
            })
        }

        conversations.forEach { summary ->
            val descriptor = runCatching { human.describe(summary.conversationKey) }.getOrNull()
            val attributedBytes = runCatching { human.conversationWorkingBytes(summary.conversationKey) }.getOrDefault(0L)
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 24)
            }
            panel.addView(TextView(this).apply {
                text = descriptor?.title ?: summary.conversationNativeId
                textSize = 18f
            })
            panel.addView(TextView(this).apply {
                text = buildString {
                    append(descriptor?.historicalDate?.toString() ?: "Historical date not exposed")
                    append(" · ${summary.nodeCount} nodes · ${summary.sourceCount} sources")
                    append(" · ${formatBytes(attributedBytes)} evidence")
                    append(" · ${summary.coverageStatus}")
                    append(if (summary.dnaArchived) " · .dna verified" else " · not archived")
                }
            })

            panel.addView(actionButton("Read conversation") {
                startActivity(
                    Intent(this, AndroidConversationReaderActivity::class.java)
                        .putExtra(AndroidConversationReaderActivity.EXTRA_CONVERSATION_KEY, summary.conversationKey)
                        .putExtra(AndroidConversationReaderActivity.EXTRA_WORKING_DATA_ID, selected.id),
                )
            })

            val cleanAndRaw = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cleanAndRaw.addView(actionButton("Export CLEAN.md") {
                pendingConversationKey = summary.conversationKey
                pendingWorkingDataId = selected.id
                val fileBase = descriptor?.fileBase ?: summary.conversationKey
                createDocument(REQUEST_EXPORT_CLEAN_MD, "$fileBase - CLEAN.md", "text/markdown")
            })
            cleanAndRaw.addView(actionButton("Export RAW.md") {
                pendingConversationKey = summary.conversationKey
                pendingWorkingDataId = selected.id
                val fileBase = descriptor?.fileBase ?: summary.conversationKey
                createDocument(REQUEST_EXPORT_RAW_MD, "$fileBase - RAW.md", "text/markdown")
            })
            panel.addView(cleanAndRaw)

            if (selected.isLatest) {
                panel.addView(actionButton("Export .dna") {
                    pendingConversationKey = summary.conversationKey
                    pendingWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                    val fileBase = descriptor?.fileBase ?: summary.conversationKey
                    createDocument(REQUEST_EXPORT_DNA, "$fileBase.dna", "application/zip")
                })
            }
            content.addView(panel)
        }
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
                runWork("Conversation .dna exported and verified") {
                    AndroidConversationDnaArchiveService(this).use { service ->
                        service.exportToUri(conversationKey, contentResolver, uri)
                    }
                }
            }
            REQUEST_EXPORT_CLEAN_MD -> {
                val conversationKey = pendingConversationKey ?: return
                val generation = AndroidWorkingDataManager(this).generation(pendingWorkingDataId)
                runWork("CLEAN Markdown exported") {
                    AndroidHumanExportService(this, generation.conversationStateDirectory)
                        .exportCleanMarkdownToUri(conversationKey, contentResolver, uri)
                }
            }
            REQUEST_EXPORT_RAW_MD -> {
                val conversationKey = pendingConversationKey ?: return
                val generation = AndroidWorkingDataManager(this).generation(pendingWorkingDataId)
                runWork("RAW Markdown exported") {
                    AndroidHumanExportService(this, generation.conversationStateDirectory)
                        .exportRawMarkdownToUri(conversationKey, contentResolver, uri)
                }
            }
            REQUEST_BACKUP -> runWork("Working Data backup exported") {
                AndroidWorkingBackupService(this).backupToUri(contentResolver, uri)
            }
            REQUEST_IMPORT -> runWork("Import reconciled into Latest Working Data") {
                importSelected(uri)
            }
        }
    }

    private fun importSelected(uri: Uri) {
        val name = displayName(uri).lowercase(Locale.ROOT)
        if (name.endsWith(".dna")) {
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
            } finally {
                temp.delete()
            }
        } else {
            AndroidWorkingBackupService(this).importFromUri(contentResolver, uri)
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

    private fun runWork(successMessage: String, work: () -> Unit) {
        if (operationInProgress) {
            showOperationInProgress()
            return
        }
        operationInProgress = true
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                operationInProgress = false
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { successMessage },
                        onFailure = { "BKE DNA operation failed: ${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                pendingConversationKey = null
                pendingWorkingDataId = selectedWorkingDataId
                refreshUi()
            }
        }.start()
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener {
                if (operationInProgress) showOperationInProgress() else action()
            }
        }

    private fun showOperationInProgress() {
        Toast.makeText(this, "Working Data operation in progress", Toast.LENGTH_SHORT).show()
    }

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
    }
}
