import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Resolve release signing properties securely from environment variables, project properties, or local keystore.properties
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun getSigningProperty(key: String): String? {
    return System.getenv(key)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(key) as? String)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }
}

val releaseStoreFile = getSigningProperty("LYRICBAR_STORE_FILE")
val releaseStorePassword = getSigningProperty("LYRICBAR_STORE_PASSWORD")
val releaseKeyAlias = getSigningProperty("LYRICBAR_KEY_ALIAS")
val releaseKeyPassword = getSigningProperty("LYRICBAR_KEY_PASSWORD")

val hasReleaseSigning = !releaseStoreFile.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ishaan.lyricbar"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "2.7.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    sourceSets {
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
        getByName("test").assets.srcDirs("$projectDir/schemas")
    }

    signingConfigs {
        create("debugConfig") {
            val localKeystore = file("${rootDir}/debug.keystore")
            if (localKeystore.exists()) {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                initWith(getByName("debug"))
            }
        }

        if (hasReleaseSigning) {
            create("release") {
                val resolvedFile = rootProject.file(releaseStoreFile!!)
                storeFile = if (resolvedFile.isAbsolute) resolvedFile else rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                if (releaseStoreFile.endsWith(".p12", ignoreCase = true)) {
                    storeType = "PKCS12"
                }
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
            signingConfig = if (file("${rootDir}/debug.keystore").exists()) {
                signingConfigs.getByName("debugConfig")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INDEX"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.palette)
    implementation(libs.haze)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Enforce that release packaging fails clearly if release signing credentials are missing,
// rather than silently falling back to debug signing or producing an insecure release artifact.
gradle.taskGraph.whenReady {
    val hasReleasePackagingTask = allTasks.any { task ->
        task.name in listOf("packageRelease", "assembleRelease", "bundleRelease")
    }
    if (hasReleasePackagingTask && !hasReleaseSigning) {
        val missing = mutableListOf<String>()
        if (releaseStoreFile.isNullOrBlank()) missing.add("LYRICBAR_STORE_FILE")
        if (releaseStorePassword.isNullOrBlank()) missing.add("LYRICBAR_STORE_PASSWORD")
        if (releaseKeyAlias.isNullOrBlank()) missing.add("LYRICBAR_KEY_ALIAS")
        if (releaseKeyPassword.isNullOrBlank()) missing.add("LYRICBAR_KEY_PASSWORD")

        throw GradleException(
            "Release signing credentials missing (${missing.joinToString(", ")}). " +
            "Please configure them via environment variables (e.g. LYRICBAR_STORE_FILE) " +
            "or a local keystore.properties file. " +
            "Release APKs must be securely signed and will not silently fall back to debug signing."
        )
    }
}

