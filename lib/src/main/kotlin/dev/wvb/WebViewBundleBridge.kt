package dev.wvb

import org.json.JSONArray

/**
 * Bridges to communicate with the WebView.
 */
internal class WebViewBundleBridge(private val wvb: WebViewBundle) : BridgeHandlers {
    override fun register(bridge: Bridge) {
        registerSource(bridge)
        registerRemote(bridge)
        registerUpdater(bridge)
    }

    private fun registerSource(bridge: Bridge) {
        val source = wvb.source
        bridge.handler("sourceListBundles") { JSONArray(source.listBundles().map { it.toJson() }) }
        bridge.handler("sourceLoadVersion") { params ->
            source.loadVersion(BridgeCodec.params(params).requireString("bundleName"))?.toJson()
        }
        bridge.handler("sourceUpdateVersion") { params ->
            val p = BridgeCodec.params(params)
            source.updateVersion(p.requireString("bundleName"), p.requireString("version"))
            null
        }
        bridge.handler("sourceResolveFilepath") { params ->
            source.resolveFilepath(BridgeCodec.params(params).requireString("bundleName"))
        }
        bridge.handler("sourceGetBuiltinBundleFilepath") { params ->
            val p = BridgeCodec.params(params)
            source.getBuiltinBundleFilepath(
                p.requireString("bundleName"), p.requireString("version")
            )
        }
        bridge.handler("sourceGetRemoteBundleFilepath") { params ->
            val p = BridgeCodec.params(params)
            source.getRemoteBundleFilepath(
                p.requireString("bundleName"), p.requireString("version")
            )
        }
        bridge.handler("sourceLoadBuiltinMetadata") { params ->
            val p = BridgeCodec.params(params)
            source.loadBuiltinMetadata(p.requireString("bundleName"), p.requireString("version"))
                ?.toJson()
        }
        bridge.handler("sourceLoadRemoteMetadata") { params ->
            val p = BridgeCodec.params(params)
            source.loadRemoteMetadata(p.requireString("bundleName"), p.requireString("version"))
                ?.toJson()
        }
        bridge.handler("sourceUnloadDescriptor") { params ->
            source.unloadDescriptor(BridgeCodec.params(params).requireString("bundleName"))
        }
        bridge.handler("sourceRemoveRemoteBundle") { params ->
            val p = BridgeCodec.params(params)
            source.removeRemoteBundle(p.requireString("bundleName"), p.requireString("version"))
        }
        bridge.handler("sourceRemoteRetainedVersions") { params ->
            source.remoteRetainedVersions(BridgeCodec.params(params).requireString("bundleName"))
        }
        bridge.handler("sourcePruneRemoteBundles") { params ->
            source.pruneRemoteBundles(BridgeCodec.params(params).requireString("bundleName"))
        }
    }

    private fun registerRemote(bridge: Bridge) {
        bridge.handler("remoteListBundles") { params ->
            val channel = BridgeCodec.params(params).optionalString("channel")
            JSONArray(requireRemote().listBundles(channel).map { it.toJson() })
        }
        bridge.handler("remoteGetInfo") { params ->
            val p = BridgeCodec.params(params)
            requireRemote().getInfo(p.requireString("bundleName"), p.optionalString("channel"))
                .toJson()
        }
        bridge.handler("remoteDownload") { params ->
            val p = BridgeCodec.params(params)
            requireRemote().download(
                p.requireString("bundleName"), p.optionalString("channel")
            ).info.toJson()
        }
        bridge.handler("remoteDownloadVersion") { params ->
            val p = BridgeCodec.params(params)
            requireRemote().downloadVersion(
                p.requireString("bundleName"), p.requireString("version")
            ).info.toJson()
        }
    }

    private fun registerUpdater(bridge: Bridge) {
        bridge.handler("updaterListRemotes") {
            JSONArray(
                requireUpdater().listRemotes().map { it.toJson() })
        }
        bridge.handler("updaterGetUpdate") { params ->
            requireUpdater().getUpdate(BridgeCodec.params(params).requireString("bundleName"))
                .toJson()
        }
        bridge.handler("updaterDownload") { params ->
            val p = BridgeCodec.params(params)
            requireUpdater().downloadUpdate(
                p.requireString("bundleName"), p.optionalString("version")
            ).toJson()
        }
        bridge.handler("updaterInstall") { params ->
            val p = BridgeCodec.params(params)
            requireUpdater().install(p.requireString("bundleName"), p.requireString("version"))
            null
        }
    }

    private fun requireRemote(): Remote = wvb.remote ?: throw BridgeError(
        code = "remote_not_initialized", message = "remote is not initialized."
    )

    private fun requireUpdater(): Updater = wvb.updater ?: throw BridgeError(
        code = "updater_not_initialized", message = "updater is not initialized."
    )
}
