package com.bke.dna.logger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

/** Owner-facing manual archive/export/backup surface. */
class AndroidExportsBackupsActivity : Activity() {
    private var pendingConversationKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshUi()
    }

    private fun refreshUi() {
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
            text = "Exports & Backups"
            textSize = 24f
        })
        content.addView(actionButton("Back to ChatGPT") { finish() })

        val workingBytes = AndroidWorkingStorage.workingBytes(this)
        val warning = DnaReconciliationContract.shouldNotifyStorage(workingBytes)
        content.addView(TextView(this).apply {
            text = buildString {
                append("Working storage: ${formatBytes(workingBytes)}")
                append("\nNotify threshold: 1 GiB — no hard limit; capture continues.")
                if (warning) {
                    append("\n⚠ Working storage reached 1 GiB. Consider manual .dna exports or a backup.")
                }
            }
            textSize = 16f
            setPadding(0, 16, 0, 16)
        })
        if (warning) {
            Toast.makeText(
                this,
                "BKE DNA working storage reached 1 GiB. Capture continues.",
                Toast.LENGTH_LONG,
            ).show()
        }

        content.addView(actionButton("Backup working store") {
            pendingConversationKey = null
            createDocument(REQUEST_BACKUP, "BKE-DNA-working-${System.currentTimeMillis()}.dna-backup.zip", "application/zip")
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

        content.addView(TextView(this).apply {
            text = "Conversations"
            textSize = 20f
            setPadding(0, 28, 0, 12)
        })

        val index = AndroidCaptureIndex(this)
        val conversations = try {
            index.listLogicalConversations()
        } finally {
            index.close()
        }
        val human = AndroidHumanExportService(this)

        if (conversations.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "No normalized logical conversations yet. Capture remains active in ChatGPT."
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
                        .putExtra(AndroidConversationReaderActivity.EXTRA_CONVERSATION_KEY, summary.conversationKey),
                )
            })

            val archiveAndClean = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            archiveAndClean.addView(actionButton("Export .dna") {
                pendingConversationKey = summary.conversationKey
                val fileBase = descriptor?.fileBase ?: summary.conversationKey
                createDocument(REQUEST_EXPORT_DNA, "$fileBase.dna", "application/zip")
            })
            archiveAndClean.addView(actionButton("Export CLEAN.md") {
                pendingConversationKey = summary.conversationKey
                val fileBase = descriptor?.fileBase ?: summary.conversationKey
                createDocument(REQUEST_EXPORT_CLEAN_MD, "$fileBase - CLEAN.md", "text/markdown")
            })
            panel.addView(archiveAndClean)

            panel.addView(actionButton("Export RAW.md") {
                pendingConversationKey = summary.conversationKey
                val fileBase = descriptor?.fileBase ?: summary.conversationKey
                createDocument(REQUEST_EXPORT_RAW_MD, "$fileBase - RAW.md", "text/markdown")
            })
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
                runWork("Conversation .dna exported and verified") {
                    AndroidConversationDnaArchiveService(this).use { service ->
                        service.exportToUri(conversationKey, contentResolver, uri)
                    }
                }
            }
            REQUEST_EXPORT_CLEAN_MD -> {
                val conversationKey = pendingConversationKey ?: return
                runWork("CLEAN Markdown exported") {
                    AndroidHumanExportService(this).exportCleanMarkdownToUri(
                        conversationKey,
                        contentResolver,
                        uri,
                    )
                }
            }
            REQUEST_EXPORT_RAW_MD -> {
                val conversationKey = pendingConversationKey ?: return
                runWork("RAW Markdown exported") {
                    AndroidHumanExportService(this).exportRawMarkdownToUri(
                        conversationKey,
                        contentResolver,
                        uri,
                    )
                }
            }
            REQUEST_BACKUP -> runWork("Working evidence backup exported") {
                AndroidWorkingBackupService(this).backupToUri(contentResolver, uri)
            }
            REQUEST_IMPORT -> runWork("Import reconciled into local working state") {
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
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                Toast.makeText(
                    this,
                    result.fold(
                        onSuccess = { successMessage },
                        onFailure = { "BKE DNA operation failed: ${it.message}" },
                    ),
                    Toast.LENGTH_LONG,
                ).show()
                pendingConversationKey = null
                refreshUi()
            }
        }.start()
    }

    private fun actionButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            setOnClickListener { action() }
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
