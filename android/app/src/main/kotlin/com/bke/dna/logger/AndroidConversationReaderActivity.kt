package com.bke.dna.logger

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Full conversation data is resolved only after the owner opens a unified-library item. */
class AndroidConversationReaderActivity : Activity() {
    private lateinit var conversationNativeId: String
    private lateinit var location: AndroidUnifiedConversationLocation
    private lateinit var workingData: AndroidWorkingDataGeneration
    private lateinit var human: AndroidHumanExportService
    private lateinit var body: TextView
    private lateinit var modeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversationNativeId = intent.getStringExtra(EXTRA_CONVERSATION_NATIVE_ID).orEmpty()
        if (conversationNativeId.isBlank()) {
            finish()
            return
        }

        location = runCatching { AndroidUnifiedConversationLibrary(this).resolve(conversationNativeId) }
            .getOrElse {
                finish()
                return
            }
        workingData = runCatching { AndroidWorkingDataManager(this).generation(location.generation.id) }
            .getOrElse {
                finish()
                return
            }
        human = AndroidHumanExportService(this, workingData.conversationStateDirectory)
        renderUi()
        showClean()
    }

    private fun renderUi() {
        val descriptor = runCatching { human.describe(location.conversationKey) }.getOrNull()
        val attributedBytes = runCatching { human.conversationWorkingBytes(location.conversationKey) }.getOrDefault(0L)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 36)
        }

        root.addView(TextView(this).apply {
            text = descriptor?.title ?: location.displayTitle
            textSize = 22f
        })
        root.addView(TextView(this).apply {
            text = buildString {
                append("Resolved from: ${workingData.label}")
                append("\nSeen in ${location.allGenerationIds.size} Working Data generation(s)")
                append("\nAttributed conversation evidence: ${formatBytes(attributedBytes)}")
                append(" · SQLite shared projection excluded")
                if (workingData.isReadOnly) append("\nREAD-ONLY RECOVERY SOURCE")
            }
            textSize = 14f
            setPadding(0, 8, 0, 12)
        })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(actionButton("CLEAN") { showClean() })
        actions.addView(actionButton("RAW") { showRaw() })
        actions.addView(actionButton("BACK") { finish() })
        root.addView(actions)

        modeLabel = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 8)
        }
        root.addView(modeLabel)

        val scroll = ScrollView(this)
        body = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 48)
        }
        scroll.addView(
            body,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(root)
    }

    private fun showClean() {
        modeLabel.text = "CLEAN · JAN / RIGHT-HAND only · no tools"
        body.text = runCatching { human.renderCleanMarkdown(location.conversationKey) }
            .getOrElse { "Unable to render CLEAN view: ${it.message}" }
    }

    private fun showRaw() {
        modeLabel.text = "RAW · unfiltered captured conversation payloads"
        body.text = runCatching { human.renderRawMarkdown(location.conversationKey) }
            .getOrElse { "Unable to render RAW view: ${it.message}" }
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
        const val EXTRA_CONVERSATION_NATIVE_ID = "conversationNativeId"
    }
}
