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
        delegate?.onReceivedError(view, request, error) ?: super.onReceivedError(
            view,
            request,
            error
        )
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

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean =
        delegate?.onRenderProcessGone(view, detail) ?: super.onRenderProcessGone(view, detail)
}
