package dev.wvb

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The native end of the `@wvb/bridge` `invoke()` bridge: the `window.wvbAndroid`
 * object the web side posts `{ name, params, success, error }` messages to. It
 * dispatches to a registered [handler] and replies via the JS success/error
 * callbacks.
 */
class Bridge internal constructor() : AutoCloseable {
    // Concurrent: registered on the UI thread, read from the background dispatch.
    private val handlers = ConcurrentHashMap<String, suspend (params: Any?) -> Any?>()

    // Handlers run on this background scope regardless of the delivering thread;
    // only the final evaluateJavascript reply hops to the UI thread.
    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    private var webView: WebView? = null

    /**
     * Registers a handler for the `invoke()` command [name], replacing any existing one.
     *
     * [block] receives the decoded `params` (an `org.json` value or `null`), may
     * suspend, and returns a JSON-encodable value. Throwing rejects the `Promise`
     * with `{ code?, message }`; throw a [BridgeError] to control the `code`.
     */
    @Suppress("unused")
    fun handler(name: String, block: suspend (params: Any?) -> Any?): Bridge {
        handlers[name] = block
        return this
    }

    /** Adds a reusable group of commands. See [BridgeHandlers]. */
    fun add(handlers: BridgeHandlers): Bridge {
        handlers.register(this)
        return this
    }

    internal fun attach(webView: WebView) {
        this.webView = webView
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView, INTERFACE_NAME, setOf("*"),
            ) { _, message, _, isMainFrame, _ ->
                if (!isMainFrame) {
                    Log.bridge.error("invoke message dropped: not from the main frame")
                } else {
                    message.data?.let { dispatch(it) }
                }
            }
        } else {
            Log.bridge.warning(
                "bridge not attached: this WebView does not supports WEB_MESSAGE_LISTENER " +
                        "(needs a modern WebView / Chrome 88+)",
            )
        }
    }

    /**
     * Cancels in-flight dispatches and detaches from the WebView. Returned from
     * [WebViewBundle.install]; call it when the WebView is destroyed.
     */
    override fun close() {
        scope.cancel()
        webView = null
    }

    private fun dispatch(message: String) {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: run {
            Log.bridge.error("invoke message dropped: malformed JSON")
            return
        }
        val name = json.optString("name")
        val success = json.optString("success")
        val error = json.optString("error")
        // No callback to reply through: the message is dropped and the web Promise
        // hangs with no other signal, so log it loudly.
        if (success.isEmpty() || error.isEmpty()) {
            Log.bridge.error(
                "invoke message dropped: missing success/error callback " +
                    "(command: ${name.ifEmpty { "<unknown>" }})",
            )
            return
        }
        val params = json.opt("params")?.takeUnless { it === JSONObject.NULL }
        scope.launch {
            val (callback, arg) = try {
                val handler = handlers[name]
                    ?: throw BridgeError(
                        code = "handler_not_found",
                        message = "no invoke handler registered for \"$name\"",
                    )
                success to encodeValue(handler(params))
            } catch (failure: Throwable) {
                error to encodeError(failure)
            }
            reply(name, callback, arg)
        }
    }

    private fun reply(name: String, callback: String, jsonArg: String) {
        val webView = webView ?: return
        val js = escapeForJs("$callback($jsonArg)")
        webView.post {
            runCatching { webView.evaluateJavascript(js, null) }
                .onFailure {
                    // Usually benign (the page navigated), so a warning, not an error.
                    Log.bridge.warning("invoke reply eval failed (command: $name): ${it.message}")
                }
        }
    }

    internal companion object {
        const val INTERFACE_NAME = "wvbAndroid"

        // U+2028/U+2029 are valid in JSON strings but are JS line terminators that
        // would break the `callback(<json>)` we evaluate, so escape them.
        val LINE_SEPARATOR: String = Char(0x2028).toString()
        val PARAGRAPH_SEPARATOR: String = Char(0x2029).toString()

        fun encodeValue(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject, is JSONArray -> value.toString()
            is String -> JSONObject.quote(value)
            is Boolean -> value.toString()
            is Double -> if (value.isFinite()) value.toString() else "null"
            is Float -> if (value.isFinite()) value.toString() else "null"
            is Number -> value.toString()
            is Map<*, *> -> JSONObject(value).toString()
            is Collection<*> -> JSONArray(value).toString()
            is Array<*> -> JSONArray(value.asList()).toString()
            else -> throw BridgeError(
                code = "unencodable_result",
                message = "invoke handler returned a value that is not JSON-encodable: ${value::class.java.name}",
            )
        }

        fun encodeError(error: Throwable): String {
            val json = JSONObject().put("message", error.message ?: error.toString())
            (error as? BridgeFailure)?.code?.let { json.put("code", it) }
            return json.toString()
        }

        fun escapeForJs(js: String): String =
            js.replace(LINE_SEPARATOR, "\\u2028").replace(PARAGRAPH_SEPARATOR, "\\u2029")
    }
}

/** A reusable, self-contained group of `invoke()` commands, added via [Bridge.add]. */
interface BridgeHandlers {
    /** Registers this group's commands on [bridge]. */
    fun register(bridge: Bridge)
}
