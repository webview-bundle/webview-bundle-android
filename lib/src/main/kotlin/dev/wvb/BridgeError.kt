package dev.wvb

/**
 * A throwable that maps to the web-facing `{ code?, message }` bridge error shape.
 */
interface BridgeFailure {
    val code: String?
    val message: String
}

/** The canonical `{ code?, message }` error thrown to reject an `invoke()` command. */
class BridgeError(
    override val code: String? = null,
    override val message: String,
) : RuntimeException(message), BridgeFailure
