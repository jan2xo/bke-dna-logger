package com.bke.dna.logger

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import java.util.concurrent.Executors

/** Owner-facing Working Data generation management plus one unified conversation library. */
class AndroidExportsBackupsActivity : Activity() {
    private var inspectedWorkingDataId: String = AndroidWorkingDataManager.LATEST_ID
    private var searchQuery: String = ""
    private var libraryLimit: Int = AndroidUnifiedConversationLibrary.DEFAULT_PAGE_SIZE
    private var pendingWorkingDataId: String = AndroidWorkingDataManager.LATEST_ID
    private var pendingConversationKey: String? = null
    @Volatile private var operationInProgress = false
    @Volatile private var queueRefreshInFlight = false
    @Volatile private var libraryRefreshInFlight = false
    private var queueStatusView: TextView? = null
    private var conversationCountView: TextView? = null
    private var conversationListContainer: LinearLayout? = null
    private var renderedConversations: List<AndroidUnifiedConversationSummary> = emptyList()
    private var renderedConversationQuery: String = ""
    private var renderedConversationLimit: Int = 0
    private var uiLoadGeneration = 0L
    private var hasRenderedUi = false
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bke-dna-working-data-io").apply { isDaemon = true }
    }
    private val queueMonitorHandler = Handler(Looper.getMainLooper())
    private val queueMonitorTick = object : Runnable {
        override fun run() {
            refreshQueueStatus()
            if (!isFinishing && !isDestroyed) {
                queueMonitorHandler.postDelayed(this, QUEUE_REFRESH_MS)
            }
        }
    }
    private val libraryMonitorTick = object : Runnable {
        override fun run() {
            refreshConversationLibrary()
            if (!isFinishing && !isDestroyed) {
                queueMonitorHandler.postDelayed(this, LIBRARY_REFRESH_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderLoadingShell()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        AndroidDerivationScheduler.start(this)
        queueMonitorHandler.removeCallbacks(queueMonitorTick)
        queueMonitorHandler.removeCallbacks(libraryMonitorTick)
        queueMonitorHandler.post(queueMonitorTick)
        queueMonitorHandler.post(libraryMonitorTick)
    }

    override fun onPause() {
        queueMonitorHandler.removeCallbacks(queueMonitorTick)
        queueMonitorHandler.removeCallbacks(libraryMonitorTick)
        super.onPause()
    }

    override fun onDestroy() {
        uiLoadGeneration += 1
        queueMonitorHandler.removeCallbacks(queueMonitorTick)
        queueMonitorHandler.removeCallbacks(libraryMonitorTick)
        ioExecutor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (operationInProgress) {
            showOperationInProgress()
            return
        }
        super.onBackPressed()
    }

    private fun renderLoadingShell() {
        if (hasRenderedUi) return
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(COLOR_BACKGROUND)
        }
        shell.addView(TextView(this).apply {
            text = "BKE DNA"
            textSize = 13f
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.DEFAULT_BOLD
        })
        shell.addView(TextView(this).apply {
            text = "Loading conversations…"
            textSize = 24f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(8))
        })
        shell.addView(TextView(this).apply {
            text = "DNA keeps processing in the background. ChatGPT stays the priority."
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
        })
        setContentView(shell)
    }

    private fun refreshUi() {
        val request = ++uiLoadGeneration
        val requestedInspectedId = inspectedWorkingDataId
        val requestedSearchQuery = searchQuery
        val requestedLibraryLimit = libraryLimit
        if (ioExecutor.isShutdown) return

        ioExecutor.execute {
            val result = runCatching {
                loadUiSnapshot(
                    requestedInspectedId = requestedInspectedId,
                    requestedSearchQuery = requestedSearchQuery,
                    requestedLibraryLimit = requestedLibraryLimit,
                )
            }
            runOnUiThread {
                if (request != uiLoadGeneration || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { snapshot ->
                        inspectedWorkingDataId = snapshot.inspected.id
                        renderUi(snapshot)
                    },
                    onFailure = { error -> renderLoadFailure(error) },
                )
            }
        }
    }

    private fun loadUiSnapshot(
        requestedInspectedId: String,
        requestedSearchQuery: String,
        requestedLibraryLimit: Int,
    ): UiSnapshot {
        val appContext = applicationContext
        val manager = AndroidWorkingDataManager(appContext)
        val generations = manager.listWorkingData()
        require(generations.isNotEmpty()) { "No Working Data generations are available" }
        val resolvedInspectedId = requestedInspectedId.takeIf { requested ->
            generations.any { it.id == requested }
        } ?: AndroidWorkingDataManager.LATEST_ID
        val inspected = generations.first { it.id == resolvedInspectedId }
        val totalWorkingBytes = AndroidWorkingStorage.workingBytes(appContext) + manager.savedWorkingDataBytes()
        val rawTelemetry = runCatching { AndroidWorkingDataTelemetry.inspect(inspected) }.getOrNull()
        val libraryResult = runCatching {
            AndroidUnifiedConversationLibrary(appContext).search(requestedSearchQuery, requestedLibraryLimit)
        }

        return UiSnapshot(
            generations = generations,
            inspected = inspected,
            currentProfile = AndroidDerivationScheduler.getProfile(appContext),
            totalWorkingBytes = totalWorkingBytes,
            storageWarning = DnaReconciliationContract.shouldNotifyStorage(totalWorkingBytes),
            rawTelemetry = rawTelemetry,
            conversations = libraryResult.getOrDefault(emptyList()),
            libraryError = libraryResult.exceptionOrNull()?.message,
            searchQuery = requestedSearchQuery,
            libraryLimit = requestedLibraryLimit,
        )
    }

    private fun renderLoadFailure(error: Throwable) {
        hasRenderedUi = true
        queueStatusView = null
        conversationCountView = null
        conversationListContainer = null
        renderedConversations = emptyList()
        renderedConversationQuery = ""
        renderedConversationLimit = 0
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(COLOR_BACKGROUND)
        }
        shell.addView(TextView(this).apply {
            text = "Unable to read Working Data"
            textSize = 22f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        })
        shell.addView(TextView(this).apply {
            text = error.message ?: "Unknown error"
            textSize = 14f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(16))
        })
        shell.addView(compactButton("RETRY") { refreshUi() })
        setContentView(shell)
    }

    private fun renderUi(snapshot: UiSnapshot) {
        hasRenderedUi = true
        val generations = snapshot.generations
        val inspected = snapshot.inspected

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(40))
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

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        header.addView(TextView(this).apply {
            text = "BKE DNA LOGGER"
            textSize = 12f
            setTextColor(COLOR_ACCENT)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        header.addView(TextView(this).apply {
            text = "Working Data & Conversations"
            textSize = 25f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(4))
        })
        header.addView(TextView(this).apply {
            text = "Browse normally. DNA can fall behind and catch up when the browser is quiet."
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
        })
        header.addView(compactRow(compactButton("← ChatGPT") { finish() }).apply {
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(header)

        val processingCard = cardContainer()
        processingCard.addView(cardEyebrow("Processing"))
        val queueHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queueHeader.addView(TextView(this).apply {
            text = "Background DNA"
            textSize = 18f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        queueHeader.addView(statusPill("Browser priority", COLOR_ACCENT_DARK, COLOR_ACCENT))
        processingCard.addView(queueHeader)
        queueStatusView = TextView(this).apply {
            text = "Reading queue…"
            textSize = 13f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(8), 0, dp(10))
        }
        processingCard.addView(queueStatusView)
        val currentProfile = snapshot.currentProfile
        processingCard.addView(
            compactRow(
                *AndroidProcessingProfile.entries.map { profile ->
                    compactButton(
                        if (profile == currentProfile) "✓ ${profileLabel(profile)}" else profileLabel(profile),
                    ) {
                        AndroidDerivationScheduler.setProfile(this, profile)
                        refreshUi()
                    }.apply {
                        isEnabled = profile != currentProfile
                        if (profile == currentProfile) {
                            background = roundedBackground(COLOR_ACCENT_DARK, COLOR_ACCENT, 10)
                            setTextColor(COLOR_ACCENT)
                        }
                    }
                }.toTypedArray(),
            ),
        )
        processingCard.addView(TextView(this).apply {
            text = "Slow gives ChatGPT the most breathing room. Balanced is the default. Fast shortens queue rest time. Capture always keeps priority."
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(processingCard)
        refreshQueueStatus()

        val workingCard = cardContainer()
        workingCard.addView(cardEyebrow("WORKING DATA"))
        workingCard.addView(TextView(this).apply {
            text = if (inspected.isLatest) "Latest · Active" else inspected.label
            textSize = 19f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        })

        val spinner = Spinner(this).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            generations.map { it.label },
        )
        spinner.setSelection(generations.indexOfFirst { it.id == inspectedWorkingDataId }, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (operationInProgress) return
                val chosen = generations[position].id
                if (chosen != inspectedWorkingDataId) {
                    inspectedWorkingDataId = chosen
                    refreshUi()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        workingCard.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })

        workingCard.addView(TextView(this).apply {
            text = if (inspected.isLatest) {
                "LIVE / WRITABLE · All new ChatGPT captures land here. Saved Working Data stays read-only."
            } else if (inspected.rawEvidenceIncluded) {
                "READ-ONLY · Self-contained recovery generation with SQLite RAW evidence."
            } else {
                "READ-ONLY · Legacy generation; RAW may still depend on pre-SQLite fallback evidence."
            }
            textSize = 13f
            setTextColor(if (inspected.isLatest) COLOR_ACCENT else COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(8))
        })

        if (inspected.isLatest) {
            workingCard.addView(
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
            workingCard.addView(TextView(this).apply {
                text = "SAVE NEW checkpoints verified SQLite/state and starts a fresh Latest. BACKUP writes a self-contained recovery copy. IMPORT accepts Working Data backups or conversation .dna files."
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(8), 0, 0)
            })
        } else {
            val backupButton = compactButton("BACKUP") { prepareWorkingDataBackup(inspected) }.apply {
                isEnabled = inspected.rawEvidenceIncluded
            }
            workingCard.addView(
                compactRow(
                    compactButton("LATEST") {
                        inspectedWorkingDataId = AndroidWorkingDataManager.LATEST_ID
                        refreshUi()
                    },
                    backupButton,
                    dangerButton("DELETE") { confirmDeleteGeneration(inspected) },
                ),
            )
            workingCard.addView(TextView(this).apply {
                text = if (inspected.rawEvidenceIncluded) {
                    "Saved Working Data is a self-contained read-only SQLite recovery generation. DELETE removes the whole saved generation after exact jan2x confirmation."
                } else {
                    "This pre-SQLite-RAW generation cannot produce a self-contained SQLite backup. It remains readable through legacy fallback and can still be deleted explicitly with jan2x."
                }
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(8), 0, 0)
            })
        }

        val totalWorkingBytes = snapshot.totalWorkingBytes
        val warning = snapshot.storageWarning
        val rawTelemetry = snapshot.rawTelemetry
        workingCard.addView(divider())
        workingCard.addView(TextView(this).apply {
            text = "${formatBytes(totalWorkingBytes)} local · ${formatBytes(inspected.snapshotBytes)} selected"
            textSize = 14f
            setTextColor(COLOR_TEXT_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        })
        rawTelemetry?.let { telemetry ->
            workingCard.addView(TextView(this).apply {
                text = "RAW ${formatBytes(telemetry.exactRawBytes)} exact · ${formatBytes(telemetry.compressedRawPayloadBytes)} compressed · ${telemetry.verifiedSourceCount} verified ${plural(telemetry.verifiedSourceCount, "source", "sources")}"
                textSize = 12f
                setTextColor(COLOR_TEXT_SECONDARY)
                setPadding(0, dp(4), 0, 0)
            })
        }
        workingCard.addView(TextView(this).apply {
            text = "${generations.size} Working Data ${plural(generations.size, "generation", "generations")} · storage warning at 1 GiB"
            textSize = 12f
            setTextColor(if (warning) COLOR_WARNING else COLOR_TEXT_MUTED)
            setPadding(0, dp(3), 0, 0)
        })
        if (warning) {
            workingCard.addView(statusPill("Storage above warning threshold", COLOR_WARNING_DARK, COLOR_WARNING).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = dp(8)
                layoutParams = params
            })
        }
        content.addView(workingCard)

        content.addView(sectionTitle("All Conversations"))
        conversationCountView = TextView(this).apply {
            text = conversationSummaryText(snapshot.conversations.size)
            textSize = 12f
            setTextColor(COLOR_TEXT_MUTED)
            setPadding(0, 0, 0, dp(10))
        }.also(content::addView)

        val searchCard = cardContainer(compact = true)
        val searchInput = EditText(this).apply {
            hint = "Search conversation titles"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setText(snapshot.searchQuery)
            setSelection(text.length)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchQuery = text.toString().trim()
                    libraryLimit = AndroidUnifiedConversationLibrary.DEFAULT_PAGE_SIZE
                    refreshUi()
                    true
                } else {
                    false
                }
            }
        }
        searchCard.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        searchCard.addView(
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
            ).apply { setPadding(0, dp(8), 0, 0) },
        )
        content.addView(searchCard)

        snapshot.libraryError?.let { error ->
            val errorCard = cardContainer(compact = true)
            errorCard.background = roundedBackground(COLOR_ERROR_DARK, COLOR_ERROR, 14)
            errorCard.addView(TextView(this).apply {
                text = "Unable to read unified library\n$error"
                textSize = 13f
                setTextColor(COLOR_ERROR_TEXT)
            })
            content.addView(errorCard)
        }

        val liveConversationContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        conversationListContainer = liveConversationContainer
        renderedConversations = snapshot.conversations
        renderedConversationQuery = snapshot.searchQuery
        renderedConversationLimit = snapshot.libraryLimit
        renderConversationList(
            container = liveConversationContainer,
            conversations = snapshot.conversations,
            requestedSearchQuery = snapshot.searchQuery,
            requestedLibraryLimit = snapshot.libraryLimit,
        )
        content.addView(liveConversationContainer)
    }

    private fun refreshConversationLibrary() {
        val targetContainer = conversationListContainer ?: return
        val targetCount = conversationCountView ?: return
        if (operationInProgress || libraryRefreshInFlight || ioExecutor.isShutdown) return

        val requestedSearchQuery = searchQuery
        val requestedLibraryLimit = libraryLimit
        val appContext = applicationContext
        libraryRefreshInFlight = true
        ioExecutor.execute {
            val result = runCatching {
                AndroidUnifiedConversationLibrary(appContext).search(
                    requestedSearchQuery,
                    requestedLibraryLimit,
                )
            }
            runOnUiThread {
                libraryRefreshInFlight = false
                if (isFinishing || isDestroyed || operationInProgress) return@runOnUiThread
                if (conversationListContainer !== targetContainer || conversationCountView !== targetCount) {
                    return@runOnUiThread
                }
                if (searchQuery != requestedSearchQuery || libraryLimit != requestedLibraryLimit) {
                    return@runOnUiThread
                }
                val conversations = result.getOrNull() ?: return@runOnUiThread
                if (
                    conversations == renderedConversations &&
                    requestedSearchQuery == renderedConversationQuery &&
                    requestedLibraryLimit == renderedConversationLimit
                ) {
                    return@runOnUiThread
                }

                renderedConversations = conversations
                renderedConversationQuery = requestedSearchQuery
                renderedConversationLimit = requestedLibraryLimit
                targetCount.text = conversationSummaryText(conversations.size)
                renderConversationList(
                    container = targetContainer,
                    conversations = conversations,
                    requestedSearchQuery = requestedSearchQuery,
                    requestedLibraryLimit = requestedLibraryLimit,
                )
            }
        }
    }

    private fun renderConversationList(
        container: LinearLayout,
        conversations: List<AndroidUnifiedConversationSummary>,
        requestedSearchQuery: String,
        requestedLibraryLimit: Int,
    ) {
        container.removeAllViews()
        if (conversations.isEmpty()) {
            val emptyCard = cardContainer()
            emptyCard.addView(TextView(this).apply {
                text = if (requestedSearchQuery.isBlank()) {
                    "No normalized conversations yet."
                } else {
                    "No conversations matched ‘$requestedSearchQuery’."
                }
                textSize = 16f
                setTextColor(COLOR_TEXT_PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
            })
            emptyCard.addView(TextView(this).apply {
                text = "DNA can keep processing in the background. You can return to ChatGPT at any time."
                textSize = 12f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(6), 0, 0)
            })
            container.addView(emptyCard)
        }

        conversations.forEach { summary ->
            val panel = cardContainer().apply {
                isClickable = true
                isFocusable = true
                contentDescription = "Read ${summary.displayTitle}"
                setOnClickListener { openConversation(summary) }
            }
            val titleRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            titleRow.addView(TextView(this).apply {
                text = summary.displayTitle
                textSize = 18f
                setTextColor(
                    if (summary.displayTitle == "Untitled conversation") COLOR_TEXT_SECONDARY else COLOR_TEXT_PRIMARY,
                )
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (summary.dnaArchived) {
                titleRow.addView(statusPill(".dna ✓", COLOR_ACCENT_DARK, COLOR_ACCENT))
            }
            panel.addView(titleRow)
            panel.addView(TextView(this).apply {
                text = summary.stateObservedThrough
                textSize = 11f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(4), 0, dp(8))
            })
            panel.addView(TextView(this).apply {
                text = buildString {
                    append("${summary.nodeCount} nodes")
                    append("  •  ${summary.sourceCount} ${plural(summary.sourceCount, "source", "sources")}")
                    append("  •  ${summary.generationCount} Working Data")
                }
                textSize = 13f
                setTextColor(COLOR_TEXT_SECONDARY)
            })
            panel.addView(statusPill(
                summary.coverageStatus,
                COLOR_NEUTRAL_PILL,
                COLOR_TEXT_SECONDARY,
            ).apply {
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                params.topMargin = dp(8)
                layoutParams = params
            })
            panel.addView(divider())

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
            panel.addView(TextView(this).apply {
                text = "Tap the card to read · CLEAN / RAW export · portable .dna export lives under MORE when Latest owns the conversation."
                textSize = 11f
                setTextColor(COLOR_TEXT_MUTED)
                setPadding(0, dp(7), 0, 0)
            })
            container.addView(panel)
        }

        if (
            conversations.size >= requestedLibraryLimit &&
            requestedLibraryLimit < AndroidUnifiedConversationLibrary.MAX_PAGE_SIZE
        ) {
            container.addView(compactRow(compactButton("LOAD MORE") {
                libraryLimit = (requestedLibraryLimit + AndroidUnifiedConversationLibrary.DEFAULT_PAGE_SIZE)
                    .coerceAtMost(AndroidUnifiedConversationLibrary.MAX_PAGE_SIZE)
                refreshUi()
            }).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(12))
            })
        }
    }

    private fun conversationSummaryText(count: Int): String =
        "$count shown · one deduplicated library across Latest + saved Working Data. Tap a conversation row to read it."

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
        if (queueRefreshInFlight || ioExecutor.isShutdown) return
        queueRefreshInFlight = true
        val appContext = applicationContext
        ioExecutor.execute {
            val text = runCatching { AndroidDerivationScheduler.snapshot(appContext) }.fold(
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
            runOnUiThread {
                queueRefreshInFlight = false
                if (isFinishing || isDestroyed || operationInProgress || queueStatusView !== target) {
                    return@runOnUiThread
                }
                target.text = text
            }
        }
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
        if (operationInProgress) {
            showOperationInProgress()
            return
        }
        operationInProgress = true
        refreshQueueStatus()
        if (ioExecutor.isShutdown) return
        ioExecutor.execute {
            val result = runCatching {
                val location = AndroidUnifiedConversationLibrary(applicationContext)
                    .resolve(summary.conversationNativeId)
                val verifiedGeneration = AndroidWorkingDataManager(applicationContext)
                    .generation(location.generation.id)
                val human = AndroidHumanExportService(applicationContext, verifiedGeneration.conversationStateDirectory)
                val descriptor = human.describe(location.conversationKey)
                HumanExportPreparation(
                    conversationKey = location.conversationKey,
                    workingDataId = verifiedGeneration.id,
                    fileName = "${descriptor.fileBase} - ${if (clean) "CLEAN" else "RAW"}.md",
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                operationInProgress = false
                result.fold(
                    onSuccess = { preparation ->
                        pendingConversationKey = preparation.conversationKey
                        pendingWorkingDataId = preparation.workingDataId
                        createDocument(
                            if (clean) REQUEST_EXPORT_CLEAN_MD else REQUEST_EXPORT_RAW_MD,
                            preparation.fileName,
                            "text/markdown",
                        )
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this,
                            "Unable to prepare export: ${error.message ?: "unknown error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
                refreshQueueStatus()
            }
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
        if (ioExecutor.isShutdown) return
        ioExecutor.execute {
            val result = runCatching {
                if (storageMutation) {
                    AndroidCaptureRuntime.withStorageMutationPause(this) { work() }
                } else {
                    work()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
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
        }
    }

    private fun sectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 21f
        setTextColor(COLOR_TEXT_PRIMARY)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), dp(10), 0, dp(8))
    }

    private fun cardEyebrow(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(COLOR_ACCENT)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding(0, 0, 0, dp(5))
    }

    private fun cardContainer(compact: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(if (compact) 12 else 15),
            dp(if (compact) 12 else 15),
            dp(if (compact) 12 else 15),
            dp(if (compact) 12 else 15),
        )
        background = roundedBackground(COLOR_CARD, COLOR_CARD_STROKE, 16)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(12)
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(COLOR_DIVIDER)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(12)
        }
    }

    private fun statusPill(label: String, fillColor: Int, textColor: Int): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(textColor)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(9), dp(4), dp(9), dp(4))
        background = roundedBackground(fillColor, fillColor, 99)
    }

    private fun compactRow(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            buttons.forEach { button ->
                addView(
                    button,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(7) },
                )
            }
        }

    private fun compactButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(COLOR_TEXT_PRIMARY)
        setAllCaps(false)
        typeface = Typeface.DEFAULT_BOLD
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        elevation = 0f
        setPadding(dp(13), dp(8), dp(13), dp(8))
        background = roundedBackground(COLOR_BUTTON, COLOR_BUTTON_STROKE, 10)
    }

    private fun compactButton(label: String, action: () -> Unit): Button =
        compactButton(label).apply {
            setOnClickListener {
                if (operationInProgress) showOperationInProgress() else action()
            }
        }

    private fun dangerButton(label: String, action: () -> Unit): Button =
        compactButton(label, action).apply {
            background = roundedBackground(COLOR_ERROR_DARK, COLOR_ERROR, 10)
            setTextColor(COLOR_ERROR_TEXT)
        }

    private fun roundedBackground(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), strokeColor)
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

    private fun plural(count: Int, singular: String, plural: String): String = if (count == 1) singular else plural

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class UiSnapshot(
        val generations: List<AndroidWorkingDataGeneration>,
        val inspected: AndroidWorkingDataGeneration,
        val currentProfile: AndroidProcessingProfile,
        val totalWorkingBytes: Long,
        val storageWarning: Boolean,
        val rawTelemetry: AndroidWorkingDataStorageTelemetry?,
        val conversations: List<AndroidUnifiedConversationSummary>,
        val libraryError: String?,
        val searchQuery: String,
        val libraryLimit: Int,
    )

    private data class HumanExportPreparation(
        val conversationKey: String,
        val workingDataId: String,
        val fileName: String,
    )

    companion object {
        private const val REQUEST_EXPORT_DNA = 1101
        private const val REQUEST_EXPORT_CLEAN_MD = 1102
        private const val REQUEST_BACKUP = 1103
        private const val REQUEST_IMPORT = 1104
        private const val REQUEST_EXPORT_RAW_MD = 1105
        private const val MENU_EXPORT_DNA = 2101
        private const val QUEUE_REFRESH_MS = 1_500L
        private const val LIBRARY_REFRESH_MS = 3_000L

        private val COLOR_BACKGROUND = Color.rgb(17, 20, 23)
        private val COLOR_CARD = Color.rgb(24, 29, 33)
        private val COLOR_CARD_STROKE = Color.rgb(49, 58, 65)
        private val COLOR_DIVIDER = Color.rgb(48, 57, 64)
        private val COLOR_BUTTON = Color.rgb(32, 38, 43)
        private val COLOR_BUTTON_STROKE = Color.rgb(57, 68, 77)
        private val COLOR_TEXT_PRIMARY = Color.rgb(242, 245, 247)
        private val COLOR_TEXT_SECONDARY = Color.rgb(180, 190, 198)
        private val COLOR_TEXT_MUTED = Color.rgb(132, 145, 155)
        private val COLOR_ACCENT = Color.rgb(121, 216, 196)
        private val COLOR_ACCENT_DARK = Color.rgb(25, 60, 55)
        private val COLOR_NEUTRAL_PILL = Color.rgb(37, 44, 49)
        private val COLOR_WARNING = Color.rgb(245, 190, 90)
        private val COLOR_WARNING_DARK = Color.rgb(74, 55, 24)
        private val COLOR_ERROR = Color.rgb(214, 82, 82)
        private val COLOR_ERROR_DARK = Color.rgb(76, 31, 31)
        private val COLOR_ERROR_TEXT = Color.rgb(255, 220, 220)
    }
}
