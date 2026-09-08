package com.bke.dna.logger

object DnaReconciliationContract {
    const val CONTRACT_ID = "bke-dna-reconciliation-v1"
    const val STORAGE_WARNING_BYTES = 1_073_741_824L
    const val ARCHIVE_SCOPE = "conversation"
    const val AUTOMATIC_DNA_EXPORT = false
    const val AUTOMATIC_MARKDOWN_EXPORT = false
    const val MERGE_SQLITE_ACROSS_DEVICES = false

    fun shouldNotifyStorage(workingBytes: Long): Boolean {
        require(workingBytes >= 0) { "workingBytes must be non-negative" }
        return workingBytes >= STORAGE_WARNING_BYTES
    }
}
