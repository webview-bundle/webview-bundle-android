package dev.wvb

import org.json.JSONArray
import org.json.JSONObject

/** The JSON contract is shared with @wvb/bridge. */
internal object BridgeCodec {
    fun params(raw: Any?): JSONObject = when (raw) {
        null, JSONObject.NULL -> JSONObject()
        is JSONObject -> raw
        else -> throw invalidParam("params")
    }
}

private fun invalidParam(key: String) =
    BridgeError(code = "invalid_params", message = "invalid param \"$key\"")

internal fun JSONObject.requireString(key: String): String =
    opt(key) as? String ?: throw invalidParam(key)

internal fun JSONObject.optionalString(key: String): String? =
    if (!has(key) || isNull(key)) null else requireString(key)

internal fun JSONObject.requireObject(key: String): JSONObject =
    opt(key) as? JSONObject ?: throw invalidParam(key)

internal fun JSONObject.optionalObject(key: String): JSONObject? =
    if (!has(key) || isNull(key)) null else requireObject(key)

internal fun JSONObject.requireArray(key: String): JSONArray =
    opt(key) as? JSONArray ?: throw invalidParam(key)

internal fun JSONObject.optionalBoolean(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else opt(key) as? Boolean ?: throw invalidParam(key)

internal fun JSONObject.optionalUnsigned(key: String, max: ULong = ULong.MAX_VALUE): ULong? {
    if (!has(key) || isNull(key)) return null
    val number = opt(key) as? Number ?: throw invalidParam(key)
    val value = number.toString().toULongOrNull() ?: throw invalidParam(key)
    if (value > max) throw invalidParam(key)
    return value
}

internal fun <T> JSONObject.mapValues(decode: (JSONObject, String) -> T): Map<String, T> =
    keys().asSequence().associateWith { decode(this, it) }

internal fun JSONObject.stringMap(): Map<String, String> = mapValues { obj, key -> obj.requireString(key) }

internal fun JSONArray.strings(): List<String> = (0 until length()).map {
    opt(it) as? String ?: throw invalidParam("[$it]")
}

internal fun <T> JSONArray.objects(decode: (JSONObject) -> T): List<T> = (0 until length()).map {
    decode(opt(it) as? JSONObject ?: throw invalidParam("[$it]"))
}

internal fun JSONObject.versionData() = ManifestVersionData(
    integrity = optionalString("integrity"), metadata = optionalObject("metadata")?.stringMap(),
)
internal fun JSONObject.stageData() = ManifestStageData(
    version = requireString("version"), data = optionalObject("data")?.versionData(),
)
internal fun JSONObject.removeData() = ManifestRemoveData(
    versions = requireArray("versions").strings(), force = optionalBoolean("force"),
)
internal fun JSONObject.bundleUpdate() = BundleUpdate(
    name = requireString("name"), version = requireString("version"),
    downloadUrl = optionalString("downloadUrl"), integrity = optionalString("integrity"),
    metadata = optionalObject("metadata")?.stringMap(),
)

internal fun SourceKind.bridgeValue(): String = when (this) {
    SourceKind.BUILTIN -> "builtin"
    SourceKind.REMOTE -> "remote"
}

internal fun ManifestBundleItemStatus.bridgeValue(): String = when (this) {
    ManifestBundleItemStatus.CURRENT -> "current"
    ManifestBundleItemStatus.PREVIOUS -> "previous"
    ManifestBundleItemStatus.STAGED -> "staged"
    ManifestBundleItemStatus.ORPHAN -> "orphan"
}

internal fun ManifestStageResultKind.bridgeValue(): String = when (this) {
    ManifestStageResultKind.STAGED -> "staged"
    ManifestStageResultKind.IN_USE -> "in_use"
}

internal fun ManifestSetCurrentVersionResultKind.bridgeValue(): String = when (this) {
    ManifestSetCurrentVersionResultKind.SETTLED -> "settled"
    ManifestSetCurrentVersionResultKind.NOT_EXISTS -> "not_exists"
    ManifestSetCurrentVersionResultKind.VERSION_NOT_EXISTS -> "version_not_exists"
}

internal fun ManifestRemoveResultKind.bridgeValue(): String = when (this) {
    ManifestRemoveResultKind.REMOVED -> "removed"
    ManifestRemoveResultKind.NOT_EXISTS -> "not_exists"
    ManifestRemoveResultKind.VERSION_NOT_EXISTS -> "version_not_exists"
    ManifestRemoveResultKind.IN_USE -> "in_use"
}

internal val WebviewBundleException.bridgeCode: String
    get() = when (this) {
        is WebviewBundleException.CoreIo -> "core.io"
        is WebviewBundleException.CoreCompress -> "core.compress"
        is WebviewBundleException.CoreDecompress -> "core.decompress"
        is WebviewBundleException.CoreEncode -> "core.encode"
        is WebviewBundleException.CoreDecode -> "core.decode"
        is WebviewBundleException.CoreHttp -> "core.http"
        is WebviewBundleException.CoreHttpInvalidUri -> "core.http_invalid_uri"
        is WebviewBundleException.CoreCancelled -> "core.cancelled"
        is WebviewBundleException.CoreTimeout -> "core.timeout"
        is WebviewBundleException.CoreInvalidMagicNum -> "core.invalid_magic_num"
        is WebviewBundleException.CoreInvalidVersion -> "core.invalid_version"
        is WebviewBundleException.CoreInvalidHeaderChecksum -> "core.invalid_header_checksum"
        is WebviewBundleException.CoreInvalidIndexChecksum -> "core.invalid_index_checksum"
        is WebviewBundleException.CoreChecksumMismatch -> "core.checksum_mismatch"
        is WebviewBundleException.CoreBundleNotFound -> "core.bundle_not_found"
        is WebviewBundleException.CoreInvalidFilepath -> "core.invalid_filepath"
        is WebviewBundleException.CoreSerdeJson -> "core.serde_json"
        is WebviewBundleException.CoreCannotResolveProxyServer -> "core.cannot_resolve_proxy_server"
        is WebviewBundleException.CoreHttpClient -> "core.http_client"
        is WebviewBundleException.CoreRemoteHttp -> "core.remote_http"
        is WebviewBundleException.CoreBadRemoteResponse -> "core.bad_remote_response"
        is WebviewBundleException.CoreInvalidRemoteConfig -> "core.invalid_remote_config"
        is WebviewBundleException.CoreInvalidUpdaterConfig -> "core.invalid_updater_config"
        is WebviewBundleException.CoreInvalidIntegrity -> "core.invalid_integrity"
        is WebviewBundleException.CoreIntegrityRequired -> "core.integrity_required"
        is WebviewBundleException.CoreIntegrityVerifyFailed -> "core.integrity_verify_failed"
        is WebviewBundleException.CoreInvalidSignature -> "core.invalid_signature"
        is WebviewBundleException.CoreInvalidSignatureKey -> "core.invalid_signature_key"
        is WebviewBundleException.CoreExpectSignatureNotFound -> "core.expect_signature_not_found"
        is WebviewBundleException.CoreSignatureVerifyFailed -> "core.signature_verify_failed"
        is WebviewBundleException.CoreGeneric -> "core.generic"
        is WebviewBundleException.BindingInvalidHeaderName -> "invalid_header_name"
        is WebviewBundleException.BindingInvalidHeaderValue -> "invalid_header_value"
        is WebviewBundleException.BindingInvalidSignatureOptions -> "invalid_signature_options"
    }

internal fun UpdaterDownloadResultKind.toJson(): JSONObject = when (this) {
    UpdaterDownloadResultKind.Downloaded -> JSONObject().put("type", "downloaded")
    is UpdaterDownloadResultKind.Error -> JSONObject().put("type", "error")
        .put("code", v1.bridgeCode).put("message", v1.message)
}

internal fun UpdaterInstallResultKind.toJson(): JSONObject = when (this) {
    UpdaterInstallResultKind.Installed -> JSONObject().put("type", "installed")
    UpdaterInstallResultKind.StagedVersionNotMatched -> JSONObject().put("type", "staged_version_not_matched")
    UpdaterInstallResultKind.StagedBundleNotExists -> JSONObject().put("type", "staged_bundle_not_exists")
    UpdaterInstallResultKind.VerifyFailed -> JSONObject().put("type", "verify_failed")
    is UpdaterInstallResultKind.Error -> JSONObject().put("type", "error")
        .put("code", v1.bridgeCode).put("message", v1.message)
}

internal fun UpdaterRollbackResultKind.toJson(): JSONObject = when (this) {
    UpdaterRollbackResultKind.RolledBack -> JSONObject().put("type", "rolled_back")
    UpdaterRollbackResultKind.PreviousVersionNotMatched -> JSONObject().put("type", "previous_version_not_matched")
    UpdaterRollbackResultKind.PreviousBundleNotExists -> JSONObject().put("type", "previous_bundle_not_exists")
    UpdaterRollbackResultKind.VerifyFailed -> JSONObject().put("type", "verify_failed")
    is UpdaterRollbackResultKind.Error -> JSONObject().put("type", "error")
        .put("code", v1.bridgeCode).put("message", v1.message)
}

internal fun ManifestVersionData.toJson(): JSONObject = JSONObject()
    .putOpt("integrity", integrity)
    .putOpt("metadata", metadata?.let { JSONObject(it) })

internal fun BundleSourceVersion.toJson(): JSONObject = JSONObject()
    .put("source", source.bridgeValue())
    .put("version", version)

internal fun ManifestBundleItem.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("version", version)
    .put("status", status.bridgeValue())
    .put("data", data.toJson())

internal fun SourceListItem.toJson(): JSONObject = JSONObject()
    .put("source", source.bridgeValue())
    .put("item", item.toJson())

internal fun ManifestStageResult.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("version", version)
    .put("kind", kind.bridgeValue())

internal fun ManifestSetCurrentVersionResult.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("version", version)
    .put("kind", kind.bridgeValue())

internal fun ManifestRemoveResult.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("version", version)
    .put("kind", kind.bridgeValue())

internal fun ManifestPruneResult.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("prunedVersions", JSONArray(prunedVersions))

internal fun BundleUpdate.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("version", version)
    .putOpt("downloadUrl", downloadUrl)
    .putOpt("integrity", integrity)
    .putOpt("metadata", metadata?.let { JSONObject(it) })

internal fun Update.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("createdAt", createdAt)
    .put("runtimeVersion", runtimeVersion.toInt())
    .put("bundles", JSONArray(bundles.map { it.toJson() }))
    .put("metadata", JSONObject(metadata))

internal fun UpdateSignature.toJson(): JSONObject = JSONObject()
    .put("keyId", keyId)
    .put("sig", sig)
    .put("alg", alg)

internal fun RemoteUpdateResponse.toJson(): JSONObject = JSONObject()
    .put("update", update.toJson())
    .putOpt("etag", etag)
    .putOpt("signature", signature?.toJson())

internal fun UpdaterDownloadResult.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .put("version", version)
    .putOpt("integrity", integrity)
    .putOpt("metadata", metadata?.let { JSONObject(it) })
    .put("result", result.toJson())

internal fun UpdaterInstallResult.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .putOpt("targetVersion", targetVersion)
    .putOpt("installVersion", installVersion)
    .put("result", result.toJson())

internal fun UpdaterRollbackResult.toJson(): JSONObject = JSONObject()
    .put("name", name)
    .putOpt("targetVersion", targetVersion)
    .putOpt("rollbackVersion", rollbackVersion)
    .put("result", result.toJson())
