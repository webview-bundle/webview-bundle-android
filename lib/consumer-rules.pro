# WebViewBundle's native FFI bindings (lib/src/main/kotlin/dev/wvb/wvb_ffi.kt)
# are UniFFI-generated and call into the native library through JNA. JNA maps the
# Rust structs/callbacks by reflection and reads the field names from
# @Structure.FieldOrder annotations, so R8 in a minified consumer app must not
# rename/strip these classes, drop their members, or remove the annotations — any
# of which breaks the FFI at runtime (UnsatisfiedLinkError / struct mismatch).
#
# JNA does not bundle these rules in its own AAR, so the library ships them here
# (they are packaged into the published AAR and applied to consumers automatically).
# This is the same set Mozilla's UniFFI-based SDKs publish:
#   https://github.com/mozilla/application-services/blob/main/proguard-rules-consumer-jna.pro
-dontwarn java.awt.*
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,RuntimeVisibleTypeAnnotations,RuntimeInvisibleTypeAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod,Signature
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
