plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cerniera.dorso"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cerniera.dorso"
        minSdk = 31
        targetSdk = 35
        // Each GitHub build gets a higher number so it installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build, so new APKs install over the old one.
    // Personal sideloading only. Never use it for a store release.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("signing/dorso.jks")
            storePassword = "dorsodorsodorso"
            keyAlias = "dorso"
            keyPassword = "dorsodorsodorso"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation(project(":kit"))
    // Where the crease is: Jetpack WindowManager's FoldingFeature
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-java:1.3.0")
    implementation("androidx.core:core:1.13.1")
    // The clean text of a page: a Kotlin port of Mozilla's Readability, on jsoup
    implementation("net.dankito.readability4j:readability4j:1.0.8")
    implementation("org.jsoup:jsoup:1.13.1")
}

// Pagination's tests run on the JVM: gradle :app:testDebugUnitTest
dependencies {
    testImplementation("junit:junit:4.13.2")
}
