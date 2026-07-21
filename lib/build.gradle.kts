import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
    // Generates the API reference that fills the published -javadoc.jar, which
    // javadoc.io then hosts automatically from Maven Central.
    alias(libs.plugins.dokka)
}

val publishVersion = (findProperty("VERSION") as String?) ?: "0.0.0-LOCAL"

// Name the generated API docs after the artifact instead of Dokka's default, which is the Gradle
// module path (`:lib`) — that name ends up in the published -javadoc.jar and on javadoc.io.
dokka {
    moduleName.set("webview-bundle-android")
    dokkaSourceSets.configureEach {
        // The default source-set name is the Gradle path + variant (`:lib/main`), which shows up in
        // the docs' platform-filter chip; relabel it so no `:lib` leaks into the published docs.
        displayName.set("android")
    }
}

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
