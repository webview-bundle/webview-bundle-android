# webview-bundle-android

Android Kotlin package for WebViewBundle. The native FFI module is built in
[webview-bundle](https://github.com/webview-bundle/webview-bundle)
and pulled into this package from its GitHub releases.

## Prerequisites

This repository uses [mise](https://mise.jdx.dev) to manage its toolchain. The
required versions of Node.js and the JDK are pinned in `mise.toml`.

[Install mise](https://mise.jdx.dev/getting-started.html), then install the
pinned tools from the repository root:

```sh
mise install
```

## Installing / updating the FFI module

`scripts/install.mjs` fetches a release from the upstream repository and wires
it into the `lib` module. From the `android.zip` asset it installs the Android
variant (`lib-android`):

- the Kotlin bindings into `lib/src/main/kotlin/`
- the JNI libraries (a `libwvb_ffi.so` per ABI) into `lib/src/main/jniLibs/`

The Kotlin bindings are overwritten in place; `jniLibs` is replaced wholesale,
so an ABI dropped between releases does not linger.

```sh
# install a release (tag ffi/0.1.0)
node scripts/install.mjs 0.1.0

# or an explicit tag
node scripts/install.mjs ffi/0.1.0

# install a prerelease build for a commit (tag prerelease/<sha>)
node scripts/install.mjs --prerelease a3f693a
node scripts/install.mjs prerelease/a3f693a

# install the latest ffi/* release
node scripts/install.mjs latest

# install from a locally-built android.zip instead of a release
node scripts/install.mjs --asset-file .output/android.zip
```

Tags follow the upstream convention: `ffi/<version>` for releases and
`prerelease/<sha>` for prereleases. Run `node scripts/install.mjs --help` for
all options.

## License

MIT License
