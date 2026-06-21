# The `wvbAndroid` invoke bridge is reached only from JavaScript via
# @JavascriptInterface, so R8 sees no Kotlin/Java caller and would otherwise be
# free to strip or rename the exposed method in a minified consumer app.
-keepclasseswithmembers,includedescriptorclasses class dev.wvb.** {
    @android.webkit.JavascriptInterface <methods>;
}
