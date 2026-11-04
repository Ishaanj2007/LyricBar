package com.example.data.updater

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    object Idle : DownloadState
    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressFraction: Float
    ) : DownloadState
    data class Completed(val apkFile: File) : DownloadState
    data class Failed(val reason: String) : DownloadState
    object Cancelled : DownloadState
}

class ApkDownloader(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var activeJob: Job? = null

    val updatesDir: File
        get() = File(context.cacheDir, "updates").apply {
            if (!exists()) mkdirs()
        }

    fun startDownload(asset: ReleaseAsset, scope: CoroutineScope) {
        if (_downloadState.value is DownloadState.Progress) {
            // Download already in progress
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch(Dispatchers.IO) {
            val fileName = asset.name.ifBlank { "LyricBar-update.apk" }
            val destinationFile = File(updatesDir, fileName)
            val tempFile = File(updatesDir, "$fileName.tmp")

            try {
                // Ensure sufficient storage space (+20MB safety headroom)
                val requiredBytes = if (asset.sizeBytes > 0) asset.sizeBytes + (20 * 1024 * 1024) else 50 * 1024 * 1024
                val statFs = StatFs(context.cacheDir.absolutePath)
                val availableBytes = statFs.availableBytes
                if (availableBytes < requiredBytes) {
                    _downloadState.value = DownloadState.Failed("Insufficient storage space to download update.")
                    return@launch
                }

                // Clean up any stale files
                if (tempFile.exists()) tempFile.delete()
                if (destinationFile.exists()) destinationFile.delete()

                val request = Request.Builder()
                    .url(asset.downloadUrl)
                    .header("User-Agent", "LyricBar-Updater/${com.example.BuildConfig.VERSION_NAME}")
                    .build()

                _downloadState.value = DownloadState.Progress(0L, asset.sizeBytes, 0f)

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Server returned HTTP ${response.code} downloading APK")
                    }

                    val body = response.body ?: throw IOException("Empty response body from APK download")
                    val contentLength = if (body.contentLength() > 0) body.contentLength() else asset.sizeBytes

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalBytesRead = 0L
                            var lastEmitTime = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (!isActive) {
                                    // Coroutine cancelled
                                    tempFile.delete()
                                    _downloadState.value = DownloadState.Cancelled
                                    return@launch
                                }

                                output.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastEmitTime > 80 || totalBytesRead == contentLength) {
                                    lastEmitTime = now
                                    val fraction = if (contentLength > 0) {
                                        (totalBytesRead.toFloat() / contentLength.toFloat()).coerceIn(0f, 1f)
                                    } else 0f
                                    _downloadState.value = DownloadState.Progress(
                                        bytesDownloaded = totalBytesRead,
                                        totalBytes = contentLength,
                                        progressFraction = fraction
                                    )
                                }
                            }
                            output.flush()
                        }
                    }

                    // Rename temp to final only after successful, complete write
                    if (!tempFile.renameTo(destinationFile)) {
                        throw IOException("Failed to finalize downloaded APK file")
                    }

                    _downloadState.value = DownloadState.Completed(destinationFile)
                }
            } catch (e: CancellationException) {
                if (tempFile.exists()) tempFile.delete()
                _downloadState.value = DownloadState.Cancelled
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                if (destinationFile.exists()) destinationFile.delete()
                _downloadState.value = DownloadState.Failed(
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Download failed"
                )
            }
        }
    }

    fun cancelDownload() {
        activeJob?.cancel()
        activeJob = null
        cleanUp()
        _downloadState.value = DownloadState.Cancelled
    }

    fun reset() {
        activeJob?.cancel()
        activeJob = null
        _downloadState.value = DownloadState.Idle
    }

    fun cleanUp() {
        try {
            updatesDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp") || file.name.endsWith(".apk")) {
                    file.delete()
                }
            }
        } catch (_: Exception) {
        }
    }
}
