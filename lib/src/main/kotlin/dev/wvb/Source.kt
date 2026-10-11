package dev.wvb

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Options for building a [Source] with sensible Android defaults. Every field
 * is optional and falls back to an app-private directory when omitted.
 *
 * @property builtinAssetsDir when non-null, the APK `assets/<builtinAssetsDir>`
 *   directory is extracted into [builtinDir] when the source is built; `null`
 *   disables extraction (e.g. when you populate [builtinDir] yourself).
 */
public data class SourceOptions(
    val builtinDir: String? = null,
    val remoteDir: String? = null,
    val builtinManifestFilepath: String? = null,
    val remoteManifestFilepath: String? = null,
    val builtinAssetsDir: String? = "bundles",
    val integrity: SourceIntegrityOptions? = null,
    val dataReadOptions: DataReadOptions? = null,
    val headerReadOptions: HeaderReadOptions? = null,
    val indexReadOptions: IndexReadOptions? = null,
    val removeBundleChunkSize: UInt? = null,
)

/** `<filesDir>/.wvb/builtin/bundles` — the read-only directory for bundles shipped with the app. */
public fun defaultBuiltinDir(context: Context): String =
    File(context.filesDir, ".wvb/builtin/bundles").absolutePath

/** `<filesDir>/.wvb/remote/bundles` — the writable directory for bundles downloaded at runtime. */
public fun defaultRemoteDir(context: Context): String =
    File(context.filesDir, ".wvb/remote/bundles").absolutePath

/**
 * Builds a [Source] from [config], filling in default directories, copying
 * bundled assets into the builtin dir, and creating the writable remote dir.
 *
 * Performs blocking file I/O; prefer building off the main thread when shipping
 * large builtin bundles.
 */
public fun makeSource(
    context: Context,
    config: SourceOptions,
): Source {
    val builtinDir = config.builtinDir ?: defaultBuiltinDir(context)
    val remoteDir = config.remoteDir ?: defaultRemoteDir(context)

    File(builtinDir).mkdirs()
    File(remoteDir).mkdirs()

    config.builtinAssetsDir?.let { assetDir ->
        installBuiltinBundlesFromAssets(context, assetDir, builtinDir)
    }

    val bundleConfig = SourceConfig(
        builtinDir = builtinDir,
        remoteDir = remoteDir,
        builtinManifestFilepath = config.builtinManifestFilepath,
        remoteManifestFilepath = config.remoteManifestFilepath,
        integrity = config.integrity,
        dataReadOptions = config.dataReadOptions,
        headerReadOptions = config.headerReadOptions,
        indexReadOptions = config.indexReadOptions,
        removeBundleChunkSize = config.removeBundleChunkSize,
    )
    return Source(bundleConfig)
}

/** Per-destination locks serializing concurrent same-process extraction. */
private val extractionLocks = ConcurrentHashMap<String, Any>()

/** Source of unique temp-file suffixes (combined with the pid for cross-process uniqueness). */
private val tmpCounter = AtomicLong(0)

/**
 * Copies bundles shipped in the APK `assets/[assetDir]` (default `bundles/`) into
 * [builtinDir] so the native [Source] can read them as real files, returning
 * the destination. Copies recursively as an additive overlay — files removed from
 * the assets in a later app version are **not** deleted from [builtinDir]. No-op
 * when the asset directory does not exist.
 */
public fun installBuiltinBundlesFromAssets(
    context: Context,
    assetDir: String = "bundles",
    builtinDir: String? = null,
): String {
    val destRoot = builtinDir ?: defaultBuiltinDir(context)
    // Serialize same-process extraction per destination; cross-process safety
    // comes from the unique temp + atomic rename in copyAssetFile.
    val lock = extractionLocks.getOrPut(File(destRoot).absolutePath) { Any() }
    synchronized(lock) {
        // Re-extract whenever the APK changed: a per-file size comparison alone
        // misses same-size content changes that would otherwise serve stale bundles.
        val stampFile = File(destRoot, ".asset-stamp")
        val currentStamp = currentAssetStamp(context)
        val storedStamp = runCatching { stampFile.readText().trim() }.getOrNull()
        val force = currentStamp == null || currentStamp != storedStamp

        copyAssetTree(context, assetDir.trim('/'), File(destRoot), force)

        if (force && currentStamp != null) {
            // Written under the lock; a torn stamp only causes a harmless re-extract.
            runCatching { stampFile.writeText(currentStamp) }
        }
    }
    return destRoot
}

/** APK identity that changes on every (re)install/update, or `null` if unavailable. */
private fun currentAssetStamp(context: Context): String? =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
    }.getOrNull()

/**
 * Recursively copies an asset directory into [dest] as an additive overlay, copying
 * changed files only and never deleting existing destination entries. [force]
 * rewrites every file regardless of size.
 */
private fun copyAssetTree(context: Context, assetPath: String, dest: File, force: Boolean) {
    // `list` returns a directory's children, or an empty array for a file (and for
    // an empty directory, which bundle dirs never are).
    val children = context.assets.list(assetPath) ?: emptyArray()

    if (children.isEmpty()) {
        copyAssetFile(context, assetPath, dest, force)
        return
    }

    dest.mkdirs()
    for (child in children) {
        val childAsset = if (assetPath.isEmpty()) child else "$assetPath/$child"
        copyAssetTree(context, childAsset, File(dest, child), force)
    }
}

/**
 * Copies a single asset file to [dest] when missing or a different size; [force]
 * rewrites regardless. `AssetManager` streams report the full uncompressed length
 * via [java.io.InputStream.available], so a size comparison is a valid skip check.
 * The bytes are written to a unique sibling temp and atomically renamed so a
 * concurrent or interrupted copy can never leave a torn destination.
 */
private fun copyAssetFile(context: Context, assetPath: String, dest: File, force: Boolean) {
    val input = try {
        context.assets.open(assetPath)
    } catch (_: FileNotFoundException) {
        return // not a readable file (e.g. an empty asset directory) — benign skip
    }
    input.use { stream ->
        val incomingSize = stream.available().toLong()
        if (!force && dest.isFile && dest.length() == incomingSize) return
        dest.parentFile?.mkdirs()
        val tmp = File(
            dest.parentFile,
            "${dest.name}.${android.os.Process.myPid()}.${tmpCounter.getAndIncrement()}.tmp",
        )
        try {
            tmp.outputStream().use { output -> stream.copyTo(output) }
            // Atomic replace on the app's single filesystem; fail loudly rather
            // than fall back to a non-atomic copy a concurrent reader could see torn.
            if (!tmp.renameTo(dest)) {
                throw java.io.IOException("failed to atomically move $tmp -> $dest")
            }
        } finally {
            tmp.delete()
        }
    }
}
