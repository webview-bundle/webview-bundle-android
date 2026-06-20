package dev.wvb

import android.util.Log as AndroidLog

/**
 * Severity of an SDK log event; mirrors Rust `tracing` levels so core events can
 * forward into the same pipeline (see [CoreLog]).
 */
internal enum class LogLevel { TRACE, DEBUG, INFO, WARNING, ERROR }

/** Unified logcat channels. Filter on the `webview-bundle.*` tags. */
internal object Log {
    val bridge: Channel = Channel("bridge")
    val core: Channel = Channel("core")

    internal class Channel(category: String) {
        private val tag = "${WebViewBundle.LOG_SUBSYSTEM}.$category"

        fun error(message: String) = log(LogLevel.ERROR, message)

        fun warning(message: String) = log(LogLevel.WARNING, message)

        fun log(level: LogLevel, message: String) {
            when (level) {
                LogLevel.TRACE, LogLevel.DEBUG -> AndroidLog.d(tag, message)
                LogLevel.INFO -> AndroidLog.i(tag, message)
                LogLevel.WARNING -> AndroidLog.w(tag, message)
                LogLevel.ERROR -> AndroidLog.e(tag, message)
            }
        }
    }
}

/**
 * Seam for forwarding the Rust core's `tracing` into the unified `core` channel.
 * The level/route mapping is implemented; wiring it to an FFI log-subscriber
 * callback is pending (the FFI exposes none yet).
 */
internal object CoreLog {
    fun forward(level: LogLevel, target: String, message: String) {
        Log.core.log(level, "[$target] $message")
    }
}
