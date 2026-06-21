import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}

val publishVersion = (findProperty("VERSION") as String?) ?: "0.0.0-LOCAL"

android {
    namespace = "dev.wvb"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        // Keep the @JavascriptInterface bridge methods in minified consumer apps.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols.add("**/*.so")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.webkit)
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )
    publishToMavenCentral()
    signAllPublications()

    coordinates("dev.wvb", "webview-bundle-android", publishVersion)

    pom {
        name = "webview-bundle-android"
        description = "Android Kotlin package for WebViewBundle."
        inceptionYear = "2024"
        url = "https://github.com/webview-bundle/webview-bundle-android"
        licenses {
            license {
                name = "MIT License"
                url = "https://github.com/webview-bundle/webview-bundle-android/blob/main/LICENSE"
                distribution =
                    "https://github.com/webview-bundle/webview-bundle-android/blob/main/LICENSE"
            }
        }
        developers {
            developer {
                id = "seokju-na"
                name = "Seokju Na"
                url = "https://github.com/seokju-na"
            }
        }
        scm {
            url = "https://github.com/webview-bundle/webview-bundle-android"
            connection = "scm:git:git://github.com/webview-bundle/webview-bundle-android.git"
            developerConnection =
                "scm:git:ssh://git@github.com/webview-bundle/webview-bundle-android.git"
        }
    }
}
