package com.bke.dna.logger

import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

/**
 * Compile-time certification seam for POC-9.
 *
 * This deliberately references only the small GeckoView surface BKE needs next.
 * It does not create a runtime/session yet; that belongs to the next stacked gate.
 */
object GeckoViewArtifactProbe {
    fun summary(): String = listOf(
        GeckoRuntime::class.java.name,
        GeckoSession::class.java.name,
        GeckoView::class.java.name,
        WebExtension::class.java.name,
    ).joinToString(prefix = "GeckoView linked: ", separator = ", ")
}
