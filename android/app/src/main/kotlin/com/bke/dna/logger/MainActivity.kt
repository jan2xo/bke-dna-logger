package com.bke.dna.logger

import android.app.Activity
import android.os.Bundle
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    private lateinit var geckoHost: GeckoViewHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidDnaPaths.capturesRoot(this)

        val geckoView = GeckoView(this)
        setContentView(geckoView)

        geckoHost = GeckoViewHost(this, geckoView)
        geckoHost.start()
    }

    override fun onDestroy() {
        if (::geckoHost.isInitialized) {
            geckoHost.stop()
        }
        super.onDestroy()
    }
}
