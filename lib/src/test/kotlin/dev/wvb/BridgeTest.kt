package dev.wvb

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import android.util.Log as AndroidLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BridgeTest {

    @Test
    fun decodeReadsRequiredAndOptionalParams() {
        val params = JSONObject().put("bundleName", "app").put("channel", JSONObject.NULL)
        assertEquals("app", params.requireString("bundleName"))
        assertNull(params.optionalString("channel")) // explicit JSON null
        assertNull(params.optionalString("missing"))
    }

    @Test
    fun requireStringThrowsInvalidParamsWhenMissing() {
        val error = assertThrows(BridgeError::class.java) {
            JSONObject().put("bundleName", "app").requireString("version")
        }
        assertEquals("invalid_params", error.code)
    }

    @Test
    fun updateInfoToJsonKeepsRequiredAndOmitsNilOptionals() {
        val json = BundleUpdateInfo(
            name = "app", version = "2.0.0", localVersion = null, isAvailable = true,
            etag = "e", integrity = null, signature = null, lastModified = null,
        ).toJson()
        assertEquals("app", json.getString("name"))
        assertEquals("2.0.0", json.getString("version"))
        assertTrue(json.getBoolean("isAvailable"))
        assertEquals("e", json.getString("etag"))
        assertFalse(json.has("localVersion"))
        assertFalse(json.has("integrity"))
    }

    @Test
    fun sourceVersionToJsonMapsKindToType() {
        assertEquals(
            "remote",
            BundleSourceVersion(BundleSourceKind.REMOTE, "1.0.0").toJson().getString("type"),
        )
        assertEquals(
            "builtin",
            BundleSourceVersion(BundleSourceKind.BUILTIN, "1.0.0").toJson().getString("type"),
        )
    }

    @Test
    fun encodeErrorProducesCodeAndMessage() {
        val withCode = JSONObject(Bridge.encodeError(BridgeError(code = "x", message = "boom")))
        assertEquals("boom", withCode.getString("message"))
        assertEquals("x", withCode.getString("code"))

        val plain = JSONObject(Bridge.encodeError(IllegalStateException("plain")))
        assertEquals("plain", plain.getString("message"))
        assertFalse(plain.has("code"))
    }

    @Test
    fun encodeValueRejectsNonJsonValues() {
        val error = assertThrows(BridgeError::class.java) { Bridge.encodeValue(Any()) }
        assertEquals("unencodable_result", error.code)
    }

    @Test
    fun escapeForJsEscapesLineSeparators() {
        assertEquals("a\\u2028b\\u2029c", Bridge.escapeForJs("a b c"))
    }

    @Test
    fun coreLogForwardsWithMappedLevelAndTag() {
        ShadowLog.clear()
        CoreLog.forward(LogLevel.WARNING, "webview_bundle::updater", "hi")
        val item = ShadowLog.getLogs().last { it.tag == "webview-bundle.core" }
        assertEquals(AndroidLog.WARN, item.type)
        assertEquals("[webview_bundle::updater] hi", item.msg)
    }

    @Test
    fun logLevelMapsToAndroidPriority() {
        ShadowLog.clear()
        CoreLog.forward(LogLevel.TRACE, "t", "m")
        CoreLog.forward(LogLevel.INFO, "t", "m")
        CoreLog.forward(LogLevel.ERROR, "t", "m")
        val types = ShadowLog.getLogs().filter { it.tag == "webview-bundle.core" }.map { it.type }
        assertEquals(listOf(AndroidLog.DEBUG, AndroidLog.INFO, AndroidLog.ERROR), types)
    }
}
