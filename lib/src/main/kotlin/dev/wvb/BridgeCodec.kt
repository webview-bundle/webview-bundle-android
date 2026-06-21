package dev.wvb

import org.json.JSONObject

/** Decodes `invoke()` params into typed values. */
internal object BridgeCodec {
    /** The params object, or an empty one when absent. */
    fun params(raw: Any?): JSONObject = raw as? JSONObject ?: JSONObject()
}

/** A required string param; throws [BridgeError] (`invalid_params`) if missing. */
internal fun JSONObject.requireString(key: String): String {
    if (!has(key) || isNull(key)) {
        throw BridgeError(code = "invalid_params", message = "expected string param \"$key\"")
    }
    return getString(key)
}

/** An optional string param; `null` when absent or JSON `null`. */
internal fun JSONObject.optionalString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)

// Response payloads. These mirror the JSON the web side consumes from the
// Electron bridge (`@wvb/node`): the source kind is keyed `type` on the wire
// though the FFI names it `kind`, and nullable fields are omitted (putOpt) to
// match the TS `?` optionals.

private fun BundleSourceKind.bridgeValue(): String = when (this) {
    BundleSourceKind.BUILTIN -> "builtin"
    BundleSourceKind.REMOTE -> "remote"
}

internal fun BundleManifestMetadata.toJson(): JSONObject =
    JSONObject()
        .putOpt("etag", etag)
        .putOpt("integrity", integrity)
        .putOpt("signature", signature)
        .putOpt("lastModified", lastModified)

internal fun BundleSourceVersion.toJson(): JSONObject =
    JSONObject().put("type", kind.bridgeValue()).put("version", version)

internal fun ListBundleItem.toJson(): JSONObject =
    JSONObject()
        .put("type", kind.bridgeValue())
        .put("name", name)
        .put("version", version)
        .put("current", current)
        .put("metadata", metadata.toJson())

internal fun ListRemoteBundleInfo.toJson(): JSONObject =
    JSONObject().put("name", name).put("version", version)

internal fun RemoteBundleInfo.toJson(): JSONObject =
    JSONObject()
        .put("name", name)
        .put("version", version)
        .putOpt("etag", etag)
        .putOpt("integrity", integrity)
        .putOpt("signature", signature)
        .putOpt("lastModified", lastModified)

internal fun BundleUpdateInfo.toJson(): JSONObject =
    JSONObject()
        .put("name", name)
        .put("version", version)
        .put("isAvailable", isAvailable)
        .putOpt("localVersion", localVersion)
        .putOpt("etag", etag)
        .putOpt("integrity", integrity)
        .putOpt("signature", signature)
        .putOpt("lastModified", lastModified)
