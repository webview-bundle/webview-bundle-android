package dev.wvb

/**
 * A throwable that maps to the web-facing `{ code?, message }` bridge error
 * shape. Implement it to deliver a `code` alongside the `message`; other thrown
 * errors are encoded with their message and no `code`.
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
