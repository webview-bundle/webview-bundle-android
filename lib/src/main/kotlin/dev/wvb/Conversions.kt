package dev.wvb

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Maps a [android.webkit.WebResourceRequest.getMethod] string to the FFI [HttpMethod].
 *
 * Defaults to [HttpMethod.GET] for unknown or missing methods, mirroring the
 * other platform integrations.
 */
internal fun httpMethodFrom(method: String?): HttpMethod =
    when (method?.uppercase()) {
        "GET" -> HttpMethod.GET
        "HEAD" -> HttpMethod.HEAD
        "OPTIONS" -> HttpMethod.OPTIONS
        "POST" -> HttpMethod.POST
        "PUT" -> HttpMethod.PUT
        "PATCH" -> HttpMethod.PATCH
        "DELETE" -> HttpMethod.DELETE
        "TRACE" -> HttpMethod.TRACE
        "CONNECT" -> HttpMethod.CONNECT
        else -> HttpMethod.GET
    }

/**
 * Splits a `Content-Type` value into a bare MIME type and a charset, e.g.
 * `"text/html; charset=utf-8"` -> `("text/html", "utf-8")`.
 *
 * [WebResourceResponse] takes the MIME type and encoding as separate arguments,
 * so the charset must be peeled off the `Content-Type` header.
 */
internal fun parseContentType(contentType: String?): Pair<String?, String?> {
    if (contentType.isNullOrBlank()) return null to null
    val parts = contentType.split(';')
    val mime = parts.first().trim().ifEmpty { null }
    var charset: String? = null
    for (i in 1 until parts.size) {
        val attr = parts[i].trim()
        if (attr.startsWith("charset=", ignoreCase = true)) {
            charset = attr.substring("charset=".length).trim().trim('"').ifEmpty { null }
        }
    }
    return mime to charset
}

/**
 * A non-empty HTTP reason phrase for [status].
 *
 * [WebResourceResponse] throws `IllegalArgumentException` when the reason phrase
 * is empty, so this never returns an empty string: unknown codes fall back to a
 * generic, code-derived phrase.
 */
internal fun reasonPhrase(status: Int): String =
    when (status) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        206 -> "Partial Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        307 -> "Temporary Redirect"
        308 -> "Permanent Redirect"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        406 -> "Not Acceptable"
        409 -> "Conflict"
        410 -> "Gone"
        416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status $status"
    }

/**
 * The MIME type used when a response carries no usable `Content-Type`. Bundle
 * entries always set one; this only guards the rare header-less case.
 */
private const val DEFAULT_MIME_TYPE = "application/octet-stream"

/** Header name (lowercased) for the `Content-Type` peeled into mime/encoding. */
private const val HEADER_CONTENT_TYPE = "content-type"

/** Header name (lowercased) reset to `0` for body-less responses. */
private const val HEADER_CONTENT_LENGTH = "content-length"

/**
 * Headers dropped before handing the response to the WebView (lowercased):
 * - `content-type` is supplied via the separate mime/encoding arguments;
 * - `content-encoding` would make the WebView expect to decode an
 *   already-decoded body (it does not gunzip the stream), corrupting rendering;
 * - `transfer-encoding`/`connection` are hop-by-hop framing headers.
 */
private val STRIPPED_HEADERS = setOf(
    HEADER_CONTENT_TYPE,
    "content-encoding",
    "transfer-encoding",
    "connection",
)

/**
 * `true` for HTTP statuses [WebResourceResponse] can represent.
 *
 * The constructor throws `IllegalArgumentException` for codes below `100`, above
 * `599`, or in the redirect range `300..399` (which includes `304 Not Modified`).
 * Such responses are surfaced as a `502` instead of crashing the render thread.
 */
private fun isRepresentableStatus(code: Int): Boolean =
    code in 100..299 || code in 400..599

/**
 * Converts an FFI [HttpResponse] into a [WebResourceResponse] that the WebView
 * can render.
 *
 * - Unrepresentable statuses (redirects/`304`, out-of-range) become a `502`; the
 *   reason phrase is always a non-empty ASCII string (both are crash sources).
 * - `Content-Type` is split into the MIME type and encoding arguments; it and the
 *   encoding/framing headers are dropped from [WebResourceResponse.responseHeaders].
 * - `Content-Length` is set authoritatively to the served byte count (never the
 *   handler's value), so a stale length — e.g. a HEAD `200` that kept the file
 *   size — cannot make the WebView hang waiting for bytes that never arrive.
 *   `Content-Range` is preserved verbatim, so `206` media seeking keeps working;
 *   `204`/`205`/`1xx` are served body-less.
 */
internal fun HttpResponse.toWebResourceResponse(): WebResourceResponse {
    val statusCode = status.toInt()
    if (!isRepresentableStatus(statusCode)) {
        return synthesizedResponse(502, "unsupported HTTP status $statusCode")
    }

    val contentType = headers.entries
        .firstOrNull { it.key.equals(HEADER_CONTENT_TYPE, ignoreCase = true) }
        ?.value
    val (mimeFromHeader, encoding) = parseContentType(contentType)
    val mimeType = mimeFromHeader ?: DEFAULT_MIME_TYPE

    // 204 No Content, 205 Reset Content, and 1xx informational responses cannot
    // carry a body (RFC 9110 §6.4.1 / §15.3.{5,6}).
    val bodyless = statusCode == 204 || statusCode == 205 || statusCode in 100..199
    val payload = if (bodyless) ByteArray(0) else body

    val responseHeaders = LinkedHashMap<String, String>()
    for ((key, value) in headers) {
        val lower = key.lowercase()
        if (lower in STRIPPED_HEADERS) continue
        if (lower == HEADER_CONTENT_LENGTH) continue // set authoritatively below
        responseHeaders[key] = value
    }
    responseHeaders["Content-Length"] = payload.size.toString()

    return WebResourceResponse(
        mimeType,
        encoding,
        statusCode,
        reasonPhrase(statusCode),
        responseHeaders,
        ByteArrayInputStream(payload),
    )
}

/**
 * A plain-text error response surfaced to the WebView when a handler throws,
 * mirroring the Tauri integration's 500 fallback. Returning a response (rather
 * than `null`) keeps the failure inside the bundle origin instead of leaking the
 * request to the network.
 */
internal fun errorWebResourceResponse(error: Throwable): WebResourceResponse =
    synthesizedResponse(500, "webview bundle error: ${error.message ?: error.toString()}")

/** A plain-text [WebResourceResponse] with [status] and [message] as the body. */
private fun synthesizedResponse(status: Int, message: String): WebResourceResponse =
    WebResourceResponse(
        "text/plain",
        "utf-8",
        status,
        reasonPhrase(status),
        LinkedHashMap(),
        ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
    )
