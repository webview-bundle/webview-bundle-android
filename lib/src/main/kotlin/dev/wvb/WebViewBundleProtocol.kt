package dev.wvb


/**
 * Configures which request hosts a [WebViewBundleProtocol.Bundle] **passes through
 * to the network** instead of serving from the bundle.
 *
 * A bundle protocol handles every `https` host by default — like the core, the
 * bundle name is the first host label (`https://app.wvb/` -> bundle `app`). Use
 * this builder to carve out external origins the page talks to (APIs, CDNs,
 * analytics), so those reach the real network instead of being resolved to a
 * (missing) bundle. Hosts are matched case-insensitively.
 *
 * Obtained via the `bundle { ... }` builder; never constructed directly.
 */
class BundlePassthrough internal constructor() {
    private val exactHosts = mutableSetOf<String>()
    private val domainSuffixes = mutableListOf<String>()
    private val predicates = mutableListOf<(String) -> Boolean>()

    /** Pass requests to this exact host through to the network, e.g. `"example.com"`. */
    fun passthrough(host: String) {
        exactHosts += host.trim().lowercase()
    }

    /**
     * Pass requests to any host under [domain] through to the network — `domain =
     * "example.com"` matches `example.com` and `cdn.example.com`.
     */
    fun passthroughDomain(domain: String) {
        val normalized = domain.trim().trim('.').lowercase()
        require(normalized.isNotEmpty()) { "passthrough domain must not be empty" }
        domainSuffixes += normalized
    }

    /** Pass requests whose host satisfies [predicate] through to the network. */
    fun passthrough(predicate: (host: String) -> Boolean) {
        predicates += predicate
    }

    internal fun shouldPassthrough(host: String): Boolean {
        val lower = host.lowercase()
        if (lower in exactHosts) return true
        if (domainSuffixes.any { lower == it || lower.endsWith(".$it") }) return true
        return predicates.any { it(host) }
    }
}

/**
 * Binds `https` hosts served inside a `WebView` to a webview-bundle request
 * handler.
 */
sealed class WebViewBundleProtocol {
    /** Returns `true` if this protocol should serve a request to [host]. */
    internal abstract fun matches(host: String): Boolean

    /**
     * Serves entries from the bundle source, backed by an FFI [BundleUrlHandler].
     *
     * Handles every `https` host (bundle name = first host label) except those the
     * optional [passthrough] sends to the network.
     */
    class Bundle internal constructor(
        private val passthrough: BundlePassthrough?,
    ) : WebViewBundleProtocol() {
        override fun matches(host: String): Boolean =
            passthrough == null || !passthrough.shouldPassthrough(host)
    }

    /**
     * Proxies requests to local HTTP servers, backed by an FFI [LocalUrlHandler].
     *
     * [hosts] maps a full request host to a local base URL, e.g.
     * `mapOf("app.wvb" to "http://10.0.2.2:3000")`. The key is matched against the
     * entire request host (case-insensitive).
     *
     * On the Android emulator the host machine's `localhost` is reachable at
     * `10.0.2.2`, and cleartext `http` to it requires a network security config.
     */
    class Local internal constructor(
        internal val hosts: Map<String, String>,
    ) : WebViewBundleProtocol() {
        private val keys = hosts.keys.map { it.lowercase() }.toSet()
        override fun matches(host: String): Boolean = host.lowercase() in keys
    }

    companion object {
        /**
         * A [Bundle] protocol that serves **every** `https` host from the bundle
         * source (bundle name = first host label). Register it last, as it matches
         * all hosts.
         */
        @Suppress("unUsed")
        fun bundle(): Bundle = Bundle(null)

        /**
         * A [Bundle] protocol that serves every `https` host **except** the ones
         * the [passthrough] builder sends to the network:
         *
         * ```kotlin
         * WebViewBundleProtocol.bundle {
         *     passthrough("example.com")          // exact host
         *     passthroughDomain("googleapis.com") // host + subdomains
         *     passthrough { it.endsWith(".cdn.net") }
         * }
         * ```
         *
         * To instead serve only a specific domain, invert it with a predicate, e.g.
         * `bundle { passthrough { host -> !host.endsWith(".wvb") } }`.
         */
        @Suppress("unUsed")
        fun bundle(passthrough: BundlePassthrough.() -> Unit): Bundle =
            Bundle(BundlePassthrough().apply(passthrough))

        /**
         * A [Local] dev-proxy protocol mapping full request hosts to local base
         * URLs, e.g. `local(mapOf("app.wvb" to "http://10.0.2.2:3000"))`.
         */
        @Suppress("unUsed")
        fun local(hosts: Map<String, String>): Local = Local(hosts)
    }
}
