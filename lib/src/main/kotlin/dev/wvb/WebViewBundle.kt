package dev.wvb

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.wvb.WebViewBundle.Companion.getInstance
import kotlinx.coroutines.runBlocking

/** Remote endpoint configuration for [WebViewBundleUpdaterConfig]. */
data class WebViewBundleRemoteConfig(
    /** The base URL of the remote server, e.g. `"https://bundles.example.com"`. */
    val endpoint: String,
)

/**
 * Updater configuration for [WebViewBundleConfig]. When present, [WebViewBundle]
 * builds a [Remote] and [Updater] wired to the source, both exposed on the instance.
 */
data class WebViewBundleUpdaterConfig(
    val remote: WebViewBundleRemoteConfig,
    /** Release channel (e.g. `"stable"`, `"beta"`). */
    val channel: String? = null,
    val integrityPolicy: IntegrityPolicy? = null,
    val signatureVerifier: SignatureVerifierOptions? = null,
)

/**
 * Configuration for [WebViewBundle].
 *
 * @property protocols protocols to register, evaluated in order (first whose
 *   matcher accepts the host wins). [WebViewBundleProtocol.bundle] matches every
 *   host, so register it last.
 * @property source bundle source options; defaults to app-private directories.
 * @property updater when set, a [Remote] and [Updater] are created and exposed.
 * @property onError invoked on the WebView's request thread when a protocol fails
 *   to serve a request.
 */
class WebViewBundleConfig(
    val protocols: List<WebViewBundleProtocol>,
    val source: SourceOptions = SourceOptions(),
    val updater: WebViewBundleUpdaterConfig? = null,
    val onError: ((Throwable) -> Unit)? = null,
)

/** A registered protocol paired with its FFI request handler. */
private class RegisteredProtocol(
    val protocol: WebViewBundleProtocol,
    val handle: suspend (HttpMethod, String, Map<String, String>?) -> HttpResponse,
    val closeable: AutoCloseable,
)

/**
 * The primary class for webview-bundle API.
 */
class WebViewBundle private constructor(
    /** The bundle source requests are served from. */
    val source: BundleSource,
    /** The remote endpoint, when an [WebViewBundleUpdaterConfig] was provided. */
    val remote: Remote?,
    /** The updater, when an [WebViewBundleUpdaterConfig] was provided. */
    val updater: Updater?,
    private val protocols: List<RegisteredProtocol>,
    private val onError: ((Throwable) -> Unit)?,
) : AutoCloseable {

    /**
     * Resolves [request] against the registered protocols, or returns `null` when
     * no protocol owns the host (so the WebView loads it normally).
     *
     * Called from [WebViewClient.shouldInterceptRequest] on a background thread; a
     * handler failure is reported to [onError] and respond http response with `500` status.
     */
    fun handleRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        if (!url.scheme.equals("http", ignoreCase = true) ||
            !url.scheme.equals("https", ignoreCase = true)
        ) return null
        val rawHost = url.host ?: return null
        // Hosts are case-insensitive, but the bundle name / local lookup downstream
        // is not, so canonicalize the host (only) to lowercase before dispatch.
        val host = rawHost.lowercase()
        val registered = protocols.firstOrNull { it.protocol.matches(host) } ?: return null

        val method = httpMethodFrom(request.method)
        val headers = request.requestHeaders?.takeIf { it.isNotEmpty() }
        val uri = if (rawHost == host) {
            url.toString()
        } else {
            val authority = if (url.port >= 0) "$host:${url.port}" else host
            url.buildUpon().encodedAuthority(authority).build().toString()
        }

        return try {
            val response = runBlocking { registered.handle(method, uri, headers) }
            response.toWebResourceResponse()
        } catch (error: Exception) {
            // Only handler exceptions fail closed as a 500; fatal Errors (OOM, a
            // missing native library, …) propagate.
            val response = errorWebResourceResponse(error)
            runCatching { onError?.invoke(error) }
            response
        }
    }

    /**
     * A [WebViewClient] that serves the registered bundles, optionally forwarding
     * other callbacks to [delegate]. Low-level seam; [install] is the high-level entry.
     */
    fun createWebViewClient(delegate: WebViewClient? = null): WebViewClient =
        WebViewBundleClient(this, delegate)

    /**
     * Wires [webView] to serve the registered bundles: applies the recommended
     * settings, installs a bundle-serving [WebViewClient], routes Service Worker
     * requests through the bundle, and installs the `window.wvbAndroid` [Bridge].
     * Customize via the [configure] lambda (see [InstallOptions]).
     *
     * Returns an [AutoCloseable] that tears down the per-WebView bridge; close it
     * when the WebView is destroyed (alongside `webView.destroy()`) rather than
     * relying on [close]. A no-op when [InstallOptions.installBridge] is `false`.
     */
    fun install(webView: WebView, configure: InstallOptions.() -> Unit = {}): AutoCloseable {
        val options = InstallOptions().apply(configure)
        options.applySettings(webView)
        webView.webViewClient = createWebViewClient(options.delegate)
        if (options.installServiceWorker) {
            installServiceWorkerClient()
        }
        val bridgeHandle = if (options.installBridge) {
            Bridge().also { bridge ->
                options.bridge?.invoke(bridge)
                bridge.add(WebViewBundleBridge(this@WebViewBundle))
                bridge.attach(webView)
            }
        } else {
            AutoCloseable {}
        }
        options.configureWebView?.invoke(webView)
        return bridgeHandle
    }

    /**
     * Releases the source, remote, and updater handles and clears the process-wide
     * instance so a later [getInstance] rebuilds it. Rarely needed (app shutdown /
     * tests); does not touch per-WebView bridges.
     */
    override fun close() {
        synchronized(Companion) { if (instance === this) instance = null }
        protocols.forEach { runCatching { it.closeable.close() } }
        runCatching { updater?.close() }
        runCatching { remote?.close() }
        runCatching { source.close() }
    }

    companion object {
        /** The logcat tag prefix the SDK logs under; filter on `webview-bundle.*`. */
        const val LOG_SUBSYSTEM: String = "webview-bundle"

        @Volatile
        private var instance: WebViewBundle? = null

        /**
         * Returns the process-wide [WebViewBundle], building it from [config] on the
         * first call. Single-instance per process: [config] is honored only on the
         * first call; later calls return the existing instance and ignore it.
         *
         * @throws Exception if the source or a native handle cannot be built; any
         *   handles already allocated are released first.
         */
        fun getInstance(context: Context, config: WebViewBundleConfig): WebViewBundle =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext, config).also { instance = it }
            }

        /** Alias for [getInstance]; returns the shared instance, building it on first use. */
        operator fun invoke(context: Context, config: WebViewBundleConfig): WebViewBundle =
            getInstance(context, config)

        private fun create(context: Context, config: WebViewBundleConfig): WebViewBundle {
            val source = makeBundleSource(context, config.source)

            var remote: Remote? = null
            var updater: Updater? = null
            val registered = mutableListOf<RegisteredProtocol>()
            try {
                config.updater?.let { updaterConfig ->
                    val createdRemote = Remote(updaterConfig.remote.endpoint)
                    remote =
                        createdRemote // assign before Updater(), so a throw there still closes it
                    updater = Updater(
                        source,
                        createdRemote,
                        UpdaterOptions(
                            channel = updaterConfig.channel,
                            integrityPolicy = updaterConfig.integrityPolicy,
                            signatureVerifier = updaterConfig.signatureVerifier,
                        ),
                    )
                }

                config.protocols.forEach { protocol ->
                    registered += when (protocol) {
                        is WebViewBundleProtocol.Bundle -> {
                            val handler = BundleUrlHandler(source)
                            RegisteredProtocol(protocol, handler::handle, handler)
                        }

                        is WebViewBundleProtocol.Local -> {
                            // Lowercase the host keys to match the case-insensitive
                            // matcher and the lowercased request host.
                            val handler =
                                LocalUrlHandler(protocol.hosts.mapKeys { it.key.lowercase() })
                            RegisteredProtocol(protocol, handler::handle, handler)
                        }
                    }
                }
            } catch (error: Throwable) {
                registered.forEach { runCatching { it.closeable.close() } }
                runCatching { updater?.close() }
                runCatching { remote?.close() }
                runCatching { source.close() }
                throw error
            }

            return WebViewBundle(source, remote, updater, registered, config.onError)
        }
    }
}

/** Alias for [WebViewBundle.getInstance]; returns the shared instance, building it on first use. */
fun webViewBundle(context: Context, config: WebViewBundleConfig): WebViewBundle =
    getInstance(context, config)

/** Short alias for [webViewBundle]. */
fun wvb(context: Context, config: WebViewBundleConfig): WebViewBundle =
    getInstance(context, config)
