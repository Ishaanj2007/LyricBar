plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Ensure debug.keystore is automatically restored from debug.keystore.base64 if missing on a clean clone
val debugKeystore = file("${rootDir}/debug.keystore")
val debugKeystoreBase64 = file("${rootDir}/debug.keystore.base64")
if (!debugKeystore.exists() && debugKeystoreBase64.exists()) {
    try {
        val decoded = java.util.Base64.getDecoder().decode(debugKeystoreBase64.readText().trim())
        debugKeystore.writeBytes(decoded)
        logger.lifecycle("Restored debug.keystore from debug.keystore.base64 for clean clone.")
    } catch (e: Exception) {
        logger.warn("Could not restore debug.keystore from base64: ${e.message}")
    }
}
