package dev.wvb

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.runBlocking

/** Remote endpoint configuration for [WebViewBundleUpdaterConfig]. */
public data class WebViewBundleRemoteConfig(
    /** The base URL of the remote server, e.g. `"https://bundles.example.com"`. */
    val endpoint: String,
)

/**
 * Updater configuration for [WebViewBundleConfig].
 *
 * When present, [WebViewBundle] builds a [Remote] from [remote] and an [Updater]
 * wired to the source, both exposed on the instance.
 */
public data class WebViewBundleUpdaterConfig(
    val remote: WebViewBundleRemoteConfig,
    /** Release channel (e.g. `"stable"`, `"beta"`). */
    val channel: String? = null,
    val integrityPolicy: IntegrityPolicy? = null,
    val signatureVerifier: SignatureVerifierOptions? = null,
)

/**
 * High-level configuration for [WebViewBundle].
 *
 * @property protocols the protocols to register, evaluated in order (first whose
 *   matcher accepts the host serves the request). [WebViewBundleProtocol.bundle]
 *   matches every host, so register it last. See [WebViewBundleProtocol.bundle]
 *   and [WebViewBundleProtocol.local].
 * @property source bundle source options. Defaults to app-private directories and
 *   extracts builtin bundles from the APK `assets`.
 * @property updater when set, a [Remote] and [Updater] are created and exposed.
 * @property onError invoked when a registered protocol fails to serve a request;
 *   called on the WebView's request thread.
 */
// Not a `data class`: the function-typed [onError] has reference identity, which
// would give generated equals/hashCode/copy surprising, unstable semantics.
public class WebViewBundleConfig(
    public val protocols: List<WebViewBundleProtocol>,
    public val source: SourceOptions = SourceOptions(),
    public val updater: WebViewBundleUpdaterConfig? = null,
    public val onError: ((Throwable) -> Unit)? = null,
)

/** A registered protocol paired with its FFI request handler. */
private class RegisteredProtocol(
    val protocol: WebViewBundleProtocol,
    val handle: suspend (HttpMethod, String, Map<String, String>?) -> HttpResponse,
    val closeable: AutoCloseable,
)

/**
 * Serves webview-bundle resources to an Android [WebView].
 *
 * Android `WebView` only treats `https` origins as first-class, so — unlike the
 * iOS integration's custom URL scheme — requests are intercepted by **host**:
 * each [WebViewBundleProtocol] owns a set of `https` hosts, and a matching
 * request is resolved from the bundle [source] (or proxied to a local server)
 * instead of hitting the network. The bundle name is the first label of the
 * request host, e.g. `https://app.wvb/index.html` -> bundle `app`.
 *
 * ```kotlin
 * val wvb = WebViewBundle(context, WebViewBundleConfig(
 *     protocols = listOf(WebViewBundleProtocol.bundle()),
 * ))
 * wvb.install(webView)
 * webView.loadUrl("https://app.wvb/")
 * ```
 *
 * Keep a reference for the lifetime of the web view; it owns the native handlers.
 * Tear down in order — `webView.destroy()` first, then [close] — to release the
 * underlying FFI resources.
 */
public class WebViewBundle private constructor(
    /** The bundle source requests are served from. */
    public val source: BundleSource,
    /** The remote endpoint, when an [WebViewBundleUpdaterConfig] was provided. */
    public val remote: Remote?,
    /** The updater, when an [WebViewBundleUpdaterConfig] was provided. */
    public val updater: Updater?,
    private val protocols: List<RegisteredProtocol>,
    private val onError: ((Throwable) -> Unit)?,
) : AutoCloseable {

    /**
     * Resolves [request] against the registered protocols, or returns `null` when
     * no protocol owns the request host (so the WebView loads it normally).
     *
     * Intended to be called from [WebViewClient.shouldInterceptRequest], which the
     * WebView invokes on a background thread; this blocks on the suspending FFI
     * handler. A handler failure is reported to [onError] and surfaced as a `500`
     * response rather than leaking the request to the network.
     */
    public fun handleRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url ?: return null
        if (!url.scheme.equals("https", ignoreCase = true)) return null
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
        } catch (error: Throwable) {
            // Build the fail-closed response first, then report: a throwing
            // onError must never suppress it or escape onto the WebView thread.
            val response = errorWebResourceResponse(error)
            runCatching { onError?.invoke(error) }
            response
        }
    }

    /**
     * A [WebViewClient] that serves the registered bundles, optionally forwarding
     * its other callbacks to [delegate]. Assign it to [WebView.setWebViewClient].
     *
     * Low-level seam for callers that manage the [WebView] themselves; [install]
     * is the high-level entry point.
     */
    public fun createWebViewClient(delegate: WebViewClient? = null): WebViewClient =
        WebViewBundleClient(this, delegate)

    /**
     * Wires [webView] to serve the registered bundles.
     *
     * By default this applies the recommended WebView settings (JavaScript, DOM
     * storage, mixed-content and file-access hardening), installs a bundle-serving
     * [WebViewClient], and routes Service Worker requests through the bundle.
     * Customize or opt out of any of these via the [configure] lambda — see
     * [InstallOptions]:
     *
     * ```kotlin
     * wvb.install(webView)                       // sensible defaults
     * wvb.install(webView) {
     *     delegate = myWebViewClient
     *     webContentsDebuggingEnabled = BuildConfig.DEBUG
     *     installServiceWorker = false
     *     configureWebView = { it.settings.userAgentString = "MyApp" }
     * }
     * ```
     */
    public fun install(webView: WebView, configure: InstallOptions.() -> Unit = {}) {
        val options = InstallOptions().apply(configure)
        options.applySettings(webView)
        webView.webViewClient = createWebViewClient(options.delegate)
        if (options.installServiceWorker) {
            installServiceWorkerClient()
        }
        options.configureWebView?.invoke(webView)
    }

    /**
     * Releases the native handlers and the source, remote, and updater handles.
     *
     * Call after `webView.destroy()` has returned, so the WebView can no longer
     * dispatch [handleRequest]. Closing while a request is still in flight is safe
     * — no use-after-free — but the racing request fails closed with a `500`
     * rather than serving the resource, because its FFI handle is destroyed.
     */
    override fun close() {
        protocols.forEach { runCatching { it.closeable.close() } }
        runCatching { updater?.close() }
        runCatching { remote?.close() }
        runCatching { source.close() }
    }

    public companion object {
        /**
         * Builds a [WebViewBundle] from a high-level [WebViewBundleConfig].
         *
         * @throws Exception if the source cannot be built (e.g. builtin assets fail
         *   to extract) or a native handle cannot be created (e.g. an invalid remote
         *   endpoint); any handles already allocated are released before the
         *   exception propagates.
         */
        public operator fun invoke(context: Context, config: WebViewBundleConfig): WebViewBundle {
            val source = makeBundleSource(context, config.source)

            var remote: Remote? = null
            var updater: Updater? = null
            val registered = mutableListOf<RegisteredProtocol>()
            try {
                config.updater?.let { updaterConfig ->
                    val createdRemote = Remote(updaterConfig.remote.endpoint)
                    remote = createdRemote // assign before Updater(), so a throw there still closes it
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
                            val handler = LocalUrlHandler(protocol.hosts.mapKeys { it.key.lowercase() })
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

/** Builds a [WebViewBundle] from a high-level [WebViewBundleConfig]. */
public fun webViewBundle(context: Context, config: WebViewBundleConfig): WebViewBundle =
    WebViewBundle(context, config)

/** Short alias for [webViewBundle]. */
public fun wvb(context: Context, config: WebViewBundleConfig): WebViewBundle =
    WebViewBundle(context, config)
