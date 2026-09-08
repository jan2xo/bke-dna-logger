package com.bke.dna.logger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Durable processing queue stored inside the active Working Data SQLite.
 *
 * Capture remains the priority lane. Semantic derivation is deliberately
 * serialized and yields between stages/jobs so GeckoView and the UI get time
 * to breathe. Queue rows survive process death and interrupted PROCESSING rows
 * are returned to WAITING when the runtime starts again.
 */
object AndroidDerivationScheduler {
    private const val TAG = "BkeDnaQueue"
    private const val PREFS = "bke-dna-processing"
    private const val PREF_PROFILE = "profile"
    private const val CAPTURE_QUIET_POLL_MS = 50L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bke-dna-breathing-derivation").apply { isDaemon = true }
    }
    private val drainScheduled = AtomicBoolean(false)
    private val activeCaptures = AtomicInteger(0)
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
            store.enqueue(
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

    fun captureStarted() {
        activeCaptures.incrementAndGet()
    }

    fun captureFinished() {
        activeCaptures.updateAndGet { current -> if (current > 0) current - 1 else 0 }
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

    /**
     * Storage mutations call this after capture ingress is paused. The single
     * executor barrier runs only after the current queue drain has finished.
     */
    fun awaitIdle(context: Context) {
        val appContext = context.applicationContext
        start(appContext)
        val barrier = CountDownLatch(1)
        executor.execute { barrier.countDown() }
        barrier.await()
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
            val job = AndroidDerivationQueueStore(context).use { it.claimNext() } ?: return
            val bodyFile = File(AndroidDnaPaths.capturesRoot(context), job.bodyPath)
            if (!bodyFile.isFile) {
                AndroidDerivationQueueStore(context).use {
                    it.markFailed(job.sourceSha256, "missing_raw_body")
                }
                continue
            }

            var firstStage = true
            val success = AndroidLiveDerivationPipeline(context).processCompletedCapture(
                bodyFile = bodyFile,
                sourceSha256 = job.sourceSha256,
                byteLength = job.byteLength,
                contentType = job.contentType,
                onStage = { stage ->
                    if (!firstStage) breathe(context)
                    waitForCaptureQuiet()
                    AndroidDerivationQueueStore(context).use {
                        it.updateStage(job.sourceSha256, stage)
                    }
                    firstStage = false
                },
            )

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

    private fun waitForCaptureQuiet() {
        while (activeCaptures.get() > 0) {
            Thread.sleep(CAPTURE_QUIET_POLL_MS)
        }
    }

    private fun breathe(context: Context) {
        waitForCaptureQuiet()
        val restMillis = getProfile(context).restMillis
        if (restMillis > 0) Thread.sleep(restMillis)
        Thread.yield()
    }
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

/** SQLite access for the derivation queue. Uses the same Working Data DB. */
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
            put("stage", STAGE_QUEUED)
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

            val values = ContentValues().apply {
                put("status", STATUS_PROCESSING)
                put("stage", STAGE_STARTING)
                put("updated_at", Instant.now().toString())
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

        private const val STAGE_QUEUED = "QUEUED"
        private const val STAGE_RECOVERED = "RECOVERED"
        private const val STAGE_STARTING = "STARTING"
        private const val STAGE_DONE = "DONE"
        private const val STAGE_FAILED = "FAILED"
    }
}
