package dev.wvb

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature

/**
 * Configuration for [WebViewBundle.install], applied to the [WebView] before it
 * starts loading.
 *
 * Sensible defaults make a typical SPA bundle work out of the box; override any
 * field in the `install(webView) { ... }` lambda, or set
 * [applyRecommendedSettings] to `false` and configure the WebView yourself. The
 * [configureWebView] hook runs last as an escape hatch for anything not exposed
 * here.
 *
 * @property delegate a [WebViewClient] whose callbacks are preserved; the
 *   bundle-serving client wraps it.
 * @property applyRecommendedSettings apply the WebView settings below. Set `false`
 *   to leave [WebView.getSettings] untouched.
 * @property javaScriptEnabled SPA bundles need this; defaults `true` (the platform
 *   default is `false`).
 * @property domStorageEnabled enable DOM storage; defaults `true` (platform
 *   default is `false`).
 * @property mixedContentMode the bundle origin is always `https`, so the default
 *   forbids mixed content.
 * @property allowFileAccess / [allowContentAccess] hardened off by default.
 * @property webContentsDebuggingEnabled enable `chrome://inspect` process-wide;
 *   gate on a debug build.
 * @property installServiceWorker route Service Worker requests through the bundle
 *   (see [installServiceWorkerClient]); defaults `true`.
 * @property configureWebView final hook to customize the [WebView] after the
 *   options above are applied.
 */
public class InstallOptions {
    public var delegate: WebViewClient? = null
    public var applyRecommendedSettings: Boolean = true
    public var javaScriptEnabled: Boolean = true
    public var domStorageEnabled: Boolean = true
    public var mixedContentMode: Int = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    public var allowFileAccess: Boolean = false
    public var allowContentAccess: Boolean = false
    public var webContentsDebuggingEnabled: Boolean = false
    public var installServiceWorker: Boolean = true
    public var configureWebView: ((WebView) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    internal fun applySettings(webView: WebView) {
        if (!applyRecommendedSettings) return
        webView.settings.apply {
            javaScriptEnabled = this@InstallOptions.javaScriptEnabled
            domStorageEnabled = this@InstallOptions.domStorageEnabled
            mixedContentMode = this@InstallOptions.mixedContentMode
            allowFileAccess = this@InstallOptions.allowFileAccess
            allowContentAccess = this@InstallOptions.allowContentAccess
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }
        if (webContentsDebuggingEnabled) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
}

/**
 * Routes Service Worker network requests through this bundle's request handler.
 *
 * Service Workers fetch resources off the document's network stack, so they
 * **bypass** [android.webkit.WebViewClient.shouldInterceptRequest]: a bundle that
 * registers a Service Worker would otherwise see its worker requests escape to the
 * network. This installs an `androidx.webkit` Service Worker client that delegates
 * to [WebViewBundle.handleRequest], mirroring the main interceptor.
 *
 * Called automatically by [WebViewBundle.install] unless
 * [InstallOptions.installServiceWorker] is `false`. The Service Worker controller
 * is **process-global**: this replaces any Service Worker client previously set in
 * the process, so the last instance to install wins. Returns `true` when
 * installed, or `false` when the WebView implementation does not support Service
 * Worker interception.
 */
public fun WebViewBundle.installServiceWorkerClient(): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
        !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)
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
