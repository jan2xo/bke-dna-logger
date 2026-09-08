package com.bke.dna.logger

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
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
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
                    ).top
                } else {
                    @Suppress("DEPRECATION")
                    val statusBarInset = insets.systemWindowInsetTop
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        @Suppress("DEPRECATION")
                        maxOf(statusBarInset, insets.displayCutout?.safeInsetTop ?: 0)
                    } else {
                        statusBarInset
                    }
                }

                view.setPadding(
                    view.paddingLeft,
                    topInset,
                    view.paddingRight,
                    view.paddingBottom,
                )
                insets
            }
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
        root.requestApplyInsets()

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
