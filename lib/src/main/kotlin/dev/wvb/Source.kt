package dev.wvb

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Options for building a [BundleSource] with sensible Android defaults.
 *
 * Every field is optional and falls back to an app-private directory when
 * omitted:
 * - [builtinDir] -> `<filesDir>/wvb/builtin` — the read-only bundles shipped with
 *   the app. The native source reads real files, so APK `assets` are copied here
 *   first (see [builtinAssetsDir] / [installBuiltinBundlesFromAssets]).
 * - [remoteDir] -> `<filesDir>/wvb/remote` — the writable directory holding
 *   bundles downloaded at runtime.
 *
 * @property builtinAssetsDir when non-null, the APK `assets/<builtinAssetsDir>`
 *   directory is extracted into [builtinDir] when the source is built. Set to
 *   `null` to disable extraction (e.g. when you populate [builtinDir] yourself).
 */
public data class SourceOptions(
    val builtinDir: String? = null,
    val remoteDir: String? = null,
    val builtinManifestFilepath: String? = null,
    val remoteManifestFilepath: String? = null,
    val builtinAssetsDir: String? = "bundles",
)

/** `<filesDir>/wvb/builtin` — the read-only directory for bundles shipped with the app. */
public fun defaultBuiltinDir(context: Context): String =
    File(context.filesDir, "wvb/builtin").absolutePath

/** `<filesDir>/wvb/remote` — the writable directory for bundles downloaded at runtime. */
public fun defaultRemoteDir(context: Context): String =
    File(context.filesDir, "wvb/remote").absolutePath

/**
 * Builds a [BundleSource] from [options], filling in default directories, copying
 * bundled assets into the builtin dir, and creating the writable remote dir.
 *
 * Performs file I/O (asset extraction, `mkdirs`); prefer building off the main
 * thread when shipping large builtin bundles.
 */
public fun makeBundleSource(context: Context, options: SourceOptions = SourceOptions()): BundleSource {
    val builtinDir = options.builtinDir ?: defaultBuiltinDir(context)
    val remoteDir = options.remoteDir ?: defaultRemoteDir(context)

    File(builtinDir).mkdirs()
    File(remoteDir).mkdirs()

    options.builtinAssetsDir?.let { assetDir ->
        installBuiltinBundlesFromAssets(context, assetDir, builtinDir)
    }

    return BundleSource(
        BundleSourceConfig(
            builtinDir = builtinDir,
            remoteDir = remoteDir,
            builtinManifestFilepath = options.builtinManifestFilepath,
            remoteManifestFilepath = options.remoteManifestFilepath,
        ),
    )
}

/** Per-destination locks serializing concurrent same-process extraction. */
private val extractionLocks = ConcurrentHashMap<String, Any>()

/** Source of unique temp-file suffixes (combined with the pid for cross-process uniqueness). */
private val tmpCounter = AtomicLong(0)

/**
 * Copies bundles shipped in the APK `assets/[assetDir]` (default `bundles/`) into
 * [builtinDir] so the native [BundleSource] can read them as real files. The
 * directory tree (the manifest plus every `.wvb`) is copied recursively as an
 * additive overlay — files removed from the assets in a later app version are
 * **not** deleted from [builtinDir]; clear it yourself for a clean slate.
 *
 * Cheap on repeat launches: within the same installed APK a file is copied only
 * when missing or a different size. The whole tree is re-extracted after an app
 * update (detected via the APK's install timestamp), so a same-size content change
 * — e.g. a `manifest.json` bumping `"0.0.1"` -> `"0.0.2"` — is not missed. No-op
 * when the asset directory does not exist. Returns the destination [builtinDir].
 * Throws [java.io.IOException] on a genuine copy failure (e.g. the disk is full).
 *
 * Concurrency-safe: same-process callers targeting the same [builtinDir] are
 * serialized, and each file is written to a unique temp and atomically renamed, so
 * concurrent extractions (multiple threads, or multiple processes sharing the
 * directory) and concurrent readers always observe a complete file — never a torn
 * or interleaved one.
 */
public fun installBuiltinBundlesFromAssets(
    context: Context,
    assetDir: String = "bundles",
    builtinDir: String? = null,
): String {
    val destRoot = builtinDir ?: defaultBuiltinDir(context)
    // Serialize same-process extraction per destination so concurrent constructors
    // don't redo each other's work; cross-process safety comes from the unique
    // temp + atomic rename in copyAssetFile.
    val lock = extractionLocks.getOrPut(File(destRoot).absolutePath) { Any() }
    synchronized(lock) {
        // Re-extract whenever the APK changed. A per-file size comparison alone
        // misses same-size content changes (e.g. a manifest.json bumping
        // "0.0.1" -> "0.0.2"), which would otherwise serve stale bundles after an
        // app update; the APK's install timestamp changes on every (re)install.
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
 * Recursively copies an asset directory into [dest] (additive overlay), copying
 * changed files only; never deletes existing destination entries. When [force] is
 * set every file is rewritten regardless of size (used after an app update).
 */
private fun copyAssetTree(context: Context, assetPath: String, dest: File, force: Boolean) {
    // `list` returns the children of a directory, or an empty array for a file
    // (and for an empty directory, which bundle dirs never are). A genuine
    // IOException is allowed to propagate rather than be silently treated as "no
    // children".
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
 * Copies a single asset file to [dest] when missing or a different size.
 *
 * Unless [force] is set, the asset stream's [java.io.InputStream.available] (the
 * full uncompressed length up front, for `AssetManager` streams compressed or not)
 * is compared against the already-extracted file's length to skip unchanged copies;
 * [force] rewrites the file regardless (size alone can't detect a same-size content
 * change). The bytes are written to a **unique** sibling temp and atomically
 * renamed, so a process killed mid-copy — or a concurrent extraction from another
 * thread or process — cannot leave a torn destination (the temp name carries the
 * pid and a counter, so concurrent copies never share one). A path that turns out
 * not to be a readable file (e.g. an empty asset directory) is skipped; a real
 * [java.io.IOException] propagates.
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
            // renameTo over an existing destination is an atomic replace on the
            // app's (single) filesystem. If it fails, fail loudly rather than fall
            // back to a non-atomic copy a concurrent reader could observe torn.
            if (!tmp.renameTo(dest)) {
                throw java.io.IOException("failed to atomically move $tmp -> $dest")
            }
        } finally {
            tmp.delete()
        }
    }
}
