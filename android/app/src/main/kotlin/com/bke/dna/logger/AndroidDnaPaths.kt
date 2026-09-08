package com.bke.dna.logger

import android.content.Context
import java.io.File

object AndroidDnaPaths {
    fun capturesRoot(context: Context): File =
        File(context.filesDir, "dna/captures").also { root ->
            check(root.exists() || root.mkdirs()) {
                "Unable to create app-private DNA capture root"
            }
        }

    fun workingDataRoot(context: Context): File =
        File(context.filesDir, "dna/working-data").also { root ->
            check(root.exists() || root.mkdirs()) {
                "Unable to create app-private Working Data root"
            }
        }
}
