package dev.wvb.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import dev.wvb.WebViewBundle
import dev.wvb.WebViewBundleConfig
import dev.wvb.WebViewBundleProtocol

class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wvb = WebViewBundle.getInstance(
            this,
            WebViewBundleConfig(
                protocols = listOf(WebViewBundleProtocol.bundle()),
                onError = { error -> Log.e(TAG, "handler error", error) },
            ),
        )

        webView = WebView(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
        }
        WebView.setWebContentsDebuggingEnabled(true)
        wvb.install(webView) {
            delegate = errorLoggingClient
        }

        // Navigate to -1 when back button clicked.
        webView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isCanceled && webView.canGoBack()) {
                webView.goBack()
                true
            } else {
                false
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f })
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val safe = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                WindowInsets.CONSUMED
            }
        }

        Log.i(TAG, "loading $START_URL")
        webView.loadUrl(START_URL)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private val errorLoggingClient = object : WebViewClient() {
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            Log.e(TAG, "onReceivedError ${request.url} -> ${error.errorCode} ${error.description}")
        }
    }

    private companion object {
        const val TAG = "WvbTestApp"
        const val START_URL = "https://hacker-news.wvb/"
    }
}
