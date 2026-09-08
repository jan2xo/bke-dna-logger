plugins {
    id("com.android.application")
}

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
        versionCode = 1
        versionName = "0.0.1-poc"

        ndk {
            abiFilters += "arm64-v8a"
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
