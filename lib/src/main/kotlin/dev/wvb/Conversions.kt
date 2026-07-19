package dev.wvb

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Maps a [android.webkit.WebResourceRequest.getMethod] string to the FFI [HttpMethod],
 * defaulting to [HttpMethod.GET] for unknown or missing methods.
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
 * Splits a `Content-Type` value into a bare MIME type and a charset, which
 * [WebResourceResponse] takes as separate arguments.
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
 * A non-empty HTTP reason phrase for [status]; [WebResourceResponse] throws when
 * the reason phrase is empty.
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

/** The MIME type used when a response carries no usable `Content-Type`. */
private const val DEFAULT_MIME_TYPE = "application/octet-stream"

private const val HEADER_CONTENT_TYPE = "content-type"

private const val HEADER_CONTENT_LENGTH = "content-length"

/**
 * Headers dropped before handing the response to the WebView. `content-encoding`
 * is dropped because the WebView does not gunzip the stream and would corrupt an
 * already-decoded body; the rest are supplied separately or are hop-by-hop.
 */
private val STRIPPED_HEADERS = setOf(
    HEADER_CONTENT_TYPE,
    "content-encoding",
    "transfer-encoding",
    "connection",
)

/**
 * `true` for HTTP statuses [WebResourceResponse] can represent. Its constructor
 * throws for out-of-range codes and the redirect range `300..399` (including
 * `304`), so those are surfaced as a `502` rather than crashing the render thread.
 */
private fun isRepresentableStatus(code: Int): Boolean =
    code in 100..299 || code in 400..599

/**
 * Converts an FFI [HttpResponse] into a [WebResourceResponse] the WebView can
 * render, mapping statuses [WebResourceResponse] cannot represent to a `502`.
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
 * A plain-text error response surfaced to the WebView when a handler throws.
 * Returning a response rather than `null` keeps the failure inside the bundle
 * origin instead of leaking the request to the network.
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
