import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.gradle.api.file.ArchiveOperations
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
    // Generates the API reference that fills the published -javadoc.jar, which
    // javadoc.io then hosts automatically from Maven Central.
    alias(libs.plugins.dokka)
}

val publishVersion = (findProperty("VERSION") as String?) ?: "0.0.0-LOCAL"

// Resolve rustls only while building this library. Keeping this configuration separate from
// implementation prevents its GitHub-only Maven coordinate from leaking into our publication.
val embeddedRustls by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
interface RustlsArchiveServices {
    @get:Inject val archives: ArchiveOperations
}
val unpackRustls by tasks.registering(Sync::class) {
    // Capture a supported Gradle service, not the build script, in this lazy provider.
    val archives = objects.newInstance<RustlsArchiveServices>().archives
    from(embeddedRustls.elements.map { artifacts -> artifacts.map { archives.zipTree(it.asFile) } }) {
        include("classes.jar", "res/**", "AndroidManifest.xml")
        filesMatching("AndroidManifest.xml") {
            // This becomes our main manifest; AGP takes the namespace/minSdk from our DSL.
            // Preserve the upstream permission and networkSecurityConfig resource reference.
            filter { line ->
                if (line.contains("<uses-sdk ")) null
                else line.replace("package=\"org.rustls.platformverifier\"", "")
            }
        }
    }
    into(layout.buildDirectory.dir("generated/rustls"))
}
val rustlsDir = unpackRustls.map { it.destinationDir }

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Keep the @JavascriptInterface bridge methods in minified consumer apps.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.named("main") {
        manifest.srcFile(rustlsDir.map { it.resolve("AndroidManifest.xml") })
        res.srcDir(rustlsDir.map { it.resolve("res") })
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

// The manifest and resources must exist before AGP processes either build variant.
tasks.named("preBuild") { dependsOn(unpackRustls) }

dependencies {
    // Match the rustls-platform-verifier-android crate used by the installed FFI.
    embeddedRustls("org.rustls:rustls-platform-verifier:0.2.0@aar")
    // AGP packages local JAR dependencies into the AAR; consumers need no rustls repository.
    implementation(files(rustlsDir.map { it.resolve("classes.jar") }))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
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
