package com.example.data.updater

import android.content.Context
import android.content.Intent
import com.example.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface UpdateUiState {
    object Idle : UpdateUiState
    object Checking : UpdateUiState
    data class UpToDate(val installedVersion: String, val checkedAt: Long) : UpdateUiState
    data class Available(
        val release: ReleaseInfo,
        val installedVersion: String
    ) : UpdateUiState
    data class Downloading(
        val release: ReleaseInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressFraction: Float
    ) : UpdateUiState
    data class DownloadFailed(
        val release: ReleaseInfo,
        val reason: String
    ) : UpdateUiState
    data class Verifying(
        val release: ReleaseInfo,
        val apkFile: File
    ) : UpdateUiState
    data class VerificationFailed(
        val release: ReleaseInfo,
        val reason: String
    ) : UpdateUiState
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkFile: File,
        val checksumStatus: ChecksumStatus,
        val certificateFingerprint: String
    ) : UpdateUiState
    data class PermissionRequired(
        val release: ReleaseInfo,
        val apkFile: File,
        val settingsIntent: Intent
    ) : UpdateUiState
    data class InstallationLaunched(val release: ReleaseInfo) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

class UpdateManager private constructor(
    private val context: Context,
    private val repository: UpdateRepository = UpdateRepository(),
    private val downloader: ApkDownloader = ApkDownloader(context)
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _uiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    val installedVersionName: String
        get() = BuildConfig.VERSION_NAME

    val installedVersionCode: Long
        get() = BuildConfig.VERSION_CODE.toLong()

    init {
        // Observe downloader progress and translate to UpdateUiState
        scope.launch {
            downloader.downloadState.collect { downloadState ->
                val currentState = _uiState.value
                val activeRelease = when (currentState) {
                    is UpdateUiState.Downloading -> currentState.release
                    is UpdateUiState.DownloadFailed -> currentState.release
                    is UpdateUiState.Available -> currentState.release
                    else -> null
                } ?: return@collect

                when (downloadState) {
                    is DownloadState.Progress -> {
                        _uiState.value = UpdateUiState.Downloading(
                            release = activeRelease,
                            bytesDownloaded = downloadState.bytesDownloaded,
                            totalBytes = downloadState.totalBytes,
                            progressFraction = downloadState.progressFraction
                        )
                    }
                    is DownloadState.Completed -> {
                        verifyApkFile(activeRelease, downloadState.apkFile)
                    }
                    is DownloadState.Failed -> {
                        _uiState.value = UpdateUiState.DownloadFailed(
                            release = activeRelease,
                            reason = downloadState.reason
                        )
                    }
                    is DownloadState.Cancelled -> {
                        _uiState.value = UpdateUiState.Available(
                            release = activeRelease,
                            installedVersion = installedVersionName
                        )
                    }
                    DownloadState.Idle -> {
                        // No-op
                    }
                }
            }
        }
    }

    fun checkForUpdates(isUserInitiated: Boolean = true) {
        if (_uiState.value is UpdateUiState.Checking || _uiState.value is UpdateUiState.Downloading) {
            return
        }

        _uiState.value = UpdateUiState.Checking

        scope.launch {
            val result = repository.fetchLatestRelease()
            result.onSuccess { release ->
                val isNewer = VersionComparator.isNewer(
                    remoteVersion = release.tagName,
                    installedVersion = installedVersionName
                )

                if (isNewer && release.apkAsset != null) {
                    _uiState.value = UpdateUiState.Available(
                        release = release,
                        installedVersion = installedVersionName
                    )
                } else if (release.apkAsset == null && isNewer) {
                    _uiState.value = UpdateUiState.Error(
                        "A new version (${release.tagName}) was found on GitHub, but no compatible APK asset was provided in this release."
                    )
                } else {
                    _uiState.value = UpdateUiState.UpToDate(
                        installedVersion = installedVersionName,
                        checkedAt = System.currentTimeMillis()
                    )
                }
            }.onFailure { error ->
                _uiState.value = UpdateUiState.Error(
                    error.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: "Failed to check for updates. Please try again."
                )
            }
        }
    }

    fun startDownload(release: ReleaseInfo) {
        val asset = release.apkAsset
        if (asset == null) {
            _uiState.value = UpdateUiState.Error("No APK download available for this release.")
            return
        }
        downloader.startDownload(asset, scope)
    }

    fun cancelDownload() {
        downloader.cancelDownload()
    }

    fun retryDownload(release: ReleaseInfo) {
        startDownload(release)
    }

    private fun verifyApkFile(release: ReleaseInfo, file: File) {
        _uiState.value = UpdateUiState.Verifying(release, file)

        scope.launch(Dispatchers.Default) {
            val result = ApkVerifier.verify(
                context = context,
                apkFile = file,
                expectedSha256 = release.sha256Checksum,
                installedVersionName = installedVersionName,
                installedVersionCode = installedVersionCode
            )

            withContext(Dispatchers.Main) {
                when (result) {
                    is VerificationResult.Success -> {
                        // Check if install permission is required
                        if (!UpdateInstaller.canRequestPackageInstalls(context)) {
                            val settingsIntent = UpdateInstaller.createManageUnknownAppSourcesIntent(context)
                            _uiState.value = UpdateUiState.PermissionRequired(
                                release = release,
                                apkFile = file,
                                settingsIntent = settingsIntent
                            )
                        } else {
                            _uiState.value = UpdateUiState.ReadyToInstall(
                                release = release,
                                apkFile = file,
                                checksumStatus = result.checksumStatus,
                                certificateFingerprint = result.certificateFingerprint
                            )
                        }
                    }
                    is VerificationResult.Error -> {
                        _uiState.value = UpdateUiState.VerificationFailed(
                            release = release,
                            reason = result.message
                        )
                    }
                }
            }
        }
    }

    fun installApk(release: ReleaseInfo, file: File) {
        val installResult = UpdateInstaller.installApk(context, file)
        when (installResult) {
            is InstallationResult.Launched -> {
                _uiState.value = UpdateUiState.InstallationLaunched(release)
            }
            is InstallationResult.PermissionRequired -> {
                _uiState.value = UpdateUiState.PermissionRequired(
                    release = release,
                    apkFile = file,
                    settingsIntent = installResult.settingsIntent
                )
            }
            is InstallationResult.Failure -> {
                _uiState.value = UpdateUiState.Error(installResult.message)
            }
        }
    }

    fun resumeAfterPermissionGranted(release: ReleaseInfo, file: File) {
        if (UpdateInstaller.canRequestPackageInstalls(context)) {
            installApk(release, file)
        }
    }

    fun dismiss() {
        if (_uiState.value is UpdateUiState.Downloading) {
            downloader.cancelDownload()
        }
        downloader.reset()
        _uiState.value = UpdateUiState.Idle
    }

    companion object {
        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
