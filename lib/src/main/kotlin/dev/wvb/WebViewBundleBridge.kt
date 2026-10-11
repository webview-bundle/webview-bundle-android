package dev.wvb

import org.json.JSONArray

/** Commands and payloads match @wvb/bridge's source, remote, and updater APIs. */
internal class WebViewBundleBridge(private val wvb: WebViewBundle) : BridgeHandlers {
    override fun register(bridge: Bridge) {
        registerSource(bridge)
        registerRemote(bridge)
        registerUpdater(bridge)
    }

    private fun registerSource(bridge: Bridge) {
        val source = wvb.source
        bridge.handler("sourceListBundles") {
            JSONArray(source.listBundles().map { it.toJson() })
        }
        bridge.handler("sourceListBuiltinBundles") {
            JSONArray(source.listBuiltinBundles().map { it.toJson() })
        }
        bridge.handler("sourceListRemoteBundles") {
            JSONArray(source.listRemoteBundles().map { it.toJson() })
        }
        bridge.handler("sourceGetVersion") { params ->
            val p = BridgeCodec.params(params)
            source.getVersion(p.requireString("bundleName"))?.toJson()
        }
        bridge.handler("sourceGetRemoteStagedVersion") { params ->
            val p = BridgeCodec.params(params)
            source.getRemoteStagedVersion(p.requireString("bundleName"))
        }
        bridge.handler("sourceGetRemotePreviousVersion") { params ->
            val p = BridgeCodec.params(params)
            source.getRemotePreviousVersion(p.requireString("bundleName"))
        }
        bridge.handler("sourceResolveFilepath") { params ->
            val p = BridgeCodec.params(params)
            source.resolveFilepath(p.requireString("bundleName"))
        }
        bridge.handler("sourceUnload") { params ->
            val p = BridgeCodec.params(params)
            source.unload(p.requireString("bundleName"))
        }
        bridge.handler("sourceGetBuiltinBundleFilepath") { params ->
            val p = BridgeCodec.params(params)
            source.getBuiltinBundleFilepath(p.requireString("bundleName"), p.requireString("version"))
        }
        bridge.handler("sourceGetRemoteBundleFilepath") { params ->
            val p = BridgeCodec.params(params)
            source.getRemoteBundleFilepath(p.requireString("bundleName"), p.requireString("version"))
        }
        bridge.handler("sourceGetBuiltinVersionData") { params ->
            val p = BridgeCodec.params(params)
            source.getBuiltinVersionData(p.requireString("bundleName"), p.requireString("version"))?.toJson()
        }
        bridge.handler("sourceGetRemoteVersionData") { params ->
            val p = BridgeCodec.params(params)
            source.getRemoteVersionData(p.requireString("bundleName"), p.requireString("version"))?.toJson()
        }
        bridge.handler("sourceUpdateRemoteVersion") { params ->
            val p = BridgeCodec.params(params)
            source.updateRemoteVersion(p.requireString("bundleName"), p.requireString("version")).toJson()
        }
        bridge.handler("sourceUpdateRemoteVersions") { params ->
            val p = BridgeCodec.params(params)
            JSONArray(source.updateRemoteVersions(p.requireObject("items").stringMap()).map { it.toJson() })
        }
        bridge.handler("sourceStageRemoteBundle") { params ->
            val p = BridgeCodec.params(params)
            source.stageRemoteBundle(p.requireString("bundleName"), p.requireObject("data").stageData()).toJson()
        }
        bridge.handler("sourceStageRemoteBundles") { params ->
            val p = BridgeCodec.params(params)
            JSONArray(source.stageRemoteBundles(p.requireObject("items").mapValues { obj, key -> obj.requireObject(key).stageData() }).map { it.toJson() })
        }
        bridge.handler("sourceRemoveRemoteBundles") { params ->
            val p = BridgeCodec.params(params)
            JSONArray(source.removeRemoteBundles(p.requireObject("items").mapValues { obj, key -> obj.requireObject(key).removeData() }).map { it.toJson() })
        }
        bridge.handler("sourceRemoveRemoteBundle") { params ->
            val p = BridgeCodec.params(params)
            source.removeRemoteBundle(p.requireString("bundleName"), p.requireString("version"), p.optionalBoolean("force")).toJson()
        }
        bridge.handler("sourcePruneRemoteBundle") { params ->
            val p = BridgeCodec.params(params)
            source.pruneRemoteBundle(p.requireString("bundleName")).toJson()
        }
        bridge.handler("sourcePruneRemoteBundles") { params ->
            val p = BridgeCodec.params(params)
            JSONArray(source.pruneRemoteBundles(p.requireArray("bundleNames").strings()).map { it.toJson() })
        }
    }

    private fun registerRemote(bridge: Bridge) {
        bridge.handler("remoteGetUpdate") { params ->
            val options = BridgeCodec.params(params).optionalObject("options")
            requireRemote().getUpdate(options?.let {
                RemoteGetUpdateOptions(etag = it.optionalString("etag"), channel = it.optionalString("channel"))
            })?.toJson()
        }
        bridge.handler("remoteDownload") { params ->
            val p = BridgeCodec.params(params)
            requireRemote().download(p.requireString("url"), p.requireString("filepath"))
            null
        }
    }

    private fun registerUpdater(bridge: Bridge) {
        bridge.handler("updaterGetUpdate") { params ->
            val options = BridgeCodec.params(params).optionalObject("options")
            requireUpdater().getUpdate(options?.let {
                UpdaterGetUpdateOptions(expectSignatureKeyId = it.optionalString("expectSignatureKeyId"))
            })?.toJson()
        }
        bridge.handler("updaterDownload") { params ->
            val p = BridgeCodec.params(params)
            val options = p.optionalObject("options")?.let {
                UpdaterDownloadOptions(
                    concurrency = it.optionalUnsigned("concurrency", UInt.MAX_VALUE.toULong())?.toUInt(),
                    timeout = it.optionalUnsigned("timeout"),
                )
            }
            JSONArray(requireUpdater().download(
                p.requireArray("bundleUpdates").objects { it.bundleUpdate() }, options,
            ).map { it.toJson() })
        }
        bridge.handler("updaterInstall") { params ->
            val targets = BridgeCodec.params(params).requireArray("targets").objects {
                UpdaterInstallTarget(name = it.requireString("name"), version = it.optionalString("version"))
            }
            JSONArray(requireUpdater().install(targets).map { it.toJson() })
        }
        bridge.handler("updaterRollback") { params ->
            val targets = BridgeCodec.params(params).requireArray("targets").objects {
                UpdaterRollbackTarget(name = it.requireString("name"), version = it.optionalString("version"))
            }
            JSONArray(requireUpdater().rollback(targets).map { it.toJson() })
        }
    }

    private fun requireRemote(): Remote = wvb.remote ?: throw BridgeError(
        code = "remote_not_initialized", message = "remote is not initialized.",
    )

    private fun requireUpdater(): Updater = wvb.updater ?: throw BridgeError(
        code = "updater_not_initialized", message = "updater is not initialized.",
    )
}
