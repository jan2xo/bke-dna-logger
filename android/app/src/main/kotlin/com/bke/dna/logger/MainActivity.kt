package com.bke.dna.logger

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.LinearLayout
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    private lateinit var geckoView: GeckoView
    private lateinit var geckoHost: GeckoViewHost
    private var backInvokedCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AndroidDnaPaths.capturesRoot(this)
        AndroidDnaPaths.workingDataRoot(this)

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
            text = "Working Data & Exports"
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(18, 8, 18, 8)
            setOnClickListener {
                // Management is a sibling Activity. Keep the GeckoSession alive so
                // Back returns to the exact ChatGPT page/scroll/session instead of
                // constructing a new browser and loading chatgpt.com again.
                startActivity(Intent(this@MainActivity, AndroidExportsBackupsActivity::class.java))
            }
        }
        root.addView(
            exportsButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        geckoView = GeckoView(this)
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

        geckoHost = GeckoViewHost(this, geckoView) { canGoBack ->
            updatePredictiveBackRegistration(canGoBack)
        }
        geckoHost.start()
        AndroidDerivationScheduler.noteBrowserActivity()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) AndroidDerivationScheduler.noteBrowserActivity()
    }

    override fun onUserInteraction() {
        AndroidDerivationScheduler.noteBrowserActivity()
        super.onUserInteraction()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        AndroidDerivationScheduler.noteBrowserActivity()
        if (::geckoHost.isInitialized && geckoHost.goBackIfPossible()) return
        super.onBackPressed()
    }

    private fun updatePredictiveBackRegistration(canGoBack: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        if (canGoBack) {
            if (backInvokedCallback != null) return
            val callback = OnBackInvokedCallback {
                AndroidDerivationScheduler.noteBrowserActivity()
                val handled = ::geckoHost.isInitialized && geckoHost.goBackIfPossible()
                // A history-state callback can race a system back gesture. If Gecko
                // no longer has history, preserve root-Activity back behavior instead
                // of swallowing the gesture.
                if (!handled) moveTaskToBack(true)
            }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
            backInvokedCallback = callback
            return
        }

        backInvokedCallback?.let { callback ->
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            backInvokedCallback = null
        }
    }

    @Deprecated("Activity result API retained for GeckoView file-prompt compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::geckoHost.isInitialized) {
            geckoHost.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::geckoHost.isInitialized) {
            geckoHost.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onDestroy() {
        updatePredictiveBackRegistration(false)
        if (::geckoHost.isInitialized) {
            geckoHost.stop()
        }
        super.onDestroy()
    }
}
