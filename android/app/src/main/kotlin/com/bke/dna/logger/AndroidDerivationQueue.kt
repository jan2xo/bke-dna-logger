package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable processing queue stored inside the active Working Data SQLite.
 *
 * Capture and interactive GeckoView use are the priority lane. RAW SQLite ingest
 * and semantic derivation run on one Android background-priority thread, wait
 * for a browser quiet window before heavy work, and expose a cooperative yield
 * point for bounded inner loops such as normalized SQLite chunk publication.
 * Queue rows and completed RAW staging files survive process death, so DNA may
 * intentionally fall behind while the owner actively uses ChatGPT.
 */
object AndroidDerivationScheduler {
    private const val TAG = "BkeDnaQueue"
    private const val PREFS = "bke-dna-processing"
    private const val PREF_PROFILE = "profile"
    private const val BROWSER_QUIET_MS = 3_000L
    private const val BROWSER_QUIET_POLL_MS = 100L
    private val STAGED_RAW_NAME = Regex("[0-9a-f]{64}\\.raw")

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(
            {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            },
            "bke-dna-breathing-derivation",
        ).apply { isDaemon = true }
    }
    private val drainScheduled = AtomicBoolean(false)
    private val activeCaptures = AtomicInteger(0)
    private val lastBrowserActivityAt = AtomicLong(SystemClock.elapsedRealtime())
    private val startLock = Any()
    @Volatile private var recoveryInitialized = false

    fun start(context: Context) {
        val appContext = context.applicationContext
        synchronized(startLock) {
            AndroidDerivationQueueStore(appContext).use { store ->
                if (!recoveryInitialized) {
                    val recovered = store.recoverInterrupted()
                    if (recovered > 0) {
                        Log.d(TAG, "BKE DNA queue: recovered_interrupted")
                    }
                    recoveryInitialized = true
                } else {
                    store.ensureSchema()
                }
            }
            val staged = recoverStagedRaw(appContext)
            if (staged > 0) {
                Log.d(TAG, "BKE DNA queue: recovered_raw_staging")
            }
        }
        kick(appContext)
    }

    fun enqueue(
        context: Context,
        bodyFile: File,
        sourceSha256: String,
        byteLength: Long,
        contentType: String?,
    ) {
        val appContext = context.applicationContext
        val captureRoot = AndroidDnaPaths.capturesRoot(appContext)
        val relativePath = bodyFile.relativeTo(captureRoot).invariantSeparatorsPath
        AndroidDerivationQueueStore(appContext).use { store ->
            // A recapture of a SHA that was already DONE still has a fresh
            // durable staging file to verify/remove. Re-arm RAW_INGEST now
            // instead of leaving that staging file orphaned until restart.
            store.requeueForRawIngest(
                AndroidDerivationJob(
                    sourceSha256 = sourceSha256,
                    bodyPath = relativePath,
                    byteLength = byteLength,
                    contentType = contentType,
                ),
            )
        }
        kick(appContext)
    }

    /** Called by the browser Activity for touches/keys/resume and by capture. */
    fun noteBrowserActivity() {
        lastBrowserActivityAt.set(SystemClock.elapsedRealtime())
    }

    fun captureStarted() {
        noteBrowserActivity()
        activeCaptures.incrementAndGet()
    }

    fun captureFinished() {
        activeCaptures.updateAndGet { current -> if (current > 0) current - 1 else 0 }
        noteBrowserActivity()
    }

    /**
     * Cooperative inner-loop gate. Heavy derivation code may call this between
     * bounded units of work. It blocks only the DNA worker; browser/capture
     * threads are never blocked by this method.
     */
    fun yieldForBrowserActivity() {
        waitForBrowserQuiet()
        Thread.yield()
    }

    fun getProfile(context: Context): AndroidProcessingProfile {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_PROFILE, null)
        return AndroidProcessingProfile.entries.firstOrNull { it.name == raw }
            ?: AndroidProcessingProfile.BALANCED
    }

    fun setProfile(context: Context, profile: AndroidProcessingProfile) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_PROFILE, profile.name)
            .apply()
        kick(context.applicationContext)
    }

    fun snapshot(context: Context): AndroidDerivationQueueSnapshot =
        AndroidDerivationQueueStore(context.applicationContext).use { it.snapshot() }

    fun awaitIdle(context: Context) {
        val appContext = context.applicationContext
        start(appContext)
        val barrier = CountDownLatch(1)
        executor.execute { barrier.countDown() }
        barrier.await()
    }

    private fun recoverStagedRaw(context: Context): Int {
        val captureRoot = AndroidDnaPaths.capturesRoot(context)
        val stagingDirectory = File(captureRoot, "staging")
        val stagedFiles = stagingDirectory.listFiles().orEmpty()
            .filter { it.isFile && STAGED_RAW_NAME.matches(it.name) }
        if (stagedFiles.isEmpty()) return 0

        AndroidDerivationQueueStore(context).use { store ->
            stagedFiles.forEach { file ->
                val sourceSha256 = file.name.removeSuffix(".raw")
                store.requeueForRawIngest(
                    AndroidDerivationJob(
                        sourceSha256 = sourceSha256,
                        bodyPath = file.relativeTo(captureRoot).invariantSeparatorsPath,
                        byteLength = file.length(),
                        contentType = null,
                    ),
                )
            }
        }
        return stagedFiles.size
    }

    private fun kick(context: Context) {
        val appContext = context.applicationContext
        if (!drainScheduled.compareAndSet(false, true)) return
        executor.execute {
            try {
                drain(appContext)
            } finally {
                drainScheduled.set(false)
                val waiting = AndroidDerivationQueueStore(appContext).use { it.hasWaiting() }
                if (waiting) kick(appContext)
            }
        }
    }

    private fun drain(context: Context) {
        while (true) {
            waitForBrowserQuiet()
            val job = AndroidDerivationQueueStore(context).use { it.claimNext() } ?: return
            val stagedRaw = File(AndroidDnaPaths.capturesRoot(context), job.bodyPath)

            val rawReady = runCatching {
                waitForBrowserQuiet()
                AndroidDerivationQueueStore(context).use {
                    it.updateStage(job.sourceSha256, STAGE_RAW_INGEST)
                }
                AndroidRawEvidenceStore(context).use { rawStore ->
                    if (stagedRaw.isFile) {
                        rawStore.importVerified(
                            sourceSha256 = job.sourceSha256,
                            stagingFile = stagedRaw,
                            expectedByteLength = job.byteLength,
                        )
                    } else {
                        check(rawStore.contains(job.sourceSha256)) { "RAW staging and SQLite source are both missing" }
                        rawStore.verifySource(job.sourceSha256, job.byteLength)
                    }
                }
                if (stagedRaw.isFile && !stagedRaw.delete()) {
                    Log.d(TAG, "BKE DNA queue: raw_staging_cleanup_deferred")
                }
                true
            }.getOrElse {
                AndroidDerivationQueueStore(context).use { store ->
                    store.markFailed(job.sourceSha256, "raw_ingest_failed")
                }
                false
            }
            if (!rawReady) {
                breathe(context)
                continue
            }

            breathe(context)
            var firstStage = true
            val success = AndroidLiveDerivationPipeline(context).processCompletedCapture(
                sourceSha256 = job.sourceSha256,
                byteLength = job.byteLength,
                contentType = job.contentType,
                onStage = { stage ->
                    if (!firstStage) breathe(context)
                    waitForBrowserQuiet()
                    AndroidDerivationQueueStore(context).use {
                        it.updateStage(job.sourceSha256, stage)
                    }
                    firstStage = false
                },
            )

            waitForBrowserQuiet()
            AndroidDerivationQueueStore(context).use { store ->
                if (success) {
                    store.markDone(job.sourceSha256)
                } else {
                    store.markFailed(job.sourceSha256, "derivative_failed")
                }
            }
            breathe(context)
        }
    }

    private fun waitForBrowserQuiet() {
        while (true) {
            if (activeCaptures.get() > 0) {
                Thread.sleep(BROWSER_QUIET_POLL_MS)
                continue
            }

            val quietFor = SystemClock.elapsedRealtime() - lastBrowserActivityAt.get()
            if (quietFor >= BROWSER_QUIET_MS) return
            val remaining = BROWSER_QUIET_MS - quietFor
            Thread.sleep(minOf(BROWSER_QUIET_POLL_MS, remaining.coerceAtLeast(1L)))
        }
    }

    private fun breathe(context: Context) {
        waitForBrowserQuiet()
        val restMillis = getProfile(context).restMillis
        if (restMillis > 0) Thread.sleep(restMillis)
        // A browser interaction may have happened during the profile rest.
        waitForBrowserQuiet()
        Thread.yield()
    }

    const val STAGE_RAW_INGEST = "RAW_INGEST"
}

enum class AndroidProcessingProfile(val restMillis: Long) {
    SLOW(500L),
    BALANCED(150L),
    FAST(25L),
}

data class AndroidDerivationJob(
    val sourceSha256: String,
    val bodyPath: String,
    val byteLength: Long,
    val contentType: String?,
)

data class AndroidDerivationQueueSnapshot(
    val waiting: Int,
    val processing: Int,
    val done: Int,
    val failed: Int,
    val currentStage: String?,
)

private class AndroidDerivationQueueStore(context: Context) : AutoCloseable {
    private val index = AndroidCaptureIndex(context.applicationContext)
    private val db: SQLiteDatabase
        get() = index.writableDatabase

    init {
        ensureSchema()
    }

    fun ensureSchema() {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS derivation_queue (
                source_sha256 TEXT PRIMARY KEY,
                body_path TEXT NOT NULL,
                byte_length INTEGER NOT NULL,
                content_type TEXT,
                status TEXT NOT NULL,
                stage TEXT NOT NULL,
                priority INTEGER NOT NULL DEFAULT 0,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error_code TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS derivation_queue_status_idx " +
                "ON derivation_queue(status, priority DESC, created_at ASC)",
        )
    }

    fun enqueue(job: AndroidDerivationJob) {
        val now = Instant.now().toString()
        val values = ContentValues().apply {
            put("source_sha256", job.sourceSha256)
            put("body_path", job.bodyPath)
            put("byte_length", job.byteLength)
            put("content_type", job.contentType)
            put("status", STATUS_WAITING)
            put("stage", AndroidDerivationScheduler.STAGE_RAW_INGEST)
            put("priority", 0)
            put("attempts", 0)
            putNull("last_error_code")
            put("created_at", now)
            put("updated_at", now)
        }
        db.insertWithOnConflict(
            "derivation_queue",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun requeueForRawIngest(job: AndroidDerivationJob) {
        enqueue(job)
        val values = ContentValues().apply {
            put("body_path", job.bodyPath)
            put("byte_length", job.byteLength)
            if (job.contentType != null) put("content_type", job.contentType)
            put("status", STATUS_WAITING)
            put("stage", AndroidDerivationScheduler.STAGE_RAW_INGEST)
            putNull("last_error_code")
            put("updated_at", Instant.now().toString())
        }
        db.update(
            "derivation_queue",
            values,
            "source_sha256 = ? AND status IN (?, ?, ?)",
            arrayOf(job.sourceSha256, STATUS_WAITING, STATUS_DONE, STATUS_FAILED),
        )
    }

    fun recoverInterrupted(): Int {
        val values = ContentValues().apply {
            put("status", STATUS_WAITING)
            put("stage", STAGE_RECOVERED)
            put("updated_at", Instant.now().toString())
        }
        return db.update(
            "derivation_queue",
            values,
            "status = ?",
            arrayOf(STATUS_PROCESSING),
        )
    }

    fun claimNext(): AndroidDerivationJob? {
        db.beginTransaction()
        try {
            val cursor = db.query(
                "derivation_queue",
                arrayOf("source_sha256", "body_path", "byte_length", "content_type"),
                "status = ?",
                arrayOf(STATUS_WAITING),
                null,
                null,
                "priority DESC, created_at ASC",
                "1",
            )
            val job = cursor.use {
                if (!it.moveToFirst()) return null
                AndroidDerivationJob(
                    sourceSha256 = it.getString(0),
                    bodyPath = it.getString(1),
                    byteLength = it.getLong(2),
                    contentType = if (it.isNull(3)) null else it.getString(3),
                )
            }

            db.execSQL(
                "UPDATE derivation_queue " +
                    "SET status = ?, stage = ?, attempts = attempts + 1, updated_at = ? " +
                    "WHERE source_sha256 = ? AND status = ?",
                arrayOf(
                    STATUS_PROCESSING,
                    STAGE_STARTING,
                    Instant.now().toString(),
                    job.sourceSha256,
                    STATUS_WAITING,
                ),
            )
            db.setTransactionSuccessful()
            return job
        } finally {
            db.endTransaction()
        }
    }

    fun updateStage(sourceSha256: String, stage: String) {
        val values = ContentValues().apply {
            put("stage", stage)
            put("updated_at", Instant.now().toString())
        }
        db.update(
            "derivation_queue",
            values,
            "source_sha256 = ? AND status = ?",
            arrayOf(sourceSha256, STATUS_PROCESSING),
        )
    }

    fun markDone(sourceSha256: String) {
        val values = ContentValues().apply {
            put("status", STATUS_DONE)
            put("stage", STAGE_DONE)
            putNull("last_error_code")
            put("updated_at", Instant.now().toString())
        }
        db.update("derivation_queue", values, "source_sha256 = ?", arrayOf(sourceSha256))
    }

    fun markFailed(sourceSha256: String, errorCode: String) {
        val values = ContentValues().apply {
            put("status", STATUS_FAILED)
            put("stage", STAGE_FAILED)
            put("last_error_code", errorCode)
            put("updated_at", Instant.now().toString())
        }
        db.update("derivation_queue", values, "source_sha256 = ?", arrayOf(sourceSha256))
    }

    fun hasWaiting(): Boolean {
        db.rawQuery(
            "SELECT 1 FROM derivation_queue WHERE status = ? LIMIT 1",
            arrayOf(STATUS_WAITING),
        ).use { return it.moveToFirst() }
    }

    fun snapshot(): AndroidDerivationQueueSnapshot {
        var waiting = 0
        var processing = 0
        var done = 0
        var failed = 0
        var currentStage: String? = null
        db.rawQuery(
            "SELECT status, stage, COUNT(*) FROM derivation_queue GROUP BY status, stage",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val status = cursor.getString(0)
                val stage = cursor.getString(1)
                val count = cursor.getInt(2)
                when (status) {
                    STATUS_WAITING -> waiting += count
                    STATUS_PROCESSING -> {
                        processing += count
                        currentStage = stage
                    }
                    STATUS_DONE -> done += count
                    STATUS_FAILED -> failed += count
                }
            }
        }
        return AndroidDerivationQueueSnapshot(
            waiting = waiting,
            processing = processing,
            done = done,
            failed = failed,
            currentStage = currentStage,
        )
    }

    override fun close() {
        index.close()
    }

    companion object {
        private const val STATUS_WAITING = "WAITING"
        private const val STATUS_PROCESSING = "PROCESSING"
        private const val STATUS_DONE = "DONE"
        private const val STATUS_FAILED = "FAILED"

        private const val STAGE_RECOVERED = "RECOVERED"
        private const val STAGE_STARTING = "STARTING"
        private const val STAGE_DONE = "DONE"
        private const val STAGE_FAILED = "FAILED"
    }
}
