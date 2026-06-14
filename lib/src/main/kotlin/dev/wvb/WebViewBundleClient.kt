package dev.wvb

import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.webkit.ClientCertRequest
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

/**
 * A [WebViewClient] that serves webview-bundle resources by intercepting requests
 * to the registered protocol hosts.
 *
 * Obtained via [WebViewBundle.install] or [WebViewBundle.createWebViewClient] —
 * never constructed directly. Requests the bundle owns are served from
 * [WebViewBundle.handleRequest]; unhandled requests and a fixed set of common
 * callbacks are forwarded to the optional [delegate]: `shouldInterceptRequest`,
 * `shouldOverrideUrlLoading`, `onPageStarted`, `onPageCommitVisible`,
 * `onPageFinished`, `onLoadResource`, `doUpdateVisitedHistory`, `onReceivedError`,
 * `onReceivedHttpError`, `onReceivedSslError`, `onReceivedHttpAuthRequest`,
 * `onReceivedClientCertRequest`, and `onRenderProcessGone`.
 *
 * Any **other** [WebViewClient] override on the delegate is not invoked — the
 * framework dispatches to this class's concrete methods. If you need one, implement
 * your own [WebViewClient] and call [WebViewBundle.handleRequest] from
 * `shouldInterceptRequest`.
 */
internal class WebViewBundleClient(
    private val owner: WebViewBundle,
    private val delegate: WebViewClient? = null,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? =
        owner.handleRequest(request) ?: delegate?.shouldInterceptRequest(view, request)

    // --- delegation of the commonly-overridden callbacks ---------------------
    // The framework dispatches to concrete overrides, so each forwarded callback
    // must be spelled out. The bundle itself only needs shouldInterceptRequest;
    // these simply preserve a wrapped client's behavior. The `delegate?.x() ?:
    // super.x()` form runs the delegate when present (its non-null/Unit result
    // suppresses super) and the default otherwise.

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        delegate?.shouldOverrideUrlLoading(view, request)
            ?: super.shouldOverrideUrlLoading(view, request)

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        delegate?.onPageStarted(view, url, favicon) ?: super.onPageStarted(view, url, favicon)
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        delegate?.onPageCommitVisible(view, url) ?: super.onPageCommitVisible(view, url)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        delegate?.onPageFinished(view, url) ?: super.onPageFinished(view, url)
    }

    override fun onLoadResource(view: WebView, url: String?) {
        delegate?.onLoadResource(view, url) ?: super.onLoadResource(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        delegate?.doUpdateVisitedHistory(view, url, isReload)
            ?: super.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        delegate?.onReceivedError(view, request, error) ?: super.onReceivedError(view, request, error)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        delegate?.onReceivedHttpError(view, request, errorResponse)
            ?: super.onReceivedHttpError(view, request, errorResponse)
    }

    // Security-sensitive callbacks: without forwarding, a delegate's TLS/auth
    // decision would be silently dropped and the (secure) default applied.

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        delegate?.onReceivedSslError(view, handler, error)
            ?: super.onReceivedSslError(view, handler, error)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String?,
        realm: String?,
    ) {
        delegate?.onReceivedHttpAuthRequest(view, handler, host, realm)
            ?: super.onReceivedHttpAuthRequest(view, handler, host, realm)
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        delegate?.onReceivedClientCertRequest(view, request)
            ?: super.onReceivedClientCertRequest(view, request)
    }

    // Boolean return: use an explicit null check, not elvis, so a delegate
    // returning `false` (handled, don't kill the process) is honored. The
    // framework only dispatches this on API 26+, so the API-26 calls are safe.
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        if (delegate != null) {
            delegate.onRenderProcessGone(view, detail)
        } else {
            super.onRenderProcessGone(view, detail)
        }
}
