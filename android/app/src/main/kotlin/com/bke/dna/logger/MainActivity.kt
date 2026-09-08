package com.bke.dna.logger

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidDnaPaths.capturesRoot(this)

        setContentView(
            TextView(this).apply {
                text = "BKE DNA Logger\n${GeckoViewArtifactProbe.summary()}"
                setPadding(48, 48, 48, 48)
            },
        )
    }
}
