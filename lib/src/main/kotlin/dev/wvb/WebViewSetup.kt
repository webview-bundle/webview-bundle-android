package dev.wvb

import android.annotation.SuppressLint
import android.view.KeyEvent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature

/**
 * Configuration for [WebViewBundle.install], applied to the [WebView] before it loads.
 */
class InstallOptions {
    /** A [WebViewClient] whose callbacks are preserved; the bundle-serving client wraps it. */
    var delegate: WebViewClient? = null
    var applyRecommendedSettings: Boolean = true
    var javaScriptEnabled: Boolean = true
    var domStorageEnabled: Boolean = true
    var mixedContentMode: Int = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    var allowFileAccess: Boolean = false
    var allowContentAccess: Boolean = false

    /**
     * Enable `chrome://inspect`; gate on a debug build. Applied independently of
     * [applyRecommendedSettings], and only ever enables the process-global flag.
     */
    var webContentsDebuggingEnabled: Boolean = false

    /** Route Service Worker requests through the bundle (see [installServiceWorkerClient]). */
    var installServiceWorker: Boolean = true

    /**
     * Pop the WebView's history on the hardware BACK key, falling through to the
     * host when there is none. Installs an `OnKeyListener` (so a listener you set
     * yourself replaces it), independently of [applyRecommendedSettings], and fires
     * only for the legacy key event — not the Android 13+ predictive back gesture.
     */
    var handleBackKeyNavigation: Boolean = false

    /** Install the `window.wvbAndroid` [Bridge] interface (added even with no handlers). */
    var installBridge: Boolean = true

    /** Register native handlers for web-side `invoke()` calls; runs against a fresh [Bridge]. */
    var bridge: (Bridge.() -> Unit)? = null

    /** Final hook to customize the [WebView] after the options above are applied. */
    var configureWebView: ((WebView) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    internal fun applySettings(webView: WebView) {
        if (applyRecommendedSettings) {
            webView.settings.apply {
                javaScriptEnabled = this@InstallOptions.javaScriptEnabled
                domStorageEnabled = this@InstallOptions.domStorageEnabled
                mixedContentMode = this@InstallOptions.mixedContentMode
                allowFileAccess = this@InstallOptions.allowFileAccess
                allowContentAccess = this@InstallOptions.allowContentAccess
            }
        }
        // Process-global and only ever enabled, so it is honored even when the
        // recommended settings are skipped and never clobbers the host's value.
        if (webContentsDebuggingEnabled) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        if (handleBackKeyNavigation) {
            applyBackKeyNavigation(webView)
        }
    }

    private fun applyBackKeyNavigation(webView: WebView) {
        webView.setOnKeyListener { _, keyCode, event ->
            // Act on the key's release and consume it only when there is history to
            // pop, else let the press propagate to the host's back handling.
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isCanceled && webView.canGoBack()) {
                webView.goBack()
                true
            } else {
                false
            }
        }
    }
}

/**
 * Routes Service Worker network requests through this bundle's request handler.
 */
fun WebViewBundle.installServiceWorkerClient(): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) || !WebViewFeature.isFeatureSupported(
            WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
        )
    ) {
        return false
    }
    ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
        object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                handleRequest(request)
        },
    )
    return true
}
