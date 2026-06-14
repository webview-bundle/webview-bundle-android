package dev.wvb.testapp

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import dev.wvb.WebViewBundle
import dev.wvb.WebViewBundleConfig
import dev.wvb.WebViewBundleProtocol

/**
 * Minimal WebView host for the E2E suite.
 *
 * It builds a [WebViewBundle], installs it on a full-screen [WebView], and loads
 * the builtin `hacker-news` bundle. The app carries no test logic of its own — all
 * scenarios live in the e2e suite (`@wvb-playground/webview-hacker-news`) and drive
 * this WebView through Appium's WEBVIEW context.
 */
class MainActivity : Activity() {

    private lateinit var wvb: WebViewBundle
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wvb = WebViewBundle(
            this,
            WebViewBundleConfig(
                protocols = listOf(WebViewBundleProtocol.bundle()),
                onError = { error -> Log.e(TAG, "handler error", error) },
            ),
        )

        webView = WebView(this)
        wvb.install(webView) {
            delegate = errorLoggingClient
            // Required so the e2e harness can attach via the WEBVIEW (chromedriver) context.
            webContentsDebuggingEnabled = true
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(webView, LinearLayout.LayoutParams(MATCH_PARENT, 0).apply { weight = 1f })
            },
        )

        Log.i(TAG, "loading $START_URL")
        webView.loadUrl(START_URL)
    }

    override fun onDestroy() {
        webView.destroy()
        wvb.close()
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
