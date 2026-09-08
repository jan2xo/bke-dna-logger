package com.bke.dna.logger

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Bounded conversation reader.
 *
 * Interactive reading must never materialize a whole large conversation into
 * one String/TextView. CLEAN pages come from SQLite message/revision rows; RAW
 * pages stream bounded windows from the current exact-evidence backend. All
 * database resolution, pager construction and page IO stay off the UI thread.
 */
class AndroidConversationReaderActivity : Activity() {
    private lateinit var conversationNativeId: String
    private lateinit var location: AndroidUnifiedConversationLocation
    private lateinit var workingData: AndroidWorkingDataGeneration

    private lateinit var titleLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var progressLabel: TextView
    private lateinit var body: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button

    private var cleanPager: AndroidCleanConversationPager? = null
    private var rawPager: AndroidRawConversationPager? = null
    private var mode = ReaderMode.CLEAN
    private var cleanOffset = 0
    private val cleanHistory = ArrayDeque<Int>()
    private var rawCursor: AndroidRawCursor? = null
    private val rawHistory = ArrayDeque<AndroidRawCursor>()
    private var loadGeneration = 0L

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bke-dna-reader-io").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conversationNativeId = intent.getStringExtra(EXTRA_CONVERSATION_NATIVE_ID).orEmpty()
        if (conversationNativeId.isBlank()) {
            finish()
            return
        }

        renderShell()
        resolveAndOpen()
    }

    private fun renderShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 24)
        }

        titleLabel = TextView(this).apply {
            text = "Opening conversation…"
            textSize = 20f
        }
        root.addView(titleLabel)

        modeLabel = TextView(this).apply {
            textSize = 13f
            setPadding(0, 6, 0, 6)
        }
        root.addView(modeLabel)

        val modeActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        modeActions.addView(compactButton("CLEAN") { switchMode(ReaderMode.CLEAN) })
        modeActions.addView(compactButton("RAW") { switchMode(ReaderMode.RAW) })
        modeActions.addView(compactButton("BACK") { finish() })
        root.addView(modeActions)

        progressLabel = TextView(this).apply {
            text = "Preparing bounded reader…"
            textSize = 12f
            setPadding(0, 8, 0, 8)
        }
        root.addView(progressLabel)

        val scroll = ScrollView(this)
        body = TextView(this).apply {
            text = "Loading…"
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, 8, 0, 20)
        }
        scroll.addView(
            body,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val paging = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        previousButton = compactButton("PREV") { previousPage() }.apply { isEnabled = false }
        nextButton = compactButton("NEXT") { nextPage() }.apply { isEnabled = false }
        paging.addView(previousButton)
        paging.addView(nextButton)
        root.addView(paging)

        setContentView(root)
    }

    private fun resolveAndOpen() {
        val request = ++loadGeneration
        ioExecutor.execute {
            val resolved = runCatching {
                val resolvedLocation = AndroidUnifiedConversationLibrary(this)
                    .resolve(conversationNativeId)
                val generation = AndroidWorkingDataManager(this)
                    .generation(resolvedLocation.generation.id)
                val clean = AndroidCleanConversationPager(
                    generation = generation,
                    conversationKey = resolvedLocation.conversationKey,
                )
                val raw = try {
                    AndroidRawConversationPager(
                        generation = generation,
                        conversationKey = resolvedLocation.conversationKey,
                        captureRoot = AndroidDnaPaths.capturesRoot(this),
                    )
                } catch (error: Throwable) {
                    clean.close()
                    throw error
                }
                ReaderResolution(resolvedLocation, generation, clean, raw)
            }
            runOnUiThread {
                if (request != loadGeneration || isFinishing || isDestroyed) {
                    resolved.getOrNull()?.close()
                    return@runOnUiThread
                }
                resolved.fold(
                    onSuccess = { resolution ->
                        closePagers()
                        location = resolution.location
                        workingData = resolution.generation
                        cleanPager = resolution.cleanPager
                        rawPager = resolution.rawPager
                        cleanOffset = 0
                        cleanHistory.clear()
                        rawCursor = rawPager?.firstCursor()
                        rawHistory.clear()
                        titleLabel.text = location.displayTitle
                        switchMode(ReaderMode.CLEAN, force = true)
                    },
                    onFailure = {
                        body.text = "Unable to open conversation."
                        progressLabel.text = "Reader unavailable"
                    },
                )
            }
        }
    }

    private fun switchMode(newMode: ReaderMode, force: Boolean = false) {
        if (!::workingData.isInitialized) return
        if (!force && mode == newMode) return
        mode = newMode
        when (mode) {
            ReaderMode.CLEAN -> {
                cleanOffset = 0
                cleanHistory.clear()
                loadCleanPage(cleanOffset)
            }
            ReaderMode.RAW -> {
                rawCursor = rawPager?.firstCursor()
                rawHistory.clear()
                val cursor = rawCursor
                if (cursor == null) {
                    modeLabel.text = "RAW · full captured evidence in context · bounded stream"
                    progressLabel.text = "No RAW sources indexed for this conversation"
                    body.text = ""
                    previousButton.isEnabled = false
                    nextButton.isEnabled = false
                } else {
                    loadRawPage(cursor)
                }
            }
        }
    }

    private fun nextPage() {
        when (mode) {
            ReaderMode.CLEAN -> {
                val next = nextButton.tag as? Int ?: return
                cleanHistory.addLast(cleanOffset)
                loadCleanPage(next)
            }
            ReaderMode.RAW -> {
                val next = nextButton.tag as? AndroidRawCursor ?: return
                rawCursor?.let(rawHistory::addLast)
                loadRawPage(next)
            }
        }
    }

    private fun previousPage() {
        when (mode) {
            ReaderMode.CLEAN -> {
                val previous = cleanHistory.removeLastOrNull() ?: return
                loadCleanPage(previous)
            }
            ReaderMode.RAW -> {
                val previous = rawHistory.removeLastOrNull() ?: return
                loadRawPage(previous)
            }
        }
    }

    private fun loadCleanPage(offset: Int) {
        val pager = cleanPager ?: return
        val request = ++loadGeneration
        setLoading("CLEAN · JAN / RIGHT-HAND only · no tools")
        ioExecutor.execute {
            val result = runCatching { pager.loadPage(offset) }
            runOnUiThread {
                if (request != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { page ->
                        cleanOffset = page.startOffset
                        modeLabel.text = "CLEAN · JAN / RIGHT-HAND only · no tools"
                        body.text = renderCleanPage(page)
                        progressLabel.text = buildString {
                            append("Bounded page · ")
                            append(page.turns.size)
                            append(" visible turn(s) · candidate position ")
                            append(minOf(page.nextOffset ?: page.totalCandidateNodes, page.totalCandidateNodes))
                            append(" / ")
                            append(page.totalCandidateNodes)
                            if (workingData.isReadOnly) append(" · READ-ONLY RECOVERY")
                        }
                        previousButton.isEnabled = cleanHistory.isNotEmpty()
                        nextButton.tag = page.nextOffset
                        nextButton.isEnabled = page.nextOffset != null
                    },
                    onFailure = { error -> showPageError("CLEAN", error) },
                )
            }
        }
    }

    private fun loadRawPage(cursor: AndroidRawCursor) {
        val pager = rawPager ?: return
        val request = ++loadGeneration
        setLoading("RAW · full captured evidence in context · bounded stream")
        ioExecutor.execute {
            val result = runCatching { pager.loadPage(cursor) }
            runOnUiThread {
                if (request != loadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { page ->
                        rawCursor = page.cursor
                        modeLabel.text = "RAW · full captured evidence in context · bounded stream"
                        body.text = page.text
                        progressLabel.text = buildString {
                            append("Source ")
                            append(page.cursor.sourceIndex + 1)
                            append(" / ")
                            append(page.sourceCount)
                            append(" · byte offset ")
                            append(page.cursor.byteOffset)
                            append(" · max page ")
                            append(AndroidRawConversationPager.RAW_PAGE_BYTES / 1024)
                            append(" KiB")
                            if (workingData.isReadOnly) append(" · READ-ONLY RECOVERY")
                        }
                        previousButton.isEnabled = rawHistory.isNotEmpty()
                        nextButton.tag = page.nextCursor
                        nextButton.isEnabled = page.nextCursor != null
                    },
                    onFailure = { error -> showPageError("RAW", error) },
                )
            }
        }
    }

    private fun renderCleanPage(page: AndroidCleanPage): String = buildString {
        page.turns.forEachIndexed { index, turn ->
            if (index > 0) appendLine()
            appendLine(turn.speaker)
            appendLine()
            appendLine(turn.text)
        }
    }.trimEnd()

    private fun setLoading(label: String) {
        modeLabel.text = label
        progressLabel.text = "Loading bounded page…"
        body.text = "Loading…"
        previousButton.isEnabled = false
        nextButton.isEnabled = false
    }

    private fun showPageError(kind: String, error: Throwable) {
        modeLabel.text = kind
        progressLabel.text = "Unable to load page"
        body.text = error.message ?: "Reader error"
        previousButton.isEnabled = false
        nextButton.isEnabled = false
    }

    private fun compactButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(18, 8, 18, 8)
            setOnClickListener { action() }
        }

    private fun closePagers() {
        cleanPager?.close()
        cleanPager = null
        rawPager?.close()
        rawPager = null
    }

    override fun onDestroy() {
        loadGeneration += 1
        val clean = cleanPager
        val raw = rawPager
        cleanPager = null
        rawPager = null
        ioExecutor.execute {
            clean?.close()
            raw?.close()
        }
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private enum class ReaderMode { CLEAN, RAW }

    private data class ReaderResolution(
        val location: AndroidUnifiedConversationLocation,
        val generation: AndroidWorkingDataGeneration,
        val cleanPager: AndroidCleanConversationPager,
        val rawPager: AndroidRawConversationPager,
    ) {
        fun close() {
            cleanPager.close()
            rawPager.close()
        }
    }

    companion object {
        const val EXTRA_CONVERSATION_NATIVE_ID = "conversationNativeId"
    }
}
