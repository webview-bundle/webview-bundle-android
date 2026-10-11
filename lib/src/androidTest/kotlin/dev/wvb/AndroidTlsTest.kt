package dev.wvb

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Network integration tests; require access to example.com and badssl.com. */
class AndroidTlsTest {
    @Test
    fun httpsWorksAfterAutomaticInitializationAndRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = WebViewBundleConfig(
            protocols = emptyList(),
            updater = WebViewBundleUpdaterConfig(WebViewBundleRemoteConfig("https://example.com")),
        )

        repeat(2) {
            WebViewBundle.getInstance(context, config).use { bundle ->
                val destination = File(context.cacheDir, "tls-download.html")
                try {
                    bundle.remote!!.download("https://example.com/", destination.absolutePath)
                    assertTrue(destination.readText().contains("Example Domain"))
                } finally {
                    destination.delete()
                }

                ProxyProtocolHandler(mapOf("tls-test" to "https://example.com")).use { proxy ->
                    val response = proxy.handle(HttpMethod.GET, "https://tls-test/")
                    assertEquals(200, response.status.toInt())
                    assertTrue(response.body.toString(Charsets.UTF_8).contains("Example Domain"))
                }
            }
        }

        WvbAndroid.initialize(context)
        WvbAndroid.initialize(context)
        ProxyProtocolHandler(mapOf("tls-test" to "https://self-signed.badssl.com")).use { proxy ->
            try {
                proxy.handle(HttpMethod.GET, "https://tls-test/")
                fail("A self-signed certificate must be rejected")
            } catch (_: WebviewBundleException.CoreHttpClient) {
                // A verifier panic would surface as InternalException, not this typed error.
            }
        }
    }
}
