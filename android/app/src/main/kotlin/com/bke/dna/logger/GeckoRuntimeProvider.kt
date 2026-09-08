package com.bke.dna.logger

import android.content.Context
import org.mozilla.geckoview.GeckoRuntime

/**
 * Gecko permits only one active runtime per Android process. Keep that runtime
 * bound to the application lifetime rather than recreating it with Activities.
 */
object GeckoRuntimeProvider {
    @Volatile
    private var instance: GeckoRuntime? = null

    fun get(context: Context): GeckoRuntime {
        instance?.let { return it }

        return synchronized(this) {
            instance ?: GeckoRuntime.create(context.applicationContext).also {
                instance = it
            }
        }
    }
}
