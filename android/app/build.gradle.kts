plugins {
    id("com.android.application")
}

val releaseKeystorePath = System.getenv("BKE_ANDROID_RELEASE_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("BKE_ANDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("BKE_ANDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("BKE_ANDROID_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.bke.dna.logger"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bke.dna.logger"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.1.0-alpha.4"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val syncMainInterceptor by tasks.registering(Copy::class) {
    from(rootProject.file("../extension/main-interceptor.js"))
    into("src/main/assets/dna-extension")
}

tasks.named("preBuild").configure {
    dependsOn(syncMainInterceptor)
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:154.0.20260824154132")
}
