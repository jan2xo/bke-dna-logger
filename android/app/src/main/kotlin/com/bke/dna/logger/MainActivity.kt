package com.bke.dna.logger

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    private lateinit var geckoHost: GeckoViewHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidDnaPaths.capturesRoot(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val exportsButton = Button(this).apply {
            text = "Exports & Backups"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AndroidExportsBackupsActivity::class.java))
            }
        }
        root.addView(
            exportsButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val geckoView = GeckoView(this)
        root.addView(
            geckoView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        setContentView(root)

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
